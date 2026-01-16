package com.example.gps_tracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.MyLocationStyle
import com.example.gps_tracker.ui.theme.GPS_TrackerTheme
import com.google.android.gms.location.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val locationViewModel: LocationViewModel by viewModels()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            ) {
                startLocationUpdates()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)

        setContent {
            GPS_TrackerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GpsTrackerScreen(locationViewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) {
            startLocationUpdates()
        } else {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startLocationUpdates() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let {
                    locationViewModel.updateLocation(it)
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        fusedLocationProviderClient.requestLocationUpdates(
            LocationRequest.create().apply {
                interval = 10000
                fastestInterval = 5000
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            },
            locationCallback,
            Looper.getMainLooper()
        )
    }

    override fun onPause() {
        super.onPause()
        if (::locationCallback.isInitialized) {
            fusedLocationProviderClient.removeLocationUpdates(locationCallback)
        }
    }
}

class LocationViewModel : ViewModel() {
    var currentLocation by mutableStateOf<Location?>(null)
    var isRecording by mutableStateOf(false)
    private val locationHistory = mutableListOf<Location>()
    private val gpxFileLogger = GpxFileLogger()

    fun updateLocation(location: Location) {
        currentLocation = location
        if (isRecording) {
            locationHistory.add(location)
        }
    }

    fun toggleRecording(context: Context) {
        isRecording = !isRecording
        if (!isRecording) {
            if (locationHistory.isNotEmpty()){
                gpxFileLogger.writeGpxFile(context, locationHistory)
                locationHistory.clear()
            }
        }
    }
}

@Composable
fun GpsTrackerScreen(locationViewModel: LocationViewModel) {
    val context = LocalContext.current
    val isRecording = locationViewModel.isRecording

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(4f)) {
            MapViewContainer(locationViewModel = locationViewModel)
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { locationViewModel.toggleRecording(context) }) {
                Text(text = if (isRecording) "Stop Recording" else "Start Recording")
            }
            Button(onClick = { Toast.makeText(context, "View History Clicked", Toast.LENGTH_SHORT).show() }) {
                Text(text = "View History")
            }
            Button(onClick = { Toast.makeText(context, "About Clicked", Toast.LENGTH_SHORT).show() }) {
                Text(text = "About")
            }
        }
    }
}

@Composable
fun MapViewContainer(locationViewModel: LocationViewModel) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val currentLocation = locationViewModel.currentLocation

    AndroidView({ mapView }) {
        val aMap = it.map
        aMap.isMyLocationEnabled = true
        aMap.myLocationStyle = MyLocationStyle().apply {
            myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATE)
            interval(2000)
        }
        currentLocation?.let {
            val latLng = com.amap.api.maps.model.LatLng(it.latitude, it.longitude)
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            mapView.onDestroy()
        }
    }
}


class GpxFileLogger {
    fun writeGpxFile(context: Context, locations: List<Location>) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        val gpxContent = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<gpx version=\"1.1\" creator=\"GPS Tracker\">\n")
            append("<trk>\n<name>Track</name>\n<trkseg>\n")
            locations.forEach {
                append("<trkpt lat=\"${it.latitude}\" lon=\"${it.longitude}\">\n")
                append("<ele>${it.altitude}</ele>\n")
                append("<time>${dateFormat.format(Date(it.time))}</time>\n")
                append("</trkpt>\n")
            }
            append("</trkseg>\n</trk>\n</gpx>")
        }
        try {
            val file = File(context.getExternalFilesDir(null), "track_${System.currentTimeMillis()}.gpx")
            FileOutputStream(file).use {
                it.write(gpxContent.toByteArray())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
