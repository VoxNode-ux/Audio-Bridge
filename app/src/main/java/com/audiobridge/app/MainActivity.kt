package com.audiobridge.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.audiobridge.app.audio.AudioStreamService
import com.audiobridge.app.ui.MainScreen
import com.audiobridge.app.ui.theme.AudioBridgeTheme
import com.audiobridge.app.util.ConnectionState
import com.audiobridge.app.util.DeviceRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // Compose-observable holder: a plain var wouldn't trigger recomposition/LaunchedEffect
    // when the service connects, since Compose has no way to know it changed.
    private val audioServiceState = mutableStateOf<AudioStreamService?>(null)
    private val audioService: AudioStreamService? get() = audioServiceState.value
    private var isServiceBound = false

    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as AudioStreamService.LocalBinder
            audioServiceState.value = localBinder.getService()
            isServiceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioServiceState.value = null
            isServiceBound = false
        }
    }

    // Permission launcher for the runtime permissions this app actually needs.
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Results observed via hasRequiredPermissions() when action is retried */ }

    // MediaProjection consent dialog — required by Android for any system-audio capture.
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            pendingSendTarget?.let { (host, port) ->
                val service = audioService ?: return@let
                val config = viewModel.uiState.value.config
                val bluetoothDevice = if (config.transport == com.audiobridge.app.util.TransportMedium.BLUETOOTH) {
                    resolveBluetoothDeviceByAddress(host)
                } else null
                service.startSending(
                    mediaProjectionManager,
                    result.resultCode,
                    result.data!!,
                    host,
                    port,
                    config.transport,
                    config.protocol,
                    config.pcmFormat,
                    bluetoothDevice
                )
            }
        }
        pendingSendTarget = null
    }

    private var pendingSendTarget: Pair<String, Int>? = null

    @android.annotation.SuppressLint("MissingPermission")
    private fun resolveBluetoothDeviceByAddress(address: String): android.bluetooth.BluetoothDevice? {
        val adapter = (getSystemService(android.bluetooth.BluetoothManager::class.java))?.adapter
        return adapter?.bondedDevices?.firstOrNull { it.address == address }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val serviceIntent = Intent(this, AudioStreamService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        requestNeededPermissions()

        setContent {
            AudioBridgeTheme {
                val uiState by viewModel.uiState.collectAsState()
                val currentService by audioServiceState

                LaunchedEffect(currentService) {
                    currentService?.let { service ->
                        launch {
                            service.connectionState.collect { state ->
                                viewModel.updateConnectionState(state)
                            }
                        }
                        launch {
                            service.streamStats.collect { stats ->
                                viewModel.updateStreamStats(stats)
                            }
                        }
                    }
                }

                // Auto-reconnect: if enabled and we have a remembered device, start
                // automatically on launch rather than waiting for a manual tap.
                //
                // Keyed also on uiState.config.lastDeviceHost (not just autoReconnectEnabled
                // and currentService): the persisted config loads asynchronously from
                // DataStore, and in-process service binding can resolve first. Without this
                // key, this effect could run its one-shot check against the *default* empty
                // config before the real lastDeviceHost value ever arrived, silently
                // skipping auto-reconnect on launch with no way to retry.
                LaunchedEffect(uiState.autoReconnectEnabled, currentService, uiState.config.lastDeviceHost) {
                    if (uiState.autoReconnectEnabled &&
                        uiState.config.lastDeviceHost.isNotEmpty() &&
                        uiState.connectionState == ConnectionState.IDLE &&
                        currentService != null
                    ) {
                        handleStartStreaming(uiState.config.role, uiState.config.lastDeviceHost, uiState.config.lastDevicePort)
                    }
                }

                MainScreen(
                    uiState = uiState,
                    onRoleChange = viewModel::setRole,
                    onTransportChange = viewModel::setTransport,
                    onProtocolChange = viewModel::setProtocol,
                    onPcmFormatChange = viewModel::setPcmFormat,
                    onVolumeChange = { volume ->
                        viewModel.setVolume(volume)
                        audioService?.setVolume(volume)
                    },
                    onSafetyBufferChange = { ms ->
                        viewModel.setSafetyBuffer(ms)
                        audioService?.setSafetyBuffer(ms)
                    },
                    onAutoReconnectChange = viewModel::setAutoReconnect,
                    onStartDiscovery = {
                        viewModel.registerSelf(Build.MODEL ?: "Device")
                        viewModel.startDiscovery()
                    },
                    onSelectDevice = viewModel::selectDevice,
                    onSelectWifiDirectPeer = viewModel::connectToWifiDirectPeer,
                    onSelectBluetoothDevice = viewModel::selectBluetoothDevice,
                    onStartStreaming = {
                        handleStartStreaming(
                            uiState.config.role,
                            uiState.config.lastDeviceHost,
                            uiState.config.lastDevicePort
                        )
                    },
                    onStopStreaming = {
                        // stopStreaming() runs synchronously in-process (this is a bound
                        // Service, not IPC) and can end up calling
                        // AudioPlaybackEngine.release(), which — if the playback thread
                        // doesn't notice isRunning=false promptly — blocks on join(300),
                        // possibly interrupt(), then join(200) again: up to ~500ms.
                        // onStopStreaming is invoked directly from a Compose click
                        // handler on the main thread, so calling it inline here could
                        // freeze the UI (dropped frames, an unresponsive "Stop" tap) for
                        // that whole window. Dispatching to Dispatchers.IO keeps the
                        // teardown off the main thread; connectionState (already
                        // collected via the Flow above) is what drives the UI update
                        // once IDLE lands, so no UI responsiveness is lost by not
                        // blocking here.
                        lifecycleScope.launch(Dispatchers.IO) {
                            audioService?.stopStreaming()
                        }
                        viewModel.stopDiscovery()
                    }
                )
            }
        }
    }

    private fun handleStartStreaming(role: DeviceRole, host: String, port: Int) {
        val service = audioService ?: return
        when (role) {
            DeviceRole.SENDER -> {
                pendingSendTarget = host to port
                // Triggers the mandatory system consent dialog for capturing playback audio.
                mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            }
            DeviceRole.RECEIVER -> {
                val config = viewModel.uiState.value.config
                service.startReceiving(
                    port = if (port != 0) port else defaultPortFor(config.protocol),
                    transportMedium = config.transport,
                    protocol = config.protocol,
                    format = config.pcmFormat,
                    initialVolume = config.volume,
                    safetyBufferMs = config.safetyBufferMs
                )
            }
        }
    }

    private fun defaultPortFor(protocol: com.audiobridge.app.util.SocketProtocol): Int =
        when (protocol) {
            com.audiobridge.app.util.SocketProtocol.UDP -> com.audiobridge.app.network.UDP_DEFAULT_PORT
            com.audiobridge.app.util.SocketProtocol.TCP -> com.audiobridge.app.network.TCP_DEFAULT_PORT
        }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            // Pre-Android 13 has no NEARBY_WIFI_DEVICES permission — WifiP2pManager peer
            // discovery on those OS versions needs ACCESS_FINE_LOCATION instead, or
            // discoverPeers() silently fails with a SecurityException.
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    override fun onDestroy() {
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        super.onDestroy()
    }
}

