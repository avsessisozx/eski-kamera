package app.eskikamera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

class OverlayConfig(
    val dateStamp: Boolean = false,
    val osd: Boolean = false,
    val gps: Boolean = false,
    val compass: Boolean = false,
    val mic: Boolean = false
) {
    val any: Boolean get() = dateStamp || osd || gps || compass || mic
}

class OverlayData(
    val timeMs: Long = System.currentTimeMillis(),
    val recording: Boolean = false,
    val recSeconds: Long = 0,
    val battery: Int = -1,
    val speedKmh: Float = Float.NaN,
    val altitude: Double = Double.NaN,
    val lat: Double = Double.NaN,
    val lon: Double = Double.NaN,
    val heading: Float = Float.NaN,
    val rollDeg: Float = 0f,
    val micLevel: Float = 0f
)

/** Kare üzerine tarih damgası, kamkorder ekranı, GPS/pusula ve ses göstergesi çizer. */
object Overlay {
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val dateFmt = SimpleDateFormat("''yy MM dd", Locale.US)
    private val osdDateFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US)

    private fun paint(color: Int, size: Float, shadow: Boolean = true) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = mono
        if (shadow) setShadowLayer(size * 0.12f, size * 0.06f, size * 0.06f, Color.BLACK)
    }

    fun drawOn(px: IntArray, w: Int, h: Int, cfg: OverlayConfig, d: OverlayData) {
        if (!cfg.any) return
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        draw(Canvas(bmp), w, h, cfg, d)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
    }

    fun draw(c: Canvas, w: Int, h: Int, cfg: OverlayConfig, d: OverlayData) {
        val ts = max(9f, h * 0.06f)
        val pad = ts * 0.6f
        val white = paint(Color.WHITE, ts)
        if (cfg.osd) {
            // sol üst: REC (yanıp söner)
            if (d.recording) {
                val on = (d.recSeconds % 2L) == 0L
                if (on) {
                    val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(230, 30, 30) }
                    c.drawCircle(pad + ts * 0.4f, pad + ts * 0.5f, ts * 0.35f, red)
                }
                c.drawText("REC", pad + ts * 1.0f, pad + ts * 0.9f, white)
            } else {
                c.drawText("STBY", pad, pad + ts * 0.9f, white)
            }
            // sağ üst: pil
            val bw = ts * 1.6f
            val bh = ts * 0.8f
            val bx = w - pad - bw - ts * 2.6f
            val by = pad + ts * 0.1f
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = max(1f, ts * 0.1f) }
            c.drawRect(bx, by, bx + bw, by + bh, outline)
            c.drawRect(bx + bw, by + bh * 0.3f, bx + bw + ts * 0.15f, by + bh * 0.7f, Paint().apply { color = Color.WHITE })
            if (d.battery >= 0) {
                val fill = Paint().apply { color = if (d.battery < 20) Color.rgb(230, 40, 40) else Color.WHITE }
                c.drawRect(bx + 2f, by + 2f, bx + 2f + (bw - 4f) * d.battery / 100f, by + bh - 2f, fill)
                c.drawText("${d.battery}%", bx + bw + ts * 0.3f, pad + ts * 0.9f, white)
            }
            // sol alt: kaset sayacı
            val cnt = "%02d:%02d".format(d.recSeconds / 60, d.recSeconds % 60)
            c.drawText(cnt, pad, h - pad - (if (cfg.gps || cfg.compass) ts * 1.2f else 0f), white)
            // sağ alt: tarih-saat
            val dt = osdDateFmt.format(Date(d.timeMs))
            val tw = white.measureText(dt)
            c.drawText(dt, w - pad - tw, h - pad, white)
        } else if (cfg.dateStamp) {
            val orange = paint(Color.rgb(255, 140, 0), ts)
            val t = dateFmt.format(Date(d.timeMs))
            val tw = orange.measureText(t)
            c.drawText(t, w - pad - tw, h - pad, orange)
        }
        if (cfg.osd && cfg.dateStamp) {
            // OSD açıkken tarih damgası ayrıca istenmişse üst ortaya
            val orange = paint(Color.rgb(255, 140, 0), ts)
            val t = dateFmt.format(Date(d.timeMs))
            c.drawText(t, (w - orange.measureText(t)) / 2f, pad + ts * 0.9f, orange)
        }
        if (cfg.gps) {
            val sp = if (d.speedKmh.isNaN()) "-- km/h" else "%d km/h".format(d.speedKmh.toInt())
            val alt = if (d.altitude.isNaN()) "" else " ↑%dm".format(d.altitude.toInt())
            c.drawText(sp + alt, pad, h - pad, paint(Color.rgb(120, 255, 120), ts))
            if (!d.lat.isNaN()) c.drawText("%.4f %.4f".format(d.lat, d.lon), pad, h - pad - ts * 1.1f, paint(Color.rgb(120, 255, 120), ts * 0.75f))
        }
        if (cfg.compass && !d.heading.isNaN()) {
            val dirs = arrayOf("K", "KD", "D", "GD", "G", "GB", "B", "KB")
            val dir = dirs[(((d.heading + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)]
            val txt = "$dir %03d°".format(d.heading.toInt())
            val p = paint(Color.rgb(255, 220, 90), ts)
            c.drawText(txt, (w - p.measureText(txt)) / 2f, h - pad, p)
            // su terazisi çizgisi
            val cx = w / 2f
            val cy = h / 2f
            val len = w * 0.12f
            val a = Math.toRadians(d.rollDeg.toDouble())
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 220, 90); strokeWidth = max(1f, ts * 0.12f) }
            c.drawLine(cx - len * cos(a).toFloat(), cy - len * sin(a).toFloat(), cx + len * cos(a).toFloat(), cy + len * sin(a).toFloat(), line)
        }
        if (cfg.mic) {
            val bh = h * 0.5f
            val x = w - pad - ts * 0.5f
            val bottom = h / 2f + bh / 2f
            val back = Paint().apply { color = Color.argb(120, 0, 0, 0) }
            c.drawRect(x, h / 2f - bh / 2f, x + ts * 0.5f, bottom, back)
            val lvl = d.micLevel.coerceIn(0f, 1f)
            val fill = Paint().apply { color = if (lvl > 0.8f) Color.RED else Color.rgb(90, 220, 90) }
            c.drawRect(x, bottom - bh * lvl, x + ts * 0.5f, bottom, fill)
        }
    }
}
