package uz.audiokitob

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.ZipFile

/** Matndagi joy belgisi: offset dan boshlab "sahifa 12 / 300" va hokazo. */
class Mark(val offset: Int, val label: String)

class Extracted(val text: String, val marks: List<Mark>, val warning: String? = null)

class ExtractException(message: String) : Exception(message)

/** Oddiy HTML/XHTML dan matn ajratish (EPUB boʻlimlari va HTML fayllar uchun). */
object HtmlText {
    private val COMMENT = Regex("(?s)<!--.*?-->")
    private val HIDDEN = Regex("(?is)<(script|style|head)\\b.*?</\\1\\s*>")
    private val BLOCK = Regex("(?i)</(p|div|h[1-6]|li|tr|blockquote|section|article|pre|table)\\s*>|<br\\s*/?>|<hr\\s*/?>")
    private val TAG = Regex("(?s)<[^>]*>")
    private val ENT = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);")
    private val NAMED = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "laquo" to "«", "raquo" to "»", "mdash" to "—", "ndash" to "–", "hellip" to "…",
        "rsquo" to "’", "lsquo" to "‘", "ldquo" to "“", "rdquo" to "”", "bdquo" to "„",
        "shy" to "", "zwnj" to "", "zwj" to ""
    )

    fun toPlain(html: String): String {
        var s = COMMENT.replace(html, " ")
        s = HIDDEN.replace(s, " ")
        s = BLOCK.replace(s, "\n")
        s = TAG.replace(s, "")
        return decode(s)
    }

    private fun cp(v: Int?): String {
        if (v == null || v <= 0 || v > 0x10FFFF) return ""
        return String(Character.toChars(v))
    }

    fun decode(s: String): String {
        if (s.indexOf('&') < 0) return s
        return ENT.replace(s) { m ->
            val g = m.groupValues[1]
            if (g.startsWith("#x") || g.startsWith("#X")) cp(g.substring(2).toIntOrNull(16))
            else if (g.startsWith("#")) cp(g.substring(1).toIntOrNull())
            else (NAMED[g] ?: m.value)
        }
    }
}

/** Fayldan matn ajratish: TXT, PDF, EPUB, DOCX, ODT, FB2 (+zip), HTML. */
object Extractors {

    fun extract(ctx: Context, file: File, name: String, progress: (String) -> Unit): Extracted {
        val type = detect(file, name)
        Journal.i("Extract", "Fayl turi: " + type + ", hajmi: " + file.length() + " bayt, nomi: " + name)
        val r: Extracted = when (type) {
            "pdf" -> pdf(ctx, file, progress)
            "epub" -> epub(file, progress)
            "docx" -> docx(file)
            "odt" -> odt(file)
            "fb2" -> {
                val ins = file.inputStream().buffered()
                try {
                    fb2(ins)
                } finally {
                    ins.close()
                }
            }
            "fb2zip" -> fb2zip(file)
            "html" -> Extracted(TextTools.clean(HtmlText.toPlain(decode(file.readBytes()))), emptyList())
            "rtf" -> throw ExtractException("RTF format hozircha qoʻllab-quvvatlanmaydi. Faylni DOCX yoki TXT ga oʻgirib qayta yuboring.")
            "doc" -> throw ExtractException("Eski DOC format qoʻllab-quvvatlanmaydi. Faylni DOCX yoki TXT ga oʻgirib qayta yuboring.")
            "mobi" -> throw ExtractException("MOBI/AZW format qoʻllab-quvvatlanmaydi. Faylni EPUB yoki TXT ga oʻgirib qayta yuboring.")
            "zip" -> throw ExtractException("ZIP arxiv ichida tanish format topilmadi. Arxivni ochib, ichidagi faylni yuboring.")
            else -> txt(file)
        }
        if (r.text.isBlank()) throw ExtractException("Faylda oʻqiladigan matn topilmadi.")
        return r
    }

