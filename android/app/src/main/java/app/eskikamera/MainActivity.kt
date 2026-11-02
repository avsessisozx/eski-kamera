package app.eskikamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var devices: List<Device>
    private lateinit var deviceSpinner: Spinner
    private lateinit var modeSpinner: Spinner
    private lateinit var infoText: TextView
    private lateinit var previewView: PreviewView
    private lateinit var btnPhoto: Button
    private lateinit var btnVideo: Button
    private lateinit var btnPickPhoto: Button
    private lateinit var btnPickVideo: Button
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var statusScroll: ScrollView

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var busy = false
    private var lastError: Throwable? = null
    private val worker = Executors.newSingleThreadExecutor()

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.CAMERA] == true) startCamera()
        else say("Kamera izni verilmedi. Galeriden dönüştürme yine de çalışır.")
    }
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) processPhoto(uri, null)
    }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) processVideo(uri, null)
    }

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
            say("Cihaz listesi yüklenemedi. 'Hata raporunu kopyala' butonuna basıp bana yapıştır.")
            return
        }
        if (ErrorLog.loadCrash(this) != null) say("Önceki çalışmada çökme olmuş. 'Hata raporunu kopyala' ile bana gönderebilirsin.")
        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startCamera()
        else permLauncher.launch(needed)
    }

    // ------------------------------------------------------------------ arayüz
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        deviceSpinner = Spinner(this)
        deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            devices.map { "${it.name}  [${it.category}]" })
        modeSpinner = Spinner(this)
        infoText = TextView(this).apply { textSize = 12f }

        deviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refreshModes()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        previewView = PreviewView(this)
        val previewParams = LinearLayout.LayoutParams(match, 0, 1f)

        btnPhoto = Button(this).apply { text = "Foto çek"; setOnClickListener { takePhoto() } }
        btnVideo = Button(this).apply { text = "Video başlat"; setOnClickListener { toggleVideo() } }
        btnPickPhoto = Button(this).apply { text = "Galeriden foto"; setOnClickListener { if (!busy) pickPhoto.launch("image/*") } }
        btnPickVideo = Button(this).apply { text = "Galeriden video"; setOnClickListener { if (!busy) pickVideo.launch("video/*") } }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row1.addView(btnPhoto, LinearLayout.LayoutParams(0, wrap, 1f))
        row1.addView(btnVideo, LinearLayout.LayoutParams(0, wrap, 1f))
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row2.addView(btnPickPhoto, LinearLayout.LayoutParams(0, wrap, 1f))
        row2.addView(btnPickVideo, LinearLayout.LayoutParams(0, wrap, 1f))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.INVISIBLE }
        statusText = TextView(this).apply { textSize = 13f; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        statusScroll = ScrollView(this).apply { addView(statusText) }
        val copyBtn = Button(this).apply { text = "Hata raporunu kopyala"; setOnClickListener { copyReport() } }

        root.addView(deviceSpinner, LinearLayout.LayoutParams(match, wrap))
        root.addView(modeSpinner, LinearLayout.LayoutParams(match, wrap))
        root.addView(infoText, LinearLayout.LayoutParams(match, wrap))
        root.addView(previewView, previewParams)
        root.addView(row1, LinearLayout.LayoutParams(match, wrap))
        root.addView(row2, LinearLayout.LayoutParams(match, wrap))
        root.addView(progress, LinearLayout.LayoutParams(match, wrap))
        root.addView(statusScroll, LinearLayout.LayoutParams(match, dp(110)))
        root.addView(copyBtn, LinearLayout.LayoutParams(match, wrap))
        setContentView(root)
        if (devices.isNotEmpty()) refreshModes()
    }

    private fun currentDevice(): Device = devices[deviceSpinner.selectedItemPosition.coerceAtLeast(0)]

    private fun currentMode(d: Device): VideoMode? =
        if (!d.hasVideo) null else d.modes[modeSpinner.selectedItemPosition.coerceIn(0, d.modes.size - 1)]

    private fun refreshModes() {
        val d = currentDevice()
        if (d.hasVideo) {
            modeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, d.modes.map { it.label })
            modeSpinner.setSelection(d.defaultMode)
            modeSpinner.isEnabled = true
        } else {
            modeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Bu cihazda video yok"))
            modeSpinner.isEnabled = false
        }
        btnVideo.isEnabled = d.hasVideo && !busy
        btnPickVideo.isEnabled = d.hasVideo && !busy
        infoText.text = "Foto ${d.photoWidth}x${d.photoHeight}. Özellikler: ${d.specSource}. Görünüm ayarı: ${d.lookSource}."
    }

    private fun say(msg: String) {
        ErrorLog.add(msg)
        runOnUiThread {
            statusText.text = ErrorLog.snapshot().takeLast(8).joinToString("\n")
            statusScroll.post { statusScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun setBusy(b: Boolean, indeterminate: Boolean = true) {
        busy = b
        runOnUiThread {
            progress.visibility = if (b) View.VISIBLE else View.INVISIBLE
            progress.isIndeterminate = indeterminate
            btnPhoto.isEnabled = !b
            btnPickPhoto.isEnabled = !b
            val d = currentDevice()
            btnVideo.isEnabled = d.hasVideo && (!b || recording != null)
            btnPickVideo.isEnabled = d.hasVideo && !b
        }
    }

    private fun copyReport() {
        val text = ErrorLog.report(this, lastError)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Eski Kamera hata raporu", text))
        Toast.makeText(this, "Hata raporu kopyalandı. Claude'a yapıştırabilirsin.", Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ kamera
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val ic = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                    .build()
                val vc = VideoCapture.withOutput(recorder)
                provider.unbindAll()
                try {
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, ic, vc)
                    videoCapture = vc
                } catch (e: Exception) {
                    say("Bu telefonda foto+video birlikte açılamadı, yalnızca foto kullanılabilir: ${e.message}")
                    provider.unbindAll()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, ic)
                    videoCapture = null
                }
                imageCapture = ic
                say("Kamera hazır.")
            } catch (e: Throwable) {
                lastError = e
                say("Kamera açılamadı: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val ic = imageCapture
        if (ic == null) { say("Kamera hazır değil."); return }
        if (busy) return
        val file = File(cacheDir, "cekim_${System.currentTimeMillis()}.jpg")
        setBusy(true)
        say("Fotoğraf çekiliyor...")
        ic.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
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
    private fun toggleVideo() {
        val active = recording
        if (active != null) {
            active.stop()
            return
        }
        val vc = videoCapture
        if (vc == null) { say("Video kaydı bu telefonda kullanılamıyor."); return }
        if (busy) return
        val file = File(cacheDir, "cekim_${System.currentTimeMillis()}.mp4")
        var pending = vc.output.prepareRecording(this, FileOutputOptions.Builder(file).build())
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        } else say("Mikrofon izni yok, video sessiz kaydedilecek.")
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                recording = null
                btnVideo.text = "Video başlat"
                if (event.hasError()) {
                    say("Video kaydı hatası (kod ${event.error}).")
                    setBusy(false)
                } else {
                    setBusy(false)
                    processVideo(Uri.fromFile(file), file)
                }
            }
        }
        btnVideo.text = "Kaydı durdur"
        setBusy(true, false)
        btnVideo.isEnabled = true
        progress.visibility = View.INVISIBLE
        say("Video kaydediliyor...")
    }

    // ------------------------------------------------------------------ işleme
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
                say("Hata oldu: ${t.message}. 'Hata raporunu kopyala' butonuna basıp bana yapıştır.")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun processPhoto(uri: Uri, temp: File?) {
        val device = currentDevice()
        runWork("Fotoğraf ${device.name} gibi işleniyor...") {
            val out = File(cacheDir, "cikti_${System.nanoTime()}.jpg")
            try {
                PhotoProcessor.process(this, uri, device, System.nanoTime(), out) { ErrorLog.add(it) }
                saveToGallery(out, "${device.id}_${stamp()}.jpg", "image/jpeg", Environment.DIRECTORY_PICTURES)
                say("Tamam: Pictures/EskiKamera/${device.id}_*.jpg")
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
