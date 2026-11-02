package app.eskikamera

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Görüntüyü eski bir cihazın görünümüne dönüştürür. Android'den bağımsızdır (JVM'de test edilir).
 * Piksel formatı: ARGB_8888 IntArray.
 */
object LookProcessor {
    private const val BAND = 128

    /** Fotoğraf: büyük görüntüleri bantlara bölüp çok iş parçacığıyla işler. */
    fun processPhoto(src: IntArray, w: Int, h: Int, look: Look, seed: Long, threads: Int = 4): IntArray {
        val out = IntArray(w * h)
        var pad = 4
        pad = max(pad, ceil(3.0 * look.blur).toInt() + 2)
        if (look.bloom > 0f) pad = max(pad, ceil(3.0 * bloomSigma(w)).toInt() + 2)
        if (pad % 2 == 1) pad++
        val bands = (h + BAND - 1) / BAND
        if (bands <= 1 || threads <= 1) {
            for (b in 0 until bands) runBand(src, out, w, h, b, pad, look, seed)
        } else {
            val pool = Executors.newFixedThreadPool(threads)
            try {
                val tasks = (0 until bands).map { b -> Callable { runBand(src, out, w, h, b, pad, look, seed) } }
                pool.invokeAll(tasks).forEach { it.get() }
            } finally {
                pool.shutdown()
            }
        }
        hotPixels(out, w, h, look, seed)
        return out
    }

    /** Video karesi: tek bant, bayer/bloom/hot pixel yok. */
    fun processFrame(src: IntArray, w: Int, h: Int, look: Look, seed: Long): IntArray {
        val out = IntArray(w * h)
        processBand(src, out, w, h, 0, h, 0, h, look, seed)
        return out
    }

    private fun bloomSigma(w: Int): Double = max(2.0, w * 0.012)

    private fun runBand(src: IntArray, out: IntArray, w: Int, h: Int, band: Int, pad: Int, look: Look, seed: Long) {
        val y0 = band * BAND
        val y1 = min(h, y0 + BAND)
        val ys = max(0, y0 - pad)
        val ye = min(h, y1 + pad)
        processBand(src, out, w, h, ys, ye, y0, y1, look, seed + band * 7919L)
    }