    fun detect(file: File, name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val head = ByteArray(4096)
        val n = try {
            file.inputStream().use { it.read(head) }
        } catch (e: Exception) {
            -1
        }
        if (n >= 4 && head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() &&
            head[2] == 'D'.code.toByte() && head[3] == 'F'.code.toByte()
        ) return "pdf"
        if (n >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) return sniffZip(file, ext)
        if (n >= 5 && String(head, 0, 5, Charsets.ISO_8859_1) == "{\\rtf") return "rtf"
        if (n >= 4 && head[0] == 0xD0.toByte() && head[1] == 0xCF.toByte() &&
            head[2] == 0x11.toByte() && head[3] == 0xE0.toByte()
        ) return "doc"
        val sample = if (n > 0) String(head, 0, n, Charsets.ISO_8859_1).lowercase(Locale.ROOT) else ""
        if (sample.contains("<fictionbook")) return "fb2"
        if (sample.contains("<html") || sample.contains("<!doctype html")) return "html"
        return when (ext) {
            "fb2" -> "fb2"
            "html", "htm", "xhtml" -> "html"
            "rtf" -> "rtf"
            "doc" -> "doc"
            "mobi", "azw", "azw3" -> "mobi"
            else -> "txt"
        }
    }

    private fun sniffZip(file: File, ext: String): String {
        try {
            val z = ZipFile(file)
            try {
                if (z.getEntry("word/document.xml") != null) return "docx"
                if (z.getEntry("META-INF/container.xml") != null) return "epub"
                if (z.getEntry("content.xml") != null && z.getEntry("mimetype") != null) return "odt"
                val en = z.entries()
                while (en.hasMoreElements()) {
                    val e = en.nextElement()
                    if (e.name.lowercase(Locale.ROOT).endsWith(".fb2")) return "fb2zip"
                }
            } finally {
                try {
                    z.close()
                } catch (t: Throwable) {
                }
            }
        } catch (e: Exception) {
            Journal.w("Extract", "ZIP tuzilishini aniqlab boʻlmadi", e)
        }
        return when (ext) {
            "epub" -> "epub"
            "docx" -> "docx"
            "odt" -> "odt"
            else -> "zip"
        }
    }

