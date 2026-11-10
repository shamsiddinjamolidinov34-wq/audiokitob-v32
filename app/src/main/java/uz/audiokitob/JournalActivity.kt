package uz.audiokitob

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

/** Xato jurnali ekrani: koʻrish, ulashish, TXT sifatida saqlash, nusxalash, sinash, tozalash. */
class JournalActivity : AppCompatActivity() {

    private lateinit var tvSummary: TextView
    private lateinit var tvLog: TextView
    private var pendingExport: File? = null

    private val saveTxt = registerForActivityResult(CreateDoc("text/plain")) { uri: Uri? -> onSaved(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Xato jurnali"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val page = Ui.page(this)
        tvSummary = Ui.text(this, "", 15f)
        page.addView(tvSummary)
        page.addView(Ui.button(this, "Jurnalni ulashish (TXT)") { shareLog() })
        page.addView(Ui.button(this, "Jurnalni TXT fayl sifatida saqlash") { saveLog() })
        page.addView(Ui.button(this, "Jurnal matnini nusxalash (bufer)") { copyLog() })
        page.addView(Ui.button(this, "Yangilash") { refresh() })
        page.addView(Ui.button(this, "Jurnalni sinash (sinov yozuvlarini qoʻshish)") { selfTest() })
        page.addView(Ui.button(this, "Jurnalni tozalash") { confirmClear() })
        page.addView(Ui.heading(this, "Jurnal (oxirgi qismi)"))
        tvLog = Ui.text(this, "", 12f)
        tvLog.typeface = Typeface.MONOSPACE
        tvLog.setTextIsSelectable(true)
        page.addView(tvLog)
        setContentView(Ui.scroll(this, page))
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun refresh() {
        val n = Journal.errorCount()
        val sb = StringBuilder()
        sb.append(if (n == 0) "Xato yozilmagan." else "Yozilgan xatolar: $n ta.")
        val last = Journal.lastErrors(5)
        if (last.isNotEmpty()) {
            sb.append(" Oxirgi xatolar:")
            for (l in last) sb.append("\n• ").append(l.take(220))
        }
        sb.append("\nJurnal API kalitlarni oʻz ichiga olmaydi (maskalanadi). Dasturchiga yuborish uchun «Jurnalni ulashish»ni bosing.")
        tvSummary.text = sb.toString()
        val tail = Journal.readTail(60000)
        tvLog.text = if (tail.isEmpty()) "Jurnal boʻsh." else tail
    }

    private fun shareLog() {
        val f = Journal.exportFile()
        if (f == null) {
            toast("Jurnal faylini tayyorlab boʻlmadi.")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, packageName + ".files", f)
            val i = Intent(Intent.ACTION_SEND)
            i.type = "text/plain"
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Jurnalni ulashish"))
        } catch (t: Throwable) {
            Journal.e("Jurnal", "Jurnalni ulashib boʻlmadi", t)
            toast("Ulashib boʻlmadi: " + (t.message ?: t.javaClass.simpleName))
        }
    }

    private fun saveLog() {
        val f = Journal.exportFile()
        if (f == null) {
            toast("Jurnal faylini tayyorlab boʻlmadi.")
            return
        }
        pendingExport = f
        saveTxt.launch(f.name)
    }

    private fun onSaved(uri: Uri?) {
        val src = pendingExport ?: return
        if (uri == null) return
        Thread {
            try {
                val os = contentResolver.openOutputStream(uri, "w")
                if (os == null) throw java.io.IOException("Fayl yaratilmadi")
                os.use { out -> src.inputStream().use { input -> input.copyTo(out) } }
                runOnUiThread { toast("Jurnal saqlandi.") }
            } catch (t: Throwable) {
                Journal.e("Jurnal", "Jurnalni saqlab boʻlmadi", t)
                runOnUiThread { toast("Saqlab boʻlmadi: " + (t.message ?: t.javaClass.simpleName)) }
            }
        }.start()
    }

    private fun copyLog() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Audiokitob jurnali", Journal.readTail(60000)))
            toast("Jurnalning oxirgi qismi nusxalandi.")
        } catch (t: Throwable) {
            toast("Nusxalab boʻlmadi.")
        }
    }

    private fun selfTest() {
        Journal.i("Sinov", "Sinov yozuvi (maʼlumot)")
        Journal.w("Sinov", "Sinov ogohlantirishi")
        Journal.e("Sinov", "Sinov xatosi — bu haqiqiy xato emas, jurnal ishlayotganini tekshirish uchun", RuntimeException("sinov istisnosi"))
        refresh()
        toast("Sinov yozuvlari qoʻshildi — jurnal ishlayapti.")
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Jurnalni tozalash")
            .setMessage("Barcha jurnal yozuvlari oʻchiriladi. Davom etasizmi?")
            .setPositiveButton("Tozalash") { _, _ ->
                Journal.clear()
                Journal.i("Jurnal", "Jurnal tozalandi")
                refresh()
            }
            .setNegativeButton("Bekor qilish", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
