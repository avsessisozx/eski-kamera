package app.eskikamera

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlin.math.max

/** Hoparlörden duyulmayan 18-21 kHz darbeler gönderir, mikrofondan yansımayı dinler. */
class SonarEngine(private val ctx: Context) {
    private val dsp = SonarDsp()
    @Volatile private var running = false
    @Volatile private var minCm = 30
    @Volatile private var maxCm = 200
    @Volatile private var sens = 3
    private var track: AudioTrack? = null
    private var rec: AudioRecord? = null
    private var txThread: Thread? = null
    private var rxThread: Thread? = null

    val isRunning: Boolean get() = running

    fun setRange(minCm: Int, maxCm: Int, sens: Int) {
        this.minCm = minCm; this.maxCm = maxCm; this.sens = sens
    }

    fun volumeWarning(): String? {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val v = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val m = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (v * 3 < m) "Medya ses seviyesi düşük, sonar için sesi yükselt." else null
    }

    /** Başlatır. Hata varsa açıklayıcı mesaj döndürür, başarılıysa null. */
    @SuppressLint("MissingPermission")
    fun start(minCm: Int, maxCm: Int, sens: Int, onResult: (SonarResult) -> Unit): String? {
        if (running) return null
        setRange(minCm, maxCm, sens)
        dsp.reset()
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val source = if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
            MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        try {
            val minBuf = AudioRecord.getMinBufferSize(dsp.fs, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(source, dsp.fs, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, dsp.frame * 8))
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return "Mikrofon başlatılamadı. Mikrofon izni verildi mi, başka uygulama mikrofonu kullanıyor mu?" }
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(dsp.fs).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(dsp.frame * 2 * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            rec = r
            track = t
            running = true
            t.play()
            r.startRecording()
            txThread = Thread({
                try {
                    while (running) t.write(dsp.txFrame, 0, dsp.frame)
                } catch (_: Exception) { }
            }, "sonar-tx").also { it.priority = Thread.MAX_PRIORITY; it.start() }
            rxThread = Thread({
                try {
                    val buf = ShortArray(dsp.frame)
                    var filled = 0
                    while (running) {
                        val n = r.read(buf, filled, buf.size - filled)
                        if (n < 0) break
                        filled += n
                        if (filled == buf.size) {
                            onResult(dsp.process(buf.copyOf(), this.minCm, this.maxCm, this.sens))
                            filled = 0
                        }
                    }
                } catch (_: Exception) { }
            }, "sonar-rx").also { it.priority = Thread.MAX_PRIORITY; it.start() }
            return null
        } catch (e: Exception) {
            stop()
            return "Sonar başlatılamadı: ${e.message}"
        }
    }

    fun stop() {
        running = false
        try { rxThread?.join(500) } catch (_: Exception) { }
        try { txThread?.join(500) } catch (_: Exception) { }
        try { track?.stop() } catch (_: Exception) { }
        try { track?.release() } catch (_: Exception) { }
        try { rec?.stop() } catch (_: Exception) { }
        try { rec?.release() } catch (_: Exception) { }
        track = null
        rec = null
    }

    /** Ses testi (yaklaşık 2.5 sn, arka plan iş parçacığında çağır). Sade Türkçe sonuç döndürür. */
    fun selfTest(): String {
        val snrs = ArrayList<Float>()
        val err = start(minCm, maxCm, sens) { snrs.add(it.snr) }
        if (err != null) return err
        try { Thread.sleep(2500) } catch (_: Exception) { }
        stop()
        if (snrs.size < 5) return "Test tamamlanamadı (yeterli ses verisi alınamadı)."
        val tail = snrs.drop(snrs.size / 3)
        val avg = tail.average().toFloat()
        val verdict = when {
            avg >= 8f -> "İYİ: hoparlörün gönderdiği ses mikrofona net ulaşıyor. Sonar çalışabilir."
            avg >= 3f -> "ZAYIF: ses mikrofona ulaşıyor ama zayıf. Medya sesini yükseltip kılıfı çıkararak dene; menzil kısa olabilir."
            else -> "ULAŞMIYOR: telefon bu yüksek frekansı çıkaramıyor ya da mikrofon bunu filtreliyor. Sonar bu telefonda çalışmaz."
        }
        return "$verdict (oran %.1f)".format(avg)
    }
}
