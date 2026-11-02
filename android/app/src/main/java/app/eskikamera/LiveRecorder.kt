package app.eskikamera

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import androidx.camera.core.ImageProxy
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

object FrameUtil {
    private fun bytes(b: ByteBuffer): ByteArray {
        val d = b.duplicate()
        d.rewind()
        val a = ByteArray(d.remaining())
        d.get(a)
        return a
    }

    fun copy(p: ImageProxy): YuvFrame {
        val c = p.cropRect
        val pl = p.planes
        return YuvFrame(
            c.width(), c.height(), c.left, c.top,
            bytes(pl[0].buffer), pl[0].rowStride, bytes(pl[1].buffer), bytes(pl[2].buffer), pl[1].rowStride, pl[1].pixelStride
        )
    }
}

/** Görüntü ve ses kodlayıcılarının çıktısını tek bir MediaMuxer'a toplar; iki iz hazır olana kadar bekletir. */
private class Coordinator(val muxer: MediaMuxer, val expected: Int) {
    private class Pending(val track: Int, val data: ByteArray, val flags: Int, val pts: Long)

    private var added = 0
    private var started = false
    private val pending = ArrayList<Pending>()

    @Synchronized
    fun addTrack(f: MediaFormat): Int {
        val t = muxer.addTrack(f)
        added++
        if (added == expected) {
            muxer.start()
            started = true
            val info = MediaCodec.BufferInfo()
            for (p in pending) {
                info.set(0, p.data.size, p.pts, p.flags)
                muxer.writeSampleData(p.track, ByteBuffer.wrap(p.data), info)
            }
            pending.clear()
        }
        return t
    }

    @Synchronized
    fun write(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (started) {
            muxer.writeSampleData(track, buf, info)
        } else {
            val a = ByteArray(info.size)
            buf.get(a)
            pending.add(Pending(track, a, info.flags, info.presentationTimeUs))
        }
    }

    @Synchronized
    fun finish() {
        try { if (started) muxer.stop() } catch (_: Exception) { }
        try { muxer.release() } catch (_: Exception) { }
    }
}

/** Bir kodlayıcının giriş/çıkış işlerini yürütür. */
private class Pump(val codec: MediaCodec, val coord: Coordinator, val label: String) {
    var track = -1
    var done = false
    var written = 0
    private val info = MediaCodec.BufferInfo()

    fun drain(timeoutUs: Long) {
        while (true) {
            val oi = codec.dequeueOutputBuffer(info, timeoutUs)
            if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = coord.addTrack(codec.outputFormat)
            } else if (oi >= 0) {
                val buf = codec.getOutputBuffer(oi)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && track >= 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    coord.write(track, buf, info)
                    written++
                }
                codec.releaseOutputBuffer(oi, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    done = true
                    return
                }
            }
        }
    }

    fun inputIndex(): Int {
        var tries = 0
        while (true) {
            drain(0)
            val i = codec.dequeueInputBuffer(10_000)
            if (i >= 0) return i
            if (++tries > 600) throw ProcessException("$label kodlayıcı yanıt vermedi")
        }
    }

    fun finish(ptsUs: Long) {
        val i = inputIndex()
        codec.queueInputBuffer(i, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        var t = 0
        while (!done && t < 400) {
            drain(10_000)
            t++
        }
    }

    fun release() {
        try { codec.stop() } catch (_: Exception) { }
        try { codec.release() } catch (_: Exception) { }
    }
}

/**
 * Kayıt sırasında kareleri işleyip hemen kodlar; ses de canlı işlenir.
 * Durdurunca dosya (neredeyse) hazırdır.
 */
