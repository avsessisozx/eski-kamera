package app.eskikamera

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** Cihaz türüne göre kodla çizilmiş küçük simgeler (telif sorunu olmasın diye ürün fotoğrafı kullanılmaz). */
class IconDrawable(private val type: String, private val color: Int) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dark = 0xFF263238.toInt()
    private val screen = 0xFF90A4AE.toInt()

    private fun rr(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, col: Int) {
        p.style = Paint.Style.FILL
        p.color = col
        c.drawRoundRect(RectF(l, t, r, b), rad, rad, p)
    }

    private fun circle(c: Canvas, x: Float, y: Float, r: Float, col: Int) {
        p.style = Paint.Style.FILL
        p.color = col
        c.drawCircle(x, y, r, p)
    }

    override fun draw(c: Canvas) {
        val b = bounds
        c.save()
        c.translate(b.left.toFloat(), b.top.toFloat())
        c.scale(b.width() / 100f, b.height() / 100f)
        when (type) {
            "candybar" -> {
                rr(c, 32f, 6f, 68f, 94f, 8f, color)
                rr(c, 37f, 14f, 63f, 40f, 3f, screen)
                for (i in 0 until 3) for (j in 0 until 3) circle(c, 41f + j * 9f, 52f + i * 11f, 3f, dark)
                circle(c, 50f, 10f, 2f, dark)
            }
            "slider" -> {
                rr(c, 30f, 4f, 70f, 70f, 8f, color)
                rr(c, 34f, 10f, 66f, 40f, 3f, screen)
                rr(c, 33f, 62f, 67f, 96f, 8f, dark)
                for (j in 0 until 4) circle(c, 40f + j * 7f, 82f, 2.5f, color)
                circle(c, 50f, 8f, 2f, dark)
            }
            "flip" -> {
                rr(c, 30f, 4f, 70f, 46f, 7f, color)
                rr(c, 35f, 10f, 65f, 32f, 3f, screen)
                rr(c, 30f, 52f, 70f, 96f, 7f, color)
                for (i in 0 until 3) for (j in 0 until 3) circle(c, 40f + j * 10f, 62f + i * 10f, 2.5f, dark)
                circle(c, 50f, 8f, 2f, dark)
            }
            "smartphone" -> {
                rr(c, 28f, 4f, 72f, 96f, 10f, color)
                rr(c, 32f, 12f, 68f, 82f, 3f, screen)
                circle(c, 50f, 89f, 3.5f, dark)
                circle(c, 50f, 8f, 1.8f, dark)
            }
            "compact" -> {
                rr(c, 8f, 30f, 92f, 78f, 8f, color)
                rr(c, 14f, 22f, 34f, 32f, 3f, color)
                circle(c, 50f, 54f, 19f, dark)
                circle(c, 50f, 54f, 12f, screen)
                circle(c, 50f, 54f, 5f, dark)
                rr(c, 72f, 36f, 86f, 44f, 2f, dark)
            }
            "camcorder" -> {
                rr(c, 24f, 32f, 90f, 74f, 7f, color)
                rr(c, 8f, 38f, 34f, 66f, 5f, dark)
                circle(c, 21f, 52f, 9f, screen)
                rr(c, 46f, 20f, 76f, 34f, 3f, dark)
                rr(c, 64f, 74f, 88f, 84f, 2f, dark)
                circle(c, 80f, 42f, 3f, Color.RED)
            }
            "pocketcam" -> {
                rr(c, 28f, 10f, 72f, 90f, 8f, color)
                circle(c, 50f, 32f, 11f, dark)
                circle(c, 50f, 32f, 6f, screen)
                rr(c, 36f, 56f, 64f, 78f, 3f, screen)
                circle(c, 50f, 16f, 2f, Color.RED)
            }
            "handheld" -> {
                rr(c, 6f, 22f, 94f, 78f, 10f, color)
                rr(c, 30f, 30f, 70f, 62f, 3f, screen)
                circle(c, 18f, 46f, 6f, dark)
                circle(c, 82f, 40f, 3.5f, dark)
                circle(c, 82f, 54f, 3.5f, dark)
                circle(c, 50f, 70f, 2.5f, dark)
            }
            "webcam" -> {
                circle(c, 50f, 38f, 30f, color)
                circle(c, 50f, 38f, 19f, dark)
                circle(c, 50f, 38f, 10f, screen)
                rr(c, 42f, 66f, 58f, 80f, 3f, color)
                rr(c, 28f, 80f, 72f, 90f, 4f, color)
            }
            "toy" -> {
                rr(c, 10f, 26f, 90f, 82f, 18f, color)
                circle(c, 38f, 54f, 18f, dark)
                circle(c, 38f, 54f, 11f, screen)
                rr(c, 62f, 36f, 84f, 66f, 5f, screen)
                circle(c, 30f, 20f, 7f, color)
                circle(c, 70f, 20f, 7f, color)
            }
            "keychain" -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 5f
                p.color = color
                c.drawCircle(50f, 22f, 15f, p)
                rr(c, 28f, 36f, 72f, 90f, 9f, color)
                circle(c, 50f, 56f, 11f, dark)
                circle(c, 50f, 56f, 5f, screen)
            }
            "thermal" -> {
                rr(c, 20f, 8f, 80f, 92f, 9f, dark)
                rr(c, 26f, 14f, 74f, 56f, 4f, 0xFF6A1B9A.toInt())
                circle(c, 50f, 36f, 14f, 0xFFFF7043.toInt())
                circle(c, 50f, 36f, 7f, 0xFFFFEE58.toInt())
                circle(c, 50f, 76f, 7f, color)
            }
            else -> rr(c, 15f, 25f, 85f, 75f, 8f, color)
        }
        c.restore()
    }

    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        fun eraColor(d: Device): Int = when (d.year) {
            in 0..1999 -> 0xFFB0BEC5.toInt()
            in 2000..2004 -> 0xFF4FC3F7.toInt()
            in 2005..2008 -> 0xFF81C784.toInt()
            else -> 0xFFFFB74D.toInt()
        }

        fun forDevice(d: Device): Drawable = IconDrawable(d.icon, eraColor(d))
    }
}
