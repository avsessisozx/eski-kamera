package app.eskikamera

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager

/** Pil, GPS (hız, rakım, konum), pusula ve eğim bilgisini toplar. */
class SensorHub(private val ctx: Context) : SensorEventListener, LocationListener {
    @Volatile var heading = Float.NaN
    @Volatile var rollDeg = 0f
    @Volatile var pitchDeg = 0f
    @Volatile var speedKmh = Float.NaN
    @Volatile var altitude = Double.NaN
    @Volatile var lat = Double.NaN
    @Volatile var lon = Double.NaN

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var orientationOn = false
    private var locationOn = false
    private val rot = FloatArray(9)
    private val ori = FloatArray(3)

    /** Pusula ve eğim için jiroskop/ivme sensörü var mı? */
    fun hasOrientationSensor(): Boolean = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null

    fun start(wantOrientation: Boolean, wantLocation: Boolean): String {
        stop()
        val notes = ArrayList<String>()
        if (wantOrientation) {
            val s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (s != null) { sm.registerListener(this, s, SensorManager.SENSOR_DELAY_UI); orientationOn = true }
            else notes.add("Bu telefonda pusula/eğim sensörü yok")
        }
        if (wantLocation) {
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
                locationOn = true
            } catch (e: SecurityException) {
                notes.add("Konum izni verilmedi")
            } catch (e: Exception) {
                notes.add("GPS açılamadı: ${e.message}")
            }
        }
        return notes.joinToString("; ")
    }

    fun stop() {
        if (orientationOn) sm.unregisterListener(this)
        if (locationOn) try { lm.removeUpdates(this) } catch (_: Exception) { }
        orientationOn = false
        locationOn = false
    }

    fun battery(): Int {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (level < 0) -1 else level * 100 / scale
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        SensorManager.getOrientation(rot, ori)
        var az = Math.toDegrees(ori[0].toDouble()).toFloat()
        if (az < 0) az += 360f
        heading = az
        pitchDeg = Math.toDegrees(ori[1].toDouble()).toFloat()
        rollDeg = Math.toDegrees(ori[2].toDouble()).toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onLocationChanged(l: Location) {
        lat = l.latitude
        lon = l.longitude
        if (l.hasAltitude()) altitude = l.altitude
        speedKmh = if (l.hasSpeed()) l.speed * 3.6f else Float.NaN
    }
}
