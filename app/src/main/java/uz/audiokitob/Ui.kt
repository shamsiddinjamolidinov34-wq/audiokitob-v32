package uz.audiokitob

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.view.ViewCompat

/** "Saqlash" (ACTION_CREATE_DOCUMENT) uchun oddiy kontrakt: kirish = tavsiya etilgan fayl nomi. */
class CreateDoc(private val mime: String) : ActivityResultContract<String, Uri?>() {
    override fun createIntent(context: Context, input: String): Intent {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = mime
        i.putExtra(Intent.EXTRA_TITLE, input)
        return i
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return if (resultCode == Activity.RESULT_OK) intent?.data else null
    }
}

/** Ekranlar kodda quriladi (XML emas) — identifikatorlar mos kelmay qolish xatosi boʻlmasligi uchun. TalkBack bilan mos. */
object Ui {
    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun lp(ctx: Context, top: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        p.topMargin = dp(ctx, top)
        return p
    }

    fun page(ctx: Context): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.VERTICAL
        val p = dp(ctx, 16)
        l.setPadding(p, p, p, p)
        return l
    }

    fun scroll(ctx: Context, content: View): ScrollView {
        val s = ScrollView(ctx)
        s.isFillViewport = true
        s.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return s
    }

    fun text(ctx: Context, s: CharSequence, size: Float = 16f, bold: Boolean = false, top: Int = 8): TextView {
        val t = TextView(ctx)
        t.text = s
        t.textSize = size
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        t.layoutParams = lp(ctx, top)
        return t
    }

    fun heading(ctx: Context, s: CharSequence): TextView {
        val t = text(ctx, s, 19f, true, 22)
        ViewCompat.setAccessibilityHeading(t, true)
        return t
    }

    fun button(ctx: Context, s: String, onClick: () -> Unit): Button {
        val b = Button(ctx)
        b.text = s
        b.isAllCaps = false
        b.minimumHeight = dp(ctx, 52)
        b.layoutParams = lp(ctx, 10)
        b.setOnClickListener { onClick() }
        return b
    }

    fun edit(ctx: Context, hint: String, multiline: Boolean = false): EditText {
        val e = EditText(ctx)
        e.hint = hint
        if (multiline) {
            e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            e.minLines = 4
            e.gravity = Gravity.TOP or Gravity.START
        } else {
            e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            e.setSingleLine(true)
        }
        e.layoutParams = lp(ctx, 4)
        return e
    }

    /** Yorliq + maydon; yorliq maydon bilan bogʻlanadi (TalkBack maydon nomini oʻqiydi). */
    fun labeled(ctx: Context, parent: LinearLayout, label: String, field: View) {
        if (field.id == View.NO_ID) field.id = View.generateViewId()
        val t = text(ctx, label, 15f, true, 14)
        t.labelFor = field.id
        parent.addView(t)
        parent.addView(field)
    }
}