    private fun processBand(
        src: IntArray, out: IntArray, w: Int, h: Int,
        ys: Int, ye: Int, y0: Int, y1: Int, look: Look, seed: Long
    ) {
        val bh = ye - ys
        val n = w * bh
        var r = FloatArray(n)
        var g = FloatArray(n)
        var b = FloatArray(n)
        for (y in 0 until bh) {
            val so = (ys + y) * w
            val d = y * w
            for (x in 0 until w) {
                val p = src[so + x]
                r[d + x] = ((p shr 16) and 255) / 255f
                g[d + x] = ((p shr 8) and 255) / 255f
                b[d + x] = (p and 255) / 255f
            }
        }

        if (look.bayer) {
            val res = bayerRoundtrip(r, g, b, w, bh)
            r = res[0]; g = res[1]; b = res[2]
        }

        val tmp = FloatArray(n)
        if (look.blur > 0f) {
            blur(r, w, bh, look.blur, tmp); blur(g, w, bh, look.blur, tmp); blur(b, w, bh, look.blur, tmp)
        }
        if (look.chromaBleed > 0) chromaBleed(r, g, b, w, bh, look.chromaBleed)

        // ton, renk
        val gammaLut = if (look.gamma != 1f) FloatArray(4097) { (it / 4096f).toDouble().pow(1.0 / look.gamma.toDouble()).toFloat() } else null
        val cr = look.cast[0]; val cg = look.cast[1]; val cb = look.cast[2]
        for (i in 0 until n) {
            var rr = r[i]; var gg = g[i]; var bb = b[i]
            if (gammaLut != null) {
                rr = gammaLut[(clamp01(rr) * 4096f).toInt()]
                gg = gammaLut[(clamp01(gg) * 4096f).toInt()]
                bb = gammaLut[(clamp01(bb) * 4096f).toInt()]
            }
            rr = (rr - 0.5f) * look.contrast + 0.5f
            gg = (gg - 0.5f) * look.contrast + 0.5f
            bb = (bb - 0.5f) * look.contrast + 0.5f
            val l = 0.299f * rr + 0.587f * gg + 0.114f * bb
            rr = (l + (rr - l) * look.saturation) * cr
            gg = (l + (gg - l) * look.saturation) * cg
            bb = (l + (bb - l) * look.saturation) * cb
            r[i] = clamp01(rr); g[i] = clamp01(gg); b[i] = clamp01(bb)
        }

        // parlak alan taşması
        if (look.bloom > 0f) {
            val hr = FloatArray(n); val hg = FloatArray(n); val hb = FloatArray(n)
            for (i in 0 until n) {
                hr[i] = max(0f, r[i] - 0.78f); hg[i] = max(0f, g[i] - 0.78f); hb[i] = max(0f, b[i] - 0.78f)
            }
            val sg = bloomSigma(w).toFloat()
            boxBlur3(hr, w, bh, sg, tmp); boxBlur3(hg, w, bh, sg, tmp); boxBlur3(hb, w, bh, sg, tmp)
            val k = look.bloom * 3f
            for (i in 0 until n) {
                r[i] = clamp01(r[i] + hr[i] * k); g[i] = clamp01(g[i] + hg[i] * k); b[i] = clamp01(b[i] + hb[i] * k)
            }
        }

        // aşırı keskinleştirme
        if (look.sharpen > 0f) {
            val br = r.copyOf(); val bg = g.copyOf(); val bbl = b.copyOf()
            blur(br, w, bh, 1.0f, tmp); blur(bg, w, bh, 1.0f, tmp); blur(bbl, w, bh, 1.0f, tmp)
            val a = look.sharpen
            for (i in 0 until n) {
                r[i] = clamp01(r[i] + (r[i] - br[i]) * a)
                g[i] = clamp01(g[i] + (g[i] - bg[i]) * a)
                b[i] = clamp01(b[i] + (b[i] - bbl[i]) * a)
            }
        }

        // vinyet + gürültü + yazma (yalnızca çekirdek satırlar)
        val vigLut = if (look.vignette > 0f) FloatArray(1025) { 1f - look.vignette * (it / 1024f).toDouble().pow(2.2).toFloat() } else null
        val rng = Rng(seed)
        val nl = look.noiseLuma / 255f
        val nc = look.noiseChroma / 255f
        val halfW = w / 2f
        val halfH = h / 2f
        val invNorm = 1f / sqrt(2f)
        for (y in y0 until y1) {
            val row = (y - ys) * w
            val bandNoise = if (look.banding > 0f) rng.gauss() * look.banding * 3f / 255f else 0f
            val dy = (y + 0.5f - halfH) / halfH
            for (x in 0 until w) {
                val i = row + x
                var rr = r[i]; var gg = g[i]; var bb = b[i]
                if (vigLut != null) {
                    val dx = (x + 0.5f - halfW) / halfW
                    val d = min(1f, sqrt(dx * dx + dy * dy) * invNorm)
                    val m = vigLut[(d * 1024f).toInt()]
                    rr *= m; gg *= m; bb *= m
                }
                if (nl > 0f) {
                    val l = 0.299f * rr + 0.587f * gg + 0.114f * bb
                    val nz = rng.gauss() * nl * (1.25f - 0.6f * l)
                    rr += nz; gg += nz; bb += nz
                }
                if (nc > 0f) {
                    val c0 = rng.gauss() * nc; val c1 = rng.gauss() * nc; val c2 = rng.gauss() * nc
                    val mean = (c0 + c1 + c2) / 3f
                    rr += c0 - mean; gg += c1 - mean; bb += c2 - mean
                }
                rr += bandNoise; gg += bandNoise; bb += bandNoise
                val ir = (clamp01(rr) * 255f + 0.5f).toInt()
                val ig = (clamp01(gg) * 255f + 0.5f).toInt()
                val ib = (clamp01(bb) * 255f + 0.5f).toInt()
                out[y * w + x] = (0xFF shl 24) or (ir shl 16) or (ig shl 8) or ib
            }
        }
    }

    private fun hotPixels(out: IntArray, w: Int, h: Int, look: Look, seed: Long) {
        if (look.hotPixels <= 0) return
        val rng = Rng(seed + 1234567L)
        val count = (look.hotPixels * (w.toLong() * h / 1e6) + 1).toInt()
        val colors = intArrayOf(0xFFFF2828.toInt(), 0xFF28FF28.toInt(), 0xFFFFFFFF.toInt(), 0xFF2828FF.toInt())
        repeat(count) {
            out[rng.nextInt(h) * w + rng.nextInt(w)] = colors[rng.nextInt(colors.size)]
        }
    }

    private fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

    // ------------------------------------------------------------ bulanıklaştırma
    private fun kernel(sigma: Float): FloatArray {
        val rad = max(1, ceil(3.0 * sigma).toInt())
        val k = FloatArray(2 * rad + 1)
        var sum = 0f
        for (i in -rad..rad) {
            val v = Math.exp(-(i * i) / (2.0 * sigma * sigma)).toFloat()
            k[i + rad] = v
            sum += v
        }
        for (i in k.indices) k[i] /= sum
        return k
    }

    /** Ayrılabilir Gauss bulanıklığı, yerinde (kenarlar yansıtılır/sabitlenir). */
    fun blur(a: FloatArray, w: Int, h: Int, sigma: Float, tmp: FloatArray) {
        val k = kernel(sigma)
        val rad = k.size / 2
        val row = FloatArray(w + 2 * rad)
        for (y in 0 until h) {
            val o = y * w
            for (i in 0 until rad) row[i] = a[o]
            System.arraycopy(a, o, row, rad, w)
            for (i in 0 until rad) row[rad + w + i] = a[o + w - 1]
            for (x in 0 until w) {
                var s = 0f
                for (t in k.indices) s += k[t] * row[x + t]
                tmp[o + x] = s
            }
        }
        for (y in 0 until h) {
            val o = y * w
            java.util.Arrays.fill(a, o, o + w, 0f)
            for (t in k.indices) {
                val yy = min(h - 1, max(0, y + t - rad))
                val wt = k[t]
                val so = yy * w
                for (x in 0 until w) a[o + x] += wt * tmp[so + x]
            }
        }
    }

