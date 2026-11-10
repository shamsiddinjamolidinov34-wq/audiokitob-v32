package uz.audiokitob

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Util {
    private val BAD_NAME = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")
    private val WS = Regex("\\s+")

    /** Fayl nomi uchun faqat taqiqlangan belgilar olib tashlanadi; boshqa hech narsa qoʻshilmaydi. */
    fun safeName(s: String): String {
        var r = BAD_NAME.replace(s, " ")
        r = WS.replace(r, " ").trim().trim('.', ' ')
        if (r.length > 100) r = r.substring(0, 100).trim()
        return if (r.isEmpty()) "Audio" else r
    }

    fun stripExt(name: String): String {
        var base = name
        val i = base.lastIndexOf('.')
        if (i > 0) {
            val ext = base.substring(i + 1)
            if (ext.isNotEmpty() && ext.length <= 5 && !ext.contains(' ')) base = base.substring(0, i)
        }
        if (base.endsWith(".fb2", ignoreCase = true)) base = base.substring(0, base.length - 4)
        return base
    }

    fun fmtDuration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val parts = ArrayList<String>()
        if (h > 0) parts.add("$h soat")
        if (m > 0) parts.add("$m daqiqa")
        if (h == 0L && (s > 0 || parts.isEmpty())) parts.add("$s soniya")
        return parts.joinToString(" ")
    }

    fun fmtDateTime(ts: Long): String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(ts))

    fun fmt1(d: Double): String = String.format(Locale.US, "%.1f", d)

    /** Matnning boshidan n ta belgi (bir qatorga keltirilgan). */
    fun snippet(s: String, n: Int): String {
        val t = WS.replace(s.trim().take(n * 3), " ").trim()
        return if (t.length > n) t.substring(0, n).trim() + "…" else t
    }

    /** Matnning oxiridan n ta belgi (bir qatorga keltirilgan). */
    fun tail(s: String, n: Int): String {
        val t = WS.replace(s.trim().takeLast(n * 3), " ").trim()
        return if (t.length > n) "…" + t.substring(t.length - n).trim() else t
    }

    fun isOnline(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= 23) {
                val n = cm.activeNetwork
                if (n == null) {
                    false
                } else {
                    val c = cm.getNetworkCapabilities(n)
                    c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
            } else {
                @Suppress("DEPRECATION")
                val i = cm.activeNetworkInfo
                i != null && i.isConnected
            }
        } catch (t: Throwable) {
            true
        }
    }

    fun displayName(ctx: Context, uri: Uri): String? {
        try {
            val c = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (c != null) {
                c.use {
                    if (it.moveToFirst()) {
                        val i = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0) {
                            val n = it.getString(i)
                            if (!n.isNullOrBlank()) return n
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Journal.w("Util", "Fayl nomini aniqlab boʻlmadi", t)
        }
        val last = uri.lastPathSegment
        return last?.substringAfterLast('/')
    }
}
