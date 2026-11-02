package app.eskikamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
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
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
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
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private class PreviewSpec(val w: Int, val h: Int, val look: Look, val prescale: IntArray?, val mode: VideoMode?)

    /** Çekim türleri. */
    enum class Cap { PHOTO, VIDEO, TIMELAPSE, SLOWMO, LOOP, MOTION, SONAR }

    private lateinit var devices: List<Device>
    private lateinit var settings: Settings
    private lateinit var hub: SensorHub
    private lateinit var sonar: SonarEngine
    private var deviceIndex = 0
    private var modeIndex = 0
    @Volatile private var cap = Cap.PHOTO

    private lateinit var previewImage: ImageView
    private lateinit var deviceChip: TextView
    private lateinit var modeChip: TextView
    private lateinit var specLine: TextView
    private lateinit var timerText: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var segPhoto: TextView
    private lateinit var segVideo: TextView
    private lateinit var segExtra: TextView
    private lateinit var shutterInner: View
    private lateinit var thumbView: ImageView
    private lateinit var menuBtn: TextView
    private lateinit var flipBtn: TextView
    private lateinit var torchBtn: TextView
    private lateinit var lensBtn: TextView
    private lateinit var zoomText: TextView
    private lateinit var radar: RadarView
    private lateinit var focusRing: View

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var analysis: ImageAnalysis? = null
    private var frontFacing = false
    private var backIndex = 0
    private var backCount = 1
    private var torchOn = false

    @Volatile private var liveRecorder: LiveRecorder? = null
    @Volatile private var spec: PreviewSpec? = null
    @Volatile private var lastRotation = 90
    @Volatile private var rawSample: Bitmap? = null
    @Volatile private var shownW = 0
    @Volatile private var shownH = 0
    private var lastPreviewNano = 0L
    private var lastSampleNano = 0L
    private var recordingStartMs = 0L
    private var segmentStartMs = 0L
    private var recW = 0
    private var recH = 0
    private var recKind = Cap.VIDEO
    private var csvBuf: StringBuilder? = null
    private var lastCsvSec = -1L
    private val loopSaved = ArrayList<Uri>()
    private var sceneBmp: Bitmap? = null

    // hareket ve sonar nöbeti
    @Volatile private var armed = false
    private var triggerRecording = false
    private val motionDetector = MotionDetector()
    @Volatile private var trigger = MotionTrigger(5000L)

    private var batteryCache = -1
    private var batteryAt = 0L

    private var busy = false
    private var lastError: Throwable? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val thumbExec = Executors.newSingleThreadExecutor()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    internal var loopSegmentMsOverride = 0L

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.CAMERA] == true) startCamera()
        else say("Kamera izni verilmedi. Menüden galeriden dönüştürme yine de çalışır.")
    }
    private val micLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        say(if (ok) "Mikrofon izni verildi. Düğmeye tekrar bas." else "Mikrofon izni verilmedi: sonar ve sesli kayıt çalışmaz.")
    }
    private val locationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) say("Konum izni verilmedi: GPS bilgisi gösterilemez.")
        applySensors()
    }
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) processPhoto(uri, null, false)
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
        settings = Settings(this)
        hub = SensorHub(this)
        sonar = SonarEngine(this)
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
        val savedId = settings.deviceId
        deviceIndex = devices.indexOfFirst { it.id == savedId }.coerceAtLeast(0)
        selectDevice(deviceIndex, devices[deviceIndex].defaultMode, save = false)
        if (ErrorLog.loadCrash(this) != null) say("Önceki çalışmada çökme olmuş. Menüden 'Hata raporunu kopyala' ile gönderebilirsin.")
        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startCamera()
        else permLauncher.launch(needed)
        loadLastThumb()
    }

    override fun onResume() {
        super.onResume()
        if (devices.isNotEmpty()) applySensors()
    }

    override fun onStop() {
        super.onStop()
        if (devices.isEmpty()) return
        disarm()
        liveRecorder?.let { stopRecordingUser() }
        hub.stop()
        if (torchOn) { torchOn = false; try { camera?.cameraControl?.enableTorch(false) } catch (_: Exception) { } ; updateControls() }
    }

    override fun onDestroy() {
        sonar.stop()
        liveRecorder?.abort()
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

    private fun sideBtn(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(6), dp(8), dp(6), dp(8))
        background = shape(0xCC202020.toInt(), 14)
        setOnClickListener { onClick() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        previewImage = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(previewImage, FrameLayout.LayoutParams(match, match))
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val z = cam.cameraInfo.zoomState.value ?: return false
                val nr = (z.zoomRatio * d.scaleFactor).coerceIn(z.minZoomRatio, z.maxZoomRatio)
                cam.cameraControl.setZoomRatio(nr)
                zoomText.text = "%.1fx".format(nr)
                zoomText.visibility = View.VISIBLE
                ui.removeCallbacks(hideZoom)
                ui.postDelayed(hideZoom, 1500)
                return true
            }
        })
        val tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { focusAt(e.x, e.y); return true }
        })
        previewImage.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            if (!scaleDetector.isInProgress) tapDetector.onTouchEvent(ev)
            true
        }

        focusRing = View(this).apply { background = shape(Color.TRANSPARENT, 28, 0xFFFFEB3B.toInt(), 2); visibility = View.GONE }
        root.addView(focusRing, FrameLayout.LayoutParams(dp(56), dp(56)))

        // üst: cihaz ve mod seçimi
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(14), dp(12), dp(6))
        }
        deviceChip = chip("", 17f).apply {
            setTypeface(typeface, Typeface.BOLD)
            compoundDrawablePadding = dp(8)
            setOnClickListener { openDevicePicker() }
        }
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

        // sağ: kamera kontrolleri
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        flipBtn = sideBtn("Ön/Arka") { flipCamera() }
        torchBtn = sideBtn("Fener") { toggleTorch() }
        lensBtn = sideBtn("Lens 1") { nextLens() }
        zoomText = TextView(this).apply {
            textSize = 14f; setTextColor(Color.WHITE); visibility = View.GONE; gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4)); background = shape(0xCC202020.toInt(), 12)
        }
        val sw = dp(64)
        side.addView(flipBtn, LinearLayout.LayoutParams(sw, wrap).apply { bottomMargin = dp(8) })
        side.addView(torchBtn, LinearLayout.LayoutParams(sw, wrap).apply { bottomMargin = dp(8) })
        side.addView(lensBtn, LinearLayout.LayoutParams(sw, wrap).apply { bottomMargin = dp(8) })
        side.addView(zoomText, LinearLayout.LayoutParams(sw, wrap))
        root.addView(side, FrameLayout.LayoutParams(wrap, wrap, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(8) })

        // alt: durum, radar, seçici, deklanşör
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
        radar = RadarView(this).apply { visibility = View.GONE }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.INVISIBLE }
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(0x99000000.toInt(), 20)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        segPhoto = TextView(this).apply { text = "FOTO"; textSize = 14f; gravity = Gravity.CENTER; setPadding(dp(18), dp(8), dp(18), dp(8)); setOnClickListener { setCap(Cap.PHOTO) } }
        segVideo = TextView(this).apply { text = "VİDEO"; textSize = 14f; gravity = Gravity.CENTER; setPadding(dp(18), dp(8), dp(18), dp(8)); setOnClickListener { setCap(Cap.VIDEO) } }
        segExtra = TextView(this).apply { text = "EKSTRA ▾"; textSize = 14f; gravity = Gravity.CENTER; setPadding(dp(18), dp(8), dp(18), dp(8)); setOnClickListener { openExtraPicker() } }
        seg.addView(segPhoto)
        seg.addView(segVideo)
        seg.addView(segExtra)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        thumbView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = shape(0xFF303030.toInt(), 12, Color.WHITE, 2)
            clipToOutline = true
            setOnClickListener { if (!busy) ViewerActivity.open(this@MainActivity, 0, false) }
            setOnLongClickListener { if (!busy) ViewerActivity.open(this@MainActivity, 0, true); true }
        }
        val shutter = FrameLayout(this)
        val ring = View(this).apply { background = shape(Color.TRANSPARENT, 40, Color.WHITE, 4) }
        shutterInner = View(this)
        shutter.addView(ring, FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER))
        shutter.addView(shutterInner, FrameLayout.LayoutParams(dp(62), dp(62), Gravity.CENTER))
        shutter.setOnClickListener { onShutter() }
        menuBtn = chip("⋮", 20f).apply { gravity = Gravity.CENTER; setOnClickListener { openMenu(it) } }
        val thumbHolder = FrameLayout(this)
        thumbHolder.addView(thumbView, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        row.addView(thumbHolder, LinearLayout.LayoutParams(0, dp(64), 1f))
        row.addView(shutter, LinearLayout.LayoutParams(dp(90), dp(90)))
        row.addView(menuBtn, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = dp(8) })

        bottom.addView(statusText, LinearLayout.LayoutParams(match, wrap))
        bottom.addView(radar, LinearLayout.LayoutParams(match, dp(140)))
        bottom.addView(progress, LinearLayout.LayoutParams(match, wrap))
        bottom.addView(seg, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(10); bottomMargin = dp(10) })
        bottom.addView(row, LinearLayout.LayoutParams(match, wrap))
        root.addView(bottom, FrameLayout.LayoutParams(match, wrap, Gravity.BOTTOM))
        setContentView(root)
    }

    private val hideZoom = Runnable { if (::zoomText.isInitialized) zoomText.visibility = View.GONE }

    private fun currentDevice(): Device = devices[deviceIndex]

    private fun currentMode(d: Device): VideoMode? = if (!d.hasVideo) null else d.modes[modeIndex.coerceIn(0, d.modes.size - 1)]

    private fun capNeedsVideo(c: Cap) = c == Cap.VIDEO || c == Cap.TIMELAPSE || c == Cap.SLOWMO || c == Cap.LOOP

    /** Önizleme ve (tetiklenen) kayıt video mu yoksa foto görünümünde mi? */
    private fun previewIsVideo(): Boolean {
        val d = currentDevice()
        if (!d.hasVideo) return false
        return when (cap) {
            Cap.PHOTO -> false
            Cap.MOTION -> settings.motionAction == 1
            Cap.SONAR -> settings.sonarAction == 1
            else -> true
        }
    }

    private fun selectDevice(index: Int, mode: Int, save: Boolean = true) {
        deviceIndex = index
        val d = devices[index]
        modeIndex = mode.coerceIn(0, maxOf(0, d.modes.size - 1))
        if (!d.hasVideo && capNeedsVideo(cap)) cap = Cap.PHOTO
        if (save) settings.deviceId = d.id
        refreshUi()
    }

    private fun setCap(c: Cap) {
        if (liveRecorder != null || armed) { Toast.makeText(this, "Önce kaydı veya nöbeti durdur", Toast.LENGTH_SHORT).show(); return }
        if (capNeedsVideo(c) && !currentDevice().hasVideo) {
            Toast.makeText(this, "Bu cihazda video yok", Toast.LENGTH_SHORT).show()
            return
        }
        cap = c
        refreshUi()
    }

    private fun refreshUi() {
        val d = currentDevice()
        val m = currentMode(d)
        deviceChip.text = "${d.name}  ▾"
        val ic = IconDrawable.forDevice(d)
        ic.setBounds(0, 0, dp(28), dp(28))
        deviceChip.setCompoundDrawables(ic, null, null, null)
        val videoish = previewIsVideo() && m != null
        modeChip.visibility = if (videoish) View.VISIBLE else View.GONE
        modeChip.text = (m?.label ?: "") + "  ▾"
        val vtxt = if (m != null) "Video ${m.width}x${m.height} ${m.fps}fps" else "video yok"
        specLine.text = "${d.era} · Foto ${d.photoWidth}x${d.photoHeight} · $vtxt" + if (d.note.isNotEmpty()) "\n${d.note}" else ""
        val on = 0xFFFFFFFF.toInt()
        val off = 0xFFAAAAAA.toInt()
        val extra = cap != Cap.PHOTO && cap != Cap.VIDEO
        segPhoto.setTextColor(if (cap == Cap.PHOTO) Color.BLACK else off)
        segPhoto.background = if (cap == Cap.PHOTO) shape(on, 16) else null
        segVideo.setTextColor(if (cap == Cap.VIDEO) Color.BLACK else if (d.hasVideo) off else 0xFF666666.toInt())
        segVideo.background = if (cap == Cap.VIDEO) shape(on, 16) else null
        segExtra.setTextColor(if (extra) Color.BLACK else off)
        segExtra.background = if (extra) shape(on, 16) else null
        segExtra.text = if (extra) helpMode(cap)!!.label + " ▾" else "EKSTRA ▾"
        radar.visibility = if (cap == Cap.SONAR) View.VISIBLE else View.GONE
        if (cap == Cap.SONAR) { radar.minCm = settings.sonarMinCm; radar.maxCm = settings.sonarMaxCm }
        updateShutter()
        updateControls()
        spec = buildSpec()
    }

    private fun helpMode(c: Cap): Help.Mode? = when (c) {
        Cap.TIMELAPSE -> Help.Mode.TIMELAPSE
        Cap.SLOWMO -> Help.Mode.SLOWMO
        Cap.LOOP -> Help.Mode.LOOP
        Cap.MOTION -> Help.Mode.MOTION
        Cap.SONAR -> Help.Mode.SONAR
        else -> null
    }

    private fun updateShutter() {
        val active = liveRecorder != null || armed
        val lp = shutterInner.layoutParams as FrameLayout.LayoutParams
        if (active) {
            lp.width = dp(34); lp.height = dp(34)
            shutterInner.background = shape(0xFFE53935.toInt(), 6)
        } else {
            lp.width = dp(62); lp.height = dp(62)
            shutterInner.background = shape(if (cap == Cap.PHOTO) Color.WHITE else 0xFFE53935.toInt(), 31)
        }
        shutterInner.layoutParams = lp
        val free = !active && !busy
        deviceChip.isEnabled = free
        modeChip.isEnabled = free
        thumbView.isEnabled = !busy
        segPhoto.isEnabled = !active
        segVideo.isEnabled = !active
        segExtra.isEnabled = !active
    }

    private fun updateControls() {
        val rec = liveRecorder != null
        flipBtn.text = if (frontFacing) "Arka\nkamera" else "Ön\nkamera"
        flipBtn.isEnabled = !rec
        torchBtn.text = if (torchOn) "Fener\nAÇIK" else "Fener"
        torchBtn.background = shape(if (torchOn) 0xFFF9A825.toInt() else 0xCC202020.toInt(), 14)
        lensBtn.visibility = if (!frontFacing && backCount > 1) View.VISIBLE else View.GONE
        lensBtn.text = "Lens ${backIndex + 1}/$backCount"
        lensBtn.isEnabled = !rec
    }

    private fun buildSpec(): PreviewSpec? {
        if (devices.isEmpty()) return null
        val d = currentDevice()
        val m = currentMode(d)
        if (previewIsVideo() && m != null && d.videoLook != null) return PreviewSpec(m.width, m.height, d.videoLook, d.videoPrescale, m)
        val w = minOf(d.photoWidth, 640)
        var h = Math.round(w.toFloat() * d.photoHeight / d.photoWidth)
        if (h % 2 == 1) h++
        val pl = d.photoLook
        val look = pl.copy(blur = maxOf(0.3f, pl.blur * w / d.photoWidth), bayer = false, hotPixels = 0)
        return PreviewSpec(w, h, look, null, null)
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
    private fun sceneBitmap(): Bitmap {
        rawSample?.let { return it }
        sceneBmp?.let { return it }
        val b = assets.open("sample_scene.jpg").use { BitmapFactory.decodeStream(it) }
        sceneBmp = b
        return b
    }

    private fun thumbBox(): IntArray = intArrayOf(dp(84), dp(63))

    private fun loadThumbs(jobs: List<Pair<ImageView, () -> Bitmap?>>) {
        thumbExec.execute {
            for ((iv, f) in jobs) {
                try {
                    val b = f() ?: continue
                    runOnUiThread { iv.setImageBitmap(b) }
                } catch (t: Throwable) {
                    ErrorLog.add("Önizleme küçük resmi hatası: ${t.javaClass.simpleName}")
                }
            }
        }
    }

    private fun pickerRow(icon: Drawable?, title: String, sub: String, note: String, thumb: ImageView?, selected: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = shape(if (selected) 0xFF1E3A5F.toInt() else 0xFF2A2A2A.toInt(), 12)
            setOnClickListener { onClick() }
        }
        if (icon != null) {
            val iv = ImageView(this).apply { setImageDrawable(icon) }
            row.addView(iv, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) })
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = title; textSize = 15f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        if (sub.isNotEmpty()) col.addView(TextView(this).apply { text = sub; textSize = 12f; setTextColor(0xFFBDBDBD.toInt()) })
        if (note.isNotEmpty()) col.addView(TextView(this).apply { text = note; textSize = 12f; setTextColor(0xFFFFCC80.toInt()) })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (thumb != null) {
            val box = thumbBox()
            thumb.scaleType = ImageView.ScaleType.FIT_CENTER
            thumb.setBackgroundColor(0xFF000000.toInt())
            row.addView(thumb, LinearLayout.LayoutParams(box[0], box[1]).apply { marginStart = dp(8) })
        }
        return row
    }

    private fun openDevicePicker() {
        if (busy || liveRecorder != null || armed) return
        val dialogView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF1B1B1B.toInt()) }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(4)) }
        val chipScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(8)) }
        val listScroll = ScrollView(this).apply { addView(list) }
        dialogView.addView(chipScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialogView.addView(listScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(500)))
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        val filters = listOf("Hepsi" to null, "Telefon" to "telefon", "Dijital kamera" to "dijital kamera", "Kamkorder" to "kamkorder", "Oyun" to "oyun konsolu", "Diğer" to "diğer")
        val known = filters.mapNotNull { it.second }.toSet()
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
            val videoThumbs = previewIsVideo()
            val items = devices.withIndex().filter {
                val sf = selectedFilter
                sf == null || (sf == "diğer" && it.value.category !in known) || it.value.category == sf
            }.sortedBy { it.value.year }
            var lastEra = ""
            val jobs = ArrayList<Pair<ImageView, () -> Bitmap?>>()
            for ((idx, d) in items) {
                if (d.era != lastEra) {
                    lastEra = d.era
                    list.addView(TextView(this).apply {
                        text = d.era; textSize = 13f; setTextColor(0xFF90CAF9.toInt()); setTypeface(typeface, Typeface.BOLD)
                        setPadding(dp(6), dp(14), dp(6), dp(4))
                    })
                }
                val m = d.modes.getOrNull(d.defaultMode)
                val tv = ImageView(this)
                val box = thumbBox()
                jobs.add(tv to {
                    val src = sceneBitmap()
                    if (videoThumbs && m != null) PreviewThumbs.videoThumb(src, d, m, box[0], box[1]) ?: PreviewThumbs.photoThumb(src, d, box[0], box[1])
                    else PreviewThumbs.photoThumb(src, d, box[0], box[1])
                })
                val sub = "${d.year} · ${d.category} · Foto ${d.photoWidth}x${d.photoHeight}" +
                    (if (m != null) " · Video ${m.width}x${m.height} ${m.fps}fps" else " · video yok")
                val row = pickerRow(IconDrawable.forDevice(d), d.name, sub, d.note, tv, idx == deviceIndex) {
                    dialog.dismiss(); selectDevice(idx, d.defaultMode)
                }
                list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            }
            loadThumbs(jobs)
        }
        rebuildChips()
        rebuild()
        dialog.show()
    }

    private fun openModePicker() {
        val d = currentDevice()
        if (!d.hasVideo || busy || liveRecorder != null || armed) return
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)); setBackgroundColor(0xFF1B1B1B.toInt()) }
        val dialog = AlertDialog.Builder(this).setTitle("Video kalitesi").setView(ScrollView(this).apply { addView(list) }).create()
        val jobs = ArrayList<Pair<ImageView, () -> Bitmap?>>()
        val box = thumbBox()
        for ((i, m) in d.modes.withIndex()) {
            val tv = ImageView(this)
            jobs.add(tv to { PreviewThumbs.videoThumb(sceneBitmap(), d, m, box[0], box[1]) })
            val row = pickerRow(null, m.label, "${m.width}x${m.height} · ${m.fps} fps · ${m.codec} · ${m.videoKbps} kbps", "", tv, i == modeIndex) {
                dialog.dismiss(); modeIndex = i; refreshUi()
            }
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        loadThumbs(jobs)
        dialog.show()
    }

    private fun openExtraPicker() {
        if (liveRecorder != null || armed || busy) return
        val modes = listOf(Cap.TIMELAPSE, Cap.SLOWMO, Cap.LOOP, Cap.MOTION, Cap.SONAR)
        val labels = modes.map { helpMode(it)!!.label }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Ekstra modlar").setItems(labels) { _, which ->
            val c = modes[which]
            val hm = helpMode(c)!!
            AlertDialog.Builder(this).setTitle(hm.title).setMessage(hm.text)
                .setPositiveButton("Bu modu seç") { _, _ -> setCap(c) }
                .setNegativeButton("Vazgeç", null).show()
        }.show()
    }

    private fun openMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Ayarlar")
        pm.menu.add(0, 2, 1, "Galeri (ızgara)")
        pm.menu.add(0, 3, 2, "Galeriden foto dönüştür")
        if (currentDevice().hasVideo) pm.menu.add(0, 4, 3, "Galeriden video dönüştür")
        pm.menu.add(0, 5, 4, "Hata raporunu kopyala")
        pm.menu.add(0, 6, 5, "Nasıl kullanılır?")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> openSettings()
                2 -> if (!busy) ViewerActivity.open(this, 0, true)
                3 -> if (!busy) pickPhoto.launch("image/*")
                4 -> if (!busy) pickVideo.launch("video/*")
                5 -> copyReport()
                else -> showHelp()
            }
            true
        }
        pm.show()
    }

    private fun openSettings() {
        SettingsDialog(this, settings, { key -> onSettingChanged(key) }, { runSonarTest() }).show()
    }

    private fun onSettingChanged(key: String) {
        if ((key == "gps" || key == "csv") && (settings.gps || settings.sensorCsv) && !hasLocationPermission()) {
            locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        applySensors()
        refreshUi()
    }

    private fun showHelp() {
        AlertDialog.Builder(this).setTitle("Nasıl kullanılır?")
            .setMessage(
                "1. Üstteki ismine dokunup bir cihaz seç. Listede her cihazın simgesi ve görüntü önizlemesi var.\n" +
                    "2. FOTO veya VİDEO seç. EKSTRA'dan zaman atlamalı, yavaş çekim, döngülü kayıt, hareketle çekim ve sonar (deneysel) modlarına bak; her birinin açıklaması orada.\n" +
                    "3. Büyük düğmeye bas. Video, kayıt biterken hazır olur.\n" +
                    "4. Sol alttaki küçük resme dokununca son çekimini görürsün (uzun basınca ızgara). Orada yakınlaştırabilir, oynatabilir, paylaşabilir, silebilirsin.\n" +
                    "5. Ekrana dokunarak odaklanır, iki parmakla yakınlaştırırsın. Sağdaki düğmelerle ön/arka kamera, fener ve lens değişir.\n" +
                    "6. Menüdeki Ayarlar'dan tarih damgası, kamkorder ekranı, GPS, pusula ve diğer her şey açılır; her ayarın altında ne işe yaradığı yazar.\n\n" +
                    "Hata olursa ⋮ menüsünden 'Hata raporunu kopyala' ve bana yapıştır."
            ).setPositiveButton("Tamam", null).show()
    }

    private fun copyReport() {
        val text = ErrorLog.report(this, lastError)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Eski Kamera hata raporu", text))
        Toast.makeText(this, "Hata raporu kopyalandı. Claude'a yapıştırabilirsin.", Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ sensörler
    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun applySensors() {
        val wantO = settings.compass || settings.sensorCsv
        val wantL = (settings.gps || settings.sensorCsv) && hasLocationPermission()
        if (!wantO && !wantL) { hub.stop(); return }
        val note = hub.start(wantO, wantL)
        if (note.isNotEmpty()) say(note)
    }

    private fun overlayConfig(d: Device, forVideo: Boolean): OverlayConfig {
        val osd = forVideo && when (settings.osdMode) { 1 -> true; 2 -> false; else -> d.category == "kamkorder" }
        return OverlayConfig(dateStamp = settings.dateStamp, osd = osd, gps = settings.gps, compass = settings.compass, mic = forVideo && settings.micMeter)
    }

    private fun overlayData(sec: Long, recording: Boolean, mic: Float): OverlayData {
        val nowMs = System.currentTimeMillis()
        if (nowMs - batteryAt > 15_000L) { batteryCache = hub.battery(); batteryAt = nowMs }
        return OverlayData(
            timeMs = nowMs, recording = recording, recSeconds = sec, battery = batteryCache,
            speedKmh = hub.speedKmh, altitude = hub.altitude, lat = hub.lat, lon = hub.lon,
            heading = hub.heading, rollDeg = hub.rollDeg, micLevel = mic
        )
    }

    // ------------------------------------------------------------------ kamera ve canlı önizleme
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindCamera()
            } catch (e: Throwable) {
                lastError = e
                say("Kamera açılamadı: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun buildSelector(provider: ProcessCameraProvider): CameraSelector {
        if (frontFacing) return CameraSelector.DEFAULT_FRONT_CAMERA
        if (backIndex == 0 || backCount <= 1) return CameraSelector.DEFAULT_BACK_CAMERA
        val idx = backIndex
        return CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .addCameraFilter { infos -> listOf(infos[idx.coerceAtMost(infos.size - 1)]) }.build()
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        try {
            backCount = maxOf(1, CameraSelector.DEFAULT_BACK_CAMERA.filter(provider.availableCameraInfos).size)
            if (backIndex >= backCount) backIndex = 0
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
            camera = provider.bindToLifecycle(this, buildSelector(provider), an, ic)
            imageCapture = ic
            analysis = an
            torchOn = false
            updateControls()
            say("Kamera hazır.")
        } catch (e: Throwable) {
            lastError = e
            if (!frontFacing && backIndex != 0) {
                backIndex = 0
                say("Bu lens açılamadı, ana kameraya dönüldü.")
                bindCamera()
            } else if (frontFacing) {
                frontFacing = false
                say("Ön kamera açılamadı: ${e.message}. Arka kameraya dönüldü.")
                bindCamera()
            } else {
                say("Kamera açılamadı: ${e.message}")
            }
        }
    }

    private fun flipCamera() {
        if (liveRecorder != null || cameraProvider == null) return
        val hasFront = try { cameraProvider!!.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) } catch (_: Exception) { false }
        if (!frontFacing && !hasFront) { Toast.makeText(this, "Bu telefonda ön kamera bulunamadı", Toast.LENGTH_SHORT).show(); return }
        frontFacing = !frontFacing
        bindCamera()
    }

    private fun nextLens() {
        if (liveRecorder != null || frontFacing || backCount <= 1) return
        backIndex = (backIndex + 1) % backCount
        bindCamera()
    }

    private fun toggleTorch() {
        val cam = camera ?: run { Toast.makeText(this, "Kamera hazır değil", Toast.LENGTH_SHORT).show(); return }
        if (!cam.cameraInfo.hasFlashUnit()) { Toast.makeText(this, "Bu kamerada fener yok", Toast.LENGTH_SHORT).show(); return }
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        updateControls()
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val bw = shownW
        val bh = shownH
        if (bw <= 0 || bh <= 0) return
        val vw = previewImage.width.toFloat()
        val vh = previewImage.height.toFloat()
        val sc = minOf(vw / bw, vh / bh)
        val dw = bw * sc
        val dh = bh * sc
        val u = (x - (vw - dw) / 2f) / dw
        val v = (y - (vh - dh) / 2f) / dh
        if (u < 0f || u > 1f || v < 0f || v > 1f) return
        val sx: Float
        val sy: Float
        when (lastRotation) {
            90 -> { sx = v; sy = 1f - u }
            270 -> { sx = 1f - v; sy = u }
            180 -> { sx = 1f - u; sy = 1f - v }
            else -> { sx = u; sy = v }
        }
        try {
            val pt = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(sx, sy)
            val action = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
            cam.cameraControl.startFocusAndMetering(action)
            val lp = focusRing.layoutParams as FrameLayout.LayoutParams
            lp.leftMargin = (x - lp.width / 2f).toInt()
            lp.topMargin = (y - lp.height / 2f).toInt()
            focusRing.layoutParams = lp
            focusRing.visibility = View.VISIBLE
            ui.removeCallbacks(hideFocus)
            ui.postDelayed(hideFocus, 900)
        } catch (e: Exception) {
            ErrorLog.add("Odaklama hatası: ${e.message}")
        }
    }

    private val hideFocus = Runnable { if (::focusRing.isInitialized) focusRing.visibility = View.GONE }

    private fun analyze(proxy: ImageProxy) {
        try {
            lastRotation = proxy.imageInfo.rotationDegrees
            val now = System.nanoTime()
            val rec = liveRecorder
            val sp = spec
            val motionArmed = armed && cap == Cap.MOTION
            val needPreview = now - lastPreviewNano > 60_000_000L
            val needSample = now - lastSampleNano > 1_500_000_000L
            if (rec == null && !needPreview && !motionArmed && !needSample) return
            val frame = FrameUtil.copy(proxy)
            if (motionArmed) feedMotion(frame)
            if (needSample) {
                lastSampleNano = now
                val px = FrameSampler.toRgb(frame, 0, 320, 240, null)
                rawSample = Bitmap.createBitmap(px, 320, 240, Bitmap.Config.ARGB_8888)
            }
            if (sp == null) return
            var out: IntArray? = null
            var ow = sp.w
            var oh = sp.h
            if (rec != null) {
                out = rec.onFrame(frame, now)
                ow = recW
                oh = recH
            } else if (needPreview) {
                lastPreviewNano = now
                if (sp.mode != null) {
                    out = FramePipeline.process(frame, sp.mode, sp.look, sp.prescale, now)
                } else {
                    val rgb = FrameSampler.toRgb(frame, 0, sp.w, sp.h, sp.prescale)
                    out = LookProcessor.processFrame(rgb, sp.w, sp.h, sp.look, now)
                }
                val cfg = overlayConfig(currentDevice(), sp.mode != null)
                if (cfg.any) Overlay.drawOn(out, ow, oh, cfg, overlayData(0, false, 0f))
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
        shownW = shown.width
        shownH = shown.height
        runOnUiThread {
            val dr = BitmapDrawable(resources, shown)
            dr.isFilterBitmap = shown.width >= 400
            previewImage.setImageDrawable(dr)
        }
    }

    // ------------------------------------------------------------------ nöbet: hareket ve sonar
    private fun feedMotion(frame: YuvFrame) {
        val score = motionDetector.update(FrameSampler.lumaThumb(frame, 64, 48))
        val ev = trigger.feed(score, MotionDetector.threshold(settings.motionSensitivity), System.currentTimeMillis())
        if (ev != MotionTrigger.Event.NONE) {
            val video = settings.motionAction == 1
            ui.post { onTrigger(ev == MotionTrigger.Event.START, video, false) }
        }
    }

    private fun toggleArm() {
        if (armed) { disarm(); say("Nöbet durduruldu."); return }
        if (busy) return
        if (cap == Cap.MOTION) {
            if (analysis == null) { say("Kamera hazır değil."); return }
            motionDetector.reset()
            trigger = MotionTrigger(settings.motionQuietSec * 1000L)
            armed = true
            say("Hareket nöbetinde: kamera hareket görünce ${if (settings.motionAction == 1) "video kaydedecek" else "fotoğraf çekecek"}. Telefonu sabit tut.")
        } else if (cap == Cap.SONAR) {
            if (!hasMicPermission()) {
                say("Sonar için mikrofon izni gerekli. İzin ver, sonra düğmeye tekrar bas.")
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
            if (analysis == null) { say("Kamera hazır değil."); return }
            trigger = MotionTrigger(settings.motionQuietSec * 1000L)
            val video = settings.sonarAction == 1
            val err = sonar.start(settings.sonarMinCm, settings.sonarMaxCm, settings.sonarSensitivity) { r ->
                radar.report(r)
                val ev = trigger.feed(if (r.motion) 1f else 0f, 0.5f, System.currentTimeMillis())
                if (ev != MotionTrigger.Event.NONE) ui.post { onTrigger(ev == MotionTrigger.Event.START, video, true) }
            }
            if (err != null) { say(err); return }
            armed = true
            say(sonar.volumeWarning() ?: "Sonar çalışıyor (deneysel): ${settings.sonarMinCm}-${settings.sonarMaxCm} cm arasındaki hareketi izliyor.")
        }
        updateShutter()
    }

    private fun disarm() {
        if (!armed) return
        armed = false
        sonar.stop()
        if (triggerRecording && liveRecorder != null) stopRecordingUser()
        triggerRecording = false
        runOnUiThread { updateShutter() }
    }

    private fun onTrigger(start: Boolean, video: Boolean, silent: Boolean) {
        if (!armed) return
        if (start) {
            if (video && currentDevice().hasVideo) {
                if (liveRecorder == null && !busy && startRecording(Cap.VIDEO, silent)) triggerRecording = true
            } else takePhoto(auto = true)
        } else if (triggerRecording && liveRecorder != null) {
            triggerRecording = false
            stopRecordingUser()
        }
    }

    private fun runSonarTest() {
        if (armed || liveRecorder != null || busy) { say("Önce kaydı veya nöbeti durdur."); return }
        if (!hasMicPermission()) { micLauncher.launch(Manifest.permission.RECORD_AUDIO); return }
        say("Sonar ses testi yapılıyor (yaklaşık 3 saniye)...")
        worker.execute {
            val res = try { sonar.selfTest() } catch (t: Throwable) { "Test hatası: ${t.message}" }
            say("Sonar testi: $res")
            runOnUiThread { AlertDialog.Builder(this).setTitle("Sonar ses testi").setMessage(res).setPositiveButton("Tamam", null).show() }
        }
    }

    // ------------------------------------------------------------------ çekim
    private fun onShutter() {
        if (devices.isEmpty()) return
        when (cap) {
            Cap.PHOTO -> takePhoto()
            Cap.MOTION, Cap.SONAR -> toggleArm()
            else -> toggleRecording()
        }
    }

    internal fun deviceCountForTest(): Int = devices.size
    internal fun shutterForTest() = onShutter()
    internal fun isRecordingForTest(): Boolean = liveRecorder != null
    internal fun selectDeviceForTest(id: String) {
        val i = devices.indexOfFirst { it.id == id }
        if (i >= 0) selectDevice(i, devices[i].defaultMode)
    }
    internal fun selectCapForTest(name: String) { setCap(Cap.valueOf(name)) }

    internal fun takePhoto(auto: Boolean = false) {
        ErrorLog.add("takePhoto çağrıldı (busy=$busy, kamera=${imageCapture != null})")
        val ic = imageCapture
        if (ic == null) { if (!auto) say("Kamera hazır değil."); return }
        if (busy) return
        if (liveRecorder != null) { if (!auto) say("Kayıt sırasında foto çekilemez."); return }
        val file = File(cacheDir, "cekim_${System.currentTimeMillis()}.jpg")
        setBusy(true)
        say(if (auto) "Tetiklendi: fotoğraf çekiliyor..." else "Fotoğraf çekiliyor...")
        ic.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    ErrorLog.add("Foto dosyası kaydedildi: ${file.length()} bayt")
                    setBusy(false)
                    processPhoto(Uri.fromFile(file), file, true)
                }

                override fun onError(exception: ImageCaptureException) {
                    lastError = exception
                    setBusy(false)
                    say("Fotoğraf çekilemedi: ${exception.message}")
                }
            })
    }

    /** Test için: video modunda değilse VİDEO'ya geçip kaydı başlatır ya da durdurur. */
    internal fun toggleVideo() {
        if (liveRecorder == null && !capNeedsVideo(cap)) cap = Cap.VIDEO
        toggleRecording()
    }

    private fun toggleRecording() {
        if (liveRecorder != null) { stopRecordingUser(); return }
        if (busy) return
        startRecording(cap, false)
    }

    private fun buildRecorder(kind: Cap, silent: Boolean): LiveRecorder? {
        val d = currentDevice()
        val m = currentMode(d)
        val look = d.videoLook
        if (m == null || look == null) { say("${d.name} cihazında video yok."); return null }
        val noAudio = silent || kind == Cap.TIMELAPSE || kind == Cap.SLOWMO
        val hasMic = !noAudio && hasMicPermission()
        if (!noAudio && !hasMic) say("Mikrofon izni yok, video sessiz kaydedilecek.")
        val opts = LiveOptions(
            overlay = overlayConfig(d, true),
            dataSource = { sec -> overlayData(sec, true, liveRecorder?.micLevel ?: 0f) },
            timelapseMs = if (kind == Cap.TIMELAPSE) settings.timelapseSec * 1000L else 0L,
            slowFactor = if (kind == Cap.SLOWMO) settings.slowFactor else 1
        )
        recW = m.width
        recH = m.height
        return LiveRecorder(d, m, look, d.videoPrescale, lastRotation, cacheDir, "kayit_${System.nanoTime()}", hasMic, opts) { ErrorLog.add(it) }
    }

    private fun newCsv(): StringBuilder? =
        if (settings.sensorCsv) StringBuilder("saniye,saat,hiz_kmh,rakim_m,enlem,boylam,yon_derece,egim_yan,egim_on\n") else null

    @SuppressLint("MissingPermission")
    private fun startRecording(kind: Cap, silent: Boolean): Boolean {
        if (liveRecorder != null) return false
        if (analysis == null) { say("Kamera hazır değil."); return false }
        val rec = buildRecorder(kind, silent) ?: return false
        try {
            rec.start()
        } catch (t: Throwable) {
            rec.abort()
            lastError = t
            say("Kayıt başlatılamadı: ${t.message}")
            return false
        }
        recKind = kind
        liveRecorder = rec
        loopSaved.clear()
        csvBuf = newCsv()
        lastCsvSec = -1L
        recordingStartMs = System.currentTimeMillis()
        segmentStartMs = recordingStartMs
        timerText.visibility = View.VISIBLE
        tick()
        refreshUi()
        say(when (kind) {
            Cap.TIMELAPSE -> "Zaman atlamalı kayıt başladı (${settings.timelapseSec} sn aralık)..."
            Cap.SLOWMO -> "Yavaş çekim kaydı başladı..."
            Cap.LOOP -> "Döngülü kayıt başladı: ${settings.loopMinutes} dk'lık parçalar, son ${settings.loopKeep} parça saklanır."
            else -> "Video kaydediliyor..."
        })
        return true
    }

    private fun tick() {
        val rec = liveRecorder
        if (rec == null) { timerText.visibility = View.GONE; return }
        val nowMs = System.currentTimeMillis()
        val s = (nowMs - recordingStartMs) / 1000
        timerText.text = "● %02d:%02d".format(s / 60, s % 60)
        val csv = csvBuf
        if (csv != null && s != lastCsvSec) {
            lastCsvSec = s
            csv.append("$s,${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(nowMs))},${num(hub.speedKmh.toDouble())},${num(hub.altitude)},${num(hub.lat, 6)},${num(hub.lon, 6)},${num(hub.heading.toDouble())},${num(hub.rollDeg.toDouble())},${num(hub.pitchDeg.toDouble())}\n")
        }
        if (recKind == Cap.LOOP) {
            val seg = if (loopSegmentMsOverride > 0) loopSegmentMsOverride else settings.loopMinutes * 60_000L
            if (nowMs - segmentStartMs >= seg) rotateLoop()
        }
        ui.postDelayed({ tick() }, 500)
    }

    private fun num(v: Double, digits: Int = 1): String = if (v.isNaN()) "" else "%.${digits}f".format(Locale.US, v)

    private fun rotateLoop() {
        val old = liveRecorder ?: return
        val device = currentDevice()
        val next = buildRecorder(Cap.LOOP, false) ?: return
        try {
            next.start()
        } catch (t: Throwable) {
            next.abort()
            lastError = t
            say("Yeni parça başlatılamadı: ${t.message}")
            return
        }
        val csvOld = csvBuf
        csvBuf = newCsv()
        liveRecorder = next
        segmentStartMs = System.currentTimeMillis()
        worker.execute { finishRecording(old, device, csvOld, true) }
    }

    /** Kullanıcı (ya da tetik) kaydı bitirir. */
    private fun stopRecordingUser() {
        val rec = liveRecorder ?: return
        liveRecorder = null
        timerText.visibility = View.GONE
        val csv = csvBuf
        csvBuf = null
        val loop = recKind == Cap.LOOP
        setBusy(true)
        say("Kayıt bitiriliyor...")
        val device = currentDevice()
        refreshUi()
        worker.execute {
            try {
                finishRecording(rec, device, csv, loop)
            } finally {
                setBusy(false)
            }
        }
    }

    /** İş parçacığında: kaydı kapatır, galeriye koyar. */
    private fun finishRecording(rec: LiveRecorder, device: Device, csv: StringBuilder?, loop: Boolean) {
        try {
            val res = rec.stop()
            val ext = if (res.file.name.endsWith(".3gp")) ".3gp" else ".mp4"
            val mime = if (ext == ".3gp") "video/3gpp" else "video/mp4"
            val base = "${device.id}_${stamp()}"
            val name = base + ext
            val uri = Gallery.save(this, res.file, name, mime, Gallery.DIR_VIDEO)
            res.file.delete()
            if (csv != null && csv.length > 100) {
                try {
                    val f = File(cacheDir, "$base.csv")
                    f.writeText(csv.toString())
                    Gallery.save(this, f, "$base.csv", "text/csv", "Documents/EskiKamera/")
                    f.delete()
                } catch (e: Exception) {
                    ErrorLog.add("CSV yazılamadı: ${e.message}")
                }
            }
            if (loop) {
                loopSaved.add(uri)
                while (loopSaved.size > settings.loopKeep) {
                    val old = loopSaved.removeAt(0)
                    if (Gallery.delete(this, old)) ErrorLog.add("Döngülü kayıt: eski parça silindi")
                }
            }
            say("Tamam: Movies/EskiKamera/$name\n${res.info}")
            showThumb(uri)
        } catch (t: Throwable) {
            rec.abort()
            lastError = t
            ErrorLog.add("HATA: ${t.javaClass.simpleName}: ${t.message}")
            say("Hata oldu: ${t.message}. Menüden 'Hata raporunu kopyala' ile bana gönder.")
        }
    }

    // ------------------------------------------------------------------ foto ve galeriden dönüştürme
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

    private fun processPhoto(uri: Uri, temp: File?, fromCamera: Boolean) {
        val device = currentDevice()
        val cfg = if (fromCamera) overlayConfig(device, false) else null
        val data = if (fromCamera && cfg != null && cfg.any) overlayData(0, false, 0f) else null
        val keepOriginal = fromCamera && settings.saveOriginal && temp != null
        runWork("Fotoğraf ${device.name} gibi işleniyor...") {
            val ext = device.photoFormat
            val out = File(cacheDir, "cikti_${System.nanoTime()}.$ext")
            try {
                PhotoProcessor.process(this, uri, device, System.nanoTime(), out, cfg, data) { ErrorLog.add(it) }
                val base = "${device.id}_${stamp()}"
                val name = "$base.$ext"
                val saved = Gallery.save(this, out, name, if (ext == "png") "image/png" else "image/jpeg", Gallery.DIR_PHOTO)
                if (keepOriginal && temp != null) {
                    try {
                        Gallery.save(this, temp, "${base}_orijinal.jpg", "image/jpeg", Gallery.DIR_ORIG)
                    } catch (e: Exception) {
                        ErrorLog.add("Orijinal kaydedilemedi: ${e.message}")
                    }
                }
                say("Tamam: Pictures/EskiKamera/$name")
                showThumb(saved)
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
                val saved = Gallery.save(this, res.file, res.file.name, mime, Gallery.DIR_VIDEO)
                say("Tamam: Movies/EskiKamera/${res.file.name}\n${res.info}")
                showThumb(saved)
            } finally {
                outDir.deleteRecursively()
                temp?.delete()
            }
        }
    }

    // ------------------------------------------------------------------ son çekim küçük resmi
    private fun loadLastThumb() {
        thumbExec.execute {
            try {
                Gallery.list(this).firstOrNull()?.let { showThumb(it.uri) }
            } catch (e: Throwable) {
                ErrorLog.add("Son çekim küçük resmi yüklenemedi: ${e.message}")
            }
        }
    }

    private fun showThumb(uri: Uri) {
        try {
            val b = contentResolver.loadThumbnail(uri, Size(192, 192), null)
            runOnUiThread { thumbView.setImageBitmap(b) }
        } catch (e: Throwable) {
            ErrorLog.add("Küçük resim oluşturulamadı: ${e.message}")
        }
    }
}
