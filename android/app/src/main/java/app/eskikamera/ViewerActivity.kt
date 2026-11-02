package app.eskikamera

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/** Uygulama içi görüntüleyici: foto yakınlaştırma, video oynatma, kaydırma, ızgara, paylaş, sil, önce/sonra. */
class ViewerActivity : AppCompatActivity() {
    private var items: List<MediaItem> = emptyList()
    private var index = 0
    private var showingOriginal = false
    private lateinit var zoom: ZoomImageView
    private lateinit var video: VideoView
    private lateinit var title: TextView
    private lateinit var empty: TextView
    private lateinit var grid: GridView
    private lateinit var btnOrig: TextView
    private val io = Executors.newFixedThreadPool(3)
    private var deviceNames: Map<String, String> = emptyMap()
    private val thumbs = object : LruCache<Uri, Bitmap>(60) {}
    private lateinit var gd: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val text = assets.open("devices.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            deviceNames = Profiles.parse(text).associate { it.id to it.name }
        } catch (_: Exception) { }
        buildUi()
        items = Gallery.list(this)
        ErrorLog.add("Görüntüleyici: ${items.size} öğe")
        index = intent.getIntExtra("index", 0).coerceIn(0, maxOf(0, items.size - 1))
        if (intent.getBooleanExtra("grid", false) && items.isNotEmpty()) showGrid(true)
        show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun chip(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply { setColor(0xCC202020.toInt()); cornerRadius = dp(16).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        zoom = ZoomImageView(this)
        video = VideoView(this)
        root.addView(zoom, FrameLayout.LayoutParams(-1, -1))
        root.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        title = TextView(this).apply {
            textSize = 13f; setTextColor(Color.WHITE); setPadding(dp(12), dp(12), dp(12), dp(8)); setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        root.addView(title, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        empty = TextView(this).apply {
            text = "Henüz çekim yok.\nFoto veya video çekince burada görünür."; textSize = 16f; setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER; visibility = View.GONE
        }
        root.addView(empty, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(6), dp(6), dp(6), dp(14)) }
        btnOrig = chip("Orijinal") { toggleOriginal() }
        val buttons = listOf(
            chip("◀") { move(-1) }, chip("▶") { move(1) }, chip("Paylaş") { share() }, chip("Sil") { confirmDelete() },
            btnOrig, chip("Izgara") { showGrid(grid.visibility != View.VISIBLE) }, chip("Bilgi") { info() }
        )
        for (b in buttons) bar.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        grid = GridView(this).apply {
            numColumns = 3; setBackgroundColor(0xF0101010.toInt()); visibility = View.GONE; verticalSpacing = dp(6); horizontalSpacing = dp(6)
            setPadding(dp(6), dp(40), dp(6), dp(70))
            adapter = GridAdapter()
            setOnItemClickListener { _, _, pos, _ -> index = pos; showGrid(false); show() }
        }
        root.addView(grid, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (grid.visibility == View.VISIBLE || zoom.isZoomed || e1 == null) return false
                if (abs(vx) > 900 && abs(vx) > abs(vy) * 1.5f) { move(if (vx < 0) 1 else -1); return true }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gd.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onPause() {
        try { video.pause() } catch (_: Exception) { }
        super.onPause()
    }

    private fun move(d: Int) {
        if (items.isEmpty()) return
        index = (index + d + items.size) % items.size
        show()
    }

    private fun current(): MediaItem? = items.getOrNull(index)

    private fun show() {
        showingOriginal = false
        val it = current()
        try { video.stopPlayback() } catch (_: Exception) { }
        if (it == null) {
            empty.visibility = View.VISIBLE; zoom.visibility = View.GONE; video.visibility = View.GONE; title.text = ""
            return
        }
        empty.visibility = View.GONE
        val dev = deviceNames[it.name.substringBefore("_20")] ?: it.name.substringBefore("_20").replace('_', ' ')
        title.text = "${index + 1} / ${items.size} · $dev\n" + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(Date(it.dateMs))
        btnOrig.visibility = View.GONE
        if (it.isVideo) {
            zoom.visibility = View.GONE
            video.visibility = View.VISIBLE
            val mc = MediaController(this)
            mc.setAnchorView(video)
            video.setMediaController(mc)
            video.setVideoURI(it.uri)
            video.setOnPreparedListener { mp -> mp.isLooping = false; video.start() }
            video.setOnErrorListener { _, w, e ->
                ErrorLog.add("Video oynatılamadı ($w/$e): ${it.name}")
                Toast.makeText(this, "Bu video uygulama içinde oynatılamadı. 'Galeri'de aç'ı dene.", Toast.LENGTH_LONG).show()
                true
            }
        } else {
            video.visibility = View.GONE
            zoom.visibility = View.VISIBLE
            loadBitmap(it.uri)
            io.execute {
                val orig = Gallery.originalFor(this, it)
                runOnUiThread { if (current() === it) btnOrig.visibility = if (orig != null) View.VISIBLE else View.GONE }
            }
        }
    }

    private fun loadBitmap(uri: Uri) {
        io.execute {
            try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                var s = 1
                while (o.outWidth / (s * 2) >= 2048 || o.outHeight / (s * 2) >= 2048) s *= 2
                val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
                runOnUiThread { if (bmp != null) zoom.setBitmapFit(bmp) }
            } catch (e: Exception) {
                ErrorLog.add("Foto açılamadı: ${e.message}")
            }
        }
    }

    private fun toggleOriginal() {
        val it = current() ?: return
        io.execute {
            val orig = Gallery.originalFor(this, it)
            runOnUiThread {
                if (orig == null) return@runOnUiThread
                showingOriginal = !showingOriginal
                btnOrig.text = if (showingOriginal) "Eski hali" else "Orijinal"
                loadBitmap(if (showingOriginal) orig else it.uri)
            }
        }
    }

    private fun showGrid(on: Boolean) {
        grid.visibility = if (on) View.VISIBLE else View.GONE
        if (on) (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun share() {
        val it = current() ?: return
        val i = Intent(Intent.ACTION_SEND).apply {
            type = if (it.isVideo) "video/*" else "image/*"
            putExtra(Intent.EXTRA_STREAM, it.uri)
            clipData = ClipData.newRawUri("", it.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Paylaş"))
    }

    private fun confirmDelete() {
        val it = current() ?: return
        AlertDialog.Builder(this).setTitle("Silinsin mi?").setMessage("${it.name} kalıcı olarak silinir.")
            .setPositiveButton("Sil") { _, _ ->
                if (Gallery.delete(this, it.uri)) {
                    items = Gallery.list(this)
                    index = index.coerceIn(0, maxOf(0, items.size - 1))
                    (grid.adapter as BaseAdapter).notifyDataSetChanged()
                    show()
                } else Toast.makeText(this, "Silinemedi. Galeri uygulamasından silmeyi dene.", Toast.LENGTH_LONG).show()
            }.setNegativeButton("Vazgeç", null).show()
    }

    private fun info() {
        val it = current() ?: return
        val kb = it.size / 1024
        AlertDialog.Builder(this).setTitle("Bilgi")
            .setMessage("Dosya: ${it.name}\nBoyut: $kb KB\nTür: ${if (it.isVideo) "video" else "foto"}\nKonum: ${if (it.isVideo) "Movies" else "Pictures"}/EskiKamera")
            .setPositiveButton("Tamam", null).show()
    }

    private inner class GridAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val box = (cv as? LinearLayout) ?: LinearLayout(this@ViewerActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; tag = "img" }, LinearLayout.LayoutParams(-1, dp(110)))
                addView(TextView(context).apply { textSize = 10f; setTextColor(Color.LTGRAY); maxLines = 1; tag = "lbl" })
            }
            val img = box.findViewWithTag<ImageView>("img")
            val lbl = box.findViewWithTag<TextView>("lbl")
            val it = items[p]
            lbl.text = (if (it.isVideo) "▶ " else "") + (deviceNames[it.name.substringBefore("_20")] ?: it.name.substringBefore("_20"))
            img.setImageBitmap(thumbs.get(it.uri))
            if (thumbs.get(it.uri) == null) {
                img.setImageDrawable(null)
                io.execute {
                    try {
                        val b = contentResolver.loadThumbnail(it.uri, Size(256, 256), null)
                        thumbs.put(it.uri, b)
                        runOnUiThread { notifyDataSetChanged() }
                    } catch (_: Exception) { }
                }
            }
            return box
        }
    }

    companion object {
        fun open(ctx: Context, index: Int = 0, grid: Boolean = false) {
            ctx.startActivity(Intent(ctx, ViewerActivity::class.java).putExtra("index", index).putExtra("grid", grid))
        }
    }
}
