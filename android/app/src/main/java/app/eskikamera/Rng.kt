package app.eskikamera

/** Hızlı, tohumlanabilir rastgele sayı üreteci (xorshift64*). */
class Rng(seed: Long) {
    private var x: Long = seed * -7046029254386353131L + 1442695040888963407L

    init {
        if (x == 0L) x = 88172645463325252L
        repeat(4) { nextLong() }
    }

    fun nextLong(): Long {
        x = x xor (x ushr 12)
        x = x xor (x shl 25)
        x = x xor (x ushr 27)
        return x * 2685821657736338717L
    }

    /** [0,1) aralığında. */
    fun uniform(): Float = ((nextLong() ushr 40).toInt()) / 16777216f

    private var bits = 0L
    private var left = 0

    /** Standart normal (önceden hesaplanmış tablodan; tek bir rastgele sayıdan 5 örnek çıkar, bu yüzden çok hızlıdır). */
    fun gauss(): Float {
        if (left == 0) { bits = nextLong(); left = 5 }
        val i = (bits and 4095L).toInt()
        bits = bits ushr 12
        left--
        return TABLE[i]
    }

    fun nextInt(bound: Int): Int = (uniform() * bound).toInt().coerceIn(0, bound - 1)

    companion object {
        private val TABLE: FloatArray = run {
            val r = java.util.Random(20261117L)
            val t = DoubleArray(4096) { r.nextGaussian() }
            val mean = t.average()
            var v = 0.0
            for (x in t) v += (x - mean) * (x - mean)
            val sd = Math.sqrt(v / t.size)
            FloatArray(4096) { ((t[it] - mean) / sd).toFloat() }
        }
    }
}
