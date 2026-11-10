package com.geogr.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class LocationMonitorService : Service() {
    companion object {
        const val EXTRA_LAT = "target_lat"
        const val EXTRA_LNG = "target_lng"
        const val EXTRA_RADIUS = "radius_m"
        const val ACTION_STOP = "com.geogr.app.STOP_MONITORING"
        private const val CHANNEL_ID = "geogr_tracking"
        private const val NOTIFICATION_ID = 71
    }

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var targetLat = Double.NaN
    private var targetLng = Double.NaN
    private var radius = 500.0
    private var alarm: Ringtone? = null
    private var alarmTriggered = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            val distance = distanceMeters(location.latitude, location.longitude, targetLat, targetLng)
            if (!alarmTriggered && distance <= radius) triggerAlarm()
            val message = if (alarmTriggered) {
                "HƏDƏFƏ ÇATDINIZ — alarmı dayandırın"
            } else {
                "Hədəfə qədər: ${distance.toInt()} m (hədd: ${radius.toInt()} m)"
            }
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(message))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        targetLat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
        targetLng = intent.getDoubleExtra(EXTRA_LNG, Double.NaN)
        radius = intent.getDoubleExtra(EXTRA_RADIUS, 500.0)
        if (!targetLat.isFinite() || !targetLng.isFinite() || targetLat !in -90.0..90.0 || targetLng !in -180.0..180.0 || radius <= 0.0) {
            stopSelf()
            return START_NOT_STICKY
        }
        alarm?.stop()
        alarm = null
        alarmTriggered = false
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Məkan izlənməsi aktivdir"))
        startLocationUpdates()
        return START_NOT_STICKY
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
            .setMinUpdateIntervalMillis(1500L)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun triggerAlarm() {
        alarmTriggered = true
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            alarm = RingtoneManager.getRingtone(this, uri)
            if (Build.VERSION.SDK_INT >= 28) {
                alarm?.isLooping = true
                alarm?.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            alarm?.play()
        } catch (_: Exception) { }
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification("HƏDƏFƏ ÇATDINIZ — dayandırmaq üçün düyməyə basın")
        )
    }

    private fun buildNotification(message: String): Notification {
        val stopIntent = Intent(this, LocationMonitorService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 72, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 73, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Geogr")
            .setContentText(message)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_pause, "Dayandır", stopPending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Geogr məkan izlənməsi",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hədəf nöqtəyə qədər məsafənin izlənməsi"
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rawA = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val a = rawA.coerceIn(0.0, 1.0)
        return earthRadius * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        alarm?.stop()
        alarm = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
