package app.eskikamera

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
    @get:Rule
    val perm: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(500)
        }
        return false
    }

    private fun logHas(s: String) = ErrorLog.snapshot().any { it.contains(s) }
    private fun logText() = ErrorLog.snapshot().joinToString("\n")

    @Test
    fun launchAndCapture() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            var count = 0
            sc.onActivity { count = it.deviceCountForTest() }
            assertEquals("cihaz sayısı\n" + logText(), 33, count)
            val ready = waitFor(30_000) { logHas("Kamera hazır") }
            assumeTrue("Emülatör kamerası açılamadı:\n" + logText(), ready)

            sc.onActivity { it.takePhoto() }
            val photoDone = waitFor(90_000) { logHas("Tamam: Pictures") || logHas("Hata oldu") || logHas("Fotoğraf çekilemedi") }
            assertTrue("foto bitmedi\n" + logText(), photoDone)
            assertFalse("foto hatası\n" + logText(), logHas("Hata oldu") || logHas("Fotoğraf çekilemedi"))

            sc.onActivity { it.toggleVideo() }
            Thread.sleep(4000)
            assertFalse("kayıt başlamadı\n" + logText(), logHas("Kayıt başlatılamadı"))
            sc.onActivity { it.toggleVideo() }
            val videoDone = waitFor(60_000) { logHas("Tamam: Movies") || logHas("Hata oldu") }
            assertTrue("video bitmedi\n" + logText(), videoDone)
            assertFalse("video hatası\n" + logText(), logHas("Hata oldu"))
        }
    }

    @Test
    fun extraModesAndViewer() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val ready = waitFor(30_000) { logHas("Kamera hazır") }
            assumeTrue("Emülatör kamerası açılamadı:\n" + logText(), ready)

            // yerel çözünürlüklü cihaz (PXL-2000): 120x90 işlenip 480x360'a büyütülür
            sc.onActivity { it.selectDeviceForTest("pxl2000"); it.selectCapForTest("TIMELAPSE") }
            sc.onActivity { it.shutterForTest() }
            Thread.sleep(5000)
            assertFalse("zaman atlamalı başlamadı\n" + logText(), logHas("Kayıt başlatılamadı"))
            sc.onActivity { it.shutterForTest() }
            val done = waitFor(60_000) { logHas("Tamam: Movies") || logHas("Hata oldu") }
            assertTrue("zaman atlamalı bitmedi\n" + logText(), done)
            assertFalse("zaman atlamalı hatası\n" + logText(), logHas("Hata oldu"))

            // görüntüleyici açılır
            ActivityScenario.launch(ViewerActivity::class.java).use {
                assertTrue("görüntüleyici açılmadı\n" + logText(), waitFor(15_000) { logHas("Görüntüleyici:") })
            }
        }
    }
}
