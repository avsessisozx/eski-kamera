package app.eskikamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/** Cihaz ailesine göre ana ekranın çerçevesi, vurgu rengi ve ekran göstergeleri. */
enum class SkinKind { KEYPAD, FLIP, DIGICAM, CAMCORDER, HANDHELD, WEBCAM, THERMAL }

class Skin(val kind: SkinKind, val accent: Int, val frame: Int, val corner: Float, val label: String) {
    companion object {
        fun of(icon: String): Skin = when (icon) {
            "candybar", "slider", "keychain", "smartphone", "toy" ->
                Skin(SkinKind.KEYPAD, 0xFF4FC3F7.toInt(), 0xFF101820.toInt(), 14f, "")
            "flip" -> Skin(SkinKind.FLIP, 0xFFCE93D8.toInt(), 0xFF2A1830.toInt(), 10f, "")
            "compact", "pocketcam" -> Skin(SkinKind.DIGICAM, 0xFFFFB300.toInt(), 0xFF1B1B1B.toInt(), 8f, "")
            "camcorder" -> Skin(SkinKind.CAMCORDER, 0xFFE53935.toInt(), 0xFF0B0B0B.toInt(), 4f, "")
            "handheld" -> Skin(SkinKind.HANDHELD, 0xFF9CCC65.toInt(), 0xFF2E3A1F.toInt(), 6f, "")
            "webcam" -> Skin(SkinKind.WEBCAM, 0xFF66BB6A.toInt(), 0xFF202226.toInt(), 12f, "")
            "thermal" -> Skin(SkinKind.THERMAL, 0xFFFF7043.toInt(), 0xFF140C08.toInt(), 6f, "")
            else -> Skin(SkinKind.DIGICAM, Ui.AMBER, 0xFF1B1B1B.toInt(), 8f, "")
        }
    }
}

/** Önizlemenin üstüne çizilen çerçeve ve göstergeler (dokunmayı geçirir). */
class SkinView(ctx: Context) : View(ctx) {
    var skin: Skin = Skin.of("compact")
        set(v) { field = v; invalidate() }
    var recording = false
        set(v) { field = v; invalidate() }
    var info = ""
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = ctx.resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val k = skin
        val m = 10f * density
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f * density
        p.color = k.accent
        p.alpha = 150
        val r = RectF(m, m, w - m, h - m)
        p.typeface = Typeface.MONOSPACE
        p.textSize = 12f * density
        when (k.kind) {
            SkinKind.KEYPAD -> {
                c.drawRoundRect(r, k.corner * density, k.corner * density, p)
                p.style = Paint.Style.FILL; p.alpha = 220
                // sinyal çubukları ve pil
                for (i in 0 until 4) c.drawRect(m + 6 * density + i * 5 * density, m + 22 * density - (i + 1) * 4 * density, m + 6 * density + i * 5 * density + 3 * density, m + 22 * density, p)
                c.drawRect(w - m - 30 * density, m + 8 * density, w - m - 8 * density, m + 20 * density, p)
                c.drawText(info, m + 6 * density, h - m - 8 * density, p)
            }
            SkinKind.FLIP -> {
                c.drawRoundRect(r, k.corner * density, k.corner * density, p)
                p.style = Paint.Style.FILL; p.alpha = 200
                c.drawLine(m, h / 2, m + 12 * density, h / 2, p)
                c.drawText(info, m + 6 * density, m + 18 * density, p)
            }
            SkinKind.DIGICAM -> {
                // odak köşeleri + alt bilgi şeridi
                p.alpha = 200
                val cx = w / 2; val cy = h / 2; val s = 34f * density; val l = 12f * density
                for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
                    c.drawLine(cx + sx * s, cy + sy * s, cx + sx * (s - l), cy + sy * s, p)
                    c.drawLine(cx + sx * s, cy + sy * s, cx + sx * s, cy + sy * (s - l), p)
                }
                p.style = Paint.Style.FILL
                c.drawText(info, m + 4 * density, h - m - 6 * density, p)
            }
            SkinKind.CAMCORDER -> {
                p.alpha = 220
                val l = 20f * density
                for (sx in intArrayOf(0, 1)) for (sy in intArrayOf(0, 1)) {
                    val x = if (sx == 0) r.left else r.right; val y = if (sy == 0) r.top else r.bottom
                    val dx = if (sx == 0) l else -l; val dy = if (sy == 0) l else -l
                    c.drawLine(x, y, x + dx, y, p); c.drawLine(x, y, x, y + dy, p)
                }
                p.style = Paint.Style.FILL
                p.color = if (recording) 0xFFE53935.toInt() else 0xFFFFFFFF.toInt()
                c.drawCircle(r.left + 16 * density, r.top + 28 * density, 5 * density, p)
                p.color = 0xFFFFFFFF.toInt(); p.alpha = 230
                c.drawText(if (recording) "REC" else "STBY", r.left + 28 * density, r.top + 33 * density, p)
            }
            SkinKind.HANDHELD -> {
                c.drawRoundRect(r, k.corner * density, k.corner * density, p)
                p.style = Paint.Style.FILL; p.alpha = 70
                var y = r.top
                while (y < r.bottom) { c.drawLine(r.left, y, r.right, y, p); y += 4 * density }
            }
            SkinKind.WEBCAM -> {
                c.drawRoundRect(r, k.corner * density, k.corner * density, p)
                p.style = Paint.Style.FILL; p.alpha = 255
                p.color = if (recording) 0xFFE53935.toInt() else k.accent
                c.drawCircle(w / 2, m + 8 * density, 4 * density, p)
            }
            SkinKind.THERMAL -> {
                p.alpha = 220
                val x0 = w - m - 16 * density
                val colors = intArrayOf(0xFFFFFF99.toInt(), 0xFFFF7043.toInt(), 0xFFB71C1C.toInt(), 0xFF4A148C.toInt(), 0xFF0D0D40.toInt())
                val seg = (h - 2 * m - 40 * density) / colors.size
                p.style = Paint.Style.FILL
                for (i in colors.indices) { p.color = colors[i]; c.drawRect(x0, m + 20 * density + i * seg, x0 + 10 * density, m + 20 * density + (i + 1) * seg, p) }
                p.color = 0xFFFFFFFF.toInt()
                c.drawText("38°", x0 - 28 * density, m + 28 * density, p)
                c.drawText("22°", x0 - 28 * density, h - m - 24 * density, p)
            }
        }
    }
}
