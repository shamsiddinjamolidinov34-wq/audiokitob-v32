package uz.audiokitob

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class Pcm(val bytes: ByteArray, val rate: Int)

/** Gemini TTS javobidagi audioni (xom PCM yoki WAV) 16-bit mono PCM ga keltiradi. */
object AudioUtil {
    private fun le16(b: ByteArray, p: Int): Int = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
            ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)

    fun toPcm(b: ByteArray, mime: String?): Pcm? {
        if (b.size >= 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte()
        ) return parseWav(b)
        var rate = 24000
        if (mime != null) {
            val m = Regex("rate=(\\d+)").find(mime)
            if (m != null) rate = m.groupValues[1].toIntOrNull() ?: 24000
        }
        val len = b.size and 1.inv()
        return Pcm(if (len == b.size) b else b.copyOf(len), rate)
    }

    private fun parseWav(b: ByteArray): Pcm? {
        var pos = 12
        var rate = 24000
        var channels = 1
        var bits = 16
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.ISO_8859_1)
            val sz = le32(b, pos + 4)
            if (id == "fmt " && pos + 24 <= b.size) {
                channels = le16(b, pos + 10)
                rate = le32(b, pos + 12)
                bits = le16(b, pos + 22)
            } else if (id == "data") {
                val start = pos + 8
                var len = sz
                if (len < 0 || start.toLong() + len > b.size) len = b.size - start
                if (channels != 1 || bits != 16) return null
                len = len and 1.inv()
                return Pcm(b.copyOfRange(start, start + len), rate)
            }
            if (sz < 0 || pos.toLong() + 8 + sz > b.size) break
            pos += 8 + sz + (sz and 1)
        }
        return null
    }
}

/** Gemini API bilan ishlash (HttpURLConnection, qoʻshimcha kutubxonasiz). */
object Gemini {
    const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    enum class Kind { OK, QUOTA_DAY, QUOTA_MIN, AUTH, REGION, MODEL, SERVER, NETWORK, BAD_CHUNK, NO_AUDIO, CANCELLED }

    class Result(
        val kind: Kind,
        val pcm: ByteArray? = null,
        val rate: Int = 24000,
        val code: Int = 0,
        val retryMs: Long = 0L,
        val message: String = ""
    )

    class KeyCheck(val ok: Boolean, val code: Int, val message: String, val ttsModels: List<String>)

    class Voice(val id: String, val label: String)

    private class Parsed {
        var data: String? = null
        var mime: String? = null
        var finish: String? = null
        var block: String? = null
        var text: String? = null
    }

    @Volatile
    private var active: HttpURLConnection? = null

    /** Ketayotgan soʻrovni uzadi (foydalanuvchi "Pauza" bosganda). */
    fun cancelActive() {
        val c = active ?: return
        Thread {
            try {
                c.disconnect()
            } catch (t: Throwable) {
            }
        }.start()
    }

    private fun isLegacy(model: String): Boolean {
        val m = model.lowercase(Locale.ROOT)
        return m.contains("2.5") || m.contains("3.1") || m.contains("2.0")
    }

