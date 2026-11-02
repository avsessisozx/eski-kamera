package app.eskikamera

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscodeInstrumentedTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx get() = InstrumentationRegistry.getInstrumentation().context

    private fun devices(): List<Device> =
        Profiles.parse(testCtx.assets.open("devices.json").bufferedReader(Charsets.UTF_8).use { it.readText() })

    private fun copyAsset(name: String): File {
        val f = File(ctx.cacheDir, name)
        testCtx.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    private fun check(dev: Device, modeIdx: Int, srcName: String) {
        val src = copyAsset(srcName)
        val mode = dev.modes[modeIdx]
        val outDir = File(ctx.cacheDir, "t_${dev.id}_${modeIdx}_${srcName}").also { it.mkdirs() }
        val logs = ArrayList<String>()
        val res = VideoTranscoder.transcode(ctx, Uri.fromFile(src), dev, mode, outDir, "sonuc", { logs.add(it) }, { })
        assertTrue("çıktı yok: ${logs}", res.file.exists() && res.file.length() > 500)
        val r = MediaMetadataRetriever()
        r.setDataSource(res.file.path)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
        val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
        val frame = r.getFrameAtTime(1_000_000)
        r.release()
        assertEquals("${dev.id} genişlik (${res.info})", mode.width, w)
        assertEquals("${dev.id} yükseklik", mode.height, h)
        assertTrue("${dev.id} süre $dur", dur in 2000..5000)
        assertEquals("${dev.id} ses", "yes", hasAudio)
        assertTrue("kare alınamadı", frame != null)
        var sum = 0L
        val px = IntArray(frame!!.width * frame.height)
        frame.getPixels(px, 0, frame.width, 0, 0, frame.width, frame.height)
        for (p in px) sum += ((p shr 16) and 255) + ((p shr 8) and 255) + (p and 255)
        assertTrue("kare siyah", sum / px.size / 3 > 8)
    }

    @Test
    fun phoneProfile() = check(devices().first { it.id == "nokia_6230" }, 0, "sample.mp4")

    @Test
    fun phoneVgaMpeg4() = check(devices().first { it.id == "nokia_n95" }, 0, "sample.mp4")

    @Test
    fun camcorderProfile() = check(devices().first { it.id == "vhs_kamkorder" }, 0, "sample.mp4")

    @Test
    fun rotatedSource() = check(devices().first { it.id == "se_k550" }, 0, "sample_rot90.mp4")

    @Test
    fun photoProfile() {
        val src = copyAsset("sample.jpg")
        val dev = devices().first { it.id == "nokia_6230" }
        val out = File(ctx.cacheDir, "foto_out.jpg")
        PhotoProcessor.process(ctx, Uri.fromFile(src), dev, 1L, out) { }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(out.path, o)
        assertEquals(640, o.outWidth)
        assertEquals(480, o.outHeight)
    }
}
