package uz.audiokitob

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Fon xizmati: navbatdagi ishlarni birma-bir audiolashtiradi.
 * Har bir boʻlakdan keyin audio faylga yoziladi va ish holati diskka saqlanadi — ilova oʻchsa ham
 * kelgan joyidan davom etadi. Barcha muhim hodisalar va xatolar xato jurnaliga yoziladi.
 */
class TtsService : Service() {

    companion object {
        const val ACTION_START = "uz.audiokitob.START"
        const val ACTION_PAUSE = "uz.audiokitob.PAUSE"
        const val ACTION_PAUSE_ALL = "uz.audiokitob.PAUSE_ALL"
        const val EXTRA_ID = "id"
        const val EXTRA_RESET = "reset"
        private const val NOTIF_ID = 101
        private const val MIN_CHUNK = 300
        private const val USER_PAUSE = "Toʻxtatildi."

        @Volatile
        var running = false

        fun start(ctx: Context, id: String, reset: Boolean = false) {
            try {
                val i = Intent(ctx, TtsService::class.java)
                i.action = ACTION_START
                i.putExtra(EXTRA_ID, id)
                i.putExtra(EXTRA_RESET, reset)
                ContextCompat.startForegroundService(ctx, i)
            } catch (t: Throwable) {
                Journal.e("Service", "Fon xizmatini ishga tushirib boʻlmadi", t)
                JobStore.update(id) {
                    it.state = JobState.PAUSED
                    it.message = "Fon xizmatini ishga tushirib boʻlmadi (" + t.javaClass.simpleName +
                        "). Ilovani ochib «Davom ettirish»ni bosing."
                }
            }
        }

        fun pause(ctx: Context, id: String) {
            try {
                val i = Intent(ctx, TtsService::class.java)
                i.action = ACTION_PAUSE
                i.putExtra(EXTRA_ID, id)
                ctx.startService(i)
            } catch (t: Throwable) {
                Journal.e("Service", "Pauza buyrugʻini yuborib boʻlmadi", t)
            }
        }
    }

    private class Cfg(val voice: String, val style: String, val chunk: Int)

    private sealed class Out {
        class Audio(val pcm: ByteArray, val rate: Int, val slot: KeyPool.Slot) : Out()
        class Bad(val why: String) : Out()
        class Stop(val state: JobState, val msg: String) : Out()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val queue = java.util.LinkedList<String>()
    private var worker: Thread? = null
    private var lastStartId = 0

    @Volatile
    private var curId: String? = null

    @Volatile
    private var stopCur = false
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        Journal.i("Service", "Xizmat yaratildi")
    }

    override fun onDestroy() {
        running = false
        stopCur = true
        Gemini.cancelActive()
        releaseWake()
        Journal.i("Service", "Xizmat toʻxtadi")
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(lock) { lastStartId = startId }
        try {
            goForeground("Audiolashtirish…")
        } catch (t: Throwable) {
            Journal.e("Service", "startForeground xatosi (fon ruxsati/bildirishnoma sozlamalarini tekshiring)", t)
        }
        val act = intent?.action
        val id = intent?.getStringExtra(EXTRA_ID)
        if (intent == null) {
            recover()
        } else if (act == ACTION_START && id != null) {
            enqueue(id, intent.getBooleanExtra(EXTRA_RESET, false))
        } else if (act == ACTION_PAUSE && id != null) {
            pauseJob(id)
        } else if (act == ACTION_PAUSE_ALL) {
            pauseAll()
        }
        ensureWorker()
        return START_STICKY
    }

    // ---------------------------------------------------------------- navbat

    private fun recover() {
        val ids = ArrayList<String>()
        for (j in JobStore.all()) if (j.state in JobStore.ACTIVE) ids.add(j.id)
        Journal.w("Service", "Xizmat qayta ishga tushdi (tizim oʻldirgan boʻlishi mumkin). Tiklanadigan ishlar: " + ids.size)
        for (x in ids) enqueue(x, false)
    }

