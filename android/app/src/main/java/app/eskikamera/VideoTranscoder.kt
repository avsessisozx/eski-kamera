package app.eskikamera

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

class TranscodeResult(val file: File, val info: String)

/**
 * Videoyu seçilen cihazın çözünürlüğü, kare hızı, bit hızı ve görünümüyle yeniden kodlar;
 * sesi de o cihazın örnekleme hızına ve bant genişliğine düşürür.
 * Akış: görüntü -> geçici dosya, ses -> geçici dosya, sonra ikisi birleştirilir.
 */
object VideoTranscoder {
    private const val TIMEOUT_US = 10_000L

    fun transcode(
        context: Context, src: Uri, device: Device, mode: VideoMode, outDir: File, baseName: String,
        log: (String) -> Unit, progress: (Float) -> Unit
    ): TranscodeResult {
        val look = device.videoLook ?: throw ProcessException("${device.name} cihazında video yok")
        val work = File(context.cacheDir, "tc_${System.nanoTime()}")
        work.mkdirs()
        try {
            val probe = openExtractor(context, src)
            var vIdx = -1
            var aIdx = -1
            var durationUs = 0L
            for (i in 0 until probe.trackCount) {
                val f = probe.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && vIdx < 0) {
                    vIdx = i
                    if (f.containsKey(MediaFormat.KEY_DURATION)) durationUs = f.getLong(MediaFormat.KEY_DURATION)
                } else if (mime.startsWith("audio/") && aIdx < 0) {
                    aIdx = i
                }
            }
            probe.release()
            if (vIdx < 0) throw ProcessException("Dosyada görüntü akışı yok")

            val primaryType = if (mode.container == "3gp") MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            val vTmp = File(work, "v.tmp")
            val aTmp = File(work, "a.tmp")
            val vInfo = encodeVideo(context, src, vIdx, device, mode, look, vTmp, primaryType, durationUs, log) { progress(it * 0.9f) }
            var aInfo = "ses yok"
            if (aIdx >= 0) {
                aInfo = encodeAudio(context, src, aIdx, mode, aTmp, primaryType, log)
            }
            progress(0.95f)

            var ext = if (primaryType == MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP) ".3gp" else ".mp4"
            var outFile = File(outDir, baseName + ext)
            try {
                mux(primaryType, vTmp, if (aIdx >= 0) aTmp else null, outFile)
            } catch (e: Exception) {
                if (primaryType == MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP) {
                    log("3GP birleştirme olmadı (${e.message}), MP4 kullanılıyor")
                    outFile.delete()
                    ext = ".mp4"
                    outFile = File(outDir, baseName + ext)
                    mux(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, vTmp, if (aIdx >= 0) aTmp else null, outFile)
                } else throw e
            }
            progress(1f)
            return TranscodeResult(outFile, "${device.name} / ${mode.label}\nGörüntü: $vInfo\nSes: $aInfo")
        } finally {
            work.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ yardımcılar
    private fun openExtractor(context: Context, uri: Uri): MediaExtractor {
        val ex = MediaExtractor()
        try {
            if (uri.scheme == "file") {
                ex.setDataSource(uri.path!!)
            } else {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw ProcessException("Dosya açılamadı")
                pfd.use { ex.setDataSource(it.fileDescriptor) }
            }
        } catch (e: Exception) {
            ex.release()
            throw ProcessException("Video açılamadı: ${e.message}", e)
        }
        return ex
    }

    private class Enc(val codec: MediaCodec, val mime: String)

    private fun createVideoEncoder(mode: VideoMode, log: (String) -> Unit): Enc {
        val wanted = when (mode.codec) { "h263" -> "video/3gpp"; "mpeg4" -> "video/mp4v-es"; else -> "video/avc" }
        val candidates = if (wanted == "video/avc") listOf("video/avc") else listOf(wanted, "video/avc")
        var lastError: Exception? = null
        for (mime in candidates) {
            var codec: MediaCodec? = null
            try {
                val f = MediaFormat.createVideoFormat(mime, mode.width, mode.height)
                f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                f.setInteger(MediaFormat.KEY_BIT_RATE, mode.videoKbps * 1000)
                f.setInteger(MediaFormat.KEY_FRAME_RATE, mode.fps)
                f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                codec = MediaCodec.createEncoderByType(mime)
                codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                if (mime != wanted) log("Bu telefonda ${mode.codec} kodlayıcısı yok, H.264 kullanıldı")
                return Enc(codec, mime)
            } catch (e: Exception) {
                lastError = e
                log("Kodlayıcı $mime kullanılamadı: ${e.message}")
                try { codec?.release() } catch (_: Exception) { }
            }
        }
        throw ProcessException("Bu telefon ${mode.width}x${mode.height} video kodlayamadı: ${lastError?.message}", lastError)
    }

    private fun bytesOf(b: ByteBuffer): ByteArray {
        val d = b.duplicate()
        d.rewind()
        val a = ByteArray(d.remaining())
        d.get(a)
        return a
    }

    private fun copyFrame(img: Image): YuvFrame {
        val crop = img.cropRect
        val p0 = img.planes[0]
        val p1 = img.planes[1]
        val p2 = img.planes[2]
        return YuvFrame(
            crop.width(), crop.height(), crop.left, crop.top,
            bytesOf(p0.buffer), p0.rowStride, bytesOf(p1.buffer), bytesOf(p2.buffer), p1.rowStride, p1.pixelStride
        )
    }

    private fun fillImage(img: Image, yuv: Array<ByteArray>, w: Int, h: Int) {
        for (p in 0..2) {
            val pl = img.planes[p]
            val buf = pl.buffer
            val rs = pl.rowStride
            val ps = pl.pixelStride
            val pw = if (p == 0) w else w / 2
            val ph = if (p == 0) h else h / 2
            val src = yuv[p]
            for (row in 0 until ph) {
                if (ps == 1) {
                    buf.position(row * rs)
                    buf.put(src, row * pw, pw)
                } else {
                    for (col in 0 until pw) buf.put(row * rs + col * ps, src[row * pw + col])
                }
            }
        }
    }

    // ------------------------------------------------------------------ görüntü
    private fun encodeVideo(
        context: Context, src: Uri, vIdx: Int, device: Device, mode: VideoMode, look: Look, out: File,
        muxType: Int, durationUs: Long, log: (String) -> Unit, progress: (Float) -> Unit
    ): String {
        val ex = openExtractor(context, src)
        var dec: MediaCodec? = null
        var enc: Enc? = null
        var muxer: MediaMuxer? = null
        try {
            ex.selectTrack(vIdx)
            val inFmt = ex.getTrackFormat(vIdx)
            val inMime = inFmt.getString(MediaFormat.KEY_MIME)!!
            val rotation = if (inFmt.containsKey(MediaFormat.KEY_ROTATION)) inFmt.getInteger(MediaFormat.KEY_ROTATION) else 0
            inFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val decoder = MediaCodec.createDecoderByType(inMime)
            dec = decoder
            decoder.configure(inFmt, null, null, 0)
            decoder.start()
            val encoder = createVideoEncoder(mode, log)
            enc = encoder
            val codec = encoder.codec
            val mx = MediaMuxer(out.path, muxType)
            muxer = mx

            var muxTrack = -1
            var muxStarted = false
            var encDone = false
            var written = 0
            val encInfo = MediaCodec.BufferInfo()

            fun drainEncoder(timeoutUs: Long) {
                while (true) {
                    val oi = codec.dequeueOutputBuffer(encInfo, timeoutUs)
                    if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) return
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxTrack = mx.addTrack(codec.outputFormat)
                        mx.start()
                        muxStarted = true
                    } else if (oi >= 0) {
                        val buf = codec.getOutputBuffer(oi)!!
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encInfo.size = 0
                        if (encInfo.size > 0 && muxStarted) {
                            buf.position(encInfo.offset)
                            buf.limit(encInfo.offset + encInfo.size)
                            mx.writeSampleData(muxTrack, buf, encInfo)
                            written++
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encDone = true
                            return
                        }
                    }
                }
            }

            fun encoderInput(): Int {
                var tries = 0
                while (true) {
                    drainEncoder(0)
                    val ei = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (ei >= 0) return ei
                    if (++tries > 600) throw ProcessException("Video kodlayıcı yanıt vermedi")
                }
            }

            val frameUs = 1_000_000L / mode.fps
            val tol = min(8000L, frameUs / 4)
            val seedBase = System.nanoTime()
            var firstPts = -1L
            var nextKeep = 0L
            var inputDone = false
            var decDone = false
            var lastRel = 0L
            var framesIn = 0
            val decInfo = MediaCodec.BufferInfo()

            while (!encDone) {
                if (!inputDone) {
                    val ib = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (ib >= 0) {
                        val buf = decoder.getInputBuffer(ib)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(ib, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                if (!decDone) {
                    val ob = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                    if (ob >= 0) {
                        val eos = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (decInfo.size > 0) {
                            val pts = decInfo.presentationTimeUs
                            if (firstPts < 0) firstPts = pts
                            val rel = pts - firstPts
                            if (rel + tol >= nextKeep) {
                                val img = decoder.getOutputImage(ob)
                                if (img != null) {
                                    val frame = try { copyFrame(img) } finally { img.close() }
                                    val rgb = FrameSampler.toRgb(frame, rotation, mode.width, mode.height, device.videoPrescale)
                                    val proc = LookProcessor.processFrame(rgb, mode.width, mode.height, look, seedBase + framesIn)
                                    val yuv = FrameSampler.rgbToYuv420(proc, mode.width, mode.height)
                                    val ei = encoderInput()
                                    val inImg = codec.getInputImage(ei) ?: throw ProcessException("Kodlayıcı giriş görüntüsü alınamadı")
                                    fillImage(inImg, yuv, mode.width, mode.height)
                                    codec.queueInputBuffer(ei, 0, mode.width * mode.height * 3 / 2, rel, 0)
                                    framesIn++
                                    lastRel = rel
                                    nextKeep += frameUs
                                    if (nextKeep <= rel) nextKeep = rel + frameUs
                                }
                            }
                            if (durationUs > 0) progress((rel.toFloat() / durationUs).coerceIn(0f, 1f))
                        }
                        decoder.releaseOutputBuffer(ob, false)
                        if (eos) {
                            decDone = true
                            val ei = encoderInput()
                            codec.queueInputBuffer(ei, 0, 0, lastRel, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        }
                    }
                }
                drainEncoder(if (decDone) TIMEOUT_US else 0)
            }
            if (framesIn == 0 || written == 0) throw ProcessException("Hiç video karesi yazılamadı")
            return "${encoder.mime} ${mode.width}x${mode.height} ${mode.fps}fps ${mode.videoKbps}kbps, $framesIn kare"
        } finally {
            try { dec?.stop() } catch (_: Exception) { }
            try { dec?.release() } catch (_: Exception) { }
            try { enc?.codec?.stop() } catch (_: Exception) { }
            try { enc?.codec?.release() } catch (_: Exception) { }
            try { muxer?.stop() } catch (_: Exception) { }
            try { muxer?.release() } catch (_: Exception) { }
            ex.release()
        }
    }

    // ------------------------------------------------------------------ ses
    private fun createAudioEncoder(spec: AudioSpec, wantAmr: Boolean, log: (String) -> Unit): Enc {
        if (wantAmr) {
            var c: MediaCodec? = null
            try {
                val f = MediaFormat.createAudioFormat("audio/3gpp", 8000, 1)
                f.setInteger(MediaFormat.KEY_BIT_RATE, 12200)
                c = MediaCodec.createEncoderByType("audio/3gpp")
                c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                c.start()
                return Enc(c, "audio/3gpp")
            } catch (e: Exception) {
                log("AMR-NB kodlayıcı yok (${e.message}), AAC kullanılıyor")
                try { c?.release() } catch (_: Exception) { }
            }
        }
        val f = MediaFormat.createAudioFormat("audio/mp4a-latm", spec.rate, spec.channels)
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        val kbps = if (spec.pcm || spec.kbps == 0) 64 else spec.kbps
        f.setInteger(MediaFormat.KEY_BIT_RATE, maxOf(kbps, 16) * 1000)
        val c = MediaCodec.createEncoderByType("audio/mp4a-latm")
        try {
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
        } catch (e: Exception) {
            c.release()
            throw ProcessException("Ses kodlayıcı başlatılamadı: ${e.message}", e)
        }
        return Enc(c, "audio/mp4a-latm")
    }

    private fun encodeAudio(
        context: Context, src: Uri, aIdx: Int, mode: VideoMode, out: File, muxType: Int, log: (String) -> Unit
    ): String {
        val spec = mode.audio
        val ex = openExtractor(context, src)
        var dec: MediaCodec? = null
        var enc: Enc? = null
        var muxer: MediaMuxer? = null
        try {
            ex.selectTrack(aIdx)
            val inFmt = ex.getTrackFormat(aIdx)
            val inMime = inFmt.getString(MediaFormat.KEY_MIME)!!
            var inRate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var inCh = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val decoder = MediaCodec.createDecoderByType(inMime)
            dec = decoder
            decoder.configure(inFmt, null, null, 0)
            decoder.start()
            val wantAmr = spec.rate == 8000 && spec.channels == 1 && !spec.pcm && spec.kbps <= 13 && muxType == MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP
            val encoder = createAudioEncoder(spec, wantAmr, log)
            enc = encoder
            val codec = encoder.codec
            val mx = MediaMuxer(out.path, muxType)
            muxer = mx

            var muxTrack = -1
            var muxStarted = false
            var encDone = false
            var written = 0
            val encInfo = MediaCodec.BufferInfo()
            val frameBytes = 2 * spec.channels
            var framesFed = 0L

            fun drainEncoder(timeoutUs: Long) {
                while (true) {
                    val oi = codec.dequeueOutputBuffer(encInfo, timeoutUs)
                    if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) return
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxTrack = mx.addTrack(codec.outputFormat)
                        mx.start()
                        muxStarted = true
                    } else if (oi >= 0) {
                        val buf = codec.getOutputBuffer(oi)!!
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encInfo.size = 0
                        if (encInfo.size > 0 && muxStarted) {
                            buf.position(encInfo.offset)
                            buf.limit(encInfo.offset + encInfo.size)
                            mx.writeSampleData(muxTrack, buf, encInfo)
                            written++
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encDone = true
                            return
                        }
                    }
                }
            }

            fun encoderInput(): Int {
                var tries = 0
                while (true) {
                    drainEncoder(0)
                    val ei = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (ei >= 0) return ei
                    if (++tries > 600) throw ProcessException("Ses kodlayıcı yanıt vermedi")
                }
            }

            fun feed(pcm: ShortArray) {
                if (pcm.isEmpty()) return
                val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.nativeOrder())
                bb.asShortBuffer().put(pcm)
                val bytes = bb.array()
                var off = 0
                while (off < bytes.size) {
                    val ei = encoderInput()
                    val ib = codec.getInputBuffer(ei)!!
                    ib.clear()
                    val cap = ib.capacity() - ib.capacity() % frameBytes
                    val n = min(bytes.size - off, cap)
                    ib.put(bytes, off, n)
                    val pts = framesFed * 1_000_000L / spec.rate
                    codec.queueInputBuffer(ei, 0, n, pts, 0)
                    framesFed += n / frameBytes
                    off += n
                }
            }

            var chain: AudioChain? = null
            var inputDone = false
            var decDone = false
            val decInfo = MediaCodec.BufferInfo()
            while (!encDone) {
                if (!inputDone) {
                    val ib = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (ib >= 0) {
                        val buf = decoder.getInputBuffer(ib)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(ib, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                if (!decDone) {
                    val ob = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                    if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = decoder.outputFormat
                        inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        inCh = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        chain = null
                    } else if (ob >= 0) {
                        val eos = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (decInfo.size > 0) {
                            val buf = decoder.getOutputBuffer(ob)!!
                            buf.position(decInfo.offset)
                            buf.limit(decInfo.offset + decInfo.size)
                            val sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val shorts = ShortArray(sb.remaining())
                            sb.get(shorts)
                            if (chain == null) chain = AudioChain(inRate, inCh, spec.rate, spec.channels)
                            feed(chain.process(shorts))
                        }
                        decoder.releaseOutputBuffer(ob, false)
                        if (eos) {
                            decDone = true
                            val ei = encoderInput()
                            codec.queueInputBuffer(ei, 0, 0, framesFed * 1_000_000L / spec.rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        }
                    }
                }
                drainEncoder(if (decDone) TIMEOUT_US else 0)
            }
            if (written == 0) throw ProcessException("Hiç ses örneği yazılamadı")
            return "${encoder.mime} ${spec.rate}Hz ${spec.channels} kanal"
        } finally {
            try { dec?.stop() } catch (_: Exception) { }
            try { dec?.release() } catch (_: Exception) { }
            try { enc?.codec?.stop() } catch (_: Exception) { }
            try { enc?.codec?.release() } catch (_: Exception) { }
            try { muxer?.stop() } catch (_: Exception) { }
            try { muxer?.release() } catch (_: Exception) { }
            ex.release()
        }
    }

    // ------------------------------------------------------------------ birleştirme
    private fun firstTrack(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            if ((ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith(prefix)) return i
        }
        throw ProcessException("Geçici dosyada $prefix akışı yok")
    }

    private fun mux(muxType: Int, vFile: File, aFile: File?, outFile: File) {
        val m = MediaMuxer(outFile.path, muxType)
        val vEx = MediaExtractor()
        var aEx: MediaExtractor? = null
        try {
            vEx.setDataSource(vFile.path)
            val vi = firstTrack(vEx, "video/")
            vEx.selectTrack(vi)
            val vOut = m.addTrack(vEx.getTrackFormat(vi))
            var aOut = -1
            if (aFile != null) {
                val ae = MediaExtractor()
                aEx = ae
                ae.setDataSource(aFile.path)
                val ai = firstTrack(ae, "audio/")
                ae.selectTrack(ai)
                aOut = m.addTrack(ae.getTrackFormat(ai))
            }
            m.start()
            val buf = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val vt = vEx.sampleTime
                val at = aEx?.sampleTime ?: -1L
                if (vt < 0 && at < 0) break
                val useV = vt >= 0 && (at < 0 || vt <= at)
                val src = if (useV) vEx else aEx!!
                val track = if (useV) vOut else aOut
                buf.clear()
                val n = src.readSampleData(buf, 0)
                if (n < 0) break
                val flags = if (src.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, n, src.sampleTime, flags)
                m.writeSampleData(track, buf, info)
                src.advance()
            }
            m.stop()
        } finally {
            try { m.release() } catch (_: Exception) { }
            vEx.release()
            aEx?.release()
        }
    }
}
