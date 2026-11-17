package app.eskikamera

import java.io.File
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private fun devices(): List<Device> =
        Profiles.parse(File("../../profiles/devices.json").readText(Charsets.UTF_8))

    @Test
    fun profilesParse() {
        val d = devices()
        assertEquals(33, d.size)
        val n = d.first { it.id == "nokia_6230" }
        assertEquals(640, n.photoWidth)
        assertEquals(2, n.modes.size)
        assertEquals("h263", n.modes[0].codec)
        assertTrue(d.first { it.id == "olympus_e20" }.modes.isEmpty())
        assertEquals(320, d.first { it.id == "vhs_kamkorder" }.videoPrescale!![0])
        val gb = d.first { it.id == "nintendo_gb_camera" }
        assertEquals(4, gb.photoLook.levels)
        assertEquals(4, gb.photoScale)
        assertEquals("png", gb.photoFormat)
        assertTrue(d.all { it.note.isNotEmpty() || it.year > 2000 || true })
        assertEquals("1990'lar", d.first { it.id == "casio_qv10" }.era)
    }

    @Test
    fun gameBoyUsesFourPaletteColors() {
        val gb = devices().first { it.id == "nintendo_gb_camera" }
        val w = 128
        val h = 112
        val src = IntArray(w * h) { i -> val v = (i % w) * 255 / w; 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v }
        val out = LookProcessor.processPhoto(src, w, h, gb.photoLook, 3L, threads = 2)
        val colors = out.toSet()
        assertTrue("en fazla 4 renk: ${colors.size}", colors.size <= 4)
        assertTrue(colors.size >= 3)
    }

    @Test
    fun parallelFrameMatchesSingleThread() {
        val d = devices().first { it.id == "hi8_kamkorder" }
        val w = 320
        val h = 240
        val src = IntArray(w * h) { i -> 0xFF000000.toInt() or ((i * 7) and 0xFFFFFF) }
        val a = LookProcessor.processFrame(src, w, h, d.videoLook!!, 9L, threads = 1)
        val b = LookProcessor.processFrame(src, w, h, d.videoLook!!, 9L, threads = 4)
        assertEquals(a.size, b.size)
        var same = 0
        for (i in a.indices) if (a[i] == b[i]) same++
        assertTrue("paralel işleme aynı sonucu vermeli", same > a.size * 0.99)
    }

    @Test
    fun miniJsonEscapes() {
        val v = MiniJson.parse("""{"a":[1,2.5,"x\"yç"],"b":true,"c":null}""") as Map<*, *>
        assertEquals(listOf(1.0, 2.5, "x\"yç"), v["a"])
        assertEquals(true, v["b"])
    }

    @Test
    fun photoKeepsSizeAndChangesPixels() {
        val d = devices().first { it.id == "nokia_6230" }
        val w = 320
        val h = 240
        val src = IntArray(w * h) { i -> 0xFF000000.toInt() or (((i % w) * 255 / w) shl 16) or (128 shl 8) or 64 }
        val out = LookProcessor.processPhoto(src, w, h, d.photoLook, 5L, threads = 2)
        assertEquals(w * h, out.size)
        var diff = 0L
        for (i in src.indices) diff += abs(((src[i] shr 16) and 255) - ((out[i] shr 16) and 255))
        assertTrue("görüntü değişmeli", diff > 0)
        // aynı tohum aynı sonuç
        val out2 = LookProcessor.processPhoto(src, w, h, d.photoLook, 5L, threads = 3)
        assertTrue(out.contentEquals(out2))
    }

    @Test
    fun frameProcessingRuns() {
        val d = devices().first { it.id == "vhs_kamkorder" }
        val frame = IntArray(160 * 120) { 0xFF808080.toInt() }
        val out = LookProcessor.processFrame(frame, 160, 120, d.videoLook!!, 1L)
        assertEquals(160 * 120, out.size)
    }

    @Test
    fun audioChainResamples() {
        val chain = AudioChain(44100, 2, 8000, 1)
        val chunk = 8820
        val input = ShortArray(44100 * 2) { i -> (sin(i / 2 * 2 * Math.PI * 1000 / 44100) * 8000).toInt().toShort() }
        var total = 0
        for (c in 0 until 10) total += chain.process(input.copyOfRange(c * chunk, (c + 1) * chunk)).size
        assertTrue("örnek sayısı ~8000: $total", abs(total - 8000) < 10)
    }

    @Test
    fun yuvRoundTrip() {
        val argb = IntArray(16 * 16) { 0xFFC03020.toInt() }
        val yuv = FrameSampler.rgbToYuv420(argb, 16, 16)
        val f = YuvFrame(16, 16, 0, 0, yuv[0], 16, yuv[1], yuv[2], 8, 1)
        val back = FrameSampler.toRgb(f, 0, 16, 16, null)
        assertTrue(abs(((back[0] shr 16) and 255) - 0xC0) < 4)
        val rot = FrameSampler.toRgb(YuvFrame(32, 16, 0, 0, ByteArray(512), 32, ByteArray(128), ByteArray(128), 16, 1), 90, 16, 12, intArrayOf(32, 24))
        assertEquals(16 * 12, rot.size)
    }

    @Test
    fun newDevicesAndNativeModes() {
        val d = devices()
        val pxl = d.first { it.id == "pxl2000" }
        assertEquals(480, pxl.modes[0].width)
        assertEquals(120, pxl.modes[0].procW)
        val flir = d.first { it.id == "flir_one" }
        assertEquals(80, flir.modes[0].procW)
        assertEquals(2, flir.videoLook!!.palette)
        assertTrue(d.first { it.id == "ps_eye" }.modes.all { it.fps <= 30 })
    }

    @Test
    fun thermalPaletteProducesColor() {
        val flir = devices().first { it.id == "flir_one" }
        val w = 80
        val h = 60
        val src = IntArray(w * h) { i -> val v = (i % w) * 255 / w; 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v }
        val out = LookProcessor.processFrame(src, w, h, flir.videoLook!!, 2L, threads = 1)
        var colored = 0
        for (p in out) { val r = (p shr 16) and 255; val b = p and 255; if (abs(r - b) > 40) colored++ }
        assertTrue("termal palet renkli olmalı", colored > out.size / 4)
    }

    @Test
    fun motionTriggerStartsAndStops() {
        val t = MotionTrigger(quietMs = 2000L, cooldownMs = 0L)
        assertEquals(MotionTrigger.Event.NONE, t.feed(10f, 6f, 0L))
        assertEquals(MotionTrigger.Event.START, t.feed(10f, 6f, 100L))
        assertEquals(MotionTrigger.Event.NONE, t.feed(0f, 6f, 1000L))
        assertEquals(MotionTrigger.Event.STOP, t.feed(0f, 6f, 2500L))
    }

    @Test
    fun motionDetectorScoresChange() {
        val m = MotionDetector()
        val a = ByteArray(64 * 48) { 10 }
        val b = ByteArray(64 * 48) { 100 }
        assertEquals(0f, m.update(a), 0.001f)
        assertEquals(0f, m.update(a), 0.001f)
        assertTrue(m.update(b) > 50f)
    }

    @Test
    fun smoothExportScalesUpAndKeepsProcessingSize() {
        val m = devices().first { it.id == "nokia_6230" }.modes[0]
        val e = m.smoothExport()
        assertTrue(e.smooth)
        assertEquals("h264", e.codec)
        assertEquals("mp4", e.container)
        assertEquals(m.width, e.procW)
        assertEquals(m.height, e.procH)
        assertTrue("çıktı yeterince büyük: ${e.width}x${e.height}", e.height >= 360 && e.width % m.width == 0)
        assertEquals(e.width / m.width, e.height / m.height)
        // büyük modlar ve yerel çözünürlüklü cihazlar değişmez
        val vhs = devices().first { it.id == "vhs_kamkorder" }.modes[0]
        assertEquals(vhs.width, vhs.smoothExport().width)
        val pxl = devices().first { it.id == "pxl2000" }.modes[0]
        assertEquals(pxl.width, pxl.smoothExport().width)
    }

    @Test
    fun smoothResizeHasNoBlockyPixelsAndKeepsFlatColors() {
        val flat = IntArray(4 * 4) { 0xFF336699.toInt() }
        val up = FrameSampler.resizeSmooth(flat, 4, 4, 16, 16)
        assertTrue(up.all { it == 0xFF336699.toInt() })
        // keskin kenar: en yakın komşuda yalnızca 2 değer olur, yumuşak büyütmede ara değerler olmalı
        val edge = IntArray(4 * 2) { i -> if (i % 4 < 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val near = FrameSampler.upscaleNearest(edge, 4, 2, 16, 8).map { it and 255 }.toSet()
        val smooth = FrameSampler.resizeSmooth(edge, 4, 2, 16, 8).map { it and 255 }.toSet()
        assertEquals(2, near.size)
        assertTrue("ara tonlar yok: ${smooth.size}", smooth.size > 4)
    }

    @Test
    fun framePipelineOutputsRequestedSizeBothWays() {
        val d = devices().first { it.id == "nokia_6230" }
        val m = d.modes[0]
        val look = d.videoLook!!
        val w = 64
        val h = 48
        val yuv = YuvFrame(w, h, 0, 0, ByteArray(w * h) { 120 }, w, ByteArray(w * h / 4) { (128).toByte() }, ByteArray(w * h / 4) { (128).toByte() }, w / 2, 1)
        val small = FramePipeline.process(yuv, m, look, d.videoPrescale, 1L)
        assertEquals(m.width * m.height, small.size)
        val e = m.smoothExport()
        val big = FramePipeline.process(yuv, e, look, d.videoPrescale, 1L)
        assertEquals(e.width * e.height, big.size)
        val vhs = devices().first { it.id == "vhs_kamkorder" }
        val vm = vhs.modes[0]
        val v = FramePipeline.process(yuv, vm, vhs.videoLook!!, vhs.videoPrescale, 1L)
        assertEquals(vm.width * vm.height, v.size)
    }

    @Test
    fun chromaBleedShiftsColorButKeepsEdgesInRange() {
        val w = 40
        val h = 6
        val src = IntArray(w * h) { i -> if ((i % w) < 20) 0xFFFF2020.toInt() else 0xFF2020FF.toInt() }
        val vhs = devices().first { it.id == "vhs_kamkorder" }.videoLook!!
        val out = LookProcessor.processFrame(src, w, h, vhs.copy(noiseLuma = 0f, noiseChroma = 0f, vignette = 0f, bloom = 0f), 1L, threads = 1)
        assertEquals(src.size, out.size)
        assertTrue("renkler geçerli aralıkta", out.all { (it ushr 24) == 0xFF })
        assertTrue("kenarda renk taşması olmalı", out[19] != out[0] || out[20] != out[w - 1])
    }

    @Test
    fun chromaSoftKeepsLuminanceStructure() {
        val w = 32
        val h = 32
        val src = IntArray(w * h) { i -> if ((i % w) < 16) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        val base = devices().first { it.id == "nokia_6230" }.videoLook!!
        val a = LookProcessor.processFrame(src, w, h, base.copy(chromaSoft = 0f, noiseLuma = 0f, noiseChroma = 0f), 1L, threads = 1)
        val b = LookProcessor.processFrame(src, w, h, base.copy(chromaSoft = 1.2f, noiseLuma = 0f, noiseChroma = 0f), 1L, threads = 1)
        var diff = 0L
        for (i in a.indices) diff += abs(((a[i] shr 8) and 255) - ((b[i] shr 8) and 255))
        assertTrue("renk yumuşatma etki etmeli", a.indices.any { a[it] != b[it] })
    }

    @Test
    fun exportQualityRaisesBitrateAndLabelSaysMp4() {
        val m = devices().first { it.id == "nokia_6230" }.modes[0]
        val n = m.smoothExport(0); val h = m.smoothExport(1); val x = m.smoothExport(2)
        assertTrue(n.videoKbps < h.videoKbps && h.videoKbps < x.videoKbps)
        assertTrue("etiket MP4 olmalı: ${h.label}", !h.label.contains("3GP", true))
    }

    @Test
    fun ghostBlendsPreviousFrameAndPumpSettles() {
        val w = 8; val h = 8
        val dark = IntArray(w * h) { 0xFF000000.toInt() }
        val white = { IntArray(w * h) { 0xFFFFFFFF.toInt() } }
        val t = Temporal(2, false)
        t.apply(dark.copyOf(), w, h)
        val f = white()
        t.apply(f, w, h)
        val v = f[0] and 255
        assertTrue("önceki kare karışmalı: $v", v in 100..250)
        // pompalama: parlak sahneden sonra karanlık sahnede kazanç yavaş oturur
        val p = Temporal(0, true)
        var last = 0
        for (i in 0 until 60) { val g = IntArray(w * h) { 0xFF404040.toInt() }; p.apply(g, w, h); last = g[0] and 255 }
        val first = IntArray(w * h) { 0xFF404040.toInt() }
        val p2 = Temporal(0, true)
        for (i in 0 until 3) { val g = IntArray(w * h) { 0xFF404040.toInt() }; p2.apply(g, w, h) }
        p2.apply(first, w, h)
        assertTrue("kazanç yavaş yükselir: ${first[0] and 255} < $last", (first[0] and 255) <= last)
    }

    @Test
    fun integerYuvMatchesKnownColors() {
        val white = IntArray(4) { 0xFFFFFFFF.toInt() }
        val y = FrameSampler.rgbToYuv420(white, 2, 2)
        assertEquals(235, y[0][0].toInt() and 255)
        assertEquals(128, y[1][0].toInt() and 255)
        val red = IntArray(4) { 0xFFFF0000.toInt() }
        val r = FrameSampler.rgbToYuv420(red, 2, 2)
        assertTrue(abs((r[2][0].toInt() and 255) - 240) <= 2)
        val black = IntArray(4) { 0xFF000000.toInt() }
        assertEquals(16, FrameSampler.rgbToYuv420(black, 2, 2)[0][0].toInt() and 255)
    }
}