    /**
     * gemini-3.8-* modellari: text — soʻzma-soʻz oʻqiladigan matn, uslub speech_metadata.style da, ovoz voiceConfig.voice da.
     * Eski (2.5/3.1) modellar: ovoz prebuiltVoiceConfig.voiceName da, uslub matn oldiga "uslub: " koʻrinishida qoʻshiladi.
     */
    fun buildBody(model: String, voice: String, text: String, style: String): String {
        val legacy = isLegacy(model)
        val part = JSONObject()
        if (legacy) {
            part.put("text", if (style.isNotBlank()) style.trim() + ": " + text else text)
        } else {
            part.put("text", text)
            if (style.isNotBlank()) part.put("speech_metadata", JSONObject().put("style", style.trim()))
        }
        val content = JSONObject()
        content.put("role", "user")
        content.put("parts", JSONArray().put(part))
        val voiceCfg = JSONObject()
        if (legacy) {
            voiceCfg.put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))
        } else {
            voiceCfg.put("voice", voice)
        }
        val gen = JSONObject()
        gen.put("responseModalities", JSONArray().put("AUDIO"))
        gen.put("speechConfig", JSONObject().put("voiceConfig", voiceCfg))
        val root = JSONObject()
        root.put("contents", JSONArray().put(content))
        root.put("generationConfig", gen)
        return root.toString()
    }

    fun synthesize(
        key: String,
        model: String,
        voice: String,
        text: String,
        style: String,
        cancelled: () -> Boolean
    ): Result {
        var c: HttpURLConnection? = null
        try {
            val body = buildBody(model, voice, text, style).toByteArray(Charsets.UTF_8)
            val conn = URL(BASE + "/models/" + model + ":generateContent").openConnection() as HttpURLConnection
            c = conn
            active = conn
            conn.requestMethod = "POST"
            conn.connectTimeout = 30000
            conn.readTimeout = 300000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code in 200..299) {
                val parsed = conn.inputStream.use { parseOk(it) }
                return interpret(parsed)
            }
            return classify(code, readErr(conn))
        } catch (e: OutOfMemoryError) {
            return Result(Kind.BAD_CHUNK, message = "Xotira yetmadi (OutOfMemory) — boʻlak kichraytiriladi")
        } catch (t: Throwable) {
            if (cancelled()) return Result(Kind.CANCELLED)
            return Result(Kind.NETWORK, message = describe(t))
        } finally {
            active = null
            try {
                c?.disconnect()
            } catch (t: Throwable) {
            }
        }
    }

    private fun parseOk(ins: InputStream): Parsed {
        val p = Parsed()
        val r = JsonReader(InputStreamReader(ins, Charsets.UTF_8))
        try {
            walk(r, p)
        } finally {
            try {
                r.close()
            } catch (t: Throwable) {
            }
        }
        return p
    }

    private fun walk(r: JsonReader, p: Parsed) {
        val t = r.peek()
        if (t == JsonToken.BEGIN_OBJECT) {
            r.beginObject()
            while (r.hasNext()) {
                val name = r.nextName()
                if (r.peek() == JsonToken.STRING) {
                    val v = r.nextString()
                    if (name == "data") {
                        if (p.data == null && v.length > 100) p.data = v
                    } else if (name == "mimeType" || name == "mime_type") {
                        if (p.mime == null) p.mime = v
                    } else if (name == "finishReason") {
                        p.finish = v
                    } else if (name == "blockReason") {
                        p.block = v
                    } else if (name == "text") {
                        if (p.text == null) p.text = v.take(300)
                    }
                } else {
                    walk(r, p)
                }
            }
            r.endObject()
        } else if (t == JsonToken.BEGIN_ARRAY) {
            r.beginArray()
            while (r.hasNext()) walk(r, p)
            r.endArray()
        } else {
            r.skipValue()
        }
    }

    private fun interpret(p: Parsed): Result {
        val blk = p.block
        if (blk != null) return Result(Kind.BAD_CHUNK, message = "Matn bloklandi (blockReason=" + blk + ")")
        val data = p.data
        if (data == null) {
            var why = "finishReason=" + (p.finish ?: "?")
            val tx = p.text
            if (tx != null) why += ", model matn qaytardi: " + tx
            return Result(Kind.NO_AUDIO, message = "Audio qaytmadi (" + why + ")")
        }
        val bytes: ByteArray = try {
            Base64.decode(data, Base64.DEFAULT)
        } catch (t: Throwable) {
            return Result(Kind.NO_AUDIO, message = "Base64 dekodlash xatosi")
        }
        val pcm = AudioUtil.toPcm(bytes, p.mime)
            ?: return Result(Kind.NO_AUDIO, message = "Audio formati tanilmadi (" + (p.mime ?: "?") + ")")
        if (pcm.bytes.size < 2000) {
            return Result(Kind.NO_AUDIO, message = "Audio juda qisqa (" + pcm.bytes.size + " bayt)")
        }
        return Result(Kind.OK, pcm = pcm.bytes, rate = pcm.rate)
    }

    private fun classify(code: Int, body: String): Result {
        val low = body.lowercase(Locale.ROOT)
        val msg = shortMsg(body)
        if (code == 429) {
            val zeroQuota = Regex("\"quotavalue\"\\s*:\\s*\"0\"").containsMatchIn(low) || low.contains("limit: 0")
            val daily = zeroQuota || low.contains("perday") || low.contains("per day") ||
                low.contains("daily") || low.contains("requests_per_day")
            val ms = retryMs(body)
            if (daily || ms > 15 * 60 * 1000L) {
                return Result(Kind.QUOTA_DAY, code = code, retryMs = ms, message = msg)
            }
            return Result(Kind.QUOTA_MIN, code = code, retryMs = if (ms > 0L) ms else 60000L, message = msg)
        }
        if (code == 400) {
            if (low.contains("api_key_invalid") || low.contains("api key not valid") ||
                low.contains("api key expired") || low.contains("key is invalid")
            ) return Result(Kind.AUTH, code = code, message = msg)
            if (low.contains("location is not supported") || low.contains("failed_precondition")) {
                return Result(Kind.REGION, code = code, message = msg)
            }
            return Result(Kind.BAD_CHUNK, code = code, message = msg)
        }
        if (code == 401 || code == 403) return Result(Kind.AUTH, code = code, message = msg)
        if (code == 404) return Result(Kind.MODEL, code = code, message = msg)
        if (code == 408 || code == 409 || code >= 500) return Result(Kind.SERVER, code = code, message = msg)
        return Result(Kind.BAD_CHUNK, code = code, message = msg)
    }

    private fun retryMs(body: String): Long {
        val m1 = Regex("\"retryDelay\"\\s*:\\s*\"([0-9.]+)s\"").find(body)
        val m2 = Regex("retry in ([0-9.]+)\\s*s", RegexOption.IGNORE_CASE).find(body)
        val m = m1 ?: m2
        if (m == null) return 0L
        val v = m.groupValues[1].toDoubleOrNull() ?: return 0L
        return (v * 1000).toLong() + 1500L
    }

    private fun shortMsg(body: String): String {
        try {
            val o = JSONObject(body).optJSONObject("error")
            if (o != null) {
                val m = o.optString("message", "")
                val st = o.optString("status", "")
                val s = (if (st.isNotEmpty()) "[" + st + "] " else "") + m
                if (s.isNotBlank()) return s.take(400)
            }
        } catch (t: Throwable) {
        }
        return body.take(300)
    }

    private fun readErr(c: HttpURLConnection): String {
        val s = c.errorStream ?: return ""
        try {
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                if (total < 100000) {
                    bos.write(buf, 0, n)
                    total += n
                }
            }
            return String(bos.toByteArray(), Charsets.UTF_8)
        } catch (t: Throwable) {
            return ""
        } finally {
            try {
                s.close()
            } catch (t: Throwable) {
            }
        }
    }

    fun describe(t: Throwable): String = t.javaClass.simpleName + ": " + (t.message ?: "")

    /** Kalitni TTS soʻrovi yubormasdan (limit sarflamasdan) tekshiradi: modellar roʻyxatini oladi. */
    fun checkKey(key: String): KeyCheck {
        var c: HttpURLConnection? = null
        try {
            val conn = URL(BASE + "/models?pageSize=1000").openConnection() as HttpURLConnection
            c = conn
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.setRequestProperty("x-goog-api-key", key)
            val code = conn.responseCode
            if (code in 200..299) {
                val txt = conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
                val arr = JSONObject(txt).optJSONArray("models")
                val names = ArrayList<String>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val n = o.optString("name", "")
                        if (n.lowercase(Locale.ROOT).contains("tts")) names.add(n.removePrefix("models/"))
                    }
                }
                return KeyCheck(true, code, "", names)
            }
            return KeyCheck(false, code, shortMsg(readErr(conn)), emptyList())
        } catch (t: Throwable) {
            return KeyCheck(false, 0, describe(t), emptyList())
        } finally {
            try {
                c?.disconnect()
            } catch (t: Throwable) {
            }
        }
    }

    /** Model topilmasa (404) — kalit ruxsat bergan TTS modellar ichidan eng mosini tanlaydi. */
    fun findTtsModel(key: String, exclude: Set<String>): String? {
        val chk = checkKey(key)
        if (!chk.ok) return null
        var best: String? = null
        var bestScore = -1
        for (m in chk.ttsModels) {
            if (exclude.contains(m)) continue
            val l = m.lowercase(Locale.ROOT)
            var sc = 10
            if (l.contains("flash") && !l.contains("lite")) {
                sc = 60
            } else if (l.contains("flash")) {
                sc = 50
            }
            if (l.contains("preview")) sc -= 15
            if (l.contains("3.8")) sc += 20
            if (sc > bestScore) {
                bestScore = sc
                best = m
            }
        }
        return best
    }

    /** Kengaytirilgan ovoz kutubxonasi (accent/til boʻyicha). Xato boʻlsa IOException tashlaydi. */
    fun listVoices(key: String, lang: String): List<Voice> {
        var c: HttpURLConnection? = null
        try {
            val url = BASE + "/voices?language_code=" + URLEncoder.encode(lang, "UTF-8") + "&page_size=100"
            val conn = URL(url).openConnection() as HttpURLConnection
            c = conn
            conn.connectTimeout = 20000
            conn.readTimeout = 40000
            conn.setRequestProperty("x-goog-api-key", key)
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP " + code + ": " + shortMsg(readErr(conn)))
            val txt = conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            val arr = JSONObject(txt).optJSONArray("voices") ?: return emptyList()
            val res = ArrayList<Voice>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                var id = o.optString("id", "")
                if (id.isEmpty()) id = o.optString("name", "")
                if (id.isEmpty()) continue
                var name = o.optString("displayName", "")
                if (name.isEmpty()) name = o.optString("display_name", "")
                val parts = ArrayList<String>()
                for (f in arrayOf("accent", "gender", "description")) {
                    val v = o.optString(f, "")
                    if (v.isNotEmpty()) parts.add(v)
                }
                val label = (if (name.isNotEmpty()) name else id) +
                    (if (parts.isNotEmpty()) " — " + parts.joinToString(", ") else "")
                res.add(Voice(id, label))
            }
            return res
        } finally {
            try {
                c?.disconnect()
            } catch (t: Throwable) {
            }
        }
    }
}