    /** BOM → qatʼiy UTF-8 → (boʻlmasa) windows-1251 (eski kirill matnlar uchun). */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        return try {
            val dec = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            dec.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            Journal.w("Extract", "Fayl UTF-8 emas — windows-1251 deb oʻqildi")
            String(bytes, Charset.forName("windows-1251"))
        }
    }

    private fun txt(file: File): Extracted {
        val bytes = file.readBytes()
        var zeros = 0
        val lim = minOf(bytes.size, 4096)
        for (i in 0 until lim) if (bytes[i].toInt() == 0) zeros++
        val bom16 = bytes.size >= 2 &&
            ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) || (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))
        if (zeros > 20 && !bom16) throw ExtractException("Fayl matn emas (ikkilik fayl) — qoʻllab-quvvatlanmaydi.")
        return Extracted(TextTools.clean(decode(bytes)), emptyList())
    }

    private fun pdf(ctx: Context, file: File, progress: (String) -> Unit): Extracted {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        var toClose: PDDocument? = null
        try {
            val d: PDDocument = try {
                PDDocument.load(file)
            } catch (e: Exception) {
                val m = (e.message ?: "").lowercase(Locale.ROOT)
                if (e.javaClass.simpleName.contains("InvalidPassword") || m.contains("password")) {
                    throw ExtractException("PDF parol bilan himoyalangan — parolsiz nusxasini yuboring.")
                }
                throw ExtractException("PDF faylni ochib boʻlmadi: " + e.javaClass.simpleName + " " + (e.message ?: ""))
            }
            toClose = d
            val pages = d.getNumberOfPages()
            val stripper = PDFTextStripper()
            stripper.setSortByPosition(true)
            val sb = StringBuilder()
            val marks = ArrayList<Mark>()
            var letters = 0L
            var nonSpace = 0L
            var badPages = 0
            for (p in 1..pages) {
                stripper.setStartPage(p)
                stripper.setEndPage(p)
                val raw = try {
                    stripper.getText(d)
                } catch (e: Exception) {
                    badPages++
                    Journal.w("Extract", "PDF sahifa $p oʻqilmadi", e)
                    ""
                }
                val t = TextTools.cleanPdfPage(raw)
                if (t.isNotEmpty()) {
                    marks.add(Mark(sb.length, "sahifa $p / $pages"))
                    sb.append(t).append('\n')
                    for (c in t) {
                        if (!c.isWhitespace()) {
                            nonSpace++
                            if (c.isLetter()) letters++
                        }
                    }
                }
                if (p % 5 == 0 || p == pages) progress("PDF oʻqilmoqda: $p / $pages sahifa")
            }
            if (sb.isBlank()) {
                throw ExtractException("PDFda matn topilmadi. Bu skan qilingan (rasmli) PDF boʻlishi mumkin — avval matnga aylantirish (OCR) kerak.")
            }
            var warn: String? = null
            if (nonSpace > 200 && letters.toDouble() / nonSpace < 0.5) {
                warn = "Diqqat: PDF matni buzilgan koʻrinadi (shrift kodirovkasi muammosi). Boshini eshitib tekshirib koʻring."
            } else if (badPages > 0) {
                warn = "Diqqat: PDFning $badPages ta sahifasi oʻqilmadi."
            }
            return Extracted(sb.toString(), marks, warn)
        } finally {
            try {
                toClose?.close()
            } catch (t: Throwable) {
            }
        }
    }

    private fun newParser(ins: InputStream): XmlPullParser {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(ins, null)
        return p
    }

    private fun local(n: String?): String {
        if (n == null) return ""
        val i = n.indexOf(':')
        return if (i >= 0) n.substring(i + 1) else n
    }

    private fun resolvePath(base: String, rel: String): String {
        val parts = ArrayList<String>()
        if (base.isNotEmpty()) parts.addAll(base.split('/'))
        for (seg in rel.split('/')) {
            if (seg == "" || seg == ".") continue
            if (seg == "..") {
                if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            } else {
                parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun epub(file: File, progress: (String) -> Unit): Extracted {
        val zip = ZipFile(file)
        try {
            val ce = zip.getEntry("META-INF/container.xml")
                ?: throw ExtractException("EPUB tuzilishi notoʻgʻri (container.xml yoʻq).")
            var opfPath: String? = null
            zip.getInputStream(ce).use { ins ->
                val p = newParser(ins)
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT && opfPath == null) {
                    if (ev == XmlPullParser.START_TAG && local(p.name) == "rootfile") {
                        opfPath = p.getAttributeValue(null, "full-path")
                    }
                    ev = p.next()
                }
            }
            val opf: String = opfPath ?: throw ExtractException("EPUB tuzilishi notoʻgʻri (OPF yoʻli topilmadi).")
            val oe = zip.getEntry(opf) ?: zip.getEntry(Uri.decode(opf))
                ?: throw ExtractException("EPUB tuzilishi notoʻgʻri (OPF fayl yoʻq).")
            val manifest = HashMap<String, String>()
            val spine = ArrayList<String>()
            zip.getInputStream(oe).use { ins ->
                val p = newParser(ins)
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        val n = local(p.name)
                        if (n == "item") {
                            val id = p.getAttributeValue(null, "id")
                            val href = p.getAttributeValue(null, "href")
                            if (id != null && href != null) manifest[id] = href
                        } else if (n == "itemref") {
                            val idref = p.getAttributeValue(null, "idref")
                            if (idref != null) spine.add(idref)
                        }
                    }
                    ev = p.next()
                }
            }
            if (spine.isEmpty()) throw ExtractException("EPUB ichida oʻqish tartibi (spine) topilmadi.")
            val dirOf = opf.substringBeforeLast('/', "")
            val sb = StringBuilder()
            val marks = ArrayList<Mark>()
            for ((idx, idref) in spine.withIndex()) {
                val href = manifest[idref] ?: continue
                val path = resolvePath(dirOf, Uri.decode(href.substringBefore('#')))
                val entry = zip.getEntry(path) ?: continue
                val html = try {
                    decode(zip.getInputStream(entry).use { it.readBytes() })
                } catch (e: Exception) {
                    Journal.w("Extract", "EPUB boʻlimi oʻqilmadi: $path", e)
                    ""
                }
                val t = TextTools.clean(HtmlText.toPlain(html))
                if (t.isNotEmpty()) {
                    marks.add(Mark(sb.length, "boʻlim " + (idx + 1) + " / " + spine.size))
                    sb.append(t).append("\n\n")
                }
                if (idx % 3 == 0 || idx == spine.size - 1) progress("EPUB oʻqilmoqda: " + (idx + 1) + " / " + spine.size + " boʻlim")
            }
            return Extracted(sb.toString(), marks)
        } finally {
            try {
                zip.close()
            } catch (t: Throwable) {
            }
        }
    }

    private fun docx(file: File): Extracted {
        val zip = ZipFile(file)
        try {
            val e = zip.getEntry("word/document.xml")
                ?: throw ExtractException("DOCX ichida matn (word/document.xml) topilmadi.")
            val sb = StringBuilder()
            zip.getInputStream(e).use { ins ->
                val p = newParser(ins)
                var inT = false
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        val n = p.name
                        if (n == "w:t") {
                            inT = true
                        } else if (n == "w:tab") {
                            sb.append(' ')
                        } else if (n == "w:br" || n == "w:cr") {
                            sb.append('\n')
                        }
                    } else if (ev == XmlPullParser.END_TAG) {
                        val n = p.name
                        if (n == "w:t") {
                            inT = false
                        } else if (n == "w:p") {
                            sb.append('\n')
                        }
                    } else if (ev == XmlPullParser.TEXT) {
                        if (inT) sb.append(p.text)
                    }
                    ev = p.next()
                }
            }
            return Extracted(TextTools.clean(sb.toString()), emptyList())
        } finally {
            try {
                zip.close()
            } catch (t: Throwable) {
            }
        }
    }

    private fun odt(file: File): Extracted {
        val zip = ZipFile(file)
        try {
            val e = zip.getEntry("content.xml") ?: throw ExtractException("ODT ichida content.xml topilmadi.")
            val sb = StringBuilder()
            zip.getInputStream(e).use { ins ->
                val p = newParser(ins)
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        val n = p.name
                        if (n == "text:tab") {
                            sb.append(' ')
                        } else if (n == "text:line-break") {
                            sb.append('\n')
                        } else if (n == "text:s") {
                            val c = p.getAttributeValue(null, "text:c")?.toIntOrNull() ?: 1
                            for (k in 0 until minOf(c, 50)) sb.append(' ')
                        }
                    } else if (ev == XmlPullParser.END_TAG) {
                        val n = p.name
                        if (n == "text:p" || n == "text:h") sb.append('\n')
                    } else if (ev == XmlPullParser.TEXT) {
                        sb.append(p.text)
                    }
                    ev = p.next()
                }
            }
            return Extracted(TextTools.clean(sb.toString()), emptyList())
        } finally {
            try {
                zip.close()
            } catch (t: Throwable) {
            }
        }
    }

    /** FB2: <binary> (rasmlar, base64) va <description> oʻtkazib yuboriladi. */
    private fun fb2(ins: InputStream): Extracted {
        val p = newParser(ins)
        val sb = StringBuilder()
        var skipTag: String? = null
        var skipDepth = 0
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                val n = p.name
                if (skipTag != null) {
                    if (n == skipTag) skipDepth++
                } else if (n == "binary" || n == "description") {
                    skipTag = n
                    skipDepth = 1
                } else if (n == "empty-line") {
                    sb.append('\n')
                }
            } else if (ev == XmlPullParser.END_TAG) {
                val n = p.name
                if (skipTag != null) {
                    if (n == skipTag) {
                        skipDepth--
                        if (skipDepth == 0) skipTag = null
                    }
                } else if (n == "p" || n == "v" || n == "subtitle" || n == "text-author" || n == "th" || n == "td") {
                    sb.append('\n')
                } else if (n == "title" || n == "stanza" || n == "section" || n == "epigraph" || n == "cite" || n == "poem") {
                    sb.append("\n\n")
                }
            } else if (ev == XmlPullParser.TEXT) {
                if (skipTag == null) sb.append(p.text)
            }
            ev = p.next()
        }
        return Extracted(TextTools.clean(sb.toString()), emptyList())
    }

    private fun fb2zip(file: File): Extracted {
        val zip = ZipFile(file)
        try {
            val en = zip.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (e.name.lowercase(Locale.ROOT).endsWith(".fb2")) {
                    return zip.getInputStream(e).use { fb2(it) }
                }
            }
            throw ExtractException("Arxiv ichida FB2 topilmadi.")
        } finally {
            try {
                zip.close()
            } catch (t: Throwable) {
            }
        }
    }
}
