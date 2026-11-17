package app.eskikamera

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Uygulamanın kendi koyu "kamera gövdesi" stili: tüm menü ve pencereler buradan çıkar. */
object Ui {
    const val BODY = 0xFF17181B.toInt()
    const val PANEL = 0xFF24262B.toInt()
    const val PANEL_HI = 0xFF1E3A5F.toInt()
    const val LINE = 0xFF3A3D44.toInt()
    const val AMBER = 0xFFFFB300.toInt()
    const val TEXT = 0xFFF2F2F2.toInt()
    const val TEXT_DIM = 0xFFA9ADB5.toInt()

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun round(color: Int, radiusDp: Int, ctx: Context, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(ctx, strokeDp), strokeColor)
        }

    fun label(ctx: Context, text: String, sizeSp: Float = 15f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    /** Düz, çerçeveli düğme. */
    fun button(ctx: Context, text: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text.uppercase()
            textSize = 13f
            letterSpacing = 0.08f
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.BLACK else TEXT)
            setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10))
            background = if (primary) round(AMBER, 8, ctx) else round(Color.TRANSPARENT, 8, ctx, LINE, 1)
            setOnClickListener { onClick() }
        }

    /** Başlıklı, yuvarlak köşeli pencere. content verilen görünümü kaydırılabilir alanda gösterir. */
    fun dialog(
        ctx: Context, title: String, content: View, scroll: Boolean = true,
        buttons: List<Triple<String, Boolean, (Dialog) -> Unit>> = listOf(Triple("Kapat", false, { d: Dialog -> d.dismiss() }))
    ): Dialog {
        val dlg = Dialog(ctx)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = round(BODY, 18, ctx, LINE, 1)
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 18), dp(ctx, 14), dp(ctx, 18), dp(ctx, 12))
        }
        head.addView(View(ctx).apply { background = round(AMBER, 3, ctx) }, LinearLayout.LayoutParams(dp(ctx, 4), dp(ctx, 20)).apply { marginEnd = dp(ctx, 10) })
        head.addView(label(ctx, title.uppercase(), 15f, AMBER, true).apply { letterSpacing = 0.1f; typeface = Typeface.MONOSPACE })
        card.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(View(ctx).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        val body: View = if (scroll) ScrollView(ctx).apply { addView(content) } else content
        card.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (buttons.isNotEmpty()) {
            val bar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 12))
            }
            for ((text, primary, action) in buttons) {
                bar.addView(button(ctx, text, primary) { action(dlg) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(ctx, 8) })
            }
            card.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        dlg.setContentView(card)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val dm = ctx.resources.displayMetrics
            setLayout((dm.widthPixels * 0.92f).toInt(), (dm.heightPixels * 0.82f).toInt().coerceAtLeast(dp(ctx, 200)))
            setDimAmount(0.7f)
        }
        return dlg
    }

    /** Başlık + açıklama + tek düğme. */
    fun message(ctx: Context, title: String, text: String, ok: String = "Tamam", extra: List<Triple<String, Boolean, (Dialog) -> Unit>> = emptyList()): Dialog {
        val tv = label(ctx, text, 14f, TEXT).apply { setLineSpacing(0f, 1.15f); setPadding(dp(ctx, 18), dp(ctx, 14), dp(ctx, 18), dp(ctx, 14)) }
        val d = dialog(ctx, title, tv, true, extra + Triple(ok, true, { dd: Dialog -> dd.dismiss() }))
        d.show()
        return d
    }

    /** Evet/Hayır sorusu. */
    fun confirm(ctx: Context, title: String, text: String, ok: String, onOk: () -> Unit): Dialog {
        val tv = label(ctx, text, 14f, TEXT).apply { setLineSpacing(0f, 1.15f); setPadding(dp(ctx, 18), dp(ctx, 14), dp(ctx, 18), dp(ctx, 14)) }
        val d = dialog(ctx, title, tv, true, listOf(
            Triple("Vazgeç", false, { dd: Dialog -> dd.dismiss() }),
            Triple(ok, true, { dd: Dialog -> dd.dismiss(); onOk() })
        ))
        d.show()
        return d
    }

    /** Satır satır seçim listesi. */
    fun choose(ctx: Context, title: String, labels: List<String>, subs: List<String>? = null, onPick: (Int) -> Unit): Dialog {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10)) }
        lateinit var dlg: Dialog
        for ((i, l) in labels.withIndex()) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = round(PANEL, 12, ctx)
                setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
                setOnClickListener { dlg.dismiss(); onPick(i) }
            }
            row.addView(label(ctx, l, 15f, TEXT, true))
            val sub = subs?.getOrNull(i)
            if (!sub.isNullOrEmpty()) row.addView(label(ctx, sub, 12f, TEXT_DIM))
            col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 6) })
        }
        dlg = dialog(ctx, title, col)
        dlg.show()
        return dlg
    }
}