    private fun enqueue(id: String, reset: Boolean) {
        if (reset) KeyPool.clearStates(applicationContext)
        var added = false
        synchronized(lock) {
            if (curId != id && !queue.contains(id)) {
                queue.add(id)
                added = true
            }
        }
        if (added) {
            JobStore.update(id) {
                if (it.state != JobState.RUNNING) {
                    it.state = JobState.QUEUED
                    it.message = "Navbatda."
                }
            }
            Journal.i("Service", "Navbatga qoʻshildi: " + id + (if (reset) " (kalit holati tiklandi)" else ""))
        }
    }

    private fun pauseJob(id: String) {
        if (curId == id) {
            stopCur = true
            Gemini.cancelActive()
            return
        }
        var removed = false
        synchronized(lock) { removed = queue.remove(id) }
        if (removed) {
            JobStore.update(id) {
                it.state = JobState.PAUSED
                it.message = "Navbatdan olindi (toʻxtatildi)."
            }
        }
    }

    private fun pauseAll() {
        stopCur = true
        Gemini.cancelActive()
        val ids = ArrayList<String>()
        synchronized(lock) {
            ids.addAll(queue)
            queue.clear()
        }
        for (x in ids) {
            JobStore.update(x) {
                it.state = JobState.PAUSED
                it.message = "Toʻxtatildi."
            }
        }
    }

    private fun ensureWorker() {
        var idle = false
        var exitId = 0
        synchronized(lock) {
            if (worker == null) {
                if (queue.isEmpty()) {
                    idle = true
                    exitId = lastStartId
                } else {
                    val t = Thread({ workLoop() }, "tts-worker")
                    worker = t
                    t.start()
                }
            }
        }
        if (idle) finishService(exitId)
    }

    private fun finishService(exitId: Int) {
        if (stopSelfResult(exitId)) {
            try {
                stopForeground(true)
            } catch (t: Throwable) {
            }
        }
    }

    private fun workLoop() {
        acquireWake()
        var exitId = 0
        try {
            while (true) {
                var id: String? = null
                synchronized(lock) {
                    id = queue.poll()
                    if (id == null) {
                        worker = null
                        exitId = lastStartId
                    }
                }
                val cur = id ?: break
                runOne(cur)
            }
        } catch (t: Throwable) {
            Journal.e("Service", "Ishchi oqim kutilmagan xato bilan tugadi", t)
            synchronized(lock) {
                worker = null
                exitId = lastStartId
            }
        } finally {
            releaseWake()
            val ex = exitId
            handler.post { finishService(ex) }
        }
    }

    private fun runOne(id: String) {
        stopCur = false
        curId = id
        val j0 = JobStore.get(id)
        if (j0 == null) {
            curId = null
            return
        }
        try {
            process(id)
        } catch (t: Throwable) {
            Journal.e("Job", "Kutilmagan xato: '" + j0.title + "'", t)
            val m = "Kutilmagan xato: " + t.javaClass.simpleName + ": " + (t.message ?: "") + ". Jurnalga qarang."
            JobStore.update(id) {
                it.state = JobState.FAILED
                it.message = m
            }
            notifyResult("Xatolik: " + j0.title, m, id)
        } finally {
            curId = null
        }
    }

    // ---------------------------------------------------------------- asosiy ish

    private fun prepare(id: String, job: Job): Boolean {
        JobStore.update(id) {
            it.state = JobState.PREPARING
            it.message = "Matn tayyorlanmoqda…"
        }
        updateNotif(job.title, "Matn tayyorlanmoqda…")
        val orig = JobStore.original(id)
        if (!orig.exists()) {
            fail(id, "Asl fayl topilmadi. Faylni qayta tanlang.", null)
            return false
        }
        val ex = try {
            Extractors.extract(applicationContext, orig, job.srcName) { p ->
                JobStore.update(id) { it.message = p }
                updateNotif(job.title, p)
            }
        } catch (e: ExtractException) {
            fail(id, e.message ?: "Faylni oʻqib boʻlmadi.", e)
            return false
        } catch (t: Throwable) {
            fail(id, "Faylni oʻqib boʻlmadi: " + t.javaClass.simpleName + ": " + (t.message ?: ""), t)
            return false
        }
        try {
            JobStore.source(id).writeText(ex.text, Charsets.UTF_8)
            TextTools.saveMarks(JobStore.marks(id), ex.marks)
        } catch (t: Throwable) {
            fail(id, "Matnni saqlab boʻlmadi (xotira yetarlimi?): " + (t.message ?: ""), t)
            return false
        }
        JobStore.update(id) {
            it.total = ex.text.length
            it.preview = Util.snippet(ex.text, 160)
            it.message = ex.warning ?: ""
        }
        try {
            orig.delete()
        } catch (t: Throwable) {
        }
        Journal.i("Job", "Matn tayyor: " + ex.text.length + " belgi, joy belgilari: " + ex.marks.size)
        return true
    }

