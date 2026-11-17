package app.eskikamera

import kotlin.math.abs
import kotlin.math.floor
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

    /** Piksel piksel (en yakın komşu) büyütme. */
    fun upscaleNearest(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        if (sw == dw && sh == dh) return src
        val out = IntArray(dw * dh)
        for (y in 0 until dh) {
            val sy = min(sh - 1, y * sh / dh)
            for (x in 0 until dw) out[y * dw + x] = src[sy * sw + min(sw - 1, x * sw / dw)]
        }
        return out
    }

    private class Taps(val idx: IntArray, val w: FloatArray)

    private fun mitchell(x: Float): Float {
        val a = abs(x)
        return when {
            a < 1f -> (7f * a * a * a - 12f * a * a + 16f / 3f) / 6f
            a < 2f -> (-7f / 3f * a * a * a + 12f * a * a - 20f * a + 32f / 3f) / 6f
            else -> 0f
        }
    }

    private fun taps(sn: Int, dn: Int): Taps {
        val idx = IntArray(dn * 4)
        val w = FloatArray(dn * 4)
        for (d in 0 until dn) {
            val c = (d + 0.5f) * sn / dn - 0.5f
            val f = floor(c).toInt()
            var sum = 0f
            for (t in 0..3) {
                val i = f - 1 + t
                val wt = mitchell(c - i)
                w[d * 4 + t] = wt
                sum += wt
                idx[d * 4 + t] = min(sn - 1, max(0, i))
            }
            for (t in 0..3) w[d * 4 + t] /= sum
        }
        return Taps(idx, w)
    }

    /** Yumuşak (Mitchell bicubic) büyütme: keskin piksel ızgarası yerine eski videolardaki gibi yumuşak geçişler verir. */
    fun resizeSmooth(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        if (sw == dw && sh == dh) return src
        val hx = taps(sw, dw)
        val vy = taps(sh, dh)
        val tmp = FloatArray(dw * sh * 3)
        for (y in 0 until sh) {
            val row = y * sw
            for (x in 0 until dw) {
                var r = 0f; var g = 0f; var b = 0f
                for (t in 0..3) {
                    val p = src[row + hx.idx[x * 4 + t]]
                    val w = hx.w[x * 4 + t]
                    r += w * ((p shr 16) and 255); g += w * ((p shr 8) and 255); b += w * (p and 255)
                }
                val o = (y * dw + x) * 3
                tmp[o] = r; tmp[o + 1] = g; tmp[o + 2] = b
            }
        }
        val out = IntArray(dw * dh)
        for (y in 0 until dh) {
            for (x in 0 until dw) {
                var r = 0f; var g = 0f; var b = 0f
                for (t in 0..3) {
                    val o = (vy.idx[y * 4 + t] * dw + x) * 3
                    val w = vy.w[y * 4 + t]
                    r += w * tmp[o]; g += w * tmp[o + 1]; b += w * tmp[o + 2]
                }
                out[y * dw + x] = (0xFF shl 24) or (clamp8(r) shl 16) or (clamp8(g) shl 8) or clamp8(b)
            }
        }
        return out
    }

    /** Karenin parlaklık (Y) düzleminden küçük bir gri ölçekli kopya (hareket algılama için). */
    fun lumaThumb(f: YuvFrame, tw: Int, th: Int): ByteArray {
        val out = ByteArray(tw * th)
        for (y in 0 until th) {
            val sy = min(f.height - 1, y * f.height / th) + f.cropTop
            for (x in 0 until tw) {
                val sx = min(f.width - 1, x * f.width / tw) + f.cropLeft
                out[y * tw + x] = f.y[sy * f.yRowStride + sx]
            }
        }
        return out
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
