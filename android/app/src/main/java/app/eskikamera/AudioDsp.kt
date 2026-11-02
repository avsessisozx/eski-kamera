package app.eskikamera

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** İkinci dereceden süzgeç (biquad), kanal başına durumlu. */
class Biquad(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double, channels: Int) {
    private val z1 = DoubleArray(channels)
    private val z2 = DoubleArray(channels)

    fun process(x: Double, ch: Int): Double {
        val y = b0 * x + z1[ch]
        z1[ch] = b1 * x - a1 * y + z2[ch]
        z2[ch] = b2 * x - a2 * y
        return y
    }

    companion object {
        fun lowpass(fc: Double, fs: Double, channels: Int): Biquad {
            val w0 = 2 * PI * fc / fs
            val alpha = sin(w0) / (2 * 0.7071)
            val c = cos(w0)
            val a0 = 1 + alpha
            return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0, channels)
        }

        fun highpass(fc: Double, fs: Double, channels: Int): Biquad {
            val w0 = 2 * PI * fc / fs
            val alpha = sin(w0) / (2 * 0.7071)
            val c = cos(w0)
            val a0 = 1 + alpha
            return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0, channels)
        }
    }
}

/**
 * Ses zinciri: kanal dönüştürme -> bant sınırlama (telefon sesi) -> yeniden örnekleme.
 * Parça parça (streaming) çalışır. Giriş/çıkış: 16 bit, kanallar iç içe (interleaved).
 */
class AudioChain(private val inRate: Int, private val inCh: Int, private val outRate: Int, private val outCh: Int) {
    private val filters = ArrayList<Biquad>()
    private val step = inRate.toDouble() / outRate
    private var pos = 1.0
    private var last = DoubleArray(outCh)

    init {
        val fs = inRate.toDouble()
        val telephone = outRate <= 8000
        if (telephone) filters.add(Biquad.highpass(300.0, fs, outCh))
        val lp = if (telephone) 3400.0 else outRate * 0.45
        if (lp < fs / 2) {
            filters.add(Biquad.lowpass(lp, fs, outCh))
            filters.add(Biquad.lowpass(lp, fs, outCh))
        }
    }

    fun process(input: ShortArray): ShortArray {
        val frames = input.size / inCh
        if (frames == 0) return ShortArray(0)
        // ext[j*outCh + c]: j=0 önceki parçanın son örneği
        val ext = DoubleArray((frames + 1) * outCh)
        for (c in 0 until outCh) ext[c] = last[c]
        for (f in 0 until frames) {
            for (c in 0 until outCh) {
                var v: Double = if (outCh == 1) {
                    var s = 0.0
                    for (k in 0 until inCh) s += input[f * inCh + k]
                    s / inCh
                } else {
                    input[f * inCh + min(c, inCh - 1)].toDouble()
                }
                v /= 32768.0
                for (flt in filters) v = flt.process(v, c)
                ext[(f + 1) * outCh + c] = v
            }
        }
        val out = ArrayList<Short>(((frames / step).toInt() + 2) * outCh)
        while (pos < frames) {
            val i = pos.toInt()
            val fr = pos - i
            for (c in 0 until outCh) {
                val a = ext[i * outCh + c]
                val b = ext[(i + 1) * outCh + c]
                val v = (a + (b - a) * fr) * 32767.0
                out.add(v.coerceIn(-32768.0, 32767.0).toInt().toShort())
            }
            pos += step
        }
        pos -= frames
        for (c in 0 until outCh) last[c] = ext[frames * outCh + c]
        return out.toShortArray()
    }
}
