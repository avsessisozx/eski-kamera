package app.eskikamera

import kotlin.math.abs

/** Kareler arası fark ile hareket algılar (64x48 gri ölçekli küçük kopyalar). */
class MotionDetector {
    private var prev: ByteArray? = null
    var lastScore = 0f
        private set

    fun update(cur: ByteArray): Float {
        val p = prev
        prev = cur
        if (p == null || p.size != cur.size) { lastScore = 0f; return 0f }
        var sum = 0L
        for (i in cur.indices) sum += abs((cur[i].toInt() and 255) - (p[i].toInt() and 255))
        lastScore = sum.toFloat() / cur.size
        return lastScore
    }

    fun reset() { prev = null; lastScore = 0f }

    companion object {
        /** Hassasiyet 1 (az hassas) ... 5 (çok hassas) için eşik. */
        fun threshold(sens: Int): Float = when (sens.coerceIn(1, 5)) { 1 -> 14f; 2 -> 9f; 3 -> 6f; 4 -> 4f; else -> 2.5f }
    }
}

/** Hareket sonucu başlat/durdur kararı verir. */
class MotionTrigger(private val quietMs: Long, private val cooldownMs: Long = 3000L) {
    enum class Event { NONE, START, STOP }

    var active = false
        private set
    private var hits = 0
    private var lastMotion = 0L
    private var lastStart = -1_000_000_000L

    fun feed(score: Float, thr: Float, nowMs: Long): Event {
        val moving = score > thr
        if (moving) { hits++; lastMotion = nowMs } else hits = 0
        if (!active) {
            if (hits >= 2 && nowMs - lastStart >= cooldownMs) {
                active = true
                lastStart = nowMs
                return Event.START
            }
        } else if (!moving && nowMs - lastMotion >= quietMs) {
            active = false
            return Event.STOP
        }
        return Event.NONE
    }

    fun reset() { active = false; hits = 0 }
}
