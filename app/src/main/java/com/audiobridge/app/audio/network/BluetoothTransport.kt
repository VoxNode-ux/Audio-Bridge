package com.audiobridge.app.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.audiobridge.app.audio.PcmCompressor
import com.audiobridge.app.util.StreamStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

private const val TAG = "BluetoothTransport"

// Fixed app-specific RFCOMM UUID — both devices must agree on this to find each
// other's service record; arbitrary but must match sender and receiver exactly.
private val AUDIOBRIDGE_UUID: UUID = UUID.fromString("8f4a2b10-6e3d-4c9a-9f21-a1b2c3d4e5f6")
private const val SDP_SERVICE_NAME = "AudioBridgeStream"

// Bounds how long a single connect attempt (standard OR fallback path) is allowed
// to hang before we give up and let AudioStreamService's retry loop try again —
// without this, a stuck SDP lookup on some OEM Bluetooth stacks can block for the
// full underlying OS timeout (up to ~12s) with no visible feedback to the user.
private const val CONNECT_TIMEOUT_MS = 6000L

/**
 * RFCOMM Bluetooth Classic transport. Standard Bluetooth tops out around 1-2 Mbps,
 * which is comfortably under what 16-bit/44.1kHz stereo needs (~1.4 Mbps) but leaves
 * very little headroom — 16-bit/48kHz (~1.5 Mbps) is tight and 32-bit/48kHz (~3 Mbps)
 * will not fit. Callers should steer users toward the lower PCM formats when
 * Bluetooth is the selected transport; this class does not downsample on its own,
 * since silently changing audio quality out from under a user-selected PCM format
 * would be a worse surprise than an occasional buffer underrun.