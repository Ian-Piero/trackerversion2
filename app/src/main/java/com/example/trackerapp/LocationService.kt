package com.example.trackerapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class LocationService : Service(), SensorEventListener {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val client = OkHttpClient()
    private lateinit var deviceId: String
    private val serverUrl = "http://3.128.226.213/tracker/receive_location.php"

    // Para contar pasos
    private var stepCount: Int = 0
    private var sensorManager: SensorManager? = null
    private var stepSensor: Sensor? = null

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        deviceId = getOrCreateDeviceId()

        // Iniciar sensor de pasos
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        stepSensor?.also {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        startForeground(1, createNotification())
        startLocationUpdates()
    }

    private fun getOrCreateDeviceId(): String {
        val prefs = getSharedPreferences("tracker_prefs", MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    private fun createNotification(): Notification {
        val channelId = "tracker_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Tracker Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Rastreo activo")
            .setContentText("Enviando ubicación, velocidad y pasos…")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
    }

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 2000 // cada 2 segundos
        )
            .setMinUpdateIntervalMillis(1000) // hasta 1 segundo
            .setMinUpdateDistanceMeters(0f)   // incluso sin moverse
            .setWaitForAccurateLocation(true)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .setMaxUpdates(Int.MAX_VALUE)
            .build()

        fusedLocationClient.requestLocationUpdates(
            request,
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val location = result.lastLocation ?: return

                    // Filtro: ignorar ubicaciones con baja precisión
                    if (location.accuracy > 30) return

                    val speedKmh = (location.speed * 3.6).toInt() // m/s -> km/h
                    sendLocation(
                        location.latitude,
                        location.longitude,
                        speedKmh,
                        location.accuracy,
                        stepCount
                    )
                }
            },
            Looper.getMainLooper()
        )
    }

    private fun sendLocation(lat: Double, lon: Double, speed: Int, accuracy: Float, steps: Int) {
        val json = JSONObject().apply {
            put("device_id", deviceId)
            put("lat", lat)
            put("lon", lon)
            put("speed", speed)
            put("accuracy", accuracy)
            put("steps", steps)
        }

        val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(serverUrl)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Listener del sensor de pasos
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_STEP_DETECTOR) {
            stepCount += event.values[0].toInt()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