class LiveRecorder(
    private val device: Device,
    private val mode: VideoMode,
    private val look: Look,
    private val prescale: IntArray?,
    private val orientationDeg: Int,
    private val outDir: File,
    private val baseName: String,
    private val withAudio: Boolean,
    private val log: (String) -> Unit
) {
    private val w = mode.width
    private val h = mode.height
    private var videoPump: Pump? = null
    private var audioPump: Pump? = null
    private var coord: Coordinator? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    @Volatile private var audioRunning = false
    @Volatile private var audioError: Throwable? = null
    private var startNano = 0L
    private var nextKeep = 0L
    private var lastRel = 0L
    private var framesIn = 0
    private var stopped = false
    private var outFile: File? = null
    private var videoMime = ""
    private var audioMime = "yok"
    private val frameUs = 1_000_000L / mode.fps
    private val tol = min(8000L, frameUs / 4)
    private val seedBase = System.nanoTime()

    @SuppressLint("MissingPermission")
    fun start() {
        // video kodlayıcı: H.263 varsa onu dene, yoksa H.264 (MPEG-4 canlı modda H.264'e çevrilir)
        val wanted = VideoTranscoder.wantedMime(mode)
        val candidates = if (wanted == "video/3gpp") listOf("video/3gpp", "video/avc") else listOf("video/avc")
        var enc: VideoTranscoder.Enc? = null
        var lastErr: Exception? = null
        for (m in candidates) {
            try {
                enc = VideoTranscoder.createVideoEncoder(mode, m)
                if (m != wanted) log("${mode.codec} kodlayıcı kullanılamadı veya canlı modda desteklenmiyor, H.264 kullanılıyor")
                break
            } catch (e: Exception) {
                lastErr = e
                log("Video kodlayıcı $m olmadı: ${e.message}")
            }
        }
        if (enc == null) throw ProcessException("Bu telefon ${w}x$h video kodlayamadı: ${lastErr?.message}", lastErr)
        videoMime = enc.mime

        // ses
        var aEnc: VideoTranscoder.Enc? = null
        var ar: AudioRecord? = null
        if (withAudio) {
            try {
                val spec = mode.audio
                val wantAmr = spec.rate == 8000 && spec.channels == 1 && !spec.pcm && spec.kbps <= 13 && mode.container == "3gp"
                val min = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                ar = AudioRecord(MediaRecorder.AudioSource.CAMCORDER, 44100, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 16384))
                if (ar.state != AudioRecord.STATE_INITIALIZED) throw ProcessException("Mikrofon başlatılamadı")
                aEnc = VideoTranscoder.createAudioEncoder(spec, wantAmr, log)
                audioMime = aEnc.mime
            } catch (e: Throwable) {
                log("Ses kaydı kapatıldı: ${e.message}")
                try { ar?.release() } catch (_: Exception) { }
                try { aEnc?.codec?.release() } catch (_: Exception) { }
                ar = null
                aEnc = null
            }
        }

        // 3GP yalnızca AMR ile (veya sessiz) güvenli; AAC için MP4
        val use3gp = mode.container == "3gp" && (aEnc == null || aEnc.mime == "audio/3gpp")
        val muxType = if (use3gp) MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        outFile = File(outDir, baseName + if (use3gp) ".3gp" else ".mp4")
        val mx = MediaMuxer(outFile!!.path, muxType)
        if (orientationDeg == 0 || orientationDeg == 90 || orientationDeg == 180 || orientationDeg == 270) mx.setOrientationHint(orientationDeg)
        val c = Coordinator(mx, if (aEnc != null) 2 else 1)
        coord = c
        videoPump = Pump(enc.codec, c, "Video")
        startNano = System.nanoTime()
        if (aEnc != null && ar != null) {
            val ap = Pump(aEnc.codec, c, "Ses")
            audioPump = ap
            audioRecord = ar
            audioRunning = true
            audioThread = Thread({ audioLoop(ar, ap) }, "live-audio").also { it.start() }
        }
    }

    /** Bir kamera karesi geldi: kareyi atla ya da işleyip kodla. İşlenmişse piksellerini döndürür. */
    @Synchronized
    fun onFrame(frame: YuvFrame, nowNano: Long): IntArray? {
        if (stopped) return null
        val vp = videoPump ?: return null
        val rel = (nowNano - startNano) / 1000
        if (rel + tol < nextKeep) return null
        val rgb = FrameSampler.toRgb(frame, 0, w, h, prescale)
        val proc = LookProcessor.processFrame(rgb, w, h, look, seedBase + framesIn)
        val yuv = FrameSampler.rgbToYuv420(proc, w, h)
        val ei = vp.inputIndex()
        val img = vp.codec.getInputImage(ei) ?: throw ProcessException("Kodlayıcı giriş görüntüsü alınamadı")
        VideoTranscoder.fillImage(img, yuv, w, h)
        vp.codec.queueInputBuffer(ei, 0, w * h * 3 / 2, rel, 0)
        framesIn++
        lastRel = rel
        nextKeep += frameUs
        if (nextKeep <= rel) nextKeep = rel + frameUs
        vp.drain(0)
        return proc
    }

    @SuppressLint("MissingPermission")
    private fun audioLoop(ar: AudioRecord, ap: Pump) {
        try {
            val spec = mode.audio
            val chain = AudioChain(44100, 1, spec.rate, spec.channels)
            val buf = ShortArray(4096)
            ar.startRecording()
            val offsetUs = (System.nanoTime() - startNano) / 1000
            var framesFed = 0L
            val frameBytes = 2 * spec.channels
            while (audioRunning) {
                val n = ar.read(buf, 0, buf.size)
                if (n <= 0) continue
                val pcm = chain.process(buf.copyOf(n))
                if (pcm.isEmpty()) continue
                val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.nativeOrder())
                bb.asShortBuffer().put(pcm)
                val bytes = bb.array()
                var off = 0
                while (off < bytes.size) {
                    val ei = ap.inputIndex()
                    val ib = ap.codec.getInputBuffer(ei)!!
                    ib.clear()
                    val cap = ib.capacity() - ib.capacity() % frameBytes
                    val len = min(bytes.size - off, cap)
                    ib.put(bytes, off, len)
                    ap.codec.queueInputBuffer(ei, 0, len, offsetUs + framesFed * 1_000_000L / spec.rate, 0)
                    framesFed += len / frameBytes
                    off += len
                }
                ap.drain(0)
            }
            try { ar.stop() } catch (_: Exception) { }
            ap.finish(offsetUs + framesFed * 1_000_000L / spec.rate)
        } catch (t: Throwable) {
            audioError = t
            log("Ses hattı hatası: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Kaydı bitirir ve dosyayı döndürür (genelde 1-2 saniye). */
    fun stop(): TranscodeResult {
        synchronized(this) { stopped = true }
        audioRunning = false
        audioThread?.join(4000)
        try { audioRecord?.release() } catch (_: Exception) { }
        val vp = videoPump ?: throw ProcessException("Kayıt başlamamıştı")
        try {
            synchronized(this) {
                if (framesIn == 0) throw ProcessException("Hiç kamera karesi alınamadı")
                vp.finish(lastRel)
            }
        } finally {
            vp.release()
            audioPump?.release()
            coord?.finish()
        }
        val f = outFile ?: throw ProcessException("Çıktı yok")
        if (!f.exists() || f.length() == 0L) throw ProcessException("Çıktı dosyası oluşmadı")
        val aInfo = if (audioPump == null) "ses yok" else "$audioMime ${mode.audio.rate}Hz ${mode.audio.channels} kanal" +
            (audioError?.let { " (hata: ${it.message})" } ?: "")
        return TranscodeResult(f, "${device.name} / ${mode.label}\nGörüntü: $videoMime ${w}x$h ${mode.fps}fps ${mode.videoKbps}kbps, $framesIn kare\nSes: $aInfo")
    }

    /** Hata durumunda her şeyi bırakır. */
    fun abort() {
        synchronized(this) { stopped = true }
        audioRunning = false
        try { audioThread?.join(2000) } catch (_: Exception) { }
        try { audioRecord?.release() } catch (_: Exception) { }
        videoPump?.release()
        audioPump?.release()
        coord?.finish()
        outFile?.delete()
    }
}
