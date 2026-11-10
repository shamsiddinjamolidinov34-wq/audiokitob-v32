package uz.audiokitob

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Kalit/model holati: qaysi kalit qachon limitga yetgan, qaysi biri yaroqsiz.
 * Bir nechta kalit boʻlsa — navbat bilan ishlatiladi: avval 1-kalit, limitga yetsa 2-, keyin 3-kalit.
 * Asosiy model hamma kalitlarda tugagach, zaxira modelga oʻtiladi.
 * Holat saqlanadi: ilova qayta ochilganda limitga yetgan kalit bilan vaqt behuda ketmaydi.
 */
class KeyPool(private val ctx: Context) {

    class Slot(val keySlot: Int, val key: String, val model: String)

    sealed class Pick {
        class Use(val slot: Slot) : Pick()
        class Wait(val ms: Long) : Pick()
        object None : Pick()
    }

    private class S(
        var fp: Int = 0,
        var until: Long = 0L,
        var exDay: Int = 0,
        var dead: Boolean = false
    )

    private val sp = ctx.applicationContext.getSharedPreferences("keystate", Context.MODE_PRIVATE)
    private val map = HashMap<String, S>()
    private var keys: List<Prefs.KeyInfo> = emptyList()
    private var models: List<String> = emptyList()
    private val deadModelSet = HashSet<String>()
    private val minuteFails = HashMap<String, Int>()

    init {
        load()
    }

    private fun load() {
        map.clear()
        try {
            val o = JSONObject(sp.getString("s", "{}") ?: "{}")
            val iter = o.keys()
            while (iter.hasNext()) {
                val k = iter.next()
                val e = o.getJSONObject(k)
                map[k] = S(e.optInt("fp"), e.optLong("u"), e.optInt("d"), e.optBoolean("x"))
            }
        } catch (t: Throwable) {
            Journal.w("KeyPool", "Kalit holatini oʻqib boʻlmadi", t)
        }
    }

    private fun save() {
        try {
            val o = JSONObject()
            for ((k, s) in map) {
                val e = JSONObject()
                e.put("fp", s.fp)
                e.put("u", s.until)
                e.put("d", s.exDay)
                e.put("x", s.dead)
                o.put(k, e)
            }
            sp.edit().putString("s", o.toString()).apply()
        } catch (t: Throwable) {
            Journal.w("KeyPool", "Kalit holatini saqlab boʻlmadi", t)
        }
    }

