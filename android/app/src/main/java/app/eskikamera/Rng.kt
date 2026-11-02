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

    /** Yaklaşık standart normal (4 düzgün sayının toplamı). */
    fun gauss(): Float = (uniform() + uniform() + uniform() + uniform() - 2f) * 1.7320508f

    fun nextInt(bound: Int): Int = (uniform() * bound).toInt().coerceIn(0, bound - 1)
}
