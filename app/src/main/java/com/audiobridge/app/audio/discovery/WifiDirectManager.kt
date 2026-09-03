package com.audiobridge.app.discovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

private const val TAG = "WifiDirectManager"

// Dedicated rendezvous port for the Group-Owner-address-resolution handshake — kept
// distinct from UDP_DEFAULT_PORT (45778) and TCP_DEFAULT_PORT (45779) so it can never
// collide with the actual audio stream sockets.
private const val HELLO_PORT = 45775
private const val HELLO_MAGIC = "ABHELLO1"

data class WifiDirectPeer(
    val deviceName: String,
    val deviceAddress: String, // MAC address — what WifiP2pManager.connect() actually needs
    val device: WifiP2pDevice
)

/** Outcome of a successful WiFi Direct connection: whether we ended up Group Owner
 *  (host) or Group Client, and the IP to dial (always the Group Owner's IP — if we
 *  ARE the owner, transports should bind/listen instead of connecting out). */
data class WifiDirectConnectionResult(
    val isGroupOwner: Boolean,
    val groupOwnerAddress: String
)

/**
 * Wraps WifiP2pManager for the app's specific need: discover the other AudioBridge
 * device, connect to it, and extract the Group Owner's IP so it can be handed to
 * UdpSenderTransport/TcpSenderTransport (the receiver, if it becomes Group Owner,
 * instead uses UdpReceiverTransport/TcpReceiverTransport bound to its own address —
 * no code changes needed there since those already just listen on all interfaces).
 *
 * WiFi Direct's own negotiation decides which side becomes Group Owner; the app
 * doesn't get to force "phone is always sender/host." That's fine for the RECEIVING
 * side (it only ever listens, regardless of network role) but is NOT fine for the
 * SENDING side, which needs an actual target IP to push UDP/TCP packets to.
 * WifiP2pInfo reliably hands the Group Owner's address to the Client, but has no
 * supported call for the reverse — a Group Owner is never told a connected Client's
 * assigned IP. If the audio Sender happens to win Group Owner negotiation, the old
 * naive "empty host" fallback caused it to stream into its own loopback interface.
 * See announceSelfToGroupOwner() / awaitClientAddress() below for the fix: a tiny
 * UDP handshake that lets the Owner learn the Client's real address directly off an
 * incoming packet, with no ARP-table reads or OEM-specific tricks.
 */
