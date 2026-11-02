package app.eskikamera

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Uygulama içi günlük ve kopyalanabilir ayrıntılı hata raporu. */
object ErrorLog {
    private val lines = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(msg: String) {
        android.util.Log.i("EskiKamera", msg)
        lines.add("${fmt.format(Date())} $msg")
        while (lines.size > 120) lines.removeAt(0)
    }

    @Synchronized
    fun snapshot(): List<String> = ArrayList(lines)

    fun stackOf(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    fun report(context: Context, t: Throwable?, header: String = "ESKİ KAMERA HATA RAPORU"): String {
        val sb = StringBuilder()
        sb.append("=== ").append(header).append(" ===\n")
        sb.append("Sürüm: ").append(BuildConfig.VERSION_NAME).append('\n')
        sb.append("Telefon: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" / Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("Bellek sınırı: ").append(Runtime.getRuntime().maxMemory() / (1024 * 1024)).append(" MB\n")
        if (t != null) {
            sb.append("\nHata: ").append(t.javaClass.name).append(": ").append(t.message).append('\n')
            sb.append(stackOf(t)).append('\n')
        }
        sb.append("\nSon kayıtlar:\n")
        for (l in snapshot()) sb.append(l).append('\n')
        val prev = loadCrash(context)
        if (prev != null) sb.append("\nÖnceki çökme:\n").append(prev).append('\n')
        return sb.toString()
    }

    private fun crashFile(context: Context) = File(context.filesDir, "son_cokme.txt")

    fun saveCrash(context: Context, t: Throwable) {
        try {
            crashFile(context).writeText("${Date()}\n${t.javaClass.name}: ${t.message}\n${stackOf(t)}")
        } catch (_: Throwable) {
        }
    }

    fun loadCrash(context: Context): String? {
        val f = crashFile(context)
        return if (f.exists()) f.readText() else null
    }

    fun clearCrash(context: Context) {
        crashFile(context).delete()
    }
}
