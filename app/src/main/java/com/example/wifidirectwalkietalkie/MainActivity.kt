package com.example.wifidirectwalkietalkie

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val walkieTalkieServiceState = mutableStateOf<WalkieTalkieService?>(null)
    private val isBoundState = mutableStateOf(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as WalkieTalkieService.LocalBinder
            walkieTalkieServiceState.value = binder.getService()
            isBoundState.value = true
            checkPermissionsAndDiscover()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBoundState.value = false
            walkieTalkieServiceState.value = null
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val nearbyGranted = permissions[Manifest.permission.NEARBY_WIFI_DEVICES] ?: false
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else true

        if (nearbyGranted && audioGranted) {
            startWalkieTalkieService()
            walkieTalkieServiceState.value?.wifiDirectManager?.discoverPeers()
        }
    }

    private fun startWalkieTalkieService() {
        val intent = Intent(this, WalkieTalkieService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Bind the service, but do not start it as a foreground service yet.
        // It will be started as a foreground service after permissions are granted.
        val intent = Intent(this, WalkieTalkieService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)

        setContent {
            val darkColors = darkColorScheme(
                background = Color.Black,
                surface = Color.Black,
                onBackground = Color.White,
                onSurface = Color.White,
                primary = Color.Black,
                onPrimary = Color.White
            )
            MaterialTheme(colorScheme = darkColors) {
                // Pass the service instance to UI. Since it might be null initially, we wait.
                val isBound = isBoundState.value
                val service = walkieTalkieServiceState.value
                if (isBound && service != null) {
                    WalkieTalkieApp(service)
                } else {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("Starting Walkie-Talkie Service...", color = Color.White)
                        }
                    }
                }
            }
        }
    }

    private fun checkPermissionsAndDiscover() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionsToRequest = mutableListOf<String>()

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }

            if (permissionsToRequest.isEmpty()) {
                startWalkieTalkieService()
                walkieTalkieServiceState.value?.wifiDirectManager?.discoverPeers()
            } else {
                requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
            }
        } else {
            // Older Android versions
            startWalkieTalkieService()
            walkieTalkieServiceState.value?.wifiDirectManager?.discoverPeers()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBoundState.value) {
            unbindService(connection)
            isBoundState.value = false
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    fun WalkieTalkieApp(service: WalkieTalkieService) {
        val peers by service.wifiDirectManager.peers.collectAsState()
        val isConnected by service.wifiDirectManager.isConnected.collectAsState()
        val isReceiving by service.audioStreamer.isReceiving.collectAsState()

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Wi-Fi Direct Walkie-Talkie",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                if (isConnected) {
                    Text("Connected!", color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.weight(1f))
                    
                    // Push to Talk Button
                    var isPressed by remember { mutableStateOf(false) }
                    val buttonColor = if (isReceiving) Color.Red else if (isPressed) Color.Blue else Color.Black
                    Button(
                        onClick = { },
                        enabled = !isReceiving,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = buttonColor,
                            disabledContainerColor = Color.Red,
                            disabledContentColor = Color.White
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White),
                        modifier = Modifier
                            .size(200.dp)
                            .pointerInteropFilter { motionEvent ->
                                if (isReceiving) return@pointerInteropFilter false
                                when (motionEvent.action) {
                                    MotionEvent.ACTION_DOWN -> {
                                        isPressed = true
                                        service.audioStreamer.startRecording()
                                        true
                                    }
                                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                        isPressed = false
                                        service.audioStreamer.stopRecording()
                                        true
                                    }
                                    else -> false
                                }
                            },
                        shape = androidx.compose.foundation.shape.CircleShape
                    ) {
                        Text(if (isReceiving) "Receiving..." else "Hold to Talk", style = MaterialTheme.typography.titleLarge)
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Button(
                        onClick = { service.wifiDirectManager.disconnect() },
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White)
                    ) {
                        Text("Disconnect")
                    }

                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Available Devices", style = MaterialTheme.typography.titleMedium)
                        Text("Scanning...", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }

                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(peers) { peer ->
                            PeerListItem(peer) {
                                service.wifiDirectManager.connect(it)
                            }
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Composable
    fun PeerListItem(peer: WifiP2pDevice, onConnect: (WifiP2pDevice) -> Unit) {
        Card(
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .clickable { onConnect(peer) }
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = peer.deviceName, style = MaterialTheme.typography.bodyLarge)
                Text(text = peer.deviceAddress, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
