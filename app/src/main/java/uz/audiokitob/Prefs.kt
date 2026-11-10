package uz.audiokitob

import android.content.Context
import android.content.SharedPreferences

/** Sozlamalar. API kalitlar faqat ilovaning shaxsiy xotirasida saqlanadi (zaxira nusxaga kirmaydi). */
object Prefs {
    const val MAX_KEYS = 3
    const val DEFAULT_MODEL = "gemini-3.8-flash-tts"
    const val DEFAULT_BACKUP = "gemini-3.8-flash-lite-tts"
    const val DEFAULT_VOICE = "Charon"
    const val DEFAULT_CHUNK = 3000

    class KeyInfo(val slot: Int, val key: String)

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Kalitdagi boʻshliq, qoʻshtirnoq, koʻrinmas belgilarni olib tashlaydi (buferdan joylashda ortib qoladi). */
    fun cleanKey(raw: String): String {
        val sb = StringBuilder()
        for (c in raw) {
            val code = c.code
            if (c.isWhitespace() || code <= 32 || code == 0xFEFF || code == 0x200B || c == '"' || c == '\'' || c == '`') continue
            sb.append(c)
        }
        var s = sb.toString()
        if (s.startsWith("key=", ignoreCase = true)) s = s.substring(4)
        return s
    }

    fun getKey(ctx: Context, slot: Int): String = cleanKey(sp(ctx).getString("key$slot", "") ?: "")

    fun setKey(ctx: Context, slot: Int, value: String) {
        sp(ctx).edit().putString("key$slot", cleanKey(value)).apply()
    }

    fun keys(ctx: Context): List<KeyInfo> {
        val l = ArrayList<KeyInfo>()
        for (i in 1..MAX_KEYS) {
            val k = getKey(ctx, i)
            if (k.isNotEmpty()) l.add(KeyInfo(i, k))
        }
        Journal.registerSecrets(l.map { it.key })
        return l
    }

    private fun str(ctx: Context, name: String, def: String): String {
        val v = sp(ctx).getString(name, def) ?: def
        return v.trim()
    }

    fun voice(ctx: Context): String {
        val v = str(ctx, "voice", DEFAULT_VOICE)
        return if (v.isEmpty()) DEFAULT_VOICE else v
    }

    fun setVoice(ctx: Context, v: String) {
        sp(ctx).edit().putString("voice", v.trim()).apply()
    }

    fun model(ctx: Context): String {
        val v = str(ctx, "model", DEFAULT_MODEL)
        return if (v.isEmpty()) DEFAULT_MODEL else v
    }

    fun setModel(ctx: Context, v: String) {
        sp(ctx).edit().putString("model", v.trim()).apply()
    }

    fun backup(ctx: Context): String = str(ctx, "backup", DEFAULT_BACKUP)

    fun setBackup(ctx: Context, v: String) {
        sp(ctx).edit().putString("backup", v.trim()).apply()
    }

    fun style(ctx: Context): String = str(ctx, "style", "")

    fun setStyle(ctx: Context, v: String) {
        sp(ctx).edit().putString("style", v.trim()).apply()
    }

    fun chunk(ctx: Context): Int = sp(ctx).getInt("chunk", DEFAULT_CHUNK).coerceIn(800, 6000)

    fun setChunk(ctx: Context, v: Int) {
        sp(ctx).edit().putInt("chunk", v.coerceIn(800, 6000)).apply()
    }

    fun voicesJson(ctx: Context): String = sp(ctx).getString("voices", "") ?: ""

    fun setVoicesJson(ctx: Context, v: String) {
        sp(ctx).edit().putString("voices", v).apply()
    }

    /** Ishlatiladigan modellar tartibi: asosiy, keyin zaxira. */
    fun models(ctx: Context): List<String> {
        val l = ArrayList<String>()
        for (m in listOf(model(ctx), backup(ctx))) {
            if (m.isNotEmpty() && !l.contains(m)) l.add(m)
        }
        return l
    }
}
