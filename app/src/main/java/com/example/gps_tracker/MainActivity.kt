
package com.example.gps_tracker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextField
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.gps_tracker.ui.theme.GPS_TrackerTheme
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
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
    private lateinit var locationManager: LocationManager
    private lateinit var gnssStatusCallback: GnssStatus.Callback

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            ) {
                startLocationUpdates()
                setupGnssStatusListener()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && permissions[Manifest.permission.ACTIVITY_RECOGNITION] == true) {
                setupStepCounter()
            }
        }

    private val selectDirectoryLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                val path = it.path ?: it.toString()
                locationViewModel.updateGpxPath(path)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        setContent {
            GPS_TrackerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (locationViewModel.currentModule) {
                        "menu" -> MainMenuScreen(locationViewModel)
                        "movement" -> GpsTrackerScreen(locationViewModel)
                        "driving" -> DrivingScreen(locationViewModel)
                        "environment" -> EnvironmentScreen(locationViewModel)
                        // "control" -> ControlScreen(locationViewModel)
                        else -> MainMenuScreen(locationViewModel)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) {
            startLocationUpdates()
            setupGnssStatusListener()
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

    private fun setupGnssStatusListener() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                gnssStatusCallback = object : GnssStatus.Callback() {
                    override fun onSatelliteStatusChanged(status: GnssStatus) {
                        super.onSatelliteStatusChanged(status)
                        locationViewModel.updateGnssStatus(status)
                    }
                }
                locationManager.registerGnssStatusCallback(gnssStatusCallback, Handler(mainLooper))
            }
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
        if (::gnssStatusCallback.isInitialized && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
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
    var satelliteCount by mutableStateOf(0)
    var signalQuality by mutableStateOf("N/A")
    
    var gpxFilePath by mutableStateOf("")
    var saveIntervalSeconds by mutableStateOf(300)  // 默认5分钟
    var showSettingsDialog by mutableStateOf(false)
    
    // 当前显示的功能模块
    var currentModule by mutableStateOf("menu")  // "menu", "movement", "environment", "control"
    
    // 环境模块 - 蓝牙传感器数据
    var temperature by mutableStateOf(0f)
    var humidity by mutableStateOf(0f)
    var oxygenLevel by mutableStateOf(0f)
    var coLevel by mutableStateOf(0f)
    var co2Level by mutableStateOf(0f)
    var bluetoothConnected by mutableStateOf(false)
    var bluetoothDevices by mutableStateOf<List<String>>(emptyList())
    var selectedDeviceName by mutableStateOf("")
    
    private var recordingStartTime: Long = 0L
    private var lastSaveTime: Long = 0L
    private var context: Context? = null
    private val waypoints = mutableListOf<Location>()

    fun updateLocation(location: Location) {
        currentLocation = location
        if (isRecording) {
            locationHistory.add(location)
            // 检查是否需要定期保存
            checkAndSaveIfNeeded()
        }
    }

    private fun checkAndSaveIfNeeded() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSaveTime >= saveIntervalSeconds * 1000 && locationHistory.isNotEmpty() && context != null) {
            lastSaveTime = currentTime
            viewModelScope.launch {
                gpxFileLogger.appendGpxFile(context!!, locationHistory, gpxFilePath, recordingStartTime)
                locationHistory.clear()
            }
        }
    }

    fun updateGnssStatus(status: GnssStatus) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            satelliteCount = status.satelliteCount
            var usedInFixCount = 0
            var totalCn0 = 0f
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) {
                    usedInFixCount++
                    totalCn0 += status.getCn0DbHz(i)
                }
            }
            val avgCn0 = if (usedInFixCount > 0) totalCn0 / usedInFixCount else 0f
            signalQuality = when {
                avgCn0 >= 40 -> "Excellent"
                avgCn0 >= 30 -> "Good"
                avgCn0 >= 20 -> "Fair"
                avgCn0 > 0 -> "Poor"
                else -> "No Signal"
            }
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
            this.context = context
            recordingStartTime = System.currentTimeMillis()
            lastSaveTime = recordingStartTime
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
            // 停止记录时保存剩余的数据
            if (locationHistory.isNotEmpty() && context != null){
                gpxFileLogger.writeGpxFile(this.context!!, locationHistory, gpxFilePath, recordingStartTime, waypoints)
                locationHistory.clear()
            }
            lastSaveTime = 0L
            this.context = null
            clearWaypoints()
        }
    }

    fun updateSettings(newPath: String, newInterval: Int) {
        gpxFilePath = newPath
        saveIntervalSeconds = newInterval
    }

    fun updateGpxPath(path: String) {
        gpxFilePath = path
    }

    fun addWaypoint(location: Location) {
        waypoints.add(location)
        context?.let {
            Toast.makeText(it, "Waypoint saved", Toast.LENGTH_SHORT).show()
        }
    }

    fun clearWaypoints() {
        waypoints.clear()
    }

    fun getWaypoints(): List<Location> {
        return waypoints.toList()
    }
}

