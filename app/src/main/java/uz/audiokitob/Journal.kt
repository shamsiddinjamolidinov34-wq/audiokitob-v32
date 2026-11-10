package uz.audiokitob

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Xato jurnali. Handled xatolar, tarmoq/limit muammolari, ilova qulashlari (uncaught exception)
 * va oldingi jarayon tugash sabablari shu yerga yoziladi. API kalitlar avtomatik maskalanadi.
 * Daraja: I = maʼlumot, W = ogohlantirish, E = xato, F = qulash.
 */
object Journal {
    private const val MAX_BYTES = 1_000_000L
    private var dir: File? = null
    private var version = "?"
    private var secrets: List<String> = emptyList()
    private var errCount = 0
    private var lastErr = ""
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val keyPatterns = listOf(
        Regex("AQ\\.[A-Za-z0-9._\\-]{12,}"),
        Regex("AIza[0-9A-Za-z_\\-]{16,}")
    )
    private val keyParam = Regex("([?&]key=)[^&\\s\"']+")

    @Synchronized
    fun init(ctx: Context) {
        val d = File(ctx.applicationContext.filesDir, "journal")
        d.mkdirs()
        dir = d
        try {
            version = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        } catch (t: Throwable) {
            version = "?"
        }
        countErrors()
    }

    fun i(tag: String, msg: String) = log("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = log("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log("E", tag, msg, t)

    fun deviceInfo(): String =
        "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), " + Build.MANUFACTURER + " " + Build.MODEL

    private fun header(): String = "=== Audiokitob v" + version + " | " + deviceInfo() + " ==="

    fun registerSecrets(list: List<String>) {
        secrets = list.filter { it.length >= 8 }
    }

    /** Jurnalga yoki ulashishga ketadigan matndan API kalitlarni olib tashlaydi. */
    fun clean(s: String): String {
        var r = s
        for (k in secrets) r = r.replace(k, "***KALIT***")
        for (p in keyPatterns) r = p.replace(r, "***KALIT***")
        r = keyParam.replace(r, "\$1***")
        return r
    }

    private fun stackOf(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val s = sw.toString()
        return if (s.length > 6000) s.substring(0, 6000) + "\n    ... (qisqartirildi)" else s
    }

    @Synchronized
    fun log(level: String, tag: String, msg: String, t: Throwable? = null) {
        val d = dir ?: return
        try {
            val f = File(d, "journal.log")
            if (f.exists() && f.length() > MAX_BYTES) {
                val old = File(d, "journal.old.log")
                if (old.exists()) old.delete()
                f.renameTo(old)
                errCount = 0
            }
            val fresh = !f.exists() || f.length() == 0L
            val sb = StringBuilder()
            if (fresh) sb.append(header()).append('\n')
            val line = fmt.format(Date()) + " [" + level + "] " + tag + ": " + clean(msg)
            sb.append(line).append('\n')
            if (t != null) sb.append(clean(stackOf(t))).append('\n')
            FileOutputStream(f, true).use { fos ->
                val w = OutputStreamWriter(fos, Charsets.UTF_8)
                w.write(sb.toString())
                w.flush()
            }
            if (level == "E" || level == "F") {
                errCount++
                lastErr = line.take(300)
            }
        } catch (x: Throwable) {
            // jurnal yozilmasa ham ilova ishlashda davom etadi
        }
    }

    /** Ushlanmagan istisnolarni (qulashlarni) jurnalga yozadi, soʻng tizimning standart ishlovchisiga uzatadi. */
    fun installCrashHandler() {
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, ex ->
            try {
                log("F", "QULASH", "Ushlanmagan xato (oqim: " + th.name + ")", ex)
            } catch (x: Throwable) {
            }
            if (old != null) old.uncaughtException(th, ex)
        }
    }

    fun file(): File? {
        val d = dir ?: return null
        return File(d, "journal.log")
    }

    private fun countErrors() {
        errCount = 0
        lastErr = ""
        val f = file() ?: return
        if (!f.exists()) return
        try {
            f.useLines(Charsets.UTF_8) { lines ->
                for (ln in lines) {
                    if (ln.contains(" [E] ") || ln.contains(" [F] ")) {
                        errCount++
                        lastErr = ln.take(300)
                    }
                }
            }
        } catch (x: Throwable) {
        }
    }

    @Synchronized
    fun errorCount(): Int = errCount

    @Synchronized
    fun lastError(): String = lastErr

    @Synchronized
    fun readTail(maxBytes: Int): String {
        val f = file() ?: return ""
        if (!f.exists()) return ""
        try {
            RandomAccessFile(f, "r").use { raf ->
                val len = raf.length()
                val start = if (len > maxBytes) len - maxBytes else 0L
                raf.seek(start)
                val buf = ByteArray((len - start).toInt())
                raf.readFully(buf)
                var s = String(buf, Charsets.UTF_8)
                if (start > 0) {
                    val nl = s.indexOf('\n')
                    if (nl >= 0) s = s.substring(nl + 1)
                }
                return s
            }
        } catch (x: Throwable) {
            return "Jurnalni oʻqib boʻlmadi: " + x.message
        }
    }

    @Synchronized
    fun lastErrors(n: Int): List<String> {
        val txt = readTail(300_000)
        val res = ArrayList<String>()
        for (ln in txt.lines()) {
            if (ln.contains(" [E] ") || ln.contains(" [F] ")) res.add(ln)
        }
        return if (res.size > n) ArrayList(res.subList(res.size - n, res.size)) else res
    }

    /** Ulashish/saqlash uchun bitta TXT fayl tayyorlaydi (eski + joriy jurnal). */
    @Synchronized
    fun exportFile(): File? {
        val d = dir ?: return null
        try {
            val out = File(d, "Audiokitob-jurnal.txt")
            out.outputStream().use { os ->
                val w = OutputStreamWriter(os, Charsets.UTF_8)
                w.write(header() + "\n")
                w.write("Eksport vaqti: " + fmt.format(Date()) + "\n\n")
                val old = File(d, "journal.old.log")
                if (old.exists()) w.write(old.readText(Charsets.UTF_8) + "\n")
                val cur = File(d, "journal.log")
                if (cur.exists()) w.write(cur.readText(Charsets.UTF_8))
                w.flush()
            }
            return out
        } catch (x: Throwable) {
            return null
        }
    }

    @Synchronized
    fun clear() {
        val d = dir ?: return
        try {
            File(d, "journal.log").delete()
            File(d, "journal.old.log").delete()
            File(d, "Audiokitob-jurnal.txt").delete()
        } catch (x: Throwable) {
        }
        errCount = 0
        lastErr = ""
    }
}
