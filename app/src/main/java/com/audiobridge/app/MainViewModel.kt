import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

package com.audiobridge.app

import android.app.Application
import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
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
        // Channel died (Wi-Fi toggled off/on, or the system's P2P service restarted).
        // WifiP2pManager offers no supported way to re-obtain a working channel after
        // this fires without recreating the manager from scratch, so surface it rather
        // than let WiFi Direct silently stop working with no explanation.
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

    // Passive observer for Wi-Fi Direct connection state (see
    // WifiDirectManager.observeConnectionInfo() doc comment) — reacts to a group
    // forming regardless of which device tapped "Connect", and resolveWifiDirectHost
    // performs the hello-packet handshake needed to learn the other device's real
    // address when THIS device ends up as Group Owner (see WifiDirectManager for the
    // full explanation of why that case needs special handling at all).
    private var wifiDirectConnectionObserverJob: Job? = null
    private var wifiDirectResolveJob: Job? = null

    private val bluetoothManager = BluetoothPairedDevicesManager(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // Counts config writes currently in flight to DataStore. While > 0, incoming
    // emissions from prefsManager.configFlow are ignored in favor of the eagerly-set
    // in-memory config below — otherwise a slightly-delayed emission for an OLDER
    // write can arrive after a NEWER write already updated _uiState, momentarily
    // reverting the user's latest change (visible as a UI flicker on rapid taps).
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
        val current = _uiState.value.config
        // 32-bit/48kHz PCM is ~3 Mbps uncompressed — well beyond what Bluetooth
        // Classic RFCOMM reliably sustains (~1-2 Mbps, see BluetoothSenderTransport's
        // own doc comment). Auto-fall back to a format that actually fits instead of
        // silently letting the stream degrade into constant buffer underruns.
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

    /** Begins discovery appropriate to the currently selected transport:
     *  - HOTSPOT_WIFI: bidirectional mDNS, looking for a device advertising the
     *    opposite role (unchanged from before).
     *  - WIFI_DIRECT: WifiP2pManager peer discovery. There's no "role" concept in
     *    raw P2P peer results the way mDNS service names carried one, so every
     *    discovered peer is surfaced and the user picks manually.
     *  - BLUETOOTH: no scan here; paired-device selection happens separately since
     *    RFCOMM requires a pre-existing OS-level pairing, not a live scan.
     */
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
        // getPairedDevices() is a direct, synchronous OS query (no scan/callback involved,
        // since RFCOMM only works with devices already bonded) — no isDiscovering spinner
        // needed, the list is just available immediately.
        _uiState.value = _uiState.value.copy(pairedBluetoothDevices = bluetoothManager.getPairedDevices())
    }

    fun selectBluetoothDevice(device: PairedBluetoothDevice) {
        updateConfig(
            _uiState.value.config.copy(
                lastDeviceName = device.name,
                lastDeviceHost = device.address, // reused as the address field for Bluetooth's sake
                lastDevicePort = 0 // RFCOMM has no port concept; UUID-based channel instead
            )
        )
        _uiState.value = _uiState.value.copy(selectedBluetoothDeviceAddress = device.address)
    }

    private fun startNsdDiscovery() {
        // Tear down any previous discovery session before starting a new one — without
        // this, repeated taps on "Scan" leak NsdManager listeners (each discoverServices()
        // call registers a fresh listener, and stopDiscovery() only ever stops the most
        // recently registered one, orphaning every earlier session) and pile up
        // concurrent collector coroutines that never complete.
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

    private fun startWifiDirectDiscovery() {
        val wdManager = wifiDirectManager
        if (wdManager == null) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "WiFi Direct is not available on this device."
            )
            return
        }
        // Same leak pattern as NSD above: tear down any previous session (cancel its
        // collector, unregister its BroadcastReceiver) before starting a new one, or
        // repeated "Scan" taps accumulate orphaned receivers and polling jobs forever.
        wifiDirectDiscoveryJob?.cancel()
        unregisterWifiDirectReceiver?.invoke()
        unregisterWifiDirectReceiver = null

        _uiState.value = _uiState.value.copy(isDiscovering = true, wifiDirectPeers = emptyList())
        unregisterWifiDirectReceiver = wdManager.registerReceiver()

        wifiDirectDiscoveryJob = viewModelScope.launch {
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
        }

        // Passive address-resolution observer — see startWifiDirectConnectionObserver's
        // own doc comment for why this needs to run independently of connectToPeer().
        startWifiDirectConnectionObserver(wdManager)
    }

    /**
     * Runs for as long as WiFi Direct discovery is active. Reacts to a group forming
     * on THIS device — whether because this device called connectToPeer() itself, or
     * because the other device did and this one simply accepted the resulting
     * invitation — and resolves the correct target host address for either outcome:
     *
     *  - We ended up Client: WifiP2pInfo already gave us the Owner's address
     *    directly, so use it, and also announce ourselves so the Owner (if it's the
     *    audio Sender) can learn OUR address the same way.
     *  - We ended up Group Owner: WifiP2pInfo cannot tell us the Client's address —
     *    wait for the Client's own hello packet instead (see WifiDirectManager).
     */
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

    /**
     * Connects to a WiFi Direct peer. The actual host/port resolution happens
     * asynchronously via the passive observer started in startWifiDirectDiscovery()
     * (see resolveWifiDirectHost) — that single code path handles BOTH group roles
     * correctly, including the "we became Group Owner" case where WifiP2pInfo alone
     * can't tell us the Client's IP, so this function only needs to kick off the
     * connection and record the peer's display name for the UI.
     */
    fun connectToWifiDirectPeer(peer: WifiDirectPeer) {
        val wdManager = wifiDirectManager ?: return
        viewModelScope.launch {
            val result = wdManager.connectToPeer(peer)
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