    private fun prepareAudioFile(id: String, job: Job) {
        val f = JobStore.audio(job)
        try {
            if (f.exists()) {
                if (f.length() > job.audioBytes) {
                    RandomAccessFile(f, "rw").use { it.setLength(job.audioBytes) }
                    Journal.i("Audio", "Yozilib ulgurmagan oxirgi qism kesib tashlandi (" + f.length() + " → " + job.audioBytes + ")")
                } else if (f.length() < job.audioBytes) {
                    val len = f.length()
                    JobStore.update(id) { it.audioBytes = len }
                    Journal.w("Audio", "Audio fayl kutilganidan qisqa: " + len + " < " + job.audioBytes)
                }
            } else if (job.audioBytes > 0L) {
                Journal.w("Audio", "Oldingi audio fayl topilmadi; yangi qism alohida yoziladi")
                JobStore.update(id) {
                    it.audioBytes = 0L
                    it.message = "Oldingi audio fayl topilmadi; faqat yangi qism yoziladi."
                }
            }
        } catch (t: Throwable) {
            Journal.e("Audio", "Audio faylni tayyorlab boʻlmadi", t)
        }
    }

    private fun process(id: String) {
        val job0 = JobStore.get(id) ?: return
        if (!JobStore.source(id).exists()) {
            if (!prepare(id, job0)) return
        }
        val text = try {
            JobStore.source(id).readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            fail(id, "Matn faylini oʻqib boʻlmadi: " + (t.message ?: ""), t)
            return
        }
        var job = JobStore.get(id) ?: return
        if (job.offset >= text.length) {
            finishDone(id)
            return
        }
        val marks = TextTools.loadMarks(JobStore.marks(id))
        val cfg = Cfg(Prefs.voice(this), Prefs.style(this), Prefs.chunk(this))
        val pool = KeyPool(applicationContext)
        pool.reload()
        if (pool.keyCount() == 0) {
            fail(id, "API kalit kiritilmagan. Sozlamalarda kamida bitta kalit kiriting.", null)
            return
        }
        prepareAudioFile(id, job)
        job = JobStore.get(id) ?: return
        Journal.i(
            "Job",
            "Boshlandi/davom: '" + job.title + "' belgi " + job.offset + "/" + text.length +
                ", ovoz " + cfg.voice + ", boʻlak " + cfg.chunk + ", kalitlar " + pool.keyCount()
        )
        JobStore.update(id) {
            it.state = JobState.RUNNING
            it.message = "Boshlanmoqda…"
        }

        val sink = SinkHolder(id)
        var curMax = cfg.chunk
        var skipStreak = 0
        var offset = job.offset
        try {
            while (true) {
                if (stopCur) {
                    finishStop(id, JobState.PAUSED, USER_PAUSE, true)
                    break
                }
                if (JobStore.get(id) == null) break
                refreshWake()
                while (offset < text.length && text[offset].isWhitespace()) offset++
                if (offset >= text.length) {
                    finishDone(id)
                    break
                }
                val end = TextTools.nextEnd(text, offset, curMax)
                val raw = text.substring(offset, end)
                val speech = TextTools.forSpeech(raw)
                if (!TextTools.hasSpeakable(speech)) {
                    offset = end
                    JobStore.update(id) { it.offset = end }
                    continue
                }

                val first = synth(id, pool, cfg, speech)
                var res: Out = first
                if (first is Out.Audio && speech.length > 400) {
                    val secs0 = first.pcm.size / (2.0 * first.rate)
                    if (implausible(speech, secs0)) {
                        Journal.w(
                            "Audio",
                            "Shubhali davomiylik: " + speech.length + " belgi → " + Util.fmt1(secs0) + " s; boʻlaklanadi"
                        )
                        res = Out.Bad("audio davomiyligi matnga mos emas (" + Util.fmt1(secs0) + " s)")
                    }
                }
                val rr = res
                when (rr) {
                    is Out.Audio -> {
                        val err = sink.append(rr.pcm, rr.rate)
                        if (err != null) {
                            finishStop(id, JobState.FAILED, err, false)
                            Journal.e("Audio", err)
                            break
                        }
                        val ms = rr.pcm.size * 1000L / (2L * rr.rate)
                        val cur = JobStore.get(id)
                        if (cur == null) break
                        val bytes = JobStore.audio(cur).length()
                        val label = TextTools.labelAt(marks, end - 1)
                        offset = end
                        JobStore.update(id) {
                            it.offset = end
                            it.audioMs += ms
                            it.audioBytes = bytes
                            it.chunksDone++
                            it.lastSnippet = Util.tail(raw, 140)
                            it.posLabel = label
                            it.state = JobState.RUNNING
                            it.message = ""
                        }
                        curMax = cfg.chunk
                        skipStreak = 0
                        val secs = rr.pcm.size / (2.0 * rr.rate)
                        Journal.i(
                            "Chunk",
                            "OK " + offset + "/" + text.length + ": " + speech.length + " belgi → " + Util.fmt1(secs) +
                                " s; kalit " + rr.slot.keySlot + "; " + rr.slot.model
                        )
                        val pct = (offset.toLong() * 100L / text.length).toInt()
                        updateNotif(cur.title, pct.toString() + "% — " + Util.fmtDuration(cur.audioMs + ms) + " audio")
                    }
                    is Out.Bad -> {
                        val size = end - offset
                        if (size > MIN_CHUNK) {
                            curMax = maxOf(MIN_CHUNK, size / 2)
                            Journal.w("Job", "Qism boʻlaklanmoqda: " + size + " → " + curMax + " belgi. Sabab: " + rr.why)
                        } else {
                            recordSkipped(id, raw, marks, offset, rr.why)
                            offset = end
                            JobStore.update(id) {
                                it.offset = end
                                it.skipped++
                                it.message = "Bitta qism audiolashtirilmadi va oʻtkazib yuborildi."
                            }
                            curMax = cfg.chunk
                            skipStreak++
                            if (skipStreak >= 3) {
                                finishStop(
                                    id, JobState.PAUSED,
                                    "Ketma-ket 3 ta qism audiolashtirilmadi — xizmatda yoki matnda muammo boʻlishi mumkin. " +
                                        "Xato jurnaliga qarang; keyin «Davom ettirish»ni bosing.",
                                    true
                                )
                                break
                            }
                        }
                    }
                    is Out.Stop -> {
                        finishStop(id, rr.state, rr.msg, true)
                        break
                    }
                }
            }
        } finally {
            sink.close()
        }
    }

