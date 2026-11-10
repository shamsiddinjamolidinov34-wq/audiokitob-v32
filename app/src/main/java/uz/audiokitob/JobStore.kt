package uz.audiokitob

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ishlar ombori: xotirada + har bir ish uchun filesDir/jobs/<id>/ papkasida.
 * Ichki fayllar (.dat/.json) foydalanuvchiga beriladigan fayllar (.mp3/.txt) bilan adashmasligi uchun alohida kengaytmada.
 */
object JobStore {
    val ACTIVE: Set<JobState> =
        setOf(JobState.NEW, JobState.PREPARING, JobState.QUEUED, JobState.RUNNING, JobState.WAITING)

    private var root: File? = null
    private val jobs = LinkedHashMap<String, Job>()
    private val listeners = CopyOnWriteArrayList<Runnable>()
    private val main = Handler(Looper.getMainLooper())
    private val pending = AtomicBoolean(false)

    @Synchronized
    fun init(ctx: Context) {
        if (root != null) return
        val r = File(ctx.applicationContext.filesDir, "jobs")
        r.mkdirs()
        root = r
        val dirs = r.listFiles() ?: return
        for (d in dirs) {
            val f = File(d, "job.json")
            if (!f.isFile) continue
            try {
                val j = Job.fromJson(JSONObject(f.readText(Charsets.UTF_8)))
                jobs[j.id] = j
            } catch (t: Throwable) {
                Journal.e("JobStore", "job.json oʻqilmadi: " + d.name, t)
            }
        }
    }

    private fun base(): File = root ?: throw IllegalStateException("JobStore init qilinmagan")

    @Synchronized
    fun dir(id: String): File {
        val d = File(base(), id)
        d.mkdirs()
        return d
    }

    fun source(id: String) = File(dir(id), "source.dat")
    fun original(id: String) = File(dir(id), "original.dat")
    fun marks(id: String) = File(dir(id), "marks.dat")
    fun skipped(id: String) = File(dir(id), "skipped.dat")

    /** Audio fayl nomi = kitob/fayl nomi + kengaytma. Hech qanday ortiqcha soʻz qoʻshilmaydi. */
    fun audio(j: Job) = File(dir(j.id), Util.safeName(j.title) + "." + j.format)
    fun remainder(j: Job) = File(dir(j.id), Util.safeName(j.title) + ".txt")
    fun skippedExport(j: Job) = File(dir(j.id), Util.safeName(j.title) + " - oʻtkazilgan qismlar.txt")

    @Synchronized
    fun all(): List<Job> = jobs.values.map { it.copy() }.sortedByDescending { it.createdAt }

    @Synchronized
    fun get(id: String): Job? = jobs[id]?.copy()

    @Synchronized
    fun create(title: String, srcName: String): Job {
        val id = java.util.UUID.randomUUID().toString().substring(0, 8)
        val t = title.trim().ifEmpty { "Audio" }
        val j = Job(id = id, title = if (t.length > 100) t.substring(0, 100) else t, srcName = srcName)
        jobs[id] = j
        persist(j)
        notifyChanged()
        return j.copy()
    }

    /** Ishni oʻzgartiradi va diskka yozadi. Ish oʻchirilgan boʻlsa false qaytaradi. */
    fun update(id: String, block: (Job) -> Unit): Boolean {
        synchronized(this) {
            val j = jobs[id] ?: return false
            block(j)
            j.updatedAt = System.currentTimeMillis()
            persist(j)
        }
        notifyChanged()
        return true
    }

    @Synchronized
    fun delete(id: String) {
        jobs.remove(id)
        try {
            File(base(), id).deleteRecursively()
        } catch (t: Throwable) {
            Journal.e("JobStore", "Ishni oʻchirib boʻlmadi", t)
        }
        notifyChanged()
    }

    /** Xizmat ishlamayotgan boʻlsa-yu, ish "ishlayapti" holatida qolgan boʻlsa — toʻxtatilgan deb belgilaydi. */
    fun fixStale() {
        if (TtsService.running) return
        val ids = synchronized(this) { jobs.values.filter { it.state in ACTIVE }.map { it.id } }
        for (id in ids) {
            update(id) {
                it.state = JobState.PAUSED
                it.message = "Ilova toʻxtab qolgan edi. «Davom ettirish» tugmasini bosing."
            }
        }
        if (ids.isNotEmpty()) Journal.w("JobStore", "Eskirgan holatdagi ishlar toʻxtatildi: " + ids.size)
    }

    fun addListener(r: Runnable) {
        listeners.addIfAbsent(r)
    }

    fun removeListener(r: Runnable) {
        listeners.remove(r)
    }

    private fun notifyChanged() {
        if (pending.compareAndSet(false, true)) {
            main.post {
                pending.set(false)
                for (l in listeners) l.run()
            }
        }
    }

    private fun persist(j: Job) {
        try {
            val d = dir(j.id)
            val tmp = File(d, "job.json.tmp")
            tmp.writeText(j.toJson().toString(), Charsets.UTF_8)
            val dst = File(d, "job.json")
            if (!tmp.renameTo(dst)) {
                dst.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (t: Throwable) {
            Journal.e("JobStore", "Ish holatini saqlab boʻlmadi (xotira yetarlimi?)", t)
        }
    }
}
