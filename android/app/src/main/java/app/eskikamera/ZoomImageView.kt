package app.eskikamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import kotlin.math.max
import kotlin.math.min

/** Çimdikle yakınlaştırma, sürükleme ve çift dokunma destekli görüntü. */
class ZoomImageView(ctx: Context) : ImageView(ctx) {
    private val m = Matrix()
    private var baseScale = 1f
    private var scale = 1f
    private var bmpW = 0
    private var bmpH = 0
    private var lastX = 0f
    private var lastY = 0f

    val isZoomed: Boolean get() = scale > 1.02f

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val target = (scale * d.scaleFactor).coerceIn(1f, 8f)
            val f = target / scale
            m.postScale(f, f, d.focusX, d.focusY)
            scale = target
            clamp()
            imageMatrix = m
            return true
        }
    })
    private val tapDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isZoomed) fit() else {
                m.postScale(3f, 3f, e.x, e.y)
                scale = 3f
                clamp()
                imageMatrix = m
            }
            return true
        }
    })

    init { scaleType = ScaleType.MATRIX }

    fun setBitmapFit(bmp: Bitmap) {
        bmpW = bmp.width
        bmpH = bmp.height
        val d = BitmapDrawable(resources, bmp)
        d.isFilterBitmap = bmp.width > 700
        setImageDrawable(d)
        fit()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (bmpW > 0) fit()
    }

    fun fit() {
        if (bmpW == 0 || width == 0) return
        baseScale = min(width.toFloat() / bmpW, height.toFloat() / bmpH)
        m.reset()
        m.postScale(baseScale, baseScale)
        m.postTranslate((width - bmpW * baseScale) / 2f, (height - bmpH * baseScale) / 2f)
        scale = 1f
        imageMatrix = m
    }

    private fun clamp() {
        val v = FloatArray(9)
        m.getValues(v)
        val cw = bmpW * v[Matrix.MSCALE_X]
        val ch = bmpH * v[Matrix.MSCALE_Y]
        var tx = v[Matrix.MTRANS_X]
        var ty = v[Matrix.MTRANS_Y]
        tx = if (cw <= width) (width - cw) / 2f else tx.coerceIn(width - cw, 0f)
        ty = if (ch <= height) (height - ch) / 2f else ty.coerceIn(height - ch, 0f)
        m.postTranslate(tx - v[Matrix.MTRANS_X], ty - v[Matrix.MTRANS_Y])
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        tapDetector.onTouchEvent(e)
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_MOVE -> if (!scaleDetector.isInProgress && isZoomed && e.pointerCount == 1) {
                m.postTranslate(e.x - lastX, e.y - lastY)
                clamp()
                imageMatrix = m
                lastX = e.x
                lastY = e.y
            }
        }
        return true
    }
}