    private fun implausible(speech: String, secs: Double): Boolean {
        val eff = TextTools.effectiveLen(speech)
        val cps = eff / maxOf(secs, 0.01)
        return cps > 40.0 || cps < 3.0
    }

    private fun recordSkipped(id: String, raw: String, marks: List<Mark>, offset: Int, why: String) {
        val label = TextTools.labelAt(marks, offset)
        try {
            val head = "[" + (if (label.isNotEmpty()) label + ", " else "") + "belgi " + offset + "] sabab: " + why + "\n"
            JobStore.skipped(id).appendText(head + raw.trim() + "\n\n", Charsets.UTF_8)
        } catch (t: Throwable) {
            Journal.e("Job", "Oʻtkazilgan qismni faylga yozib boʻlmadi", t)
        }
        Journal.e(
            "Job",
            "Qism audiolashtirilmadi va oʻtkazib yuborildi (belgi " + offset + "): " + why + " | matn: " + Util.snippet(raw, 80)
        )
    }

    // ---------------------------------------------------------------- Gemini soʻrovi, kalit almashtirish, qayta urinishlar

    private fun synth(id: String, pool: KeyPool, cfg: Cfg, speech: String): Out {
        var netFails = 0
        var serverFails = 0
        var badFails = 0
        while (true) {
            if (stopCur || JobStore.get(id) == null) return Out.Stop(JobState.PAUSED, USER_PAUSE)
            if (!Util.isOnline(this)) {
                waitStatus(id, "Internet yoʻq — ulanish kutilmoqda…")
                sleepMs(8000L)
                continue
            }
            pool.reload()
            val pick = pool.pick(System.currentTimeMillis())
            if (pick is KeyPool.Pick.None) {
                if (pool.modelCount() == 0) {
                    return Out.Stop(JobState.FAILED, "Ishlaydigan TTS modeli topilmadi. Sozlamalarda model nomini tekshiring.")
                }
                if (pool.allKeysDead()) {
                    return Out.Stop(JobState.FAILED, "Barcha API kalitlar yaroqsiz. " + pool.limitMessage())
                }
                return Out.Stop(JobState.LIMIT, pool.limitMessage())
            }
            if (pick is KeyPool.Pick.Wait) {
                waitStatus(id, "Limit kutilmoqda: taxminan " + (pick.ms / 1000L + 1L) + " soniya…")
                sleepMs(minOf(pick.ms + 500L, 20000L))
                continue
            }
            val slot = (pick as KeyPool.Pick.Use).slot
            setRunning(id, "Audiolashtirilmoqda (kalit " + slot.keySlot + ")…")
            val r = Gemini.synthesize(slot.key, slot.model, cfg.voice, speech, cfg.style) { stopCur }
            when (r.kind) {
                Gemini.Kind.OK -> {
                    pool.success(slot)
                    val pcm = r.pcm
                    if (pcm != null) return Out.Audio(pcm, r.rate, slot)
                    badFails++
                    if (badFails >= 2) return Out.Bad("boʻsh audio")
                }
                Gemini.Kind.CANCELLED -> return Out.Stop(JobState.PAUSED, USER_PAUSE)
                Gemini.Kind.QUOTA_DAY -> {
                    pool.exhaust(slot)
                    Journal.w("Limit", "Kalit " + slot.keySlot + " / " + slot.model + ": kunlik limit tugadi (HTTP " + r.code + "). " + r.message)
                }
                Gemini.Kind.QUOTA_MIN -> {
                    pool.cooldown(slot, r.retryMs)
                    Journal.w(
                        "Limit",
                        "Kalit " + slot.keySlot + " / " + slot.model + ": daqiqalik limit, " + (r.retryMs / 1000L) + " s kutiladi. " + r.message
                    )
                }
                Gemini.Kind.AUTH -> handleAuth(pool, slot, r)
                Gemini.Kind.REGION -> {
                    Journal.e("Region", "HTTP " + r.code + ": " + r.message)
                    return Out.Stop(
                        JobState.FAILED,
                        "Google Gemini API bu joylashuvda ishlamayapti (" + r.message +
                            "). Boshqa tarmoq yoki pullik rejim kerak boʻlishi mumkin."
                    )
                }
                Gemini.Kind.MODEL -> handleModel(pool, slot, r)
                Gemini.Kind.SERVER -> {
                    serverFails++
                    Journal.w("Tarmoq", "Server xatosi HTTP " + r.code + ": " + r.message + " (urinish " + serverFails + ")")
                    if (serverFails >= 3) return Out.Bad("server xatosi " + r.code)
                    sleepMs(4000L * serverFails)
                }
                Gemini.Kind.NETWORK -> {
                    netFails++
                    Journal.w("Tarmoq", "Tarmoq xatosi: " + r.message + " (urinish " + netFails + "/6)")
                    if (netFails >= 6) {
                        return Out.Stop(
                            JobState.PAUSED,
                            "Tarmoq xatosi ketma-ket takrorlandi (" + r.message + "). Internetni tekshirib «Davom ettirish»ni bosing."
                        )
                    }
                    waitStatus(id, "Tarmoq xatosi, qayta uriniladi (" + netFails + "/6)…")
                    sleepMs(minOf(60000L, 5000L * netFails))
                }
                Gemini.Kind.NO_AUDIO, Gemini.Kind.BAD_CHUNK -> {
                    badFails++
                    Journal.w("Gemini", "Natija yaroqsiz: " + r.message + " (urinish " + badFails + ")")
                    if (badFails >= 2) return Out.Bad(r.message)
                    sleepMs(1500L)
                }
            }
        }
    }

