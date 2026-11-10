package uz.audiokitob

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : AppCompatActivity() {

    private class Card(val root: LinearLayout, val title: TextView, val status: TextView)

    private lateinit var jobsBox: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvKeys: TextView
    private lateinit var tvJournal: TextView
    private lateinit var etTitle: EditText
    private lateinit var etText: EditText
    private val cards = HashMap<String, Card>()
    private val ui = Handler(Looper.getMainLooper())
    private val listener = Runnable { refresh() }
    private var pendingExport: File? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importUri(uri)
    }
    private val saveMp3 = registerForActivityResult(CreateDoc("audio/mpeg")) { uri: Uri? -> onSaved(uri) }
    private val saveWav = registerForActivityResult(CreateDoc("audio/x-wav")) { uri: Uri? -> onSaved(uri) }
    private val saveTxt = registerForActivityResult(CreateDoc("text/plain")) { uri: Uri? -> onSaved(uri) }
    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted: Boolean ->
        Journal.i("UI", "Bildirishnoma ruxsati: " + granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Audiokitob"
        val page = Ui.page(this)
        page.addView(
            Ui.text(
                this,
                "Matn yoki kitobni oʻzbekcha audioga (MP3) aylantiradi. Fayl tanlang yoki matn joylang. " +
                    "Ish fonda davom etadi — ilovani yopsangiz ham.",
                16f
            )
        )
        tvKeys = Ui.text(this, "", 15f)
        page.addView(tvKeys)
        page.addView(Ui.button(this, "Fayl tanlash (TXT, PDF, EPUB, DOCX, FB2, HTML, ODT)") {
            pickFile.launch(arrayOf("*/*"))
        })

        etTitle = Ui.edit(this, "Audio nomi (ixtiyoriy)")
        Ui.labeled(this, page, "Matn joylash uchun: audio nomi (ixtiyoriy)", etTitle)
        etText = Ui.edit(this, "Matnni shu yerga joylang", true)
        Ui.labeled(this, page, "Matn", etText)
        page.addView(Ui.button(this, "Joylangan matnni audioga aylantirish") { onTextClicked() })

        page.addView(Ui.button(this, "Sozlamalar (API kalitlar, ovoz)") {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        page.addView(Ui.button(this, "Xato jurnali") {
            startActivity(Intent(this, JournalActivity::class.java))
        })
        tvJournal = Ui.text(this, "", 14f)
        page.addView(tvJournal)

        page.addView(Ui.heading(this, "Ishlar roʻyxati"))
        page.addView(
            Ui.text(
                this,
                "Ishni tanlab ikki marta bosing — amallar menyusi ochiladi (davom ettirish, saqlash, ulashish, qolgan matn).",
                14f
            )
        )
        tvEmpty = Ui.text(this, "Hozircha hech narsa yoʻq.", 15f)
        page.addView(tvEmpty)
        jobsBox = LinearLayout(this)
        jobsBox.orientation = LinearLayout.VERTICAL
        page.addView(jobsBox, Ui.lp(this, 4))

        setContentView(Ui.scroll(this, page))

        askNotificationPermission()
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        JobStore.addListener(listener)
        refresh()
    }

    override fun onStop() {
        JobStore.removeListener(listener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateInfo()
        ui.postDelayed({ JobStore.fixStale() }, 2500L)
    }

    // ---------------------------------------------------------------- kirish: fayl yoki matn

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val act = i.action
        if (act == Intent.ACTION_SEND) {
            val stream = streamOf(i)
            if (stream != null) {
                importUri(stream)
            } else {
                val t = i.getStringExtra(Intent.EXTRA_TEXT)
                if (!t.isNullOrBlank()) importText(t, i.getStringExtra(Intent.EXTRA_SUBJECT))
            }
            i.action = null
        } else if (act == Intent.ACTION_VIEW) {
            val d = i.data
            if (d != null) importUri(d)
            i.action = null
        }
    }

    @Suppress("DEPRECATION")
    private fun streamOf(i: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            i.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun ensureKeys(): Boolean {
        if (Prefs.keys(this).isNotEmpty()) return true
        AlertDialog.Builder(this)
            .setTitle("API kalit kerak")
            .setMessage("Avval Sozlamalarda kamida bitta Gemini API kalit kiriting. Sozlamalar ochilsinmi?")
            .setPositiveButton("Ochish") { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
            .setNegativeButton("Bekor qilish", null)
            .show()
        return false
    }

    private fun importUri(uri: Uri) {
        if (!ensureKeys()) return
        val name = Util.displayName(this, uri) ?: "Kitob"
        toast("Fayl qabul qilinmoqda…")
        val app = applicationContext
        Thread {
            var jobId: String? = null
            try {
                val job = JobStore.create(Util.stripExt(name), name)
                jobId = job.id
                val dst = JobStore.original(job.id)
                val ins = app.contentResolver.openInputStream(uri)
                if (ins == null) throw java.io.IOException("Faylni ochib boʻlmadi")
                ins.use { input -> dst.outputStream().use { out -> input.copyTo(out) } }
                Journal.i("Import", "Fayl nusxalandi: '" + name + "' (" + dst.length() + " bayt)")
                TtsService.start(app, job.id)
            } catch (t: Throwable) {
                Journal.e("Import", "Faylni qabul qilib boʻlmadi: " + name, t)
                val jid = jobId
                if (jid != null) JobStore.delete(jid)
                runOnUiThread { toast("Faylni oʻqib boʻlmadi: " + (t.message ?: t.javaClass.simpleName)) }
            }
        }.start()
    }

    private fun onTextClicked() {
        val t = etText.text.toString()
        if (t.isBlank()) {
            toast("Avval matnni joylang.")
            return
        }
        importText(t, etTitle.text.toString())
    }

    private fun firstLine(s: String): String {
        val line = s.trimStart().lineSequence().firstOrNull() ?: ""
        val t = Util.snippet(line, 60)
        return if (t.isEmpty()) "Matn" else t.removeSuffix("…")
    }

    private fun importText(text: String, titleHint: String?) {
        if (!ensureKeys()) return
        val app = applicationContext
        Thread {
            var jobId: String? = null
            try {
                val cleaned = TextTools.clean(text)
                if (cleaned.isBlank()) {
                    runOnUiThread { toast("Matn boʻsh.") }
                } else {
                    val hint = titleHint?.trim() ?: ""
                    val ttl = if (hint.isNotEmpty()) hint else firstLine(cleaned)
                    val job = JobStore.create(ttl, "")
                    jobId = job.id
                    JobStore.source(job.id).writeText(cleaned, Charsets.UTF_8)
                    JobStore.update(job.id) {
                        it.total = cleaned.length
                        it.preview = Util.snippet(cleaned, 160)
                    }
                    Journal.i("Import", "Matn qabul qilindi: '" + ttl + "' (" + cleaned.length + " belgi)")
                    runOnUiThread {
                        etText.setText("")
                        etTitle.setText("")
                    }
                    TtsService.start(app, job.id)
                }
            } catch (t: Throwable) {
                Journal.e("Import", "Matnni qabul qilib boʻlmadi", t)
                val jid = jobId
                if (jid != null) JobStore.delete(jid)
                runOnUiThread { toast("Matnni qabul qilib boʻlmadi: " + (t.message ?: t.javaClass.simpleName)) }
            }
        }.start()
    }

    // ---------------------------------------------------------------- ro'yxat

    private fun updateInfo() {
        val n = Prefs.keys(this).size
        tvKeys.text = if (n == 0) {
            "API kalit kiritilmagan. «Sozlamalar»da kamida bitta Gemini API kalit kiriting."
        } else {
            "Kiritilgan API kalitlar: " + n + " ta (eng koʻpi " + Prefs.MAX_KEYS + ")."
        }
        val ec = Journal.errorCount()
        tvJournal.text = if (ec == 0) {
            "Xato jurnali: xato yozilmagan."
        } else {
            "Xato jurnali: " + ec + " ta xato yozilgan. Oxirgisi: " + Journal.lastError().take(160)
        }
    }

    private fun refresh() {
        val jobs = JobStore.all()
        val ids = HashSet<String>()
        for (j in jobs) ids.add(j.id)
        val iter = cards.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (!ids.contains(e.key)) {
                jobsBox.removeView(e.value.root)
                iter.remove()
            }
        }
        tvEmpty.visibility = if (jobs.isEmpty()) View.VISIBLE else View.GONE
        for ((idx, j) in jobs.withIndex()) {
            var c = cards[j.id]
            if (c == null) {
                c = makeCard(j.id)
                cards[j.id] = c
                jobsBox.addView(c.root, if (idx <= jobsBox.childCount) idx else jobsBox.childCount)
            }
            bind(c, j)
        }
        updateInfo()
    }

    private fun makeCard(id: String): Card {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val p = Ui.dp(this, 14)
        root.setPadding(p, p, p, p)
        root.setBackgroundResource(R.drawable.card_bg)
        root.isClickable = true
        root.isFocusable = true
        root.layoutParams = Ui.lp(this, 12)
        val title = Ui.text(this, "", 18f, true, 0)
        val status = Ui.text(this, "", 15f, false, 6)
        root.addView(title)
        root.addView(status)
        root.setOnClickListener { showActions(id) }
        return Card(root, title, status)
    }

    /** Matnni faqat oʻzgargandagina yangilaymiz — TalkBack fokusi va oʻqishi buzilmasligi uchun. */
    private fun bind(c: Card, j: Job) {
        if (c.title.text.toString() != j.title) c.title.text = j.title
        val st = statusText(j)
        if (c.status.text.toString() != st) c.status.text = st
    }

    private fun statusText(j: Job): String {
        val pct = if (j.total > 0) (j.offset.toLong() * 100L / j.total).toInt() else 0
        val sb = StringBuilder()
        val head = when (j.state) {
            JobState.NEW -> "Holat: boshlanmagan"
            JobState.PREPARING -> "Holat: matn tayyorlanmoqda"
            JobState.QUEUED -> "Holat: navbatda"
            JobState.RUNNING -> "Holat: audiolashtirilmoqda — " + pct + "%"
            JobState.WAITING -> "Holat: kutilmoqda — " + pct + "%"
            JobState.PAUSED -> "Holat: toʻxtatilgan — " + pct + "%"
            JobState.LIMIT -> "Holat: limit tugadi — " + pct + "%"
            JobState.DONE -> "Holat: tayyor — 100%"
            JobState.FAILED -> "Holat: xatolik — " + pct + "%"
        }
        sb.append(head)
        if (j.message.isNotBlank()) sb.append("\n").append(j.message)
        if (j.audioMs > 0L) {
            sb.append("\nAudio uzunligi: ").append(Util.fmtDuration(j.audioMs))
                .append(" (").append(j.format.uppercase()).append(")")
        }
        if (j.offset > 0 && j.state != JobState.DONE) {
            sb.append("\nAudiolashtirilgan joy: ")
            if (j.posLabel.isNotBlank()) sb.append(j.posLabel).append(", ")
            sb.append(j.offset).append(" / ").append(j.total).append(" belgi")
        }
        if (j.lastSnippet.isNotBlank() && j.state != JobState.DONE) {
            sb.append("\nOxirgi audiolashtirilgan matn: «").append(j.lastSnippet).append("»")
        }
        if (j.skipped > 0) sb.append("\nOʻtkazib yuborilgan qismlar: ").append(j.skipped).append(" ta")
        val stopped = j.state == JobState.PAUSED || j.state == JobState.LIMIT || j.state == JobState.FAILED
        if (stopped && j.total > 0 && j.offset < j.total) {
            sb.append("\nAudiolashtirilmagan qism TXT sifatida tayyor (Amallar → Qolgan matn).")
        }
        if (j.preview.isNotBlank() && j.offset == 0 && j.state != JobState.DONE) {
            sb.append("\nMatn boshi: «").append(j.preview).append("»")
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- amallar menyusi

    private fun mimeOf(j: Job): String = if (j.format == "wav") "audio/x-wav" else "audio/mpeg"

    private fun showActions(id: String) {
        val j = JobStore.get(id) ?: return
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        val working = j.state in JobStore.ACTIVE && j.state != JobState.NEW
        if (working) {
            labels.add("Pauza")
            acts.add { TtsService.pause(this, id) }
        } else if (j.state != JobState.DONE) {
            labels.add(if (j.offset > 0) "Davom ettirish" else "Boshlash")
            acts.add { TtsService.start(this, id, true) }
        }
        if (j.audioBytes > 0L) {
            labels.add("Audioni saqlash (" + j.format.uppercase() + ")")
            acts.add { saveAudio(j) }
            labels.add("Audioni ulashish")
            acts.add { shareFile(JobStore.audio(j), mimeOf(j)) }
            labels.add("Audioni ijro etish")
            acts.add { playFile(JobStore.audio(j), mimeOf(j)) }
        }
        if (j.total > 0 && j.offset < j.total && j.state != JobState.PREPARING) {
            labels.add("Audiolashtirilmagan qolgan matnni saqlash (TXT)")
            acts.add { exportRemainder(id, false) }
            labels.add("Qolgan matnni ulashish (TXT)")
            acts.add { exportRemainder(id, true) }
        }
        if (j.skipped > 0) {
            labels.add("Oʻtkazib yuborilgan qismlarni saqlash (TXT)")
            acts.add { exportSkipped(id) }
        }
        if (!working) {
            labels.add("Ishni oʻchirish")
            acts.add { confirmDelete(id, j.title) }
        }
        AlertDialog.Builder(this)
            .setTitle(j.title)
            .setItems(labels.toTypedArray()) { _, which -> acts[which]() }
            .setNegativeButton("Yopish", null)
            .show()
    }

    private fun saveAudio(j: Job) {
        val f = JobStore.audio(j)
        if (!f.exists()) {
            toast("Audio fayl topilmadi.")
            return
        }
        pendingExport = f
        if (j.format == "wav") saveWav.launch(f.name) else saveMp3.launch(f.name)
    }

    private fun shareFile(f: File, mime: String) {
        try {
            val uri = FileProvider.getUriForFile(this, packageName + ".files", f)
            val i = Intent(Intent.ACTION_SEND)
            i.type = mime
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Ulashish"))
        } catch (t: Throwable) {
            Journal.e("Export", "Ulashib boʻlmadi: " + f.name, t)
            toast("Ulashib boʻlmadi: " + (t.message ?: t.javaClass.simpleName))
        }
    }

    private fun playFile(f: File, mime: String) {
        try {
            val uri = FileProvider.getUriForFile(this, packageName + ".files", f)
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, mime)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            toast("Audioni ochadigan ilova topilmadi. Avval saqlab oling.")
        } catch (t: Throwable) {
            Journal.e("Export", "Ijro etib boʻlmadi: " + f.name, t)
            toast("Ijro etib boʻlmadi: " + (t.message ?: t.javaClass.simpleName))
        }
    }

    /** Audiolashtirilmagan qolgan matn — faqat oʻsha matnning oʻzi, hech narsa qoʻshilmagan TXT. Nomi = kitob nomi. */
    private fun exportRemainder(id: String, share: Boolean) {
        Thread {
            try {
                val j = JobStore.get(id)
                if (j == null) {
                    runOnUiThread { toast("Ish topilmadi.") }
                } else {
                    val text = JobStore.source(id).readText(Charsets.UTF_8)
                    val rest = text.substring(minOf(j.offset, text.length)).trim()
                    if (rest.isEmpty()) {
                        runOnUiThread { toast("Audiolashtirilmagan matn qolmagan.") }
                    } else {
                        val f = JobStore.remainder(j)
                        f.writeText(rest, Charsets.UTF_8)
                        runOnUiThread {
                            if (share) {
                                shareFile(f, "text/plain")
                            } else {
                                pendingExport = f
                                saveTxt.launch(f.name)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Journal.e("Export", "Qolgan matnni tayyorlab boʻlmadi", t)
                runOnUiThread { toast("Tayyorlab boʻlmadi: " + (t.message ?: t.javaClass.simpleName)) }
            }
        }.start()
    }

    private fun exportSkipped(id: String) {
        Thread {
            try {
                val j = JobStore.get(id)
                val src = JobStore.skipped(id)
                if (j == null || !src.exists()) {
                    runOnUiThread { toast("Oʻtkazilgan qismlar topilmadi.") }
                } else {
                    val f = JobStore.skippedExport(j)
                    f.writeText(src.readText(Charsets.UTF_8), Charsets.UTF_8)
                    runOnUiThread {
                        pendingExport = f
                        saveTxt.launch(f.name)
                    }
                }
            } catch (t: Throwable) {
                Journal.e("Export", "Oʻtkazilgan qismlarni tayyorlab boʻlmadi", t)
                runOnUiThread { toast("Tayyorlab boʻlmadi: " + (t.message ?: t.javaClass.simpleName)) }
            }
        }.start()
    }

    private fun onSaved(uri: Uri?) {
        val src = pendingExport ?: return
        if (uri == null) return
        Thread {
            try {
                val os = contentResolver.openOutputStream(uri, "w")
                if (os == null) throw java.io.IOException("Fayl yaratilmadi")
                os.use { out -> src.inputStream().use { input -> input.copyTo(out) } }
                Journal.i("Export", "Saqlandi: " + src.name + " (" + src.length() + " bayt)")
                runOnUiThread { toast("Saqlandi.") }
            } catch (t: Throwable) {
                Journal.e("Export", "Saqlab boʻlmadi: " + src.name, t)
                runOnUiThread { toast("Saqlab boʻlmadi: " + (t.message ?: t.javaClass.simpleName)) }
            }
        }.start()
    }

    private fun confirmDelete(id: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle("Ishni oʻchirish")
            .setMessage("«" + name + "» va uning audio fayli oʻchiriladi. Avval audioni saqlab oling. Oʻchirilsinmi?")
            .setPositiveButton("Oʻchirish") { _, _ ->
                JobStore.delete(id)
                Journal.i("UI", "Ish oʻchirildi: " + name)
            }
            .setNegativeButton("Bekor qilish", null)
            .show()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val sp = getSharedPreferences("ui", Context.MODE_PRIVATE)
        if (sp.getBoolean("askedNotif", false)) return
        sp.edit().putBoolean("askedNotif", true).apply()
        askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
