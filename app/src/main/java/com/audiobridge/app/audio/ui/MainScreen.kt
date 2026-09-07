package com.audiobridge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.audiobridge.app.MainUiState
import com.audiobridge.app.discovery.PairedBluetoothDevice
import com.audiobridge.app.discovery.WifiDirectPeer
import com.audiobridge.app.util.ConnectionState
import com.audiobridge.app.util.DeviceRole
import com.audiobridge.app.util.DiscoveredDevice
import com.audiobridge.app.util.PcmFormat
import com.audiobridge.app.util.SocketProtocol
import com.audiobridge.app.util.TransportMedium

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: MainUiState,
    onRoleChange: (DeviceRole) -> Unit,
    onTransportChange: (TransportMedium) -> Unit,
    onProtocolChange: (SocketProtocol) -> Unit,
    onPcmFormatChange: (PcmFormat) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onSafetyBufferChange: (Int) -> Unit,
    onAutoReconnectChange: (Boolean) -> Unit,
    onStartDiscovery: () -> Unit,
    onSelectDevice: (DiscoveredDevice) -> Unit,
    onSelectWifiDirectPeer: (WifiDirectPeer) -> Unit,
    onSelectBluetoothDevice: (PairedBluetoothDevice) -> Unit,
    onStartStreaming: () -> Unit,
    onStopStreaming: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AudioBridge", fontWeight = FontWeight.Bold) },
                actions = { ConnectionStatusBadge(uiState.connectionState, Modifier.padding(end = 12.dp)) }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { RoleSection(uiState, onRoleChange) }
            item { TransportSection(uiState, onTransportChange, onProtocolChange) }
            item { PcmFormatSection(uiState, onPcmFormatChange) }
            item {
                DiscoverySection(
                    uiState = uiState,
                    onStartDiscovery = onStartDiscovery,
                    onSelectDevice = onSelectDevice,
                    onSelectWifiDirectPeer = onSelectWifiDirectPeer,
                    onSelectBluetoothDevice = onSelectBluetoothDevice
                )
            }
            if (uiState.config.role == DeviceRole.RECEIVER) {
                item { VolumeSection(uiState, onVolumeChange) }
                item { SafetyBufferSection(uiState, onSafetyBufferChange) }
            }
            if (uiState.connectionState == ConnectionState.STREAMING) {
                item { StatsSection(uiState) }
            }
            item { SettingsSection(uiState, onAutoReconnectChange) }
            item {
                StreamControlButton(
                    uiState = uiState,
                    onStart = onStartStreaming,
                    onStop = onStopStreaming
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun RoleSection(uiState: MainUiState, onRoleChange: (DeviceRole) -> Unit) {
    SectionCard(title = "This device is the…") {
        SegmentedSelector(
            options = listOf(DeviceRole.SENDER, DeviceRole.RECEIVER),
            selected = uiState.config.role,
            labelFor = { if (it == DeviceRole.SENDER) "📱 Sender" else "📻 Receiver" },
            onSelect = onRoleChange
        )
    }
}

@Composable
private fun TransportSection(
    uiState: MainUiState,
    onTransportChange: (TransportMedium) -> Unit,
    onProtocolChange: (SocketProtocol) -> Unit
) {
    SectionCard(title = "Connection") {
        Text(
            "Link",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SegmentedSelector(
            options = TransportMedium.entries,
            selected = uiState.config.transport,
            labelFor = { it.label },
            onSelect = onTransportChange
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Protocol",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SegmentedSelector(
            options = SocketProtocol.entries,
            selected = uiState.config.protocol,
            labelFor = { it.label },
            onSelect = onProtocolChange
        )
    }
}

@Composable
private fun PcmFormatSection(uiState: MainUiState, onPcmFormatChange: (PcmFormat) -> Unit) {
    SectionCard(title = "Audio Quality (PCM)") {
        PcmFormat.entries.forEach { format ->
            val isSelected = format == uiState.config.pcmFormat
            // 32-bit/48kHz is ~3 Mbps uncompressed — beyond what Bluetooth Classic
            // RFCOMM reliably sustains (~1-2 Mbps). Disable rather than silently allow
            // a selection that will produce constant buffer underruns/audio breakup.
            val isBluetoothIncompatible = format == PcmFormat.PCM_32_48 &&
                uiState.config.transport == TransportMedium.BLUETOOTH
            Column {
                ListItem(
                    headlineContent = { Text(format.label) },
                    supportingContent = {
                        Text(
                            if (isBluetoothIncompatible)
                                "Too high bitrate for Bluetooth — pick a lower quality"
                            else
                                "${format.bytesPerSecond() / 1024} KB/s uncompressed, stereo"
                        )
                    },
                    trailingContent = {
                        if (isSelected) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                )
                if (!isSelected) {
                    OutlinedButton(
                        onClick = { onPcmFormatChange(format) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isBluetoothIncompatible
                    ) {
                        Text("Use this format")
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverySection(
    uiState: MainUiState,
    onStartDiscovery: () -> Unit,
    onSelectDevice: (DiscoveredDevice) -> Unit,
    onSelectWifiDirectPeer: (WifiDirectPeer) -> Unit,
    onSelectBluetoothDevice: (PairedBluetoothDevice) -> Unit
) {
    SectionCard(title = "Find Device") {
        if (uiState.config.lastDeviceName.isNotEmpty()) {
            ListItem(
                headlineContent = { Text(uiState.config.lastDeviceName) },
                supportingContent = { Text("${uiState.config.lastDeviceHost}:${uiState.config.lastDevicePort} - last used") },
                leadingContent = { Icon(Icons.Filled.Wifi, contentDescription = null) }
            )
        }

        when (uiState.config.transport) {
            TransportMedium.BLUETOOTH -> {
                Text(
                    "Pair the two devices in Android Bluetooth settings first - AudioBridge " +
                        "picks from devices already paired, it doesn't scan for new ones.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = onStartDiscovery,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Refresh Paired Devices")
                }

                uiState.pairedBluetoothDevices.forEach { device ->
                    val isSelected = device.address == uiState.selectedBluetoothDeviceAddress
                    Column {
                        ListItem(
                            headlineContent = { Text(device.name) },
                            supportingContent = { Text(device.address) },
                            trailingContent = {
                                if (isSelected) {
                                    Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        )
                        OutlinedButton(
                            onClick = { onSelectBluetoothDevice(device) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Use ${device.name}")
                        }
                    }
                }
                if (uiState.pairedBluetoothDevices.isEmpty()) {
                    Text(
                        "No paired devices found. Pair in Android Settings > Bluetooth, then tap Refresh.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            TransportMedium.WIFI_DIRECT -> {
                ScanButton(isDiscovering = uiState.isDiscovering, onStartDiscovery = onStartDiscovery)

                uiState.wifiDirectPeers.forEach { peer ->
                    Column {
                        ListItem(
                            headlineContent = { Text(peer.deviceName) },
                            supportingContent = { Text(peer.deviceAddress) }
                        )
                        OutlinedButton(
                            onClick = { onSelectWifiDirectPeer(peer) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Connect to ${peer.deviceName}")
                        }
                    }
                }
                if (!uiState.isDiscovering && uiState.wifiDirectPeers.isEmpty()) {
                    Text(
                        "No WiFi Direct peers found yet. Make sure WiFi is on and the other device has AudioBridge open, then scan.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            TransportMedium.HOTSPOT_WIFI -> {
                ScanButton(isDiscovering = uiState.isDiscovering, onStartDiscovery = onStartDiscovery)

                uiState.discoveredDevices.forEach { device ->
                    val isSelected = device.host == uiState.config.lastDeviceHost &&
                        device.port == uiState.config.lastDevicePort
                    Column {
                        ListItem(
                            headlineContent = { Text(device.name) },
                            supportingContent = { Text("${device.host}:${device.port}") },
                            trailingContent = {
                                if (isSelected) {
                                    Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        )
                        OutlinedButton(
                            onClick = { onSelectDevice(device) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Use ${device.name}")
                        }
                    }
                }

                if (!uiState.isDiscovering && uiState.discoveredDevices.isEmpty() && uiState.config.lastDeviceName.isEmpty()) {
                    Text(
                        "No device found yet. Make sure both devices are on the same hotspot, then scan.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanButton(isDiscovering: Boolean, onStartDiscovery: () -> Unit) {
    Button(
        onClick = onStartDiscovery,
        modifier = Modifier.fillMaxWidth(),
        enabled = !isDiscovering,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
    ) {
        if (isDiscovering) {
            CircularProgressIndicator(
                modifier = Modifier.height(18.dp).width(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSecondary
            )
            Spacer(Modifier.width(8.dp))
            Text("Searching…")
        } else {
            Icon(Icons.Filled.Search, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Scan for Device")
        }
    }
}

@Composable
private fun VolumeSection(uiState: MainUiState, onVolumeChange: (Float) -> Unit) {
    SectionCard(title = "Volume") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.VolumeUp, contentDescription = null)
            Slider(
                value = uiState.config.volume,
                onValueChange = onVolumeChange,
                modifier = Modifier.weight(1f)
            )
            Text("${(uiState.config.volume * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SafetyBufferSection(uiState: MainUiState, onSafetyBufferChange: (Int) -> Unit) {
    // Range matches AudioPlaybackEngine.setSafetyBuffer()'s own coerceIn(20, 1000) —
    // keeping the slider's bounds in sync with the engine's clamp avoids a slider that
    // can show a value the engine would silently reject.
    val bufferMs = uiState.config.safetyBufferMs
    SectionCard(title = "Jitter Buffer") {
        Text(
            "How much audio to cushion against network jitter before playing it. " +
                "Higher smooths a rougher connection but adds lag; lower stays in sync " +
                "but risks glitches on Bluetooth or a weak hotspot signal.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Slider(
                value = bufferMs.toFloat(),
                onValueChange = { onSafetyBufferChange(it.toInt()) },
                valueRange = 20f..1000f,
                modifier = Modifier.weight(1f)
            )
            Text("${bufferMs} ms", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun StatsSection(uiState: MainUiState) {
    val isSender = uiState.config.role == DeviceRole.SENDER
    val packetsLabel = if (isSender) "Packets Sent" else "Packets Received"
    val packetsValue = if (isSender) uiState.streamStats.packetsSent else uiState.streamStats.packetsReceived

    SectionCard(title = "Live Stats") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard(
                label = "Latency",
                value = "${uiState.streamStats.latencyMs.toInt()} ms",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = "Jitter",
                value = "${uiState.streamStats.jitterMs.toInt()} ms",
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard(
                label = packetsLabel,
                value = "$packetsValue",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = "Packets Lost",
                value = "${uiState.streamStats.packetsLost}",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SettingsSection(uiState: MainUiState, onAutoReconnectChange: (Boolean) -> Unit) {
    SectionCard(title = "Settings") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Auto-reconnect", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Reconnect to the last device automatically on launch",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = uiState.autoReconnectEnabled, onCheckedChange = onAutoReconnectChange)
        }
    }
}

@Composable
private fun StreamControlButton(
    uiState: MainUiState,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val isActive = uiState.connectionState == ConnectionState.STREAMING ||
        uiState.connectionState == ConnectionState.CONNECTING
    val canStart = uiState.config.lastDeviceHost.isNotEmpty() || uiState.config.role == DeviceRole.RECEIVER
    val isSender = uiState.config.role == DeviceRole.SENDER

    val label = when {
        isActive && isSender -> "Stop Sending"
        isActive && !isSender -> "Stop Receiving"
        !isActive && isSender -> "Start Sending"
        else -> "Start Receiving"
    }

    Button(
        onClick = { if (isActive) onStop() else onStart() },
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        enabled = canStart,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Icon(if (isActive) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium
        )
    }
}