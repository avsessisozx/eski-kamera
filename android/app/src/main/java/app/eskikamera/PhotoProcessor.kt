package app.eskikamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

class ProcessException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

object PhotoProcessor {
    /** Fotoğrafı cihazın görünümüne dönüştürüp JPEG olarak outFile'a yazar. */
    fun process(context: Context, src: Uri, device: Device, seed: Long, outFile: File, log: (String) -> Unit): String {
        val resolver = context.contentResolver
        var rotation = 0
        try {
            resolver.openInputStream(src)?.use { ins ->
                val exif = ExifInterface(ins)
                rotation = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        } catch (e: Exception) {
            log("EXIF okunamadı, yön 0 varsayıldı: ${e.message}")
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(src)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw ProcessException("Fotoğraf açılamadı")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ProcessException("Fotoğraf boyutu okunamadı")

        val rotW = if (rotation == 90 || rotation == 270) bounds.outHeight else bounds.outWidth
        val rotH = if (rotation == 90 || rotation == 270) bounds.outWidth else bounds.outHeight
        var tw = device.photoWidth
        var th = device.photoHeight
        if (rotH > rotW) { val t = tw; tw = th; th = t }

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= tw && bounds.outHeight / (sample * 2) >= th &&
            (bounds.outWidth / (sample * 2) >= tw * 1 && bounds.outHeight / (sample * 2) >= th * 1)) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(src)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw ProcessException("Fotoğraf çözülemedi")

        val upright = if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            val r = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
            if (r !== decoded) decoded.recycle()
            r
        } else decoded

        // merkezden hedef orana kırp ve hedef boyuta ölçekle
        val aspect = tw.toFloat() / th
        val cw: Int
        val ch: Int
        if (upright.width.toFloat() / upright.height > aspect) { ch = upright.height; cw = Math.round(ch * aspect) }
        else { cw = upright.width; ch = Math.round(cw / aspect) }
        val left = (upright.width - cw) / 2
        val top = (upright.height - ch) / 2
        val scaled = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        Canvas(scaled).drawBitmap(upright, Rect(left, top, left + cw, top + ch), Rect(0, 0, tw, th), Paint(Paint.FILTER_BITMAP_FLAG))
        upright.recycle()

        val px = IntArray(tw * th)
        scaled.getPixels(px, 0, tw, 0, 0, tw, th)
        val t0 = System.currentTimeMillis()
        val out = LookProcessor.processPhoto(px, tw, th, device.photoLook, seed)
        scaled.setPixels(out, 0, tw, 0, 0, tw, th)
        FileOutputStream(outFile).use { scaled.compress(Bitmap.CompressFormat.JPEG, device.photoLook.jpegQuality, it) }
        scaled.recycle()
        val info = "${device.name}: ${tw}x$th foto, JPEG kalite ${device.photoLook.jpegQuality}, ${System.currentTimeMillis() - t0} ms"
        log(info)
        return info
    }
}
