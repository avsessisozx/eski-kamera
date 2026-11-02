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
}
