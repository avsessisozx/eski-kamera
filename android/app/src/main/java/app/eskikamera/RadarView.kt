package app.eskikamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Sonar için yarım daire radar göstergesi: tarama çizgisi ve algılanan hareketin mesafesi. */
class RadarView(ctx: Context) : View(ctx) {
    var minCm = 30
    var maxCm = 200
    private var distanceCm = -1
    private var lastHit = 0L
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 80, 255, 120); style = Paint.Style.STROKE; strokeWidth = 3f }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 0, 40, 10); style = Paint.Style.FILL }
    private val blip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 80, 80); style = Paint.Style.FILL }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f }

    fun report(r: SonarResult?) {
        if (r != null && r.motion) { distanceCm = r.distanceCm; lastHit = System.currentTimeMillis() }
        postInvalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = min(w / 2f - 8f, h - 40f)
        val cx = w / 2f
        val cy = h - 20f
        c.drawArc(RectF(cx - radius, cy - radius, cx + radius, cy + radius), 180f, 180f, true, fill)
        for (i in 1..3) {
            val rr = radius * i / 3f
            c.drawArc(RectF(cx - rr, cy - rr, cx + rr, cy + rr), 180f, 180f, false, line)
        }
        val t = (System.currentTimeMillis() % 2000L) / 2000f
        val ang = Math.PI * (1.0 + t)  // 180..360 derece
        c.drawLine(cx, cy, cx + (radius * cos(ang)).toFloat(), cy + (radius * sin(ang)).toFloat(), line)
        val recent = System.currentTimeMillis() - lastHit < 1500
        if (recent && distanceCm > 0) {
            val frac = ((distanceCm - minCm).toFloat() / (maxCm - minCm).coerceAtLeast(1)).coerceIn(0f, 1f)
            val rr = radius * (0.15f + 0.85f * frac)
            c.drawCircle(cx, cy - rr, 16f, blip)
            c.drawText("%.1f m".format(distanceCm / 100f), cx + 24f, cy - rr, txt)
        } else {
            c.drawText("Hareket yok", cx - 90f, cy - 10f, txt)
        }
        postInvalidateDelayed(40)
    }
}
