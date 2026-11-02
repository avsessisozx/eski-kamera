package app.eskikamera

import android.Manifest
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveRecorderTest {
    @get:Rule
    val perm: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx get() = InstrumentationRegistry.getInstrumentation().context

    private fun devices(): List<Device> =
        Profiles.parse(testCtx.assets.open("devices.json").bufferedReader(Charsets.UTF_8).use { it.readText() })

    private fun syntheticFrame(w: Int, h: Int, shift: Int): YuvFrame {
        val argb = IntArray(w * h) { i ->
            val x = (i % w + shift) % w
            val y = i / w
            0xFF000000.toInt() or ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or 0x60
        }
        val yuv = FrameSampler.rgbToYuv420(argb, w, h)
        return YuvFrame(w, h, 0, 0, yuv[0], w, yuv[1], yuv[2], w / 2, 1)
    }

    private fun record(id: String, modeIdx: Int, audio: Boolean, orientation: Int) {
        val dev = devices().first { it.id == id }
        val mode = dev.modes[modeIdx]
        val dir = File(ctx.cacheDir, "live_$id").also { it.mkdirs() }
        val logs = ArrayList<String>()
        val rec = LiveRecorder(dev, mode, dev.videoLook!!, dev.videoPrescale, orientation, dir, "sonuc", audio) { logs.add(it) }
        rec.start()
        val frame = syntheticFrame(640, 480, 0)
        var kept = 0
        val t0 = System.nanoTime()
        var i = 0
        while (System.nanoTime() - t0 < 3_000_000_000L) {
            if (rec.onFrame(syntheticFrame(640, 480, i * 5), System.nanoTime()) != null) kept++
            i++
            Thread.sleep(33)
        }
        val s0 = System.nanoTime()
        val res = rec.stop()
        val stopMs = (System.nanoTime() - s0) / 1_000_000
        val r = MediaMetadataRetriever()
        r.setDataSource(res.file.path)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
        val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
        val fr = r.getFrameAtTime(1_000_000)
        r.release()
        assertEquals("$id genişlik (${res.info}) $logs", mode.width, w)
        assertEquals("$id yükseklik", mode.height, h)
        assertTrue("$id süre $dur", dur in 2000..4500)
        assertTrue("$id kare alınamadı", fr != null)
        assertTrue("$id çok az kare: $kept", kept >= 5)
        assertEquals("$id yön", orientation.toString(), rot)
        assertTrue("$id durdurma çok uzun sürdü: $stopMs ms", stopMs < 5000)
    }

    @Test
    fun phoneH263() = record("nokia_6230", 0, audio = true, orientation = 90)

    @Test
    fun cifPhoneNoAudio() = record("genel_cif_telefon", 0, audio = false, orientation = 0)

    @Test
    fun camcorderH264() = record("vhs_kamkorder", 0, audio = true, orientation = 90)

    @Test
    fun iphone3gs() = record("iphone_3gs", 0, audio = false, orientation = 90)
}
