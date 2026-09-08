package com.audiobridge.app

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audiobridge.app.discovery.BluetoothPairedDevicesManager
import com.audiobridge.app.discovery.NsdDiscoveryManager
import com.audiobridge.app.discovery.PairedBluetoothDevice
import com.audiobridge.app.discovery.WifiDirectConnectionResult
import com.audiobridge.app.discovery.WifiDirectManager
import com.audiobridge.app.discovery.WifiDirectPeer
import com.audiobridge.app.network.TCP_DEFAULT_PORT
import com.audiobridge.app.network.UDP_DEFAULT_PORT
import com.audiobridge.app.util.ConnectionConfig
import com.audiobridge.app.util.ConnectionState
import com.audiobridge.app.util.DeviceRole
import com.audiobridge.app.util.DiscoveredDevice
import com.audiobridge.app.util.PcmFormat
import com.audiobridge.app.util.PreferencesManager
import com.audiobridge.app.util.SocketProtocol
import com.audiobridge.app.util.StreamStats
import com.audiobridge.app.util.TransportMedium
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class MainUiState(
    val config: ConnectionConfig = ConnectionConfig(),
    val connectionState: ConnectionState = ConnectionState.IDLE,
    val streamStats: StreamStats = StreamStats(),
    val discoveredDevices: List<DiscoveredDevice> = emptyList(),
    val wifiDirectPeers: List<WifiDirectPeer> = emptyList(),
    val pairedBluetoothDevices: List<PairedBluetoothDevice> = emptyList(),
    val selectedBluetoothDeviceAddress: String = "",
    val isDiscovering: Boolean = false,
    val autoReconnectEnabled: Boolean = true,
    val errorMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefsManager = PreferencesManager(application)
    private val discoveryManager = NsdDiscoveryManager(application)

    private val wifiP2pManager = application.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val wifiP2pChannel = wifiP2pManager?.initialize(application, application.mainLooper) {
        _uiState.value = _uiState.value.copy(
            errorMessage = "WiFi Direct lost its connection to the system service — restart the app to use it again."
        )
    }
    private val wifiDirectManager: WifiDirectManager? =
        if (wifiP2pManager != null && wifiP2pChannel != null) {
            WifiDirectManager(application, wifiP2pManager, wifiP2pChannel)
        } else null
    private var unregisterWifiDirectReceiver: (() -> Unit)? = null
    private var wifiDirectDiscoveryJob: Job? = null
    private var nsdDiscoveryJob: Job? = null

    private var wifiDirectConnectionObserverJob: Job? = null
    private var wifiDirectResolveJob: Job? = null

    private val bluetoothManager = BluetoothPairedDevicesManager(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var pendingConfigWrites = 0

    init {
        viewModelScope.launch {
            prefsManager.configFlow.collect { config ->
                if (pendingConfigWrites == 0) {
                    _uiState.value = _uiState.value.copy(config = config)
                }
            }
        }
        viewModelScope.launch {
            prefsManager.autoReconnectEnabled.collect { enabled ->
                _uiState.value = _uiState.value.copy(autoReconnectEnabled = enabled)
            }
        }
    }

    fun setRole(role: DeviceRole) {
        updateConfig(_uiState.value.config.copy(role = role))
    }

    fun setTransport(transport: TransportMedium) {
        // Without this, switching transports mid-session (e.g. WiFi Direct -> Hotspot)
        // left the OLD transport's discovery job, broadcast receiver, and connection
        // observer all still running in the background — competing with whatever the
        // newly-selected transport tries to start next. That's the most likely cause
        // of "switched transports and now nothing detects" — stale WiFi Direct state
        // was still holding onto the WifiP2pManager broadcast receiver and/or socket
        // resources the new Hotspot/mDNS discovery then couldn't cleanly claim.
        stopDiscovery()
        unregisterSelf()

        val current = _uiState.value.config
        val safeFormat = if (transport == TransportMedium.BLUETOOTH && current.pcmFormat == PcmFormat.PCM_32_48) {
            PcmFormat.PCM_16_48
        } else {
            current.pcmFormat
        }
        updateConfig(current.copy(transport = transport, pcmFormat = safeFormat))
    }

    fun setProtocol(protocol: SocketProtocol) {
        updateConfig(_uiState.value.config.copy(protocol = protocol))
    }

    fun setPcmFormat(format: PcmFormat) {
        updateConfig(_uiState.value.config.copy(pcmFormat = format))
    }

    fun setVolume(volume: Float) {
        updateConfig(_uiState.value.config.copy(volume = volume))
    }

    fun setSafetyBuffer(ms: Int) {
        updateConfig(_uiState.value.config.copy(safetyBufferMs = ms))
    }

    fun setAutoReconnect(enabled: Boolean) {
        viewModelScope.launch { prefsManager.setAutoReconnect(enabled) }
    }

    fun updateConnectionState(state: ConnectionState) {
        _uiState.value = _uiState.value.copy(connectionState = state)
    }

    fun updateStreamStats(stats: StreamStats) {
        _uiState.value = _uiState.value.copy(streamStats = stats)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private fun updateConfig(newConfig: ConnectionConfig) {
        _uiState.value = _uiState.value.copy(config = newConfig)
        pendingConfigWrites++
        viewModelScope.launch {
            try {
                prefsManager.saveConfig(newConfig)
            } finally {
                pendingConfigWrites--
            }
        }
    }

    fun startDiscovery() {
        when (_uiState.value.config.transport) {
            TransportMedium.HOTSPOT_WIFI -> startNsdDiscovery()
            TransportMedium.WIFI_DIRECT -> startWifiDirectDiscovery()
            TransportMedium.BLUETOOTH -> loadPairedBluetoothDevices()
        }
    }

    private fun loadPairedBluetoothDevices() {
        if (!bluetoothManager.isBluetoothAvailable()) {
            _uiState.value = _uiState.value.copy(errorMessage = "No Bluetooth adapter available on this device.")
            return
        }
        if (!bluetoothManager.isBluetoothEnabled()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Turn on Bluetooth to see paired devices.")
            return
        }
        _uiState.value = _uiState.value.copy(pairedBluetoothDevices = bluetoothManager.getPairedDevices())
    }

    fun selectBluetoothDevice(device: PairedBluetoothDevice) {
        updateConfig(
            _uiState.value.config.copy(
                lastDeviceName = device.name,
                lastDeviceHost = device.address,
                lastDevicePort = 0
            )
        )
        _uiState.value = _uiState.value.copy(selectedBluetoothDeviceAddress = device.address)
    }

    private fun startNsdDiscovery() {
        nsdDiscoveryJob?.cancel()
        discoveryManager.stopDiscovery()

        _uiState.value = _uiState.value.copy(isDiscovering = true, discoveredDevices = emptyList())
        val myRole = _uiState.value.config.role
        val wantedRole = if (myRole == DeviceRole.SENDER) DeviceRole.RECEIVER else DeviceRole.SENDER

        nsdDiscoveryJob = viewModelScope.launch {
            discoveryManager.discoverServices()
                .catch { e ->
                    _uiState.value = _uiState.value.copy(
                        isDiscovering = false,
                        errorMessage = "Discovery failed: ${e.message}"
                    )
                }
                .collect { device ->
                    if (device.role == wantedRole) {
                        val current = _uiState.value.discoveredDevices
                        if (current.none { it.host == device.host && it.port == device.port }) {
                            _uiState.value = _uiState.value.copy(
                                discoveredDevices = current + device
                            )
                        }
                    }
                }
        }
    }

    private fun hasWifiDirectDiscoveryPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(getApplication(), permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun startWifiDirectDiscovery() {
        val wdManager = wifiDirectManager
        if (wdManager == null) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "WiFi Direct is not available on this device."
            )
            return
        }
        if (!hasWifiDirectDiscoveryPermission()) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "WiFi Direct needs the nearby devices/location permission to scan for peers."
            )
            return
        }
        wifiDirectDiscoveryJob?.cancel()
        unregisterWifiDirectReceiver?.invoke()
        unregisterWifiDirectReceiver = null

        _uiState.value = _uiState.value.copy(isDiscovering = true, wifiDirectPeers = emptyList())
        unregisterWifiDirectReceiver = wdManager.registerReceiver()

        wifiDirectDiscoveryJob = viewModelScope.launch {
            try {
                wdManager.discoverPeers()
                    .catch { e ->
                        _uiState.value = _uiState.value.copy(
                            isDiscovering = false,
                            errorMessage = "WiFi Direct discovery failed: ${e.message}"
                        )
                    }
                    .collect { peers ->
                        _uiState.value = _uiState.value.copy(wifiDirectPeers = peers)
                    }
            } catch (e: SecurityException) {
                _uiState.value = _uiState.value.copy(
                    isDiscovering = false,
                    errorMessage = "WiFi Direct permission was revoked: ${e.message}"
                )
            }
        }

        startWifiDirectConnectionObserver(wdManager)
    }

    private fun startWifiDirectConnectionObserver(wdManager: WifiDirectManager) {
        wifiDirectConnectionObserverJob?.cancel()
        wifiDirectConnectionObserverJob = viewModelScope.launch {
            wdManager.observeConnectionInfo()
                .catch { /* best-effort — a failure here just means auto-resolution doesn't fire */ }
                .collect { result ->
                    wifiDirectResolveJob?.cancel()
                    wifiDirectResolveJob = viewModelScope.launch {
                        resolveWifiDirectHost(wdManager, result)
                    }
                }
        }
    }

    private suspend fun resolveWifiDirectHost(wdManager: WifiDirectManager, result: WifiDirectConnectionResult) {
        val port = when (_uiState.value.config.protocol) {
            SocketProtocol.UDP -> UDP_DEFAULT_PORT
            SocketProtocol.TCP -> TCP_DEFAULT_PORT
        }

        val resolvedHost: String? = if (result.isGroupOwner) {
            wdManager.awaitClientAddress()
        } else {
            wdManager.announceSelfToGroupOwner(result.groupOwnerAddress)
            result.groupOwnerAddress
        }

        if (resolvedHost == null) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Connected over Wi-Fi Direct as the group host, but couldn't detect the " +
                    "other device's address yet. Make sure AudioBridge is open on the other device too, " +
                    "then try connecting again."
            )
            return
        }

        updateConfig(
            _uiState.value.config.copy(
                lastDeviceHost = resolvedHost,
                lastDevicePort = port
            )
        )
    }

    fun connectToWifiDirectPeer(peer: WifiDirectPeer) {
        val wdManager = wifiDirectManager ?: return
        if (!hasWifiDirectDiscoveryPermission()) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "WiFi Direct needs the nearby devices/location permission to connect."
            )
            return
        }
        viewModelScope.launch {
            val result = try {
                wdManager.connectToPeer(peer)
            } catch (e: SecurityException) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "WiFi Direct permission was revoked: ${e.message}"
                )
                return@launch
            }
            if (result == null) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Could not connect to ${peer.deviceName} over WiFi Direct."
                )
                return@launch
            }
            updateConfig(_uiState.value.config.copy(lastDeviceName = peer.deviceName))
        }
    }

    fun stopDiscovery() {
        nsdDiscoveryJob?.cancel()
        nsdDiscoveryJob = null
        discoveryManager.stopDiscovery()

        wifiDirectDiscoveryJob?.cancel()
        wifiDirectDiscoveryJob = null
        wifiDirectConnectionObserverJob?.cancel()
        wifiDirectConnectionObserverJob = null
        wifiDirectResolveJob?.cancel()
        wifiDirectResolveJob = null
        unregisterWifiDirectReceiver?.invoke()
        unregisterWifiDirectReceiver = null

        _uiState.value = _uiState.value.copy(isDiscovering = false)
    }

    fun registerSelf(deviceName: String) {
        val role = _uiState.value.config.role
        val port = when (_uiState.value.config.protocol) {
            SocketProtocol.UDP -> UDP_DEFAULT_PORT
            SocketProtocol.TCP -> TCP_DEFAULT_PORT
        }
        discoveryManager.registerService(deviceName, port, role)
    }

    fun unregisterSelf() {
        discoveryManager.unregisterService()
    }

    fun selectDevice(device: DiscoveredDevice) {
        updateConfig(
            _uiState.value.config.copy(
                lastDeviceName = device.name,
                lastDeviceHost = device.host,
                lastDevicePort = device.port
            )
        )
    }

    override fun onCleared() {
        nsdDiscoveryJob?.cancel()
        discoveryManager.stopDiscovery()
        discoveryManager.unregisterService()
        wifiDirectDiscoveryJob?.cancel()
        wifiDirectConnectionObserverJob?.cancel()
        wifiDirectResolveJob?.cancel()
        unregisterWifiDirectReceiver?.invoke()
        wifiDirectManager?.disconnect()
        super.onCleared()
    }
}
 