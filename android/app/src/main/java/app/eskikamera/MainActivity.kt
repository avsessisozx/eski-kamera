package app.eskikamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private class PreviewSpec(val w: Int, val h: Int, val look: Look, val prescale: IntArray?)

    private lateinit var devices: List<Device>
    private var deviceIndex = 0
    private var modeIndex = 0
    private var videoMode = false

    private lateinit var previewImage: ImageView
    private lateinit var deviceChip: TextView
    private lateinit var modeChip: TextView
    private lateinit var specLine: TextView
    private lateinit var timerText: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var segPhoto: TextView
    private lateinit var segVideo: TextView
    private lateinit var shutterInner: View
    private lateinit var galleryBtn: TextView
    private lateinit var menuBtn: TextView

    private var imageCapture: ImageCapture? = null
    private var analysis: ImageAnalysis? = null
    @Volatile private var liveRecorder: LiveRecorder? = null
    @Volatile private var spec: PreviewSpec? = null
    @Volatile private var lastRotation = 90
    private var lastPreviewNano = 0L
    private var recordingStartMs = 0L

    private var busy = false
    private var lastError: Throwable? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.CAMERA] == true) startCamera()
        else say("Kamera izni verilmedi. Menüden galeriden dönüştürme yine de çalışır.")
    }
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) processPhoto(uri, null)
    }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) processVideo(uri, null)
    }

    // ------------------------------------------------------------------ yaşam döngüsü
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            ErrorLog.saveCrash(applicationContext, e)
            previous?.uncaughtException(t, e)
        }
        try {
            val text = assets.open("devices.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            devices = Profiles.parse(text)
        } catch (e: Throwable) {
            ErrorLog.add("Profil dosyası okunamadı: ${e.message}")
            lastError = e
            devices = emptyList()
        }
        buildUi()
        if (devices.isEmpty()) {
            say("Cihaz listesi yüklenemedi. Menüden 'Hata raporunu kopyala' ile bana gönder.")
            return
        }
        val prefs = getSharedPreferences("eski_kamera", Context.MODE_PRIVATE)
        val savedId = prefs.getString("device", null)
        deviceIndex = devices.indexOfFirst { it.id == savedId }.coerceAtLeast(0)
        modeIndex = devices[deviceIndex].defaultMode
        selectDevice(deviceIndex, modeIndex, save = false)
        if (ErrorLog.loadCrash(this) != null) say("Önceki çalışmada çökme olmuş. Menüden 'Hata raporunu kopyala' ile gönderebilirsin.")
        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startCamera()
        else permLauncher.launch(needed)
    }

    override fun onDestroy() {
        liveRecorder?.let { it.abort() }
        liveRecorder = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ arayüz
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun shape(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    private fun chip(text: String, sizeSp: Float = 15f): TextView = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(Color.WHITE)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = shape(0xCC202020.toInt(), 18)
    }

    private fun buildUi() {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        previewImage = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(previewImage, FrameLayout.LayoutParams(match, match))

        // üst: cihaz ve mod seçimi
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(14), dp(12), dp(6))
        }
        deviceChip = chip("", 17f).apply { setTypeface(typeface, Typeface.BOLD); setOnClickListener { openDevicePicker() } }
        modeChip = chip("", 14f).apply { setOnClickListener { openModePicker() } }
        specLine = TextView(this).apply {
            textSize = 12f; setTextColor(0xFFE0E0E0.toInt()); gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 0f, Color.BLACK); setPadding(dp(8), dp(6), dp(8), 0)
        }
        timerText = TextView(this).apply {
            textSize = 15f; setTextColor(Color.WHITE); visibility = View.GONE
            setPadding(dp(12), dp(4), dp(12), dp(4)); background = shape(0xCCD32F2F.toInt(), 14)
        }
        top.addView(deviceChip, LinearLayout.LayoutParams(wrap, wrap))
        top.addView(modeChip, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(8) })
        top.addView(specLine, LinearLayout.LayoutParams(match, wrap))
        top.addView(timerText, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(6) })
        root.addView(top, FrameLayout.LayoutParams(match, wrap, Gravity.TOP))

        // alt: durum, seçici, deklanşör
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(10), dp(16), dp(22))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0xB0000000.toInt()))
        }
        statusText = TextView(this).apply {
            textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 3
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.INVISIBLE }
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(0x99000000.toInt(), 20)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        segPhoto = TextView(this).apply { text = "FOTO"; textSize = 14f; gravity = Gravity.CENTER; setPadding(dp(22), dp(8), dp(22), dp(8)); setOnClickListener { setVideoMode(false) } }
        segVideo = TextView(this).apply { text = "VİDEO"; textSize = 14f; gravity = Gravity.CENTER; setPadding(dp(22), dp(8), dp(22), dp(8)); setOnClickListener { setVideoMode(true) } }
        seg.addView(segPhoto)
        seg.addView(segVideo)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        galleryBtn = chip("Galeri", 14f).apply { setOnClickListener { openGalleryMenu(it) } }
        val shutter = FrameLayout(this)
        val ring = View(this).apply { background = shape(Color.TRANSPARENT, 40, Color.WHITE, 4) }
        shutterInner = View(this)
        shutter.addView(ring, FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER))
        shutter.addView(shutterInner, FrameLayout.LayoutParams(dp(62), dp(62), Gravity.CENTER))
        shutter.setOnClickListener { onShutter() }
        menuBtn = chip("⋮", 20f).apply { setOnClickListener { openMenu(it) } }
        row.addView(galleryBtn, LinearLayout.LayoutParams(0, wrap, 1f))
        row.addView(shutter, LinearLayout.LayoutParams(dp(90), dp(90)))
        row.addView(menuBtn, LinearLayout.LayoutParams(0, wrap, 1f).apply { })
        (galleryBtn.layoutParams as LinearLayout.LayoutParams).apply { marginEnd = dp(8) }
        (menuBtn.layoutParams as LinearLayout.LayoutParams).apply { marginStart = dp(8) }
        galleryBtn.gravity = Gravity.CENTER
        menuBtn.gravity = Gravity.CENTER

        bottom.addView(statusText, LinearLayout.LayoutParams(match, wrap))
        bottom.addView(progress, LinearLayout.LayoutParams(match, wrap))
        bottom.addView(seg, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(10); bottomMargin = dp(10) })
        bottom.addView(row, LinearLayout.LayoutParams(match, wrap))
        root.addView(bottom, FrameLayout.LayoutParams(match, wrap, Gravity.BOTTOM))
        setContentView(root)
    }

    private fun currentDevice(): Device = devices[deviceIndex]

    private fun currentMode(d: Device): VideoMode? = if (!d.hasVideo) null else d.modes[modeIndex.coerceIn(0, d.modes.size - 1)]

    private fun selectDevice(index: Int, mode: Int, save: Boolean = true) {
        deviceIndex = index
        val d = devices[index]
        modeIndex = mode.coerceIn(0, maxOf(0, d.modes.size - 1))
        if (!d.hasVideo) videoMode = false
        if (save) getSharedPreferences("eski_kamera", Context.MODE_PRIVATE).edit().putString("device", d.id).apply()
        refreshUi()
    }

    private fun setVideoMode(v: Boolean) {
        if (liveRecorder != null) return
        if (v && !currentDevice().hasVideo) {
            Toast.makeText(this, "Bu cihazda video yok", Toast.LENGTH_SHORT).show()
            return
        }
        videoMode = v
        refreshUi()
    }

    private fun refreshUi() {
        val d = currentDevice()
        val m = currentMode(d)
        deviceChip.text = "${d.name}  ▾"
        modeChip.visibility = if (videoMode && m != null) View.VISIBLE else View.GONE
        modeChip.text = (m?.label ?: "") + "  ▾"
        val vtxt = if (m != null) "Video ${m.width}x${m.height} ${m.fps}fps" else "video yok"
        specLine.text = "${d.era} · Foto ${d.photoWidth}x${d.photoHeight} · $vtxt" + if (d.note.isNotEmpty()) "\n${d.note}" else ""
        val on = 0xFFFFFFFF.toInt()
        val off = 0xFFAAAAAA.toInt()
        segPhoto.setTextColor(if (!videoMode) Color.BLACK else off)
        segPhoto.background = if (!videoMode) shape(on, 16) else null
        segVideo.setTextColor(if (videoMode) Color.BLACK else if (d.hasVideo) off else 0xFF666666.toInt())
        segVideo.background = if (videoMode) shape(on, 16) else null
        updateShutter()
        spec = buildSpec()
    }

    private fun updateShutter() {
        val recording = liveRecorder != null
        val lp = shutterInner.layoutParams as FrameLayout.LayoutParams
        if (recording) {
            lp.width = dp(34); lp.height = dp(34)
            shutterInner.background = shape(0xFFE53935.toInt(), 6)
        } else {
            lp.width = dp(62); lp.height = dp(62)
            shutterInner.background = shape(if (videoMode) 0xFFE53935.toInt() else Color.WHITE, 31)
        }
        shutterInner.layoutParams = lp
        val enable = !busy
        deviceChip.isEnabled = !recording && enable
        modeChip.isEnabled = !recording && enable
        galleryBtn.isEnabled = !recording && enable
        segPhoto.isEnabled = !recording
        segVideo.isEnabled = !recording
    }

    private fun buildSpec(): PreviewSpec? {
        if (devices.isEmpty()) return null
        val d = currentDevice()
        val m = currentMode(d)
        if (videoMode && m != null && d.videoLook != null) return PreviewSpec(m.width, m.height, d.videoLook, d.videoPrescale)
        val w = minOf(d.photoWidth, 640)
        var h = Math.round(w.toFloat() * d.photoHeight / d.photoWidth)
        if (h % 2 == 1) h++
        val pl = d.photoLook
        val look = pl.copy(blur = maxOf(0.3f, pl.blur * w / d.photoWidth), bayer = false, hotPixels = 0)
        return PreviewSpec(w, h, look, null)
    }

    private fun say(msg: String) {
        ErrorLog.add(msg)
        runOnUiThread {
            if (::statusText.isInitialized) statusText.text = ErrorLog.snapshot().takeLast(2).joinToString("\n")
        }
    }

    private fun setBusy(b: Boolean, indeterminate: Boolean = true) {
        busy = b
        runOnUiThread {
            progress.visibility = if (b) View.VISIBLE else View.INVISIBLE
            progress.isIndeterminate = indeterminate
            updateShutter()
        }
    }

    // ------------------------------------------------------------------ seçiciler ve menü
    private fun openDevicePicker() {
        if (busy || liveRecorder != null) return
        val dialogView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF1B1B1B.toInt()) }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(4)) }
        val chipScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(8)) }
        val listScroll = ScrollView(this).apply { addView(list) }
        dialogView.addView(chipScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialogView.addView(listScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(480)))
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        val filters = listOf("Hepsi" to null, "Telefon" to "telefon", "Dijital kamera" to "dijital kamera", "Kamkorder" to "kamkorder", "Oyun" to "oyun konsolu")
        var selectedFilter: String? = null
        lateinit var rebuild: () -> Unit
        fun rebuildChips() {
            chips.removeAllViews()
            for ((label, key) in filters) {
                val active = key == selectedFilter
                val c = TextView(this).apply {
                    text = label; textSize = 13f
                    setTextColor(if (active) Color.BLACK else Color.WHITE)
                    background = shape(if (active) Color.WHITE else 0xFF3A3A3A.toInt(), 16)
                    setPadding(dp(14), dp(7), dp(14), dp(7))
                    setOnClickListener { selectedFilter = key; rebuildChips(); rebuild() }
                }
                chips.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
            }
        }
        rebuild = {
            list.removeAllViews()
            val items = devices.withIndex().filter { selectedFilter == null || it.value.category == selectedFilter }
                .sortedBy { it.value.year }
            var lastEra = ""
            for ((idx, d) in items) {
                if (d.era != lastEra) {
                    lastEra = d.era
                    list.addView(TextView(this).apply {
                        text = d.era; textSize = 13f; setTextColor(0xFF90CAF9.toInt()); setTypeface(typeface, Typeface.BOLD)
                        setPadding(dp(6), dp(14), dp(6), dp(4))
                    })
                }
                val m = d.modes.getOrNull(d.defaultMode)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = shape(if (idx == deviceIndex) 0xFF1E3A5F.toInt() else 0xFF2A2A2A.toInt(), 12)
                    setOnClickListener { dialog.dismiss(); selectDevice(idx, d.defaultMode) }
                }
                row.addView(TextView(this).apply { text = d.name; textSize = 16f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
                row.addView(TextView(this).apply {
                    text = "${d.year} · ${d.category} · Foto ${d.photoWidth}x${d.photoHeight}" +
                        (if (m != null) " · Video ${m.width}x${m.height} ${m.fps}fps" else " · video yok")
                    textSize = 12f; setTextColor(0xFFBDBDBD.toInt())
                })
                if (d.note.isNotEmpty()) row.addView(TextView(this).apply { text = d.note; textSize = 12f; setTextColor(0xFFFFCC80.toInt()) })
                list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            }
        }
        rebuildChips()
        rebuild()
        dialog.show()
    }

    private fun openModePicker() {
        val d = currentDevice()
        if (!d.hasVideo || busy || liveRecorder != null) return
        AlertDialog.Builder(this).setTitle("Video kalitesi")
            .setItems(d.modes.map { it.label }.toTypedArray()) { _, which -> modeIndex = which; refreshUi() }.show()
    }

    private fun openGalleryMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Galeriden foto dönüştür")
        if (currentDevice().hasVideo) pm.menu.add(0, 2, 1, "Galeriden video dönüştür")
        pm.setOnMenuItemClickListener {
            if (!busy) if (it.itemId == 1) pickPhoto.launch("image/*") else pickVideo.launch("video/*")
            true
        }
        pm.show()
    }

    private fun openMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Hata raporunu kopyala")
        pm.menu.add(0, 2, 1, "Nasıl kullanılır?")
        pm.setOnMenuItemClickListener {
            if (it.itemId == 1) copyReport() else showHelp()
            true
        }
        pm.show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this).setTitle("Nasıl kullanılır?")
            .setMessage(
                "1. Üstteki ismine dokunup bir cihaz seç (dönemlere göre gruplu).\n" +
                    "2. FOTO veya VİDEO seç. Video için kaliteyi üstteki ikinci butondan değiştirebilirsin.\n" +
                    "3. Büyük düğmeye bas. Video, kayıt biterken hazır olur.\n" +
                    "4. Sonuçlar Galeri'de Pictures/EskiKamera ve Movies/EskiKamera klasörlerine gider.\n" +
                    "5. Galeri düğmesiyle eski foto ve videolarını da dönüştürebilirsin.\n\n" +
                    "Hata olursa ⋮ menüsünden 'Hata raporunu kopyala' ve bana yapıştır."
            ).setPositiveButton("Tamam", null).show()
    }

    private fun copyReport() {
        val text = ErrorLog.report(this, lastError)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Eski Kamera hata raporu", text))
        Toast.makeText(this, "Hata raporu kopyalandı. Claude'a yapıştırabilirsin.", Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ kamera ve canlı önizleme
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val ic = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                val sel = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
                val an = ImageAnalysis.Builder()
                    .setResolutionSelector(sel)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                an.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, an, ic)
                imageCapture = ic
                analysis = an
                say("Kamera hazır.")
            } catch (e: Throwable) {
                lastError = e
                say("Kamera açılamadı: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            lastRotation = proxy.imageInfo.rotationDegrees
            val now = System.nanoTime()
            val rec = liveRecorder
            val sp = spec ?: return
            val needPreview = rec == null && now - lastPreviewNano > 60_000_000L
            if (rec == null && !needPreview) return
            val frame = FrameUtil.copy(proxy)
            var out: IntArray? = null
            var ow = sp.w
            var oh = sp.h
            if (rec != null) {
                out = rec.onFrame(frame, now)
                // kayıt modunun boyutları spec ile aynıdır (video modu)
            } else {
                lastPreviewNano = now
                val rgb = FrameSampler.toRgb(frame, 0, sp.w, sp.h, sp.prescale)
                out = LookProcessor.processFrame(rgb, sp.w, sp.h, sp.look, now)
            }
            if (out != null) showFrame(out, ow, oh, lastRotation)
        } catch (t: Throwable) {
            lastError = t
            ErrorLog.add("Önizleme hatası: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            proxy.close()
        }
    }

    private fun showFrame(px: IntArray, w: Int, h: Int, rot: Int) {
        val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val shown = if (rot != 0) Bitmap.createBitmap(bmp, 0, 0, w, h, Matrix().apply { postRotate(rot.toFloat()) }, false) else bmp
        runOnUiThread {
            val dr = BitmapDrawable(resources, shown)
            dr.isFilterBitmap = shown.width >= 400
            previewImage.setImageDrawable(dr)
        }
    }

    // ------------------------------------------------------------------ çekim
    private fun onShutter() {
        if (devices.isEmpty()) return
        if (videoMode) toggleVideo() else takePhoto()
    }

    internal fun deviceCountForTest(): Int = devices.size

    internal fun takePhoto() {
        ErrorLog.add("takePhoto çağrıldı (busy=$busy, kamera=${imageCapture != null})")
        val ic = imageCapture
        if (ic == null) { say("Kamera hazır değil."); return }
        if (busy) return
        if (liveRecorder != null) { say("Kayıt sırasında foto çekilemez."); return }
        val file = File(cacheDir, "cekim_${System.currentTimeMillis()}.jpg")
        setBusy(true)
        say("Fotoğraf çekiliyor...")
        ic.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    ErrorLog.add("Foto dosyası kaydedildi: ${file.length()} bayt")
                    setBusy(false)
                    processPhoto(Uri.fromFile(file), file)
                }

                override fun onError(exception: ImageCaptureException) {
                    lastError = exception
                    setBusy(false)
                    say("Fotoğraf çekilemedi: ${exception.message}")
                }
            })
    }

    @SuppressLint("MissingPermission")
    internal fun toggleVideo() {
        val active = liveRecorder
        if (active != null) {
            stopRecording(active)
            return
        }
        if (busy) return
        if (analysis == null) { say("Kamera hazır değil."); return }
        val d = currentDevice()
        val m = currentMode(d)
        val look = d.videoLook
        if (m == null || look == null) { say("${d.name} cihazında video yok."); return }
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) say("Mikrofon izni yok, video sessiz kaydedilecek.")
        val rec = LiveRecorder(d, m, look, d.videoPrescale, lastRotation, cacheDir, "kayit_${System.nanoTime()}", hasMic) { ErrorLog.add(it) }
        try {
            rec.start()
        } catch (t: Throwable) {
            rec.abort()
            lastError = t
            say("Kayıt başlatılamadı: ${t.message}")
            return
        }
        videoMode = true
        liveRecorder = rec
        recordingStartMs = System.currentTimeMillis()
        timerText.visibility = View.VISIBLE
        tickTimer()
        refreshUi()
        say("Video kaydediliyor...")
    }

    private fun tickTimer() {
        if (liveRecorder == null) { timerText.visibility = View.GONE; return }
        val s = (System.currentTimeMillis() - recordingStartMs) / 1000
        timerText.text = "● %02d:%02d".format(s / 60, s % 60)
        ui.postDelayed({ tickTimer() }, 500)
    }

    private fun stopRecording(rec: LiveRecorder) {
        liveRecorder = null
        timerText.visibility = View.GONE
        setBusy(true)
        say("Kayıt bitiriliyor...")
        val device = currentDevice()
        worker.execute {
            try {
                val res = rec.stop()
                val mime = if (res.file.name.endsWith(".3gp")) "video/3gpp" else "video/mp4"
                val name = "${device.id}_${stamp()}${if (res.file.name.endsWith(".3gp")) ".3gp" else ".mp4"}"
                saveToGallery(res.file, name, mime, Environment.DIRECTORY_MOVIES)
                res.file.delete()
                say("Tamam: Movies/EskiKamera/$name\n${res.info}")
            } catch (t: Throwable) {
                rec.abort()
                lastError = t
                ErrorLog.add("HATA: ${t.javaClass.simpleName}: ${t.message}")
                say("Hata oldu: ${t.message}. Menüden 'Hata raporunu kopyala' ile bana gönder.")
            } finally {
                setBusy(false)
            }
        }
    }

    // ------------------------------------------------------------------ galeriden dönüştürme
    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun runWork(title: String, block: () -> Unit) {
        if (busy) return
        setBusy(true)
        say(title)
        worker.execute {
            try {
                block()
            } catch (t: Throwable) {
                lastError = t
                ErrorLog.add("HATA: ${t.javaClass.simpleName}: ${t.message}")
                say("Hata oldu: ${t.message}. Menüden 'Hata raporunu kopyala' ile bana gönder.")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun processPhoto(uri: Uri, temp: File?) {
        val device = currentDevice()
        runWork("Fotoğraf ${device.name} gibi işleniyor...") {
            val ext = device.photoFormat
            val out = File(cacheDir, "cikti_${System.nanoTime()}.$ext")
            try {
                PhotoProcessor.process(this, uri, device, System.nanoTime(), out) { ErrorLog.add(it) }
                val name = "${device.id}_${stamp()}.$ext"
                saveToGallery(out, name, if (ext == "png") "image/png" else "image/jpeg", Environment.DIRECTORY_PICTURES)
                say("Tamam: Pictures/EskiKamera/$name")
            } finally {
                out.delete()
                temp?.delete()
            }
        }
    }

    private fun processVideo(uri: Uri, temp: File?) {
        val device = currentDevice()
        val mode = currentMode(device)
        if (mode == null) {
            say("${device.name} cihazında video yok.")
            temp?.delete()
            return
        }
        runWork("Video ${device.name} / ${mode.label} gibi işleniyor... (uzun sürebilir)") {
            val outDir = File(cacheDir, "cikti_${System.nanoTime()}")
            outDir.mkdirs()
            try {
                runOnUiThread { progress.isIndeterminate = false; progress.progress = 0 }
                val res = VideoTranscoder.transcode(this, uri, device, mode, outDir, "${device.id}_${stamp()}", { ErrorLog.add(it) }) { p ->
                    runOnUiThread { progress.progress = (p * 100).toInt() }
                }
                val mime = if (res.file.name.endsWith(".3gp")) "video/3gpp" else "video/mp4"
                saveToGallery(res.file, res.file.name, mime, Environment.DIRECTORY_MOVIES)
                say("Tamam: Movies/EskiKamera/${res.file.name}\n${res.info}")
            } finally {
                outDir.deleteRecursively()
                temp?.delete()
            }
        }
    }

    private fun saveToGallery(file: File, name: String, mime: String, dir: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/EskiKamera")
        }
        val collection = if (mime.startsWith("video")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = contentResolver.insert(collection, values) ?: throw ProcessException("Galeriye kayıt oluşturulamadı")
        contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: throw ProcessException("Galeri dosyası yazılamadı")
        return uri
    }
}
