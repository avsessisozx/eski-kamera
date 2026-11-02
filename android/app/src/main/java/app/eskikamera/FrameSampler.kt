package app.eskikamera

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Çözülmüş video karesinin YUV420 düzlemleri (kopyalanmış, Android'den bağımsız). */
class YuvFrame(
    val width: Int,            // kırpılmış görünür genişlik
    val height: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val y: ByteArray,
    val yRowStride: Int,
    val u: ByteArray,
    val v: ByteArray,
    val uvRowStride: Int,
    val uvPixelStride: Int
)

object FrameSampler {
    private fun clamp8(v: Float): Int = if (v < 0f) 0 else if (v > 255f) 255 else (v + 0.5f).toInt()

    /**
     * Kareyi döndürür (rotation: 0/90/180/270), merkezden hedef en-boy oranına kırpar ve
     * (varsa prescale çözünürlüğüne, sonra) outW x outH'a küçültür. Çıktı ARGB IntArray.
     */
    fun toRgb(f: YuvFrame, rotation: Int, outW: Int, outH: Int, prescale: IntArray?): IntArray {
        val sw = prescale?.get(0) ?: outW
        val sh = prescale?.get(1) ?: outH
        val rot = ((rotation % 360) + 360) % 360
        val uw = if (rot == 90 || rot == 270) f.height else f.width
        val uh = if (rot == 90 || rot == 270) f.width else f.height
        val aspect = outW.toFloat() / outH
        val cw: Float
        val ch: Float
        if (uw.toFloat() / uh > aspect) { ch = uh.toFloat(); cw = ch * aspect } else { cw = uw.toFloat(); ch = cw / aspect }
        val cx0 = (uw - cw) / 2f
        val cy0 = (uh - ch) / 2f
        val s = min(3, max(1, (cw / sw / 1.5f).roundToInt()))
        val hd = f.height >= 720
        val out = IntArray(sw * sh)
        val inv = 1f / (s * s)
        for (ty in 0 until sh) {
            for (tx in 0 until sw) {
                var ar = 0f; var ag = 0f; var ab = 0f
                for (sy in 0 until s) {
                    val vy = cy0 + (ty + (sy + 0.5f) / s) * ch / sh
                    for (sx in 0 until s) {
                        val vx = cx0 + (tx + (sx + 0.5f) / s) * cw / sw
                        var px: Int
                        var py: Int
                        when (rot) {
                            90 -> { px = vy.toInt(); py = (f.height - vx).toInt() }
                            180 -> { px = (f.width - vx).toInt(); py = (f.height - vy).toInt() }
                            270 -> { px = (f.width - vy).toInt(); py = vx.toInt() }
                            else -> { px = vx.toInt(); py = vy.toInt() }
                        }
                        px = min(f.width - 1, max(0, px))
                        py = min(f.height - 1, max(0, py))
                        val yy = (f.y[(py + f.cropTop) * f.yRowStride + (px + f.cropLeft)].toInt() and 255) - 16
                        val ci = ((py + f.cropTop) / 2) * f.uvRowStride + ((px + f.cropLeft) / 2) * f.uvPixelStride
                        val uu = (f.u[ci].toInt() and 255) - 128
                        val vv = (f.v[ci].toInt() and 255) - 128
                        val yf = 1.164f * yy
                        if (hd) {
                            ar += yf + 1.793f * vv
                            ag += yf - 0.213f * uu - 0.533f * vv
                            ab += yf + 2.112f * uu
                        } else {
                            ar += yf + 1.596f * vv
                            ag += yf - 0.392f * uu - 0.813f * vv
                            ab += yf + 2.017f * uu
                        }
                    }
                }
                out[ty * sw + tx] = (0xFF shl 24) or (clamp8(ar * inv) shl 16) or (clamp8(ag * inv) shl 8) or clamp8(ab * inv)
            }
        }
        return if (prescale != null) resizeBilinear(out, sw, sh, outW, outH) else out
    }

    fun resizeBilinear(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        val out = IntArray(dw * dh)
        for (y in 0 until dh) {
            val fy = max(0f, (y + 0.5f) * sh / dh - 0.5f)
            val y0 = min(sh - 1, fy.toInt())
            val y1 = min(sh - 1, y0 + 1)
            val wy = fy - y0
            for (x in 0 until dw) {
                val fx = max(0f, (x + 0.5f) * sw / dw - 0.5f)
                val x0 = min(sw - 1, fx.toInt())
                val x1 = min(sw - 1, x0 + 1)
                val wx = fx - x0
                var res = 0xFF shl 24
                for (sh2 in intArrayOf(16, 8, 0)) {
                    val a = (src[y0 * sw + x0] shr sh2) and 255
                    val b = (src[y0 * sw + x1] shr sh2) and 255
                    val c = (src[y1 * sw + x0] shr sh2) and 255
                    val d = (src[y1 * sw + x1] shr sh2) and 255
                    val top = a + (b - a) * wx
                    val bot = c + (d - c) * wx
                    res = res or (((top + (bot - top) * wy) + 0.5f).toInt().coerceIn(0, 255) shl sh2)
                }
                out[y * dw + x] = res
            }
        }
        return out
    }

    /** ARGB -> BT.601 sınırlı aralık YUV420 (Y: w*h, U/V: (w/2)*(h/2)). */
    fun rgbToYuv420(argb: IntArray, w: Int, h: Int): Array<ByteArray> {
        val yp = ByteArray(w * h)
        val cw = w / 2
        val ch = h / 2
        val up = ByteArray(cw * ch)
        val vp = ByteArray(cw * ch)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = argb[y * w + x]
                val r = ((p shr 16) and 255).toFloat()
                val g = ((p shr 8) and 255).toFloat()
                val b = (p and 255).toFloat()
                yp[y * w + x] = clamp8(16f + 0.257f * r + 0.504f * g + 0.098f * b).toByte()
            }
        }
        for (y in 0 until ch) {
            for (x in 0 until cw) {
                var r = 0f; var g = 0f; var b = 0f
                for (dy in 0..1) for (dx in 0..1) {
                    val p = argb[(2 * y + dy) * w + (2 * x + dx)]
                    r += ((p shr 16) and 255); g += ((p shr 8) and 255); b += (p and 255)
                }
                r /= 4f; g /= 4f; b /= 4f
                up[y * cw + x] = clamp8(128f - 0.148f * r - 0.291f * g + 0.439f * b).toByte()
                vp[y * cw + x] = clamp8(128f + 0.439f * r - 0.368f * g - 0.071f * b).toByte()
            }
        }
        return arrayOf(yp, up, vp)
    }
}