class WifiDirectManager(
    private val context: Context,
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel
) {

    /** Must be called before discoverPeers() will do anything, and unregistered via
     *  the returned function when the screen/feature is no longer in use. */
    fun registerReceiver(): () -> Unit {
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        val receiver = broadcastReceiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, intentFilter)
        }
        return { runCatching { context.unregisterReceiver(receiver) } }
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    // Peer list changed — actual peers are pulled via requestPeers()
                    // in discoverPeers()'s own PeerListListener, not read directly off
                    // this broadcast, since the broadcast carries no peer data itself
                    // on most API levels.
                    lastPeersChangedAt = System.currentTimeMillis()
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    Log.i(TAG, "WiFi Direct connection state changed")
                }
            }
        }
    }

    @Volatile private var lastPeersChangedAt = 0L

    /**
     * Emits the peer list every time Android reports a change, for as long as the
     * returned Flow is collected. Caller filters for the specific device name it
     * expects (e.g. matching the other device's Bluetooth/hostname), since WiFi
     * Direct's own peer list has no concept of "AudioBridge role" the way the mDNS
     * service names did.
     */
    @RequiresPermission(anyOf = ["android.permission.NEARBY_WIFI_DEVICES", "android.permission.ACCESS_FINE_LOCATION"])
    fun discoverPeers(): Flow<List<WifiDirectPeer>> = callbackFlow {
        val peerListListener = WifiP2pManager.PeerListListener { peers: WifiP2pDeviceList ->
            val mapped = peers.deviceList.map {
                WifiDirectPeer(deviceName = it.deviceName, deviceAddress = it.deviceAddress, device = it)
            }
            trySend(mapped)
        }

        // Re-query whenever the OS broadcasts a peer-list change. A lightweight
        // polling fallback also runs in case the broadcast is delayed/missed on some
        // OEM skins — discoverPeers() itself is cheap to call repeatedly.
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Peer discovery started")
                manager.requestPeers(channel, peerListListener)
            }
            override fun onFailure(reasonCode: Int) {
                Log.e(TAG, "Peer discovery failed: reason=$reasonCode")
                close(IllegalStateException("WiFi Direct discovery failed: $reasonCode"))
            }
        })

        // Re-query whenever the OS broadcasts a peer-list change. A lightweight
        // polling fallback also runs in case the broadcast is delayed/missed on some
        // OEM skins — discoverPeers() itself is cheap to call repeatedly. Launched on
        // `this` (the callbackFlow's own ProducerScope), not GlobalScope, so it's
        // structured under the flow's lifecycle and cancels automatically with it —
        // no separate job to leak if the collector goes away unexpectedly.
        val pollJob = launch(Dispatchers.IO) {
            while (isActive) {
                delay(3000)
                manager.requestPeers(channel, peerListListener)
            }
        }

        awaitClose {
            pollJob.cancel()
            runCatching { manager.stopPeerDiscovery(channel, null) }
        }
    }

    /**
     * Connects to a specific peer and resolves the resulting Group Owner IP. This
     * suspends until the connection info callback fires or fails — WifiP2pManager's
     * API is callback-based, so this wraps it into a single suspend call for the
     * service layer to await cleanly.
     */
    @RequiresPermission(anyOf = ["android.permission.NEARBY_WIFI_DEVICES", "android.permission.ACCESS_FINE_LOCATION"])
    suspend fun connectToPeer(peer: WifiDirectPeer): WifiDirectConnectionResult? =
        suspendCancellableCoroutine { cont ->
            val config = WifiP2pConfig().apply {
                deviceAddress = peer.deviceAddress
                wps.setup = WpsInfo.PBC
            }

            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    // Negotiation succeeded; now fetch connection info for the actual
                    // Group Owner IP — connect() succeeding doesn't hand us the IP
                    // directly, a separate call is required.
                    manager.requestConnectionInfo(channel) { info: WifiP2pInfo ->
                        if (!info.groupFormed) {
                            Log.e(TAG, "Connect reported success but no group was formed")
                            if (cont.isActive) cont.resumeWith(Result.success(null))
                            return@requestConnectionInfo
                        }
                        val ownerAddress = info.groupOwnerAddress?.hostAddress
                        if (ownerAddress == null) {
                            if (cont.isActive) cont.resumeWith(Result.success(null))
                            return@requestConnectionInfo
                        }
                        val result = WifiDirectConnectionResult(
                            isGroupOwner = info.isGroupOwner,
                            groupOwnerAddress = ownerAddress
                        )
                        if (cont.isActive) cont.resumeWith(Result.success(result))
                    }
                }

                override fun onFailure(reasonCode: Int) {
                    Log.e(TAG, "Connect to ${peer.deviceName} failed: reason=$reasonCode")
                    if (cont.isActive) cont.resumeWith(Result.success(null))
                }
            })
        }

    /**
     * Passively observes this device's own Wi-Fi Direct group membership, firing
     * whenever the OS reports a connection state change AND a group is actually
     * formed. Unlike connectToPeer()'s one-shot result — which only fires for the
     * device that actively called connect() — this reacts even when THIS device
     * never initiated anything itself (e.g. it was the target of the other device's
     * connection request and the OS auto-negotiated the link). Both sides of a link
     * need SOME way to learn their role and address; this is what lets the passive
     * side participate in the hello handshake below without the user having to tap
     * "Connect" on both devices.
     */
    fun observeConnectionInfo(): Flow<WifiDirectConnectionResult> = callbackFlow {
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                manager.requestConnectionInfo(channel) { info: WifiP2pInfo ->
                    if (info.groupFormed) {
                        val address = info.groupOwnerAddress?.hostAddress
                        if (address != null) {
                            trySend(WifiDirectConnectionResult(info.isGroupOwner, address))
                        }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        awaitClose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /**
     * Client-side half of the address-resolution handshake: fires a small UDP
     * "hello" datagram at the Group Owner's known address so the Owner can read our
     * real IP straight off the arriving packet (see awaitClientAddress). Retries a
     * handful of times over ~1.5s since the freshly-formed p2p network interface can
     * take a brief moment to come up after the connection negotiation completes.
     */
    suspend fun announceSelfToGroupOwner(groupOwnerAddress: String) = withContext(Dispatchers.IO) {
        val payload = HELLO_MAGIC.toByteArray(Charsets.US_ASCII)
        repeat(5) {
            runCatching {
                DatagramSocket().use { socket ->
                    val address = InetAddress.getByName(groupOwnerAddress)
                    socket.send(DatagramPacket(payload, payload.size, address, HELLO_PORT))
                }
            }
            delay(300)
        }
    }

    /**
     * Group-Owner-side half of the handshake: WifiP2pInfo never exposes a connected
     * Client's IP to the Owner — Android's framework has no supported call for it.
     * Listening for the Client's own hello packet and reading its source address off
     * the incoming DatagramPacket sidesteps that gap entirely without touching the
     * ARP table or any OEM-specific mechanism. Blocks (on Dispatchers.IO) until a
     * recognized hello arrives or timeoutMs elapses.
     */
    suspend fun awaitClientAddress(timeoutMs: Long = 12_000): String? = withContext(Dispatchers.IO) {
        var result: String? = null
        runCatching {
            val socket = DatagramSocket(HELLO_PORT)
            try {
                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(32)
                while (result == null) {
                    // Recomputed every iteration rather than set once to the FULL
                    // timeoutMs before the loop: soTimeout only bounds a SINGLE
                    // receive() call. Setting it once meant every non-matching
                    // packet arriving on this port (background LAN noise — mDNS,
                    // ARP, other devices' broadcast chatter) let the loop go around
                    // again and start a fresh full-length wait on the next
                    // receive(), so a run of noise packets could keep this call
                    // blocked for many multiples of the timeout the caller actually
                    // asked for, instead of the one bounded window it expected.
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    socket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)

                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: java.net.SocketTimeoutException) {
                        break
                    }
                    val text = String(packet.data, 0, packet.length, Charsets.US_ASCII)
                    if (text == HELLO_MAGIC) {
                        result = packet.address?.hostAddress
                    }
                }
            } finally {
                socket.close()
            }
        }
        result
    }

    fun disconnect() {
        runCatching { manager.removeGroup(channel, null) }
    }
}