    /** Sozlamalardan kalit va modellarni qayta oʻqiydi; oʻzgargan/oʻchirilgan kalitlar holatini tozalaydi. */
    fun reload() {
        keys = Prefs.keys(ctx)
        val ms = ArrayList<String>()
        for (m in Prefs.models(ctx)) {
            if (!deadModelSet.contains(m)) ms.add(m)
        }
        models = ms
        var changed = false
        val iter = map.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            val slot = e.key.substringBefore('|').toIntOrNull() ?: -1
            val k = keys.firstOrNull { it.slot == slot }
            if (k == null || k.key.hashCode() != e.value.fp) {
                iter.remove()
                changed = true
            }
        }
        if (changed) save()
    }

    fun keyCount(): Int = keys.size

    fun modelCount(): Int = models.size

    private fun st(keySlot: Int, key: String, model: String): S {
        val id = keySlot.toString() + "|" + model
        var s = map[id]
        if (s == null) {
            s = S(fp = key.hashCode())
            map[id] = s
        }
        return s
    }

    private fun isKeyDead(keySlot: Int): Boolean {
        val s = map[keySlot.toString() + "|*"]
        return s != null && s.dead
    }

    fun allKeysDead(): Boolean {
        if (keys.isEmpty()) return false
        for (k in keys) if (!isKeyDead(k.slot)) return false
        return true
    }

    /** Navbatdagi ishlatiladigan kalit+model; hammasi band boʻlsa — kutish vaqti; hammasi tugagan boʻlsa — None. */
    fun pick(now: Long): Pick {
        val today = pacificDay(now)
        var minWait = Long.MAX_VALUE
        for (m in models) {
            for (k in keys) {
                if (isKeyDead(k.slot)) continue
                val s = map[k.slot.toString() + "|" + m]
                if (s != null) {
                    if (s.dead) continue
                    if (s.exDay == today) continue
                    if (s.until > now) {
                        val w = s.until - now
                        if (w < minWait) minWait = w
                        continue
                    }
                }
                return Pick.Use(Slot(k.slot, k.key, m))
            }
        }
        return if (minWait != Long.MAX_VALUE) Pick.Wait(minWait) else Pick.None
    }

    fun success(slot: Slot) {
        minuteFails.remove(slot.keySlot.toString() + "|" + slot.model)
        val s = st(slot.keySlot, slot.key, slot.model)
        if (s.until != 0L) {
            s.until = 0L
            save()
        }
    }

    fun cooldown(slot: Slot, ms: Long) {
        val fid = slot.keySlot.toString() + "|" + slot.model
        val cnt = (minuteFails[fid] ?: 0) + 1
        minuteFails[fid] = cnt
        if (cnt >= 8) {
            // 8 marta ketma-ket "daqiqalik" limit — amalda kunlik limit tugagan
            minuteFails.remove(fid)
            Journal.w("KeyPool", "Kalit " + slot.keySlot + " / " + slot.model + ": limit ketma-ket $cnt marta — bugungi limit tugagan deb hisoblandi")
            exhaust(slot)
            return
        }
        val s = st(slot.keySlot, slot.key, slot.model)
        val w = if (ms < 5000L) 5000L else if (ms > 600000L) 600000L else ms
        s.until = System.currentTimeMillis() + w
        save()
    }

    fun exhaust(slot: Slot) {
        val s = st(slot.keySlot, slot.key, slot.model)
        s.exDay = pacificDay(System.currentTimeMillis())
        save()
    }

    fun deadSlot(slot: Slot) {
        st(slot.keySlot, slot.key, slot.model).dead = true
        save()
    }

    fun deadKey(slot: Slot) {
        st(slot.keySlot, slot.key, "*").dead = true
        save()
    }

    fun deadModel(model: String) {
        deadModelSet.add(model)
    }

    fun deadModels(): Set<String> = HashSet(deadModelSet)

    fun limitMessage(): String {
        if (keys.isEmpty()) return "API kalit kiritilmagan."
        val now = System.currentTimeMillis()
        val today = pacificDay(now)
        val sb = StringBuilder()
        sb.append("Mavjud kalitlar bilan audiolashtirish imkoni tugadi. ")
        for (k in keys) {
            sb.append("Kalit ").append(k.slot).append(": ")
            if (isKeyDead(k.slot)) {
                sb.append("yaroqsiz. ")
            } else {
                var allEx = models.isNotEmpty()
                for (m in models) {
                    val s = map[k.slot.toString() + "|" + m]
                    if (s == null || (s.exDay != today && !s.dead)) allEx = false
                }
                sb.append(if (allEx) "bugungi limit tugagan. " else "vaqtincha band yoki ruxsat yoʻq. ")
            }
        }
        val reset = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nextResetMs(now)))
        sb.append("Kunlik limit Tinch okeani vaqti (Los-Anjeles) yarim tunida tiklanadi — qurilma vaqti bilan taxminan ")
            .append(reset).append(". ")
        sb.append("Shu yergacha tayyor audio saqlandi; qolgan matn TXT sifatida tayyor.")
        return sb.toString()
    }

    companion object {
        fun clearStates(ctx: Context) {
            ctx.applicationContext.getSharedPreferences("keystate", Context.MODE_PRIVATE).edit().clear().apply()
        }

        /** Google kunlik kvotalari Tinch okeani vaqti (Los-Anjeles) yarim tunida yangilanadi. */
        fun pacificDay(now: Long): Int {
            val c = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles"))
            c.timeInMillis = now
            return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
        }

        fun nextResetMs(now: Long): Long {
            val c = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles"))
            c.timeInMillis = now
            c.set(Calendar.HOUR_OF_DAY, 0)
            c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            c.add(Calendar.DAY_OF_MONTH, 1)
            return c.timeInMillis
        }
    }
}