@Composable
fun GpsTrackerScreen(locationViewModel: LocationViewModel) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(if (isLandscape) 1f else 4f)) {
                MapViewContainer(locationViewModel = locationViewModel)
                if (locationViewModel.mapFailedToLoad) {
                    MapFailurePlaceholder()
                }
            }
            
            if (isLandscape) {
                // 横屏：按钮和信息在右侧
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { locationViewModel.toggleRecording(context) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(35.dp)
                        ) {
                            Text(text = if (locationViewModel.isRecording) "Stop" else "Start", fontSize = 10.sp)
                        }
                        Button(
                            onClick = { Toast.makeText(context, "View History Clicked", Toast.LENGTH_SHORT).show() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(35.dp)
                        ) {
                            Text(text = "History", fontSize = 10.sp)
                        }
                        Button(
                            onClick = { 
                                locationViewModel.currentLocation?.let {
                                    locationViewModel.addWaypoint(it)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(35.dp)
                        ) {
                            Text(text = "Waypoint", fontSize = 10.sp)
                        }
                        Button(
                            onClick = { locationViewModel.showSettingsDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(35.dp)
                        ) {
                            Text(text = "Settings", fontSize = 10.sp)
                        }
                    }
                    
                    // 信息显示
                    if (locationViewModel.gpxFilePath.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(Color.LightGray)
                                .padding(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        ) {
                            Text(
                                text = "GPX: ${locationViewModel.gpxFilePath.takeLast(20)}",
                                fontSize = 10.sp,
                                color = Color.DarkGray
                            )
                        }
                    }
                }
            } else {
                // 竖屏：按钮在下方
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { locationViewModel.toggleRecording(context) },
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                        ) {
                            Text(text = if (locationViewModel.isRecording) "Stop Recording" else "Start Recording", fontSize = 10.sp)
                        }
                        Button(
                            onClick = { Toast.makeText(context, "View History Clicked", Toast.LENGTH_SHORT).show() },
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                        ) {
                            Text(text = "View History", fontSize = 10.sp)
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { 
                                locationViewModel.currentLocation?.let {
                                    locationViewModel.addWaypoint(it)
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                        ) {
                            Text(text = "Add Waypoint", fontSize = 10.sp)
                        }
                        Button(
                            onClick = { locationViewModel.showSettingsDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                        ) {
                            Text(text = "Settings", fontSize = 10.sp)
                        }
                    }
                    
                    // Display GPX file path at the bottom
                    if (locationViewModel.gpxFilePath.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.LightGray)
                                .padding(6.dp)
                        ) {
                            Text(
                                text = "GPX Path: ${locationViewModel.gpxFilePath}",
                                fontSize = 10.sp,
                                color = Color.DarkGray,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
        FloatingStats(locationViewModel)

        // 返回菜单按钮（右上方）
        Button(
            onClick = { locationViewModel.currentModule = "menu" },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            Text("×", fontSize = 16.sp)
        }

        // Settings Dialog
        if (locationViewModel.showSettingsDialog) {
            SettingsDialog(locationViewModel)
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
        Text("Map failed to load", fontSize = 18.sp)
    }
}

@Composable
fun SettingsDialog(locationViewModel: LocationViewModel) {
    var pathInput by remember { mutableStateOf(locationViewModel.gpxFilePath) }
    var intervalInput by remember { mutableStateOf(locationViewModel.saveIntervalSeconds.toString()) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = { locationViewModel.showSettingsDialog = false },
        title = { Text("Settings") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text("GPX File Storage Path:", fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (pathInput.isEmpty()) "No folder selected" else pathInput.substringAfterLast('/'),
                        fontSize = 14.sp,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.LightGray)
                            .padding(12.dp)
                    )
                    Button(onClick = {
                        val selectDirIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        (context as? ComponentActivity)?.let {
                            val launcher = it.activityResultRegistry.register(
                                "select_directory",
                                ActivityResultContracts.OpenDocumentTree()
                            ) { uri ->
                                uri?.let { selectedUri ->
                                    val path = selectedUri.path ?: selectedUri.toString()
                                    pathInput = path
                                }
                            }
                            launcher.launch(null)
                        }
                    }) {
                        Text("Browse")
                    }
                }

                Text("Save Interval (seconds):", fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                TextField(
                    value = intervalInput,
                    onValueChange = { intervalInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g., 300") }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val interval = intervalInput.toIntOrNull() ?: locationViewModel.saveIntervalSeconds
                    val path = pathInput.ifEmpty { locationViewModel.gpxFilePath }
                    locationViewModel.updateSettings(path, interval)
                    locationViewModel.showSettingsDialog = false
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Button(
                onClick = { locationViewModel.showSettingsDialog = false }
            ) {
                Text("Cancel")
            }
        }
    )
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
            Text("Satellites: ${locationViewModel.satelliteCount}", color = Color.White, fontSize = 14.sp)
            Text("Signal: ${locationViewModel.signalQuality}", color = Color.White, fontSize = 14.sp)
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

    AndroidView({ mapView }) { view ->
        view.getMapAsync { googleMap ->
            try {
                if (ContextCompat.checkSelfPermission(view.context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    googleMap.isMyLocationEnabled = true
                }
                googleMap.uiSettings.isMyLocationButtonEnabled = true
                currentLocation?.let {
                    val latLng = LatLng(it.latitude, it.longitude)
                    googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                }
            } catch (e: Exception) {
                Log.e("MapViewContainer", "Error while updating map", e)
                locationViewModel.mapFailedToLoad = true
            }
        }
    }
}

@Composable
fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        MapView(context).apply {
            onCreate(Bundle())
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapView) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(Bundle())
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
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
    fun writeGpxFile(context: Context, locations: List<Location>, customPath: String = "", startTimeMillis: Long = 0L, waypoints: List<Location> = emptyList()) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        val fileNameDateFormat = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US)
        
        val gpxContent = buildString {
            append("<?xml version='''1.0''' encoding='''UTF-8'''?>")
            append("<gpx version='''1.1''' creator='''GPS Tracker'''>")
            
            // 添加航点
            waypoints.forEach { waypoint ->
                append("<wpt lat='''${waypoint.latitude}''' lon='''${waypoint.longitude}'''>")
                append("<ele>${waypoint.altitude}</ele>")
                append("<time>${dateFormat.format(Date(waypoint.time))}</time>")
                append("<name>Waypoint</name>")
                append("</wpt>")
            }
            
            // 添加轨迹
            append("<trk><name>Track</name><trkseg>")
            locations.forEach {
                append("<trkpt lat='''${it.latitude}''' lon='''${it.longitude}'''>")
                append("<ele>${it.altitude}</ele>")
                append("<time>${dateFormat.format(Date(it.time))}</time>")
                append("</trkpt>")
            }
            append("</trkseg></trk></gpx>")
        }
        try {
            val fileName = if (startTimeMillis > 0L) {
                "${fileNameDateFormat.format(Date(startTimeMillis))}.gpx"
            } else {
                "track_${System.currentTimeMillis()}.gpx"
            }
            
            val targetDir = if (customPath.isNotEmpty()) {
                File(customPath)
            } else {
                context.getExternalFilesDir(null) ?: context.filesDir
            }
            
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            
            val file = File(targetDir, fileName)
            FileOutputStream(file).use {
                it.write(gpxContent.toByteArray())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun appendGpxFile(context: Context, locations: List<Location>, customPath: String = "", startTimeMillis: Long = 0L) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        val fileNameDateFormat = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US)
        
        // 生成轨迹点内容
        val trackpointsContent = buildString {
            locations.forEach {
                append("<trkpt lat='''${it.latitude}''' lon='''${it.longitude}'''>")
                append("<ele>${it.altitude}</ele>")
                append("<time>${dateFormat.format(Date(it.time))}</time>")
                append("</trkpt>")
            }
        }
        
        try {
            val fileName = if (startTimeMillis > 0L) {
                "${fileNameDateFormat.format(Date(startTimeMillis))}.gpx"
            } else {
                "track_${System.currentTimeMillis()}.gpx"
            }
            
            val targetDir = if (customPath.isNotEmpty()) {
                File(customPath)
            } else {
                context.getExternalFilesDir(null) ?: context.filesDir
            }
            
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            
            val file = File(targetDir, fileName)
            
            // 如果文件存在，进行追加写入；否则创建新文件
            if (file.exists()) {
                // 读取现有文件，在</trkseg>之前插入新的轨迹点
                val existingContent = file.readText()
                val updatedContent = existingContent.replace("</trkseg>", "$trackpointsContent</trkseg>")
                file.writeText(updatedContent)
            } else {
                // 创建新文件
                val gpxContent = buildString {
                    append("<?xml version='''1.0''' encoding='''UTF-8'''?>")
                    append("<gpx version='''1.1''' creator='''GPS Tracker'''>")
                    append("<trk><name>Track</name><trkseg>")
                    append(trackpointsContent)
                    append("</trkseg></trk></gpx>")
                }
                FileOutputStream(file).use {
                    it.write(gpxContent.toByteArray())
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

@Composable
fun MainMenuScreen(locationViewModel: LocationViewModel) {
    val menuItems = listOf(
        Pair("运动", "sports"),
        Pair("环境", "environment"),
        Pair("中控", "control"),
        Pair("设置", "settings"),
        Pair("驾驶", "driving"),
        Pair("XX2", "xx2"),
        Pair("XX3", "xx3"),
        Pair("XX4", "xx4")
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("GPS Tracker", fontSize = 16.sp, modifier = Modifier.padding(bottom = 2.dp))
            
            // 4行2列的菜单按钮
            for (row in 0 until 4) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (col in 0 until 2) {
                        val index = row * 2 + col
                        if (index < menuItems.size) {
                            val (label, module) = menuItems[index]
                            MenuButton(
                                label = label,
                                modifier = Modifier
                                    .weight(1f),
                                onClick = {
                                    when (module) {
                                        "sports" -> locationViewModel.currentModule = "movement"
                                        "environment" -> locationViewModel.currentModule = "environment"
                                        "driving" -> locationViewModel.currentModule = "driving"
                                        "settings" -> {
                                            locationViewModel.showSettingsDialog = true
                                            // 保持在菜单页面，设置对话框弹出
                                        }
                                        else -> {
                                            // 其他功能暂未开发
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MenuButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        contentPadding = PaddingValues(2.dp)
    ) {
        Text(label, fontSize = 11.sp)
    }
}

@Composable
fun DrivingScreen(locationViewModel: LocationViewModel) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val speed = locationViewModel.currentLocation?.speed ?: 0f
    var rpm by remember { mutableStateOf(0f) }

    // 模拟转速随速度变化
    rpm = (speed * 50).coerceIn(0f, 7000f)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            if (isLandscape) {
                // 横屏：仪表盘横排
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 速度仪表
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("速度", fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
                        Gauge(
                            value = speed,
                            maxValue = 200f,
                            unit = "km/h",
                            modifier = Modifier
                                .size(120.dp)
                        )
                    }

                    // 转速仪表
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("转速", fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
                        Gauge(
                            value = rpm,
                            maxValue = 7000f,
                            unit = "RPM",
                            modifier = Modifier
                                .size(120.dp)
                        )
                    }

                    // 信息标签
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(Color.LightGray)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("车辆信息", fontSize = 12.sp)
                        InfoTag("水温", "85°C")
                        InfoTag("油量", "75%")
                        InfoTag("电量", "90%")
                    }
                }
            } else {
                // 竖屏：仪表盘排成2列
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 速度仪表
                    Column(
                        modifier = Modifier
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("速度", fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
                        Gauge(
                            value = speed,
                            maxValue = 200f,
                            unit = "km/h",
                            modifier = Modifier
                                .size(130.dp)
                        )
                    }

                    // 转速仪表
                    Column(
                        modifier = Modifier
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("转速", fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
                        Gauge(
                            value = rpm,
                            maxValue = 7000f,
                            unit = "RPM",
                            modifier = Modifier
                                .size(130.dp)
                        )
                    }
                }

                // 信息标签区域
                Column(
                    modifier = Modifier
                        .weight(0.8f)
                        .fillMaxWidth()
                        .background(Color.LightGray)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("车辆信息", fontSize = 16.sp, modifier = Modifier.padding(bottom = 4.dp))
                    InfoTag("水温", "85°C")
                    InfoTag("油量", "75%")
                    InfoTag("电量", "90%")
                }
            }
        }

        // 返回菜单按钮（右上方）
        Button(
            onClick = { locationViewModel.currentModule = "menu" },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            Text("×", fontSize = 16.sp)
        }
    }
}

@Composable
fun Gauge(
    value: Float,
    maxValue: Float,
    unit: String,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerX = size.width / 2
            val centerY = size.height / 2
            val radius = size.minDimension / 2 - 20

            // 绘制背景圆
            drawCircle(
                color = Color.White,
                radius = radius,
                center = androidx.compose.ui.geometry.Offset(centerX, centerY)
            )

            // 绘制边框
            drawCircle(
                color = Color.Black,
                radius = radius,
                center = androidx.compose.ui.geometry.Offset(centerX, centerY),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
            )

            // 绘制刻度
            for (i in 0..10) {
                val angle = Math.PI / 6 + (i * Math.PI * 0.8 / 10)
                val x1 = centerX + (radius * 0.9 * kotlin.math.cos(angle)).toFloat()
                val y1 = centerY + (radius * 0.9 * kotlin.math.sin(angle)).toFloat()
                val x2 = centerX + (radius * kotlin.math.cos(angle)).toFloat()
                val y2 = centerY + (radius * kotlin.math.sin(angle)).toFloat()

                drawLine(
                    color = Color.Black,
                    start = androidx.compose.ui.geometry.Offset(x1, y1),
                    end = androidx.compose.ui.geometry.Offset(x2, y2),
                    strokeWidth = 2f
                )
            }

            // 绘制指针
            val ratio = (value / maxValue).coerceIn(0f, 1f)
            val angle = Math.PI / 6 + (ratio * Math.PI * 0.8)
            val pointerX = centerX + (radius * 0.7 * kotlin.math.cos(angle)).toFloat()
            val pointerY = centerY + (radius * 0.7 * kotlin.math.sin(angle)).toFloat()

            drawLine(
                color = Color.Red,
                start = androidx.compose.ui.geometry.Offset(centerX, centerY),
                end = androidx.compose.ui.geometry.Offset(pointerX, pointerY),
                strokeWidth = 3f
            )

            // 绘制中心圆
            drawCircle(
                color = Color.Black,
                radius = 8f,
                center = androidx.compose.ui.geometry.Offset(centerX, centerY)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = String.format("%.1f", value),
                fontSize = 20.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(text = unit, fontSize = 12.sp)
        }
    }
}

@Composable
fun InfoTag(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp)
        Text(value, fontSize = 14.sp, color = Color.Blue)
    }
}

@Composable
fun EnvironmentScreen(locationViewModel: LocationViewModel) {
    val context = LocalContext.current
    var isScanning by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("环境监测", fontSize = 18.sp, modifier = Modifier.padding(bottom = 8.dp))

            // 蓝牙连接状态
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (locationViewModel.bluetoothConnected) Color(0xFF4CAF50) else Color.LightGray)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (locationViewModel.bluetoothConnected) "已连接: ${locationViewModel.selectedDeviceName}" else "未连接蓝牙",
                    fontSize = 14.sp,
                    color = Color.White
                )
            }

            // 扫描和连接按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        isScanning = true
                        // 模拟扫描蓝牙设备
                        locationViewModel.bluetoothDevices = listOf(
                            "传感器-001",
                            "传感器-002",
                            "传感器-003"
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    enabled = !isScanning
                ) {
                    Text("扫描设备", fontSize = 11.sp)
                }
                
                Button(
                    onClick = { isScanning = false },
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    enabled = isScanning
                ) {
                    Text("停止扫描", fontSize = 11.sp)
                }
            }

            // 设备列表
            if (locationViewModel.bluetoothDevices.isNotEmpty()) {
                Text("可用设备:", fontSize = 12.sp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF5F5F5))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    locationViewModel.bluetoothDevices.forEach { device ->
                        Button(
                            onClick = {
                                locationViewModel.selectedDeviceName = device
                                locationViewModel.bluetoothConnected = true
                                // 模拟连接后接收数据
                                locationViewModel.temperature = 22.5f
                                locationViewModel.humidity = 65f
                                locationViewModel.oxygenLevel = 20.9f
                                locationViewModel.coLevel = 8.5f
                                locationViewModel.co2Level = 410f
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                        ) {
                            Text(device, fontSize = 11.sp)
                        }
                    }
                }
            }

            // 传感器数据显示
            if (locationViewModel.bluetoothConnected) {
                Text("传感器数据:", fontSize = 12.sp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF5F5F5))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SensorData("温度", "${String.format("%.1f", locationViewModel.temperature)}°C", Color(0xFFFF9800))
                    SensorData("湿度", "${String.format("%.0f", locationViewModel.humidity)}%", Color(0xFF2196F3))
                    SensorData("氧气", "${String.format("%.1f", locationViewModel.oxygenLevel)}%", Color(0xFF4CAF50))
                    SensorData("一氧化碳", "${String.format("%.1f", locationViewModel.coLevel)} ppm", Color(0xFFF44336))
                    SensorData("二氧化碳", "${String.format("%.0f", locationViewModel.co2Level)} ppm", Color(0xFF9C27B0))
                }
            }
        }

        // 返回菜单按钮（右上方）
        Button(
            onClick = { locationViewModel.currentModule = "menu" },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            Text("×", fontSize = 16.sp)
        }
    }
}

@Composable
fun SensorData(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.2f))
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp)
        Text(value, fontSize = 12.sp, color = color)
    }
}