    private fun handleAuth(pool: KeyPool, slot: KeyPool.Slot, r: Gemini.Result) {
        val chk = Gemini.checkKey(slot.key)
        if (chk.ok) {
            pool.deadSlot(slot)
            Journal.e("Kalit", "Kalit " + slot.keySlot + ": " + slot.model + " modeliga ruxsat yoʻq (HTTP " + r.code + "): " + r.message)
        } else if (chk.code == 0) {
            Journal.w("Kalit", "Kalit " + slot.keySlot + " tekshirilmadi (tarmoq): " + chk.message)
            sleepMs(5000L)
        } else {
            pool.deadKey(slot)
            Journal.e("Kalit", "Kalit " + slot.keySlot + " yaroqsiz (HTTP " + chk.code + "): " + chk.message)
        }
    }

    private fun handleModel(pool: KeyPool, slot: KeyPool.Slot, r: Gemini.Result) {
        Journal.e("Model", "Model topilmadi yoki ishlamayapti: " + slot.model + " (HTTP " + r.code + "). " + r.message)
        pool.deadModel(slot.model)
        val alt = Gemini.findTtsModel(slot.key, pool.deadModels())
        if (alt != null) {
            Journal.i("Model", "Muqobil TTS modeli topildi: " + alt)
            if (slot.model == Prefs.model(this)) Prefs.setModel(this, alt) else Prefs.setBackup(this, alt)
        } else {
            Journal.w("Model", "Muqobil TTS modeli topilmadi")
        }
    }