    /** Büyük yarıçaplı bulanıklık için 3 geçişli kutu süzgeç (Gauss'a yakın, yarıçaptan bağımsız hız). */
    fun boxBlur3(a: FloatArray, w: Int, h: Int, sigma: Float, tmp: FloatArray) {
        val r = max(1, ((sqrt(4.0 * sigma * sigma + 1.0) - 1.0) / 2.0).toInt())
        repeat(3) {
            boxH(a, tmp, w, h, r)
            boxV(tmp, a, w, h, r)
        }
    }

    private fun boxH(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val inv = 1f / (2 * r + 1)
        for (y in 0 until h) {
            val o = y * w
            var sum = 0f
            for (k in -r..r) sum += src[o + min(w - 1, max(0, k))]
            for (x in 0 until w) {
                dst[o + x] = sum * inv
                sum += src[o + min(w - 1, x + r + 1)] - src[o + max(0, x - r)]
            }
        }
    }

    private fun boxV(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val inv = 1f / (2 * r + 1)
        val sums = FloatArray(w)
        for (k in -r..r) {
            val o = min(h - 1, max(0, k)) * w
            for (x in 0 until w) sums[x] += src[o + x]
        }
        for (y in 0 until h) {
            val o = y * w
            for (x in 0 until w) dst[o + x] = sums[x] * inv
            val add = min(h - 1, y + r + 1) * w
            val sub = max(0, y - r) * w
            for (x in 0 until w) sums[x] += src[add + x] - src[sub + x]
        }
    }

    // ------------------------------------------------------------ bayer
    private fun bayerRoundtrip(r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int): Array<FloatArray> {
        val m = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            m[i] = if (y % 2 == 0) { if (x % 2 == 0) r[i] else g[i] } else { if (x % 2 == 0) g[i] else b[i] }
        }
        val orr = FloatArray(w * h); val og = FloatArray(w * h); val ob = FloatArray(w * h)
        fun at(x: Int, y: Int): Float = m[min(h - 1, max(0, y)) * w + min(w - 1, max(0, x))]
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val v = m[i]
            val cross = (at(x - 1, y) + at(x + 1, y) + at(x, y - 1) + at(x, y + 1)) * 0.25f
            val diag = (at(x - 1, y - 1) + at(x + 1, y - 1) + at(x - 1, y + 1) + at(x + 1, y + 1)) * 0.25f
            val hor = (at(x - 1, y) + at(x + 1, y)) * 0.5f
            val ver = (at(x, y - 1) + at(x, y + 1)) * 0.5f
            if (y % 2 == 0) {
                if (x % 2 == 0) { orr[i] = v; og[i] = cross; ob[i] = diag }
                else { og[i] = v; orr[i] = hor; ob[i] = ver }
            } else {
                if (x % 2 == 0) { og[i] = v; orr[i] = ver; ob[i] = hor }
                else { ob[i] = v; og[i] = cross; orr[i] = diag }
            }
        }
        return arrayOf(orr, og, ob)
    }

    // ------------------------------------------------------------ analog renk taşması (VHS/Hi8)
    private fun chromaBleed(r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int, bleed: Int) {
        val rad = max(1, (bleed * 0.8f).toInt())
        val cbRow = FloatArray(w); val crRow = FloatArray(w)
        val cbOut = FloatArray(w); val crOut = FloatArray(w)
        for (y in 0 until h) {
            val o = y * w
            for (x in 0 until w) {
                val yy = 0.299f * r[o + x] + 0.587f * g[o + x] + 0.114f * b[o + x]
                cbRow[x] = (b[o + x] - yy) * 0.564f
                crRow[x] = (r[o + x] - yy) * 0.713f
            }
            for (x in 0 until w) {
                var sb = 0f; var sr = 0f
                val cnt = 2 * rad + 1
                for (t in -rad..rad) {
                    sb += cbRow[min(w - 1, max(0, x - bleed + t))]
                    sr += crRow[min(w - 1, max(0, x + bleed + t))]
                }
                cbOut[x] = sb / cnt; crOut[x] = sr / cnt
            }
            for (x in 0 until w) {
                val yy = 0.299f * r[o + x] + 0.587f * g[o + x] + 0.114f * b[o + x]
                val nr = yy + crOut[x] / 0.713f
                val nb = yy + cbOut[x] / 0.564f
                val ng = (yy - 0.299f * nr - 0.114f * nb) / 0.587f
                r[o + x] = clamp01(nr); g[o + x] = clamp01(ng); b[o + x] = clamp01(nb)
            }
        }
    }
}
