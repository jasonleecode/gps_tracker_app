
package com.example.gps_tracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.MyLocationStyle
import com.example.gps_tracker.ui.theme.GPS_TrackerTheme
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val locationViewModel: LocationViewModel by viewModels()
    private var sensorManager: SensorManager? = null
    private var stepSensor: Sensor? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            ) {
                startLocationUpdates()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && permissions[Manifest.permission.ACTIVITY_RECOGNITION] == true) {
                setupStepCounter()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasActivityRecognitionPermission()) {
            setupStepCounter()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
        }

        stepSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun setupStepCounter() {
        stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        stepSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
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

    private fun hasActivityRecognitionPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
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
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                locationViewModel.updateSteps(it.values[0].toInt())
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

class LocationViewModel : ViewModel() {
    var currentLocation by mutableStateOf<Location?>(null)
    var isRecording by mutableStateOf(false)
    private val locationHistory = mutableListOf<Location>()
    private val gpxFileLogger = GpxFileLogger()

    var steps by mutableStateOf(0)
    private var initialSteps = -1
    var elapsedTime by mutableStateOf(0L)
    var mapFailedToLoad by mutableStateOf(false)

    fun updateLocation(location: Location) {
        currentLocation = location
        if (isRecording) {
            locationHistory.add(location)
        }
    }

    fun updateSteps(sensorSteps: Int) {
        if (isRecording) {
            if (initialSteps == -1) {
                initialSteps = sensorSteps
            }
            steps = sensorSteps - initialSteps
        }
    }

    fun toggleRecording(context: Context) {
        isRecording = !isRecording
        if (isRecording) {
            initialSteps = -1
            steps = 0
            elapsedTime = 0
            viewModelScope.launch {
                while (isRecording) {
                    delay(1000)
                    elapsedTime++
                }
            }
        } else {
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

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(4f)) {
                if (locationViewModel.mapFailedToLoad) {
                    MapFailurePlaceholder()
                } else {
                    MapViewContainer(locationViewModel = locationViewModel)
                }
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = { locationViewModel.toggleRecording(context) }) {
                    Text(text = if (locationViewModel.isRecording) "Stop Recording" else "Start Recording")
                }
                Button(onClick = { Toast.makeText(context, "View History Clicked", Toast.LENGTH_SHORT).show() }) {
                    Text(text = "View History")
                }
                Button(onClick = { Toast.makeText(context, "About Clicked", Toast.LENGTH_SHORT).show() }) {
                    Text(text = "About")
                }
            }
        }
        if (locationViewModel.isRecording) {
            FloatingStats(locationViewModel)
        }
    }
}

@Composable
fun MapFailurePlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.LightGray),
        contentAlignment = Alignment.Center
    ) {
        Text("地图加载失败", fontSize = 18.sp)
    }
}


@Composable
fun FloatingStats(locationViewModel: LocationViewModel) {
    val location = locationViewModel.currentLocation
    val elapsedTime = locationViewModel.elapsedTime

    val hours = elapsedTime / 3600
    val minutes = (elapsedTime % 3600) / 60
    val seconds = elapsedTime % 60

    Box(
        modifier = Modifier
            .padding(16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(8.dp)
    ) {
        Column {
            Text("Speed: ${location?.speed ?: 0.0f} m/s", color = Color.White, fontSize = 14.sp)
            Text("Lat: ${location?.latitude ?: 0.0}", color = Color.White, fontSize = 14.sp)
            Text("Lon: ${location?.longitude ?: 0.0}", color = Color.White, fontSize = 14.sp)
            Text("Alt: ${location?.altitude ?: 0.0}", color = Color.White, fontSize = 14.sp)
            Text("Steps: ${locationViewModel.steps}", color = Color.White, fontSize = 14.sp)
            Text(
                text = "Time: %02d:%02d:%02d".format(hours, minutes, seconds),
                color = Color.White, fontSize = 14.sp
            )
        }
    }
}


@Composable
fun MapViewContainer(locationViewModel: LocationViewModel) {
    val mapView = rememberMapViewWithLifecycle()
    val currentLocation = locationViewModel.currentLocation

    AndroidView({ mapView }) {
        try {
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
        } catch (e: Throwable) {
            Log.e("MapViewContainer", "Error while updating map", e)
            locationViewModel.mapFailedToLoad = true
        }
    }
}

@Composable
fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        MapView(context).apply {
            onCreate(Bundle()) // Important: Call onCreate here
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapView) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
        }
    }
    return mapView
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