    // ---------------------------------------------------------------- audio fayl yozish (MP3, zaxira — WAV)

    private inner class SinkHolder(val id: String) {
        private var fos: FileOutputStream? = null

        /** Muvaffaqiyatli boʻlsa null, aks holda xato matni. */
        fun append(pcm: ByteArray, rate: Int): String? {
            var job = JobStore.get(id) ?: return "Ish oʻchirilgan."
            if (job.format == "mp3") {
                try {
                    val bytes = Mp3Writer.encode(pcm, rate)
                    var o = fos
                    if (o == null) {
                        o = FileOutputStream(JobStore.audio(job), true)
                        fos = o
                    }
                    o.write(bytes)
                    o.flush()
                    try {
                        o.fd.sync()
                    } catch (t: Throwable) {
                    }
                    return null
                } catch (t: Throwable) {
                    Journal.e("Mp3", "MP3 kodlash/yozish xatosi", t)
                    if (job.audioBytes > 0L) {
                        return "MP3 kodlash ishlamadi (" + t.javaClass.simpleName + ": " + (t.message ?: "") + "). Jurnalga qarang."
                    }
                    close()
                    try {
                        JobStore.audio(job).delete()
                    } catch (x: Throwable) {
                    }
                    JobStore.update(id) {
                        it.format = "wav"
                        it.message = "MP3 kodeki ishlamadi — audio WAV formatida saqlanmoqda."
                    }
                    job = JobStore.get(id) ?: return "Ish oʻchirilgan."
                }
            }
            try {
                WavWriter.append(JobStore.audio(job), pcm, rate)
                return null
            } catch (t: Throwable) {
                Journal.e("Wav", "Audio faylga yozib boʻlmadi", t)
                return "Audio faylga yozib boʻlmadi: " + (t.message ?: t.javaClass.simpleName)
            }
        }

        fun close() {
            try {
                fos?.close()
            } catch (t: Throwable) {
            }
            fos = null
        }
    }

    // ---------------------------------------------------------------- holat, bildirishnomalar

    private fun fail(id: String, msg: String, t: Throwable?) {
        Journal.e("Job", msg, t)
        finishStop(id, JobState.FAILED, msg, false)
    }

