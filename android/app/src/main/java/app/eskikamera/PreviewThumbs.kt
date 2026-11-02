package app.eskikamera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Cihaz listesinde gösterilen küçük görünüm önizlemeleri (kamera karesi ya da örnek sahne cihazın görünümüyle işlenir). */
object PreviewThumbs {
    /** Kaynağı merkezden hedef orana kırpıp w x h boyutuna getirir. */
    fun fitCrop(src: Bitmap, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val aspect = w.toFloat() / h
        val sw = src.width
        val sh = src.height
        val cw: Int
        val ch: Int
        if (sw.toFloat() / sh > aspect) { ch = sh; cw = (sh * aspect).roundToInt() } else { cw = sw; ch = (sw / aspect).roundToInt() }
        val l = (sw - cw) / 2
        val t = (sh - ch) / 2
        Canvas(out).drawBitmap(src, Rect(l, t, l + cw, t + ch), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun finish(px: IntArray, w: Int, h: Int, boxW: Int, boxH: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.setPixels(px, 0, w, 0, 0, w, h)
        // küçük çözünürlük piksel piksel büyür, büyük çözünürlük yumuşak küçülür
        return Bitmap.createScaledBitmap(b, boxW, boxH, w >= boxW)
    }

    fun photoThumb(src: Bitmap, d: Device, boxW: Int, boxH: Int): Bitmap {
        val w0 = max(2, min(d.photoWidth, 320))
        var h0 = (w0.toFloat() * d.photoHeight / d.photoWidth).roundToInt()
        if (h0 % 2 == 1) h0++
        val fit = fitCrop(src, w0, h0)
        val px = IntArray(w0 * h0)
        fit.getPixels(px, 0, w0, 0, 0, w0, h0)
        val pl = d.photoLook
        val look = pl.copy(blur = max(0.3f, pl.blur * w0 / d.photoWidth), bayer = false, hotPixels = 0)
        val out = LookProcessor.processFrame(px, w0, h0, look, 11L, threads = 1)
        return finish(out, w0, h0, boxW, boxH)
    }

    fun videoThumb(src: Bitmap, d: Device, mode: VideoMode, boxW: Int, boxH: Int): Bitmap? {
        val look = d.videoLook ?: return null
        val nw = mode.procW
        val nh = mode.procH
        val fit = fitCrop(src, nw, nh)
        var px = IntArray(nw * nh)
        fit.getPixels(px, 0, nw, 0, 0, nw, nh)
        val ps = d.videoPrescale
        if (ps != null) {
            val small = FrameSampler.resizeBilinear(px, nw, nh, ps[0], ps[1])
            px = FrameSampler.resizeBilinear(small, ps[0], ps[1], nw, nh)
        }
        val out = LookProcessor.processFrame(px, nw, nh, look, 12L, threads = 1)
        return finish(out, nw, nh, boxW, boxH)
    }
}
