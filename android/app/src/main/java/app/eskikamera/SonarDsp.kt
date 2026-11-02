package app.eskikamera

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class SonarResult(
    val motion: Boolean,
    val distanceCm: Int,
    val strength: Float,
    val directLevel: Float,
    /** Doğrudan yol tepe seviyesinin gürültü tabanına oranı (ses testi için). */
    val snr: Float
)

/**
 * Ses sonarı: 18-21 kHz'lik kısa "chirp" darbeleri gönderir, mikrofondaki yansımayı eşleştirilmiş süzgeçle arar.
 * Duran yansımaları (arka plan) çıkarıp hareket eden nesneleri ve yaklaşık mesafesini bulur.
 */
class SonarDsp(
    val fs: Int = 48000,
    val frame: Int = 2048,
    f0: Double = 18000.0,
    f1: Double = 21000.0,
    val chirpLen: Int = 480
) {
    private val n = 2 * frame
    private val chirp = DoubleArray(chirpLen)
    val txFrame = ShortArray(frame)
    private val cRe = DoubleArray(n)
    private val cIm = DoubleArray(n)
    private val prev = DoubleArray(frame)
    private val bg = DoubleArray(frame)
    private var bgInit = false
    private var consecutive = 0
    private var warm = 0
    private val bufRe = DoubleArray(n)
    private val bufIm = DoubleArray(n)

    init {
        for (i in 0 until chirpLen) {
            val t = i.toDouble() / fs
            val dur = chirpLen.toDouble() / fs
            val phase = 2 * PI * (f0 * t + 0.5 * (f1 - f0) / dur * t * t)
            val win = 0.5 - 0.5 * cos(2 * PI * i / (chirpLen - 1))
            chirp[i] = sin(phase) * win
            txFrame[i] = (chirp[i] * 26000).toInt().toShort()
        }
        for (i in 0 until chirpLen) cRe[i] = chirp[i]
        fft(cRe, cIm, false)
        for (i in 0 until n) cIm[i] = -cIm[i]  // eşlenik
    }

    private fun lagFor(cm: Int): Int = (2.0 * cm / 100.0 / 343.0 * fs).toInt()

    fun reset() { bgInit = false; consecutive = 0; warm = 0; prev.fill(0.0); bg.fill(0.0) }

    /** Bir mikrofon çerçevesini (frame örnek) işler. Çerçeve verici çerçevesiyle aynı periyotta olmalı. */
    fun process(rx: ShortArray, minCm: Int, maxCm: Int, sens: Int): SonarResult {
        // son iki çerçeve
        for (i in 0 until frame) {
            bufRe[i] = prev[i]
            bufRe[frame + i] = rx[i].toDouble() / 32768.0
            bufIm[i] = 0.0
            bufIm[frame + i] = 0.0
        }
        for (i in 0 until frame) prev[i] = rx[i].toDouble() / 32768.0
        fft(bufRe, bufIm, false)
        // çarpım: X * conj(C), analitik sinyal için negatif frekansları sıfırla
        for (k in 0 until n) {
            val xr = bufRe[k]
            val xi = bufIm[k]
            val mr = xr * cRe[k] - xi * cIm[k]
            val mi = xr * cIm[k] + xi * cRe[k]
            val scale = when {
                k == 0 || k == n / 2 -> 1.0
                k < n / 2 -> 2.0
                else -> 0.0
            }
            bufRe[k] = mr * scale
            bufIm[k] = mi * scale
        }
        fft(bufRe, bufIm, true)
        // zarf ve periyoda katlama
        val folded = DoubleArray(frame)
        for (j in 0 until frame) {
            val a = sqrt(bufRe[j] * bufRe[j] + bufIm[j] * bufIm[j])
            val k2 = j + frame
            folded[j] = if (k2 <= n - chirpLen) maxOf(a, sqrt(bufRe[k2] * bufRe[k2] + bufIm[k2] * bufIm[k2])) else a
        }
        var p0 = 0
        for (j in 1 until frame) if (folded[j] > folded[p0]) p0 = j
        val direct = folded[p0]
        val rel = DoubleArray(frame) { folded[(p0 + it) % frame] }
        // gürültü tabanı: ortanca (kabaca)
        val sorted = rel.copyOf()
        sorted.sort()
        val median = sorted[frame / 2] + 1e-12
        val snr = (direct / median).toFloat()
        if (!bgInit || direct < 1e-6) {
            for (i in 0 until frame) bg[i] = rel[i]
            bgInit = direct >= 1e-6
            warm = 0
            return SonarResult(false, 0, 0f, direct.toFloat(), snr)
        }
        if (warm < 8) {
            // ısınma: arka planı hızlı öğren, algılama yapma
            warm++
            for (i in 0 until frame) bg[i] = bg[i] * 0.5 + rel[i] * 0.5
            return SonarResult(false, 0, 0f, direct.toFloat(), snr)
        }
        val lo = lagFor(minCm).coerceAtLeast(24)
        val hi = lagFor(maxCm).coerceAtMost(frame - chirpLen - 1)
        var best = 0.0
        var bestK = lo
        var resSum = 0.0
        for (k in lo..hi) {
            val r = abs(rel[k] - bg[k])
            resSum += r
            if (r > best) { best = r; bestK = k }
        }
        for (i in 0 until frame) bg[i] = bg[i] * 0.9 + rel[i] * 0.1
        val avg = resSum / (hi - lo + 1) + 1e-12
        val strength = (best / direct).toFloat()
        val thr = when (sens.coerceIn(1, 5)) { 1 -> 0.02f; 2 -> 0.01f; 3 -> 0.005f; 4 -> 0.003f; else -> 0.002f }
        val hit = strength > thr && best > 6.0 * avg
        consecutive = if (hit) consecutive + 1 else 0
        val dist = (bestK.toDouble() / fs * 343.0 / 2.0 * 100.0).toInt()
        return SonarResult(consecutive >= 2, dist, strength, direct.toFloat(), snr)
    }

    companion object {
        /** Yerinde radix-2 FFT. inverse=true ise ters FFT (1/n ile ölçekli). */
        fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }
            var len = 2
            while (len <= n) {
                val ang = 2 * PI / len * (if (inverse) 1 else -1)
                val wr = cos(ang)
                val wi = sin(ang)
                var i = 0
                while (i < n) {
                    var cr = 1.0
                    var ci = 0.0
                    for (k in 0 until len / 2) {
                        val a = i + k
                        val b = i + k + len / 2
                        val xr = re[b] * cr - im[b] * ci
                        val xi = re[b] * ci + im[b] * cr
                        re[b] = re[a] - xr; im[b] = im[a] - xi
                        re[a] += xr; im[a] += xi
                        val ncr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr
                        cr = ncr
                    }
                    i += len
                }
                len = len shl 1
            }
            if (inverse) {
                for (q in 0 until n) {
                    re[q] = re[q] / n
                    im[q] = im[q] / n
                }
            }
        }
    }
}