    private fun finishDone(id: String) {
        val j0 = JobStore.get(id) ?: return
        val extra = if (j0.skipped > 0) " Oʻtkazib yuborilgan qismlar: " + j0.skipped + " ta (TXT sifatida olish mumkin)." else ""
        JobStore.update(id) {
            it.state = JobState.DONE
            it.offset = it.total
            it.message = "Hammasi audiolashtirildi." + extra
        }
        Journal.i("Job", "Tugadi: '" + j0.title + "', audio " + Util.fmtDuration(j0.audioMs) + extra)
        notifyResult(
            "Tayyor: " + j0.title,
            "Audio tayyor (" + Util.fmtDuration(j0.audioMs) + "). Saqlash uchun ilovani oching." + extra,
            id
        )
    }

    private fun finishStop(id: String, state: JobState, msg: String, logIt: Boolean) {
        val j0 = JobStore.get(id) ?: return
        JobStore.update(id) {
            it.state = state
            it.message = msg
        }
        val userPause = state == JobState.PAUSED && msg == USER_PAUSE
        if (userPause) {
            Journal.i("Job", "Toʻxtatildi: '" + j0.title + "' (" + j0.offset + "/" + j0.total + ")")
            return
        }
        if (logIt) {
            Journal.log(if (state == JobState.FAILED) "E" else "W", "Job", "'" + j0.title + "' — " + state.name + ": " + msg)
        }
        val head = when (state) {
            JobState.LIMIT -> "Limit tugadi: "
            JobState.FAILED -> "Xatolik: "
            else -> "Toʻxtadi: "
        }
        notifyResult(head + j0.title, msg, id)
    }

    private fun waitStatus(id: String, msg: String) {
        val j = JobStore.get(id) ?: return
        if (j.state == JobState.WAITING && j.message == msg) return
        JobStore.update(id) {
            it.state = JobState.WAITING
            it.message = msg
        }
        updateNotif(j.title, msg)
    }

    private fun setRunning(id: String, msg: String) {
        val j = JobStore.get(id) ?: return
        if (j.state == JobState.RUNNING && j.message == msg) return
        JobStore.update(id) {
            it.state = JobState.RUNNING
            it.message = msg
        }
    }

    private fun sleepMs(ms: Long) {
        var left = ms
        while (left > 0L && !stopCur) {
            val s = if (left < 250L) left else 250L
            try {
                Thread.sleep(s)
            } catch (e: InterruptedException) {
                return
            }
            left -= s
        }
    }

    private fun acquireWake() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val w = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "audiokitob:work")
            w.setReferenceCounted(false)
            w.acquire(6L * 60L * 60L * 1000L)
            wake = w
        } catch (t: Throwable) {
            Journal.w("Service", "WakeLock olib boʻlmadi", t)
        }
    }

    private fun refreshWake() {
        try {
            wake?.acquire(6L * 60L * 60L * 1000L)
        } catch (t: Throwable) {
        }
    }

    private fun releaseWake() {
        try {
            val w = wake
            if (w != null && w.isHeld) w.release()
        } catch (t: Throwable) {
        }
        wake = null
    }

    private fun pFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

    private fun buildNotification(title: String, text: String): Notification {
        val openI = Intent(this, MainActivity::class.java)
        openI.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(this, 0, openI, pFlags())
        val pauseI = Intent(this, TtsService::class.java)
        pauseI.action = ACTION_PAUSE_ALL
        val pause = PendingIntent.getService(this, 1, pauseI, pFlags())
        return NotificationCompat.Builder(this, "progress")
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Pauza", pause)
            .build()
    }

    private fun goForeground(text: String) {
        val n = buildNotification("Audiokitob", text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotif(title: String, text: String) {
        try {
            val n = buildNotification(title, text)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, n)
        } catch (t: Throwable) {
        }
    }

    private fun notifyResult(title: String, text: String, jobId: String) {
        try {
            val openI = Intent(this, MainActivity::class.java)
            openI.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val open = PendingIntent.getActivity(this, 2, openI, pFlags())
            val n = NotificationCompat.Builder(this, "result")
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(open)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(1000 + (jobId.hashCode() and 0xFFFF), n)
        } catch (t: Throwable) {
            Journal.w("Bildirishnoma", "Natija bildirishnomasini yuborib boʻlmadi", t)
        }
    }
}
