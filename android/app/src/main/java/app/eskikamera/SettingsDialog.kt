package app.eskikamera

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Ayarlar ekranı: her ayarın altında ne işe yaradığını anlatan kısa açıklama bulunur. */
class SettingsDialog(
    private val act: AppCompatActivity,
    private val s: Settings,
    private val onChanged: (key: String) -> Unit,
    private val runSonarTest: () -> Unit
) {
    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()

    fun show() {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        fun section(t: String) = col.addView(TextView(act).apply {
            text = t; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF1565C0.toInt()); setPadding(0, dp(18), 0, dp(4))
        })
        fun desc(t: String) = col.addView(TextView(act).apply { text = t; textSize = 12f; setTextColor(0xFF616161.toInt()); setPadding(0, 0, 0, dp(6)) })
        fun switchRow(title: String, d: String, get: () -> Boolean, set: (Boolean) -> Unit, key: String) {
            val sw = Switch(act).apply {
                text = title; textSize = 15f; isChecked = get()
                setOnCheckedChangeListener { _: CompoundButton, v: Boolean -> set(v); onChanged(key) }
            }
            col.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            desc(d)
        }
        fun choiceRow(title: String, labels: List<String>, values: List<Int>, get: () -> Int, set: (Int) -> Unit, key: String) {
            val row = TextView(act).apply {
                textSize = 15f; setTextColor(Color.BLACK)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply { setColor(0xFFEEEEEE.toInt()); cornerRadius = dp(10).toFloat() }
            }
            fun refresh() { row.text = "$title: " + labels[values.indexOf(get()).coerceAtLeast(0)] }
            refresh()
            row.setOnClickListener {
                AlertDialog.Builder(act).setTitle(title).setItems(labels.toTypedArray()) { _, which ->
                    set(values[which]); refresh(); onChanged(key)
                }.show()
            }
            col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }

        section("Görüntü üstü bilgiler")
        switchRow("Tarih damgası", Help.SETTINGS_DATE, { s.dateStamp }, { s.dateStamp = it }, "date")
        choiceRow("Kamkorder ekranı", listOf("Otomatik (kamkorderlerde açık)", "Her zaman açık", "Kapalı"), listOf(0, 1, 2), { s.osdMode }, { s.osdMode = it }, "osd")
        desc(Help.SETTINGS_OSD)
        switchRow("GPS: hız, rakım, konum", Help.SETTINGS_GPS, { s.gps }, { s.gps = it }, "gps")
        switchRow("Pusula ve eğim çizgisi", Help.SETTINGS_COMPASS, { s.compass }, { s.compass = it }, "compass")
        switchRow("Ses seviyesi çubuğu", Help.SETTINGS_MIC, { s.micMeter }, { s.micMeter = it }, "mic")

        section("Kayıtlar")
        switchRow("Orijinal fotoğrafı da kaydet", Help.SETTINGS_ORIGINAL, { s.saveOriginal }, { s.saveOriginal = it }, "orig")
        switchRow("Sensör verisini CSV'ye yaz", Help.SETTINGS_CSV, { s.sensorCsv }, { s.sensorCsv = it }, "csv")

        section("Zaman atlamalı ve yavaş çekim")
        choiceRow("Kare aralığı", listOf("1 sn", "2 sn", "5 sn", "10 sn", "30 sn"), listOf(1, 2, 5, 10, 30), { s.timelapseSec }, { s.timelapseSec = it }, "tl")
        desc(Help.SETTINGS_TIMELAPSE)
        choiceRow("Yavaşlatma", listOf("2 kat", "4 kat"), listOf(2, 4), { s.slowFactor }, { s.slowFactor = it }, "slow")
        desc(Help.SETTINGS_SLOW)

        section("Döngülü kayıt")
        choiceRow("Parça süresi", listOf("1 dk", "3 dk", "5 dk"), listOf(1, 3, 5), { s.loopMinutes }, { s.loopMinutes = it }, "loopmin")
        choiceRow("Saklanacak parça sayısı", listOf("3", "5", "10"), listOf(3, 5, 10), { s.loopKeep }, { s.loopKeep = it }, "loopkeep")
        desc(Help.SETTINGS_LOOP)

        section("Hareketle çekim")
        choiceRow("Hassasiyet", listOf("1 (az)", "2", "3 (orta)", "4", "5 (çok)"), listOf(1, 2, 3, 4, 5), { s.motionSensitivity }, { s.motionSensitivity = it }, "msens")
        choiceRow("Hareket olunca", listOf("Fotoğraf çek", "Video kaydet"), listOf(0, 1), { s.motionAction }, { s.motionAction = it }, "maction")
        choiceRow("Hareket bitince videoyu bitir", listOf("3 sn sonra", "5 sn sonra", "10 sn sonra"), listOf(3, 5, 10), { s.motionQuietSec }, { s.motionQuietSec = it }, "mquiet")
        desc(Help.SETTINGS_MOTION)

        section("Sonar (deneysel)")
        choiceRow("En yakın mesafe", listOf("30 cm", "50 cm", "100 cm"), listOf(30, 50, 100), { s.sonarMinCm }, { s.sonarMinCm = it }, "smin")
        choiceRow("En uzak mesafe", listOf("100 cm", "150 cm", "200 cm", "300 cm"), listOf(100, 150, 200, 300), { s.sonarMaxCm }, { s.sonarMaxCm = it }, "smax")
        choiceRow("Hassasiyet", listOf("1 (az)", "2", "3 (orta)", "4", "5 (çok)"), listOf(1, 2, 3, 4, 5), { s.sonarSensitivity }, { s.sonarSensitivity = it }, "ssens")
        choiceRow("Hareket olunca", listOf("Fotoğraf çek", "Video kaydet (sessiz)"), listOf(0, 1), { s.sonarAction }, { s.sonarAction = it }, "saction")
        desc(Help.SETTINGS_SONAR)
        val test = TextView(act).apply {
            text = "Sonar ses testi"; textSize = 15f; setTextColor(Color.WHITE); gravity = android.view.Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply { setColor(0xFF1565C0.toInt()); cornerRadius = dp(10).toFloat() }
            setOnClickListener { runSonarTest() }
        }
        col.addView(test, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(16) })

        val sv = ScrollView(act).apply { addView(col) }
        AlertDialog.Builder(act).setTitle("Ayarlar").setView(sv).setPositiveButton("Kapat", null).show()
    }
}
