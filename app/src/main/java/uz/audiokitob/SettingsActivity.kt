package uz.audiokitob

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {

    private val keyEdits = ArrayList<EditText>()
    private lateinit var spVoice: Spinner
    private lateinit var etModel: EditText
    private lateinit var etBackup: EditText
    private lateinit var etChunk: EditText
    private lateinit var etStyle: EditText
    private lateinit var tvStatus: TextView
    private val voiceIds = ArrayList<String>()
    private val voiceLabels = ArrayList<String>()

    companion object {
        /** Gemini TTS ning 30 ta tayyor ovozi (nomi + qisqa taʼrifi). */
        val VOICES = listOf(
            "Zephyr" to "yorqin", "Puck" to "quvnoq", "Charon" to "maʼlumotli", "Kore" to "qatʼiy",
            "Fenrir" to "hayajonli", "Leda" to "yosh", "Orus" to "qatʼiy", "Aoede" to "yengil",
            "Callirrhoe" to "xotirjam", "Autonoe" to "yorqin", "Enceladus" to "nafasli", "Iapetus" to "ravshan",
            "Umbriel" to "xotirjam", "Algieba" to "silliq", "Despina" to "silliq", "Erinome" to "ravshan",
            "Algenib" to "xirillagan", "Rasalgethi" to "maʼlumotli", "Laomedeia" to "quvnoq", "Achernar" to "yumshoq",
            "Alnilam" to "qatʼiy", "Schedar" to "bir tekis", "Gacrux" to "yetuk", "Pulcherrima" to "dadil",
            "Achird" to "doʻstona", "Zubenelgenubi" to "oddiy", "Vindemiatrix" to "muloyim", "Sadachbia" to "jonli",
            "Sadaltager" to "bilimdon", "Sulafat" to "iliq"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Sozlamalar"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val page = Ui.page(this)
        page.addView(
            Ui.text(
                this,
                "Muhim: bir nechta kalit qoʻshsangiz, ularni har xil Google loyihalarida (yoki hisoblarida) yarating — " +
                    "limit kalit boʻyicha emas, loyiha boʻyicha hisoblanadi. Kalitlar faqat shu qurilmada saqlanadi.",
                15f
            )
        )
        for (i in 1..Prefs.MAX_KEYS) {
            val et = Ui.edit(this, "AQ... yoki AIza...")
            et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            et.setText(Prefs.getKey(this, i))
            keyEdits.add(et)
            Ui.labeled(this, page, "API kalit $i", et)
            page.addView(Ui.button(this, "Kalit $i ni buferdan joylash") { pasteInto(et) })
        }
        page.addView(Ui.button(this, "Kalitlarni tekshirish") { checkKeys() })
        tvStatus = Ui.text(this, "", 15f)
        tvStatus.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        page.addView(tvStatus)

        spVoice = Spinner(this)
        buildVoiceItems()
        applyVoiceAdapter()
        Ui.labeled(this, page, "Ovoz", spVoice)
        page.addView(Ui.button(this, "Oʻzbek ovozlarini yuklash (Gemini ovoz kutubxonasi)") { loadVoices() })

        etModel = Ui.edit(this, Prefs.DEFAULT_MODEL)
        etModel.setText(Prefs.model(this))
        Ui.labeled(this, page, "Asosiy TTS modeli", etModel)

        etBackup = Ui.edit(this, "boʻsh qoldirish mumkin")
        etBackup.setText(Prefs.backup(this))
        Ui.labeled(this, page, "Zaxira model (asosiy modelning limiti tugasa ishlatiladi; boʻsh = oʻchiq)", etBackup)

        etChunk = Ui.edit(this, "3000")
        etChunk.inputType = InputType.TYPE_CLASS_NUMBER
        etChunk.setText(Prefs.chunk(this).toString())
        Ui.labeled(this, page, "Boʻlak hajmi (belgi, 800–6000). Katta boʻlak — kamroq soʻrov, lekin xato ehtimoli yuqoriroq", etChunk)

        etStyle = Ui.edit(this, "ixtiyoriy")
        etStyle.setText(Prefs.style(this))
        Ui.labeled(this, page, "Oʻqish uslubi (ixtiyoriy; boʻsh qoldirsangiz matn aynan oʻqiladi, hech narsa qoʻshilmaydi)", etStyle)

        page.addView(Ui.button(this, "Batareya tejash sozlamalari (fonda uzilmasligi uchun)") { openBattery() })
        page.addView(Ui.button(this, "Saqlash va ortga qaytish") {
            saveAll()
            finish()
        })
        setContentView(Ui.scroll(this, page))
    }

    override fun onPause() {
        saveAll()
        super.onPause()
    }

    override fun onSupportNavigateUp(): Boolean {
        saveAll()
        finish()
        return true
    }

    // ---------------------------------------------------------------- ovozlar

    private fun buildVoiceItems() {
        voiceIds.clear()
        voiceLabels.clear()
        val js = Prefs.voicesJson(this)
        if (js.isNotEmpty()) {
            try {
                val arr = JSONArray(js)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    voiceIds.add(o.getString("id"))
                    voiceLabels.add("★ " + o.getString("label"))
                }
            } catch (t: Throwable) {
                Journal.w("Sozlamalar", "Saqlangan ovozlar roʻyxati oʻqilmadi", t)
            }
        }
        for (v in VOICES) {
            voiceIds.add(v.first)
            voiceLabels.add(v.first + " — " + v.second)
        }
        val cur = Prefs.voice(this)
        if (!voiceIds.contains(cur)) {
            voiceIds.add(0, cur)
            voiceLabels.add(0, cur + " (saqlangan)")
        }
    }

    private fun applyVoiceAdapter() {
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, voiceLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spVoice.adapter = adapter
        val idx = voiceIds.indexOf(Prefs.voice(this))
        if (idx >= 0) spVoice.setSelection(idx)
    }

    private fun firstKey(): String? {
        for (e in keyEdits) {
            val k = Prefs.cleanKey(e.text.toString())
            if (k.isNotEmpty()) return k
        }
        return null
    }

    private fun loadVoices() {
        val key = firstKey()
        if (key == null) {
            tvStatus.text = "Avval kamida bitta API kalit kiriting."
            return
        }
        tvStatus.text = "Ovozlar yuklanmoqda…"
        Thread {
            try {
                var list = Gemini.listVoices(key, "uz-UZ")
                if (list.isEmpty()) list = Gemini.listVoices(key, "uz")
                if (list.isEmpty()) {
                    runOnUiThread {
                        tvStatus.text = "Oʻzbek tili uchun maxsus ovoz topilmadi. Standart ovozlardan foydalaning " +
                            "(ular oʻzbekcha matnni oʻqiy oladi)."
                    }
                } else {
                    val arr = JSONArray()
                    for (v in list) arr.put(JSONObject().put("id", v.id).put("label", v.label))
                    Prefs.setVoicesJson(this, arr.toString())
                    val n = list.size
                    runOnUiThread {
                        buildVoiceItems()
                        applyVoiceAdapter()
                        tvStatus.text = n.toString() + " ta oʻzbekcha ovoz yuklandi (★ belgisi bilan roʻyxat boshida)."
                    }
                }
            } catch (t: Throwable) {
                Journal.w("Sozlamalar", "Ovozlarni yuklab boʻlmadi", t)
                runOnUiThread { tvStatus.text = "Ovozlarni yuklab boʻlmadi: " + (t.message ?: t.javaClass.simpleName) }
            }
        }.start()
    }

    // ---------------------------------------------------------------- kalitlar

    private fun pasteInto(et: EditText) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip == null || clip.itemCount == 0) {
                Toast.makeText(this, "Bufer boʻsh.", Toast.LENGTH_SHORT).show()
                return
            }
            val t = clip.getItemAt(0).coerceToText(this).toString()
            et.setText(Prefs.cleanKey(t))
            Toast.makeText(this, "Joylandi.", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Journal.w("Sozlamalar", "Buferdan joylab boʻlmadi", t)
            Toast.makeText(this, "Buferdan joylab boʻlmadi.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkKeys() {
        val vals = ArrayList<Pair<Int, String>>()
        for ((i, e) in keyEdits.withIndex()) {
            val k = Prefs.cleanKey(e.text.toString())
            if (k.isNotEmpty()) vals.add(Pair(i + 1, k))
        }
        if (vals.isEmpty()) {
            tvStatus.text = "Tekshirish uchun kamida bitta kalit kiriting."
            return
        }
        tvStatus.text = "Tekshirilmoqda…"
        Journal.registerSecrets(vals.map { it.second })
        Thread {
            val sb = StringBuilder()
            for (p in vals) {
                val r = Gemini.checkKey(p.second)
                sb.append("Kalit ").append(p.first).append(": ")
                if (r.ok) {
                    sb.append("ishlaydi. TTS modellar: ")
                    sb.append(if (r.ttsModels.isEmpty()) "topilmadi" else r.ttsModels.joinToString(", "))
                } else if (r.code == 0) {
                    sb.append("tekshirib boʻlmadi (tarmoq): ").append(r.message)
                } else {
                    sb.append("yaroqsiz yoki ruxsat yoʻq (HTTP ").append(r.code).append("): ").append(r.message)
                }
                sb.append("\n")
                if (!r.ok) Journal.w("Sozlamalar", "Kalit " + p.first + " tekshiruvi: HTTP " + r.code + " " + r.message)
            }
            val out = sb.toString().trim()
            runOnUiThread { tvStatus.text = out }
        }.start()
    }

    // ---------------------------------------------------------------- saqlash

    private fun saveAll() {
        for ((i, e) in keyEdits.withIndex()) Prefs.setKey(this, i + 1, e.text.toString())
        val idx = spVoice.selectedItemPosition
        if (idx >= 0 && idx < voiceIds.size) Prefs.setVoice(this, voiceIds[idx])
        val m = etModel.text.toString().trim()
        Prefs.setModel(this, if (m.isEmpty()) Prefs.DEFAULT_MODEL else m)
        Prefs.setBackup(this, etBackup.text.toString().trim())
        val ch = etChunk.text.toString().trim().toIntOrNull() ?: Prefs.DEFAULT_CHUNK
        Prefs.setChunk(this, ch)
        Prefs.setStyle(this, etStyle.text.toString())
        val n = Prefs.keys(this).size
        Journal.i(
            "Sozlamalar",
            "Saqlandi: kalitlar=" + n + ", ovoz=" + Prefs.voice(this) + ", model=" + Prefs.model(this) +
                ", zaxira=" + Prefs.backup(this) + ", boʻlak=" + Prefs.chunk(this)
        )
    }

    private fun openBattery() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (t: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (x: Throwable) {
                Toast.makeText(this, "Sozlamalarni ochib boʻlmadi.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
