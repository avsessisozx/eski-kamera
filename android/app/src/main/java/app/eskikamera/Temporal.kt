package app.eskikamera

/**
 * Kareler arası etkiler: hareket izi (önceki karenin sönümlü kalıntısı) ve otomatik pozlama "pompalaması"
 * (sahne değişince parlaklık yavaşça oturur). Tamsayı matematiği; her akış (önizleme, kayıt, dönüştürme) kendi örneğini kullanır.
 */
class Temporal(private val ghost: Int, private val pump: Boolean) {
    companion object {
        /** Ayarlardan doldurulur; yeni Temporal örnekleri bu değerleri kullanır. */
        @Volatile var cfgGhost = 1
        @Volatile var cfgPump = true
        fun fromConfig() = Temporal(cfgGhost, cfgPump)
        /** Hareket izi seviyesi -> önceki kare ağırlığı (256 üzerinden). */
        fun ghostWeight(level: Int) = when (level) { 1 -> 56; 2 -> 112; else -> 0 }
    }

    private var prev: IntArray? = null
    private var gain = 256 // 256 = 1.0

    val active: Boolean get() = ghost > 0 || pump

    fun apply(px: IntArray, w: Int, h: Int) {
        val n = w * h
        if (n == 0 || px.size < n) return
        if (pump) {
            var sum = 0L
            var i = 0
            val step = maxOf(1, n / 2048)
            var cnt = 0
            while (i < n) {
                val p = px[i]
                sum += (((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) shr 8
                cnt++
                i += step
            }
            val avg = (sum / cnt).toInt().coerceAtLeast(1)
            // hedef: ortalama parlaklığı ortaya çekmek, ama yarı güçte (bozulma değil, hafif nefes alma)
            var ideal = 256 * 118 / avg
            ideal = (256 + (ideal - 256) / 2).coerceIn(190, 330)
            gain += (ideal - gain) / 10
            gain = gain.coerceIn(190, 330)
            // g, "sahne ışığı"na göre ayarlandığı için ilk kareden sonra fark ancak sahne değişince görülür
            val g = gain
            if (g != 256) for (k in 0 until n) {
                val p = px[k]
                val r = (((p shr 16) and 255) * g) shr 8
                val gg = (((p shr 8) and 255) * g) shr 8
                val b = ((p and 255) * g) shr 8
                px[k] = (p and 0xFF000000.toInt()) or ((if (r > 255) 255 else r) shl 16) or ((if (gg > 255) 255 else gg) shl 8) or (if (b > 255) 255 else b)
            }
        }
        val wgt = ghostWeight(ghost)
        val pr = prev
        if (wgt > 0) {
            if (pr != null && pr.size == n) {
                val inv = 256 - wgt
                for (k in 0 until n) {
                    val a = px[k]
                    val b = pr[k]
                    val r = (((a shr 16) and 255) * inv + ((b shr 16) and 255) * wgt) shr 8
                    val gg = (((a shr 8) and 255) * inv + ((b shr 8) and 255) * wgt) shr 8
                    val bl = ((a and 255) * inv + (b and 255) * wgt) shr 8
                    px[k] = (a and 0xFF000000.toInt()) or (r shl 16) or (gg shl 8) or bl
                }
            }
            if (pr == null || pr.size != n) prev = px.copyOf(n) else System.arraycopy(px, 0, pr, 0, n)
        }
    }
}
