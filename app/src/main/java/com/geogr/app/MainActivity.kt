package com.geogr.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONArray
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        private const val REQUEST_PERMISSIONS = 410
        private const val STATE_TARGET_LAT = "target_lat"
        private const val STATE_TARGET_LNG = "target_lng"
    }

    private lateinit var mapView: MapView
    private lateinit var status: TextView
    private lateinit var searchInput: EditText
    private lateinit var radiusInput: EditText

    private var map: MapLibreMap? = null
    private var target: LatLng? = null
    private var targetMarker: Marker? = null
    private var userMarker: Marker? = null
    private var hasCenteredOnUser = false
    private var locationUpdatesActive = false
    private var lastSearchAt = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val searchExecutor = Executors.newSingleThreadExecutor()

    private val fused by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    private val activityLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            val point = LatLng(location.latitude, location.longitude)
            val currentMap = map ?: return

            if (userMarker == null) {
                userMarker = currentMap.addMarker(
                    MarkerOptions().position(point).title("Siz buradasınız")
                )
            } else {
                userMarker?.position = point
            }

            if (!hasCenteredOnUser) {
                hasCenteredOnUser = true
                currentMap.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(point, 15.0)
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize MapLibre before constructing MapView.
        MapLibre.getInstance(applicationContext)

        target = if (savedInstanceState?.containsKey(STATE_TARGET_LAT) == true) {
            LatLng(
                savedInstanceState.getDouble(STATE_TARGET_LAT),
                savedInstanceState.getDouble(STATE_TARGET_LNG)
            )
        } else {
            null
        }

        buildScreen()
        mapView.onCreate(savedInstanceState)

        mapView.addOnDidFailLoadingMapListener {
            status.text =
                "Xəritə yüklənmədi. İnternet bağlantısını yoxlayın və tətbiqi yenidən açın."
        }

        mapView.getMapAsync { readyMap ->
            map = readyMap
            startActivityLocationUpdates()

            // MapLibre does not expose Google Maps' zoom-controls setting.
            readyMap.uiSettings.isCompassEnabled = true

            readyMap.setStyle(Style.Builder().fromUri(STYLE_URL)) {
                status.text = if (target == null) {
                    "Xəritə hazırdır. Hədəf seçmək üçün xəritəyə toxunun."
                } else {
                    "Saxlanmış hədəf bərpa edildi."
                }

                target?.let { savedTarget ->
                    showTarget(savedTarget, moveCamera = false)
                }
            }

            readyMap.addOnMapClickListener { point ->
                target = point
                showTarget(point, moveCamera = false)
                status.text =
                    "Hədəf seçildi: %.5f, %.5f".format(point.latitude, point.longitude)
                true
            }
        }

        requestNeededPermissions()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(4))
            setBackgroundColor(Color.WHITE)
        }

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        searchInput = EditText(this).apply {
            hint = "Yer və ya küçə axtar"
            setSingleLine(true)
            inputType =
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            textSize = 15f
            setPadding(dp(10), 0, dp(8), 0)
        }

        searchRow.addView(
            searchInput,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        val searchButton = Button(this).apply {
            text = "Axtar"
            setOnClickListener { searchPlace() }
        }

        searchRow.addView(
            searchButton,
            LinearLayout.LayoutParams(dp(88), dp(48))
        )
        root.addView(searchRow)

        mapView = MapView(this)
        root.addView(
            mapView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val attribution = TextView(this).apply {
            text = "© OpenStreetMap contributors · OpenFreeMap · MapLibre"
            textSize = 11f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(2, dp(4), 2, dp(4))
            setOnClickListener {
                try {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.openstreetmap.org/copyright")
                        )
                    )
                } catch (_: Exception) {
                }
            }
        }
        root.addView(attribution)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }

        controls.addView(TextView(this).apply {
            text = "Xəbərdarlıq məsafəsi (metr)"
            textSize = 14f
        })

        radiusInput = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("500")
            hint = "Məsələn: 500"
            textSize = 16f
        }

        controls.addView(
            radiusInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )

        status = TextView(this).apply {
            text = "Xəritə yüklənir..."
            textSize = 13f
            setPadding(2, dp(4), 2, dp(4))
        }
        controls.addView(status)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val startButton = Button(this).apply {
            text = "İzləməni başlat"
            setOnClickListener { startMonitoring() }
        }

        val stopButton = Button(this).apply {
            text = "Dayandır"
            setOnClickListener { stopMonitoring() }
        }

        buttons.addView(
            startButton,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )
        buttons.addView(
            stopButton,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )
        controls.addView(buttons)

        root.addView(controls)
        setContentView(root)
    }

    private fun showTarget(point: LatLng, moveCamera: Boolean) {
        val currentMap = map ?: return

        targetMarker?.remove()
        targetMarker = currentMap.addMarker(
            MarkerOptions().position(point).title("Seçilmiş hədəf")
        )

        if (moveCamera) {
            currentMap.animateCamera(
                CameraUpdateFactory.newLatLngZoom(point, 16.0)
            )
        }
    }

    private fun searchPlace() {
        val query = searchInput.text.toString().trim()

        if (query.length < 2) {
            searchInput.error = "Ən azı 2 simvol daxil edin"
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastSearchAt < 1100L) {
            Toast.makeText(
                this,
                "Zəhmət olmasa bir az gözləyin.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        lastSearchAt = now
        status.text = "Axtarılır..."

        searchExecutor.execute {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = URL(
                    "https://nominatim.openstreetmap.org/search" +
                        "?format=jsonv2&limit=6&addressdetails=1" +
                        "&accept-language=az,en&q=$encoded"
                )

                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty(
                        "User-Agent",
                        "Geogr-Android/2.0 (manual place search)"
                    )
                    setRequestProperty("Accept", "application/json")
                }

                val response = connection.inputStream.bufferedReader().use {
                    it.readText()
                }
                connection.disconnect()

                val json = JSONArray(response)
                val results = ArrayList<PlaceResult>()

                for (i in 0 until json.length()) {
                    val item = json.getJSONObject(i)
                    val lat = item.optString("lat").toDoubleOrNull() ?: continue
                    val lon = item.optString("lon").toDoubleOrNull() ?: continue

                    results += PlaceResult(
                        item.optString("display_name", "Naməlum yer"),
                        lat,
                        lon
                    )
                }

                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post

                    if (results.isEmpty()) {
                        status.text = "Nəticə tapılmadı. Başqa adla axtarın."
                        Toast.makeText(
                            this,
                            "Heç bir nəticə tapılmadı.",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        status.text = "Axtarış nəticələrindən birini seçin."

                        AlertDialog.Builder(this)
                            .setTitle("Axtarış nəticələri")
                            .setItems(results.map { it.name }.toTypedArray()) { _, index ->
                                val selected = results[index]
                                val point = LatLng(
                                    selected.latitude,
                                    selected.longitude
                                )

                                target = point
                                showTarget(point, moveCamera = true)
                                status.text = "Hədəf: ${selected.name}"
                            }
                            .setNegativeButton("Bağla", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        status.text =
                            "Axtarış alınmadı. İnternet bağlantısını yoxlayın."
                        Toast.makeText(
                            this,
                            "Axtarış xətası: ${e.localizedMessage ?: "şəbəkə xətası"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private data class PlaceResult(
        val name: String,
        val latitude: Double,
        val longitude: Double
    )

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }

        if (
            android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }

        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    private fun startActivityLocationUpdates() {
        if (!hasLocationPermission()) return

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5000L
        )
            .setMinUpdateIntervalMillis(3000L)
            .build()

        if (locationUpdatesActive) return

        try {
            fused.requestLocationUpdates(
                request,
                activityLocationCallback,
                mainHandler.looper
            )
            locationUpdatesActive = true
        } catch (_: SecurityException) {
        }
    }

    private fun startMonitoring() {
        val point = target

        if (point == null) {
            Toast.makeText(
                this,
                "Əvvəlcə xəritədə hədəf nöqtə seçin.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (!hasLocationPermission()) {
            requestNeededPermissions()
            Toast.makeText(
                this,
                "İzləmə üçün məkan icazəsi lazımdır.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val radius = radiusInput.text.toString().toDoubleOrNull()
        if (
            radius == null ||
            !radius.isFinite() ||
            radius <= 0.0 ||
            radius > 100000.0
        ) {
            radiusInput.error = "1–100000 metr aralığında məsafə daxil edin"
            return
        }

        val intent = Intent(this, LocationMonitorService::class.java).apply {
            putExtra(LocationMonitorService.EXTRA_LAT, point.latitude)
            putExtra(LocationMonitorService.EXTRA_LNG, point.longitude)
            putExtra(LocationMonitorService.EXTRA_RADIUS, radius)
        }

        try {
            ContextCompat.startForegroundService(this, intent)
            status.text = "İzləmə aktivdir · Radius: ${radius.toInt()} m"
            Toast.makeText(
                this,
                "Məkan izlənməsi başladı.",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "İzləmə başlaya bilmədi: ${e.localizedMessage}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun stopMonitoring() {
        val intent = Intent(
            this,
            LocationMonitorService::class.java
        ).setAction(LocationMonitorService.ACTION_STOP)

        try {
            startService(intent)
        } catch (_: Exception) {
        }

        status.text = "İzləmə və alarm dayandırıldı."
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == REQUEST_PERMISSIONS) {
            if (hasLocationPermission()) {
                startActivityLocationUpdates()
            } else {
                Toast.makeText(
                    this,
                    "Məkan icazəsi olmadan izləmə işləməyəcək.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        if (map != null) startActivityLocationUpdates()
    }

    override fun onPause() {
        fused.removeLocationUpdates(activityLocationCallback)
        locationUpdatesActive = false
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        searchExecutor.shutdownNow()
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        target?.let {
            outState.putDouble(STATE_TARGET_LAT, it.latitude)
            outState.putDouble(STATE_TARGET_LNG, it.longitude)
        }

        mapView.onSaveInstanceState(outState)
    }
}
