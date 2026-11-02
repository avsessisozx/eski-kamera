package app.eskikamera

import android.content.Context

/** Kalıcı ayarlar (SharedPreferences). */
class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("eski_kamera", Context.MODE_PRIVATE)

    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun i(k: String, d: Int) = sp.getInt(k, d)

    var deviceId: String?
        get() = sp.getString("device", null)
        set(v) { sp.edit().putString("device", v).apply() }
    var dateStamp: Boolean get() = b("dateStamp", false); set(v) { sp.edit().putBoolean("dateStamp", v).apply() }
    /** 0 = otomatik (kamkorderlerde açık), 1 = hep açık, 2 = kapalı */
    var osdMode: Int get() = i("osdMode", 0); set(v) { sp.edit().putInt("osdMode", v).apply() }
    var gps: Boolean get() = b("gps", false); set(v) { sp.edit().putBoolean("gps", v).apply() }
    var compass: Boolean get() = b("compass", false); set(v) { sp.edit().putBoolean("compass", v).apply() }
    var micMeter: Boolean get() = b("micMeter", false); set(v) { sp.edit().putBoolean("micMeter", v).apply() }
    var saveOriginal: Boolean get() = b("saveOriginal", false); set(v) { sp.edit().putBoolean("saveOriginal", v).apply() }
    var sensorCsv: Boolean get() = b("sensorCsv", false); set(v) { sp.edit().putBoolean("sensorCsv", v).apply() }
    var motionSensitivity: Int get() = i("motionSens", 3); set(v) { sp.edit().putInt("motionSens", v).apply() }
    /** 0 = fotoğraf, 1 = video */
    var motionAction: Int get() = i("motionAction", 0); set(v) { sp.edit().putInt("motionAction", v).apply() }
    var motionQuietSec: Int get() = i("motionQuiet", 5); set(v) { sp.edit().putInt("motionQuiet", v).apply() }
    var timelapseSec: Int get() = i("timelapseSec", 2); set(v) { sp.edit().putInt("timelapseSec", v).apply() }
    var slowFactor: Int get() = i("slowFactor", 4); set(v) { sp.edit().putInt("slowFactor", v).apply() }
    var loopMinutes: Int get() = i("loopMin", 1); set(v) { sp.edit().putInt("loopMin", v).apply() }
    var loopKeep: Int get() = i("loopKeep", 5); set(v) { sp.edit().putInt("loopKeep", v).apply() }
    var sonarMinCm: Int get() = i("sonarMin", 30); set(v) { sp.edit().putInt("sonarMin", v).apply() }
    var sonarMaxCm: Int get() = i("sonarMax", 200); set(v) { sp.edit().putInt("sonarMax", v).apply() }
    var sonarSensitivity: Int get() = i("sonarSens", 3); set(v) { sp.edit().putInt("sonarSens", v).apply() }
    var sonarAction: Int get() = i("sonarAction", 0); set(v) { sp.edit().putInt("sonarAction", v).apply() }
}
