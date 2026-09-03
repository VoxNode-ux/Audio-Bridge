package com.audiobridge.app.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.audiobridge.app.MainActivity
import com.audiobridge.app.network.AudioTransport
import com.audiobridge.app.network.BluetoothReceiverTransport
import com.audiobridge.app.network.BluetoothSenderTransport
import com.audiobridge.app.network.TcpReceiverTransport
import com.audiobridge.app.network.TcpSenderTransport
import com.audiobridge.app.network.UdpReceiverTransport
import com.audiobridge.app.network.UdpSenderTransport
import com.audiobridge.app.util.ConnectionState
import com.audiobridge.app.util.PcmFormat
import com.audiobridge.app.util.SocketProtocol
import com.audiobridge.app.util.StreamStats
import com.audiobridge.app.util.TransportMedium
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "AudioStreamService"
private const val NOTIFICATION_CHANNEL_ID = "audiobridge_streaming"
private const val NOTIFICATION_ID = 1

// A drop is "transient" (worth auto-retrying) up to this many consecutive attempts,
// with a short backoff between each — covers a several-second WiFi blip without
// looping forever if the other device is genuinely gone.
private const val MAX_TRANSIENT_RETRIES = 5
private const val RETRY_BACKOFF_MS = 2000L

/**
 * Foreground service owning the live streaming session — whichever role is active
 * (sender capturing + pushing, or receiver listening + playing), this keeps running
 * with the screen off, since Android would otherwise throttle/kill background audio work.
 *
 * Streaming is expressed entirely through the AudioTransport interface, so this class
 * has no protocol-specific branching for the actual send/receive loop — only a small
 * factory function picks which concrete transport to build.
 */
class AudioStreamService : Service() {

    private val binder = LocalBinder()
    private var serviceScope: CoroutineScope? = null
    private var streamingJob: Job? = null

    private var activeTransport: AudioTransport? = null
    private var captureEngine: AudioCaptureEngine? = null
    private var playbackEngine: AudioPlaybackEngine? = null

    private var activeMediaProjection: MediaProjection? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // --- Wi-Fi process binding (see bindProcessToWifiIfNeeded doc comment below) --
    private var connectivityManager: ConnectivityManager? = null
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var isProcessBoundToWifi = false

    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _streamStats = MutableStateFlow(StreamStats())
    val streamStats: StateFlow<StreamStats> = _streamStats.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): AudioStreamService = this@AudioStreamService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(Dispatchers.IO + Job())
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start with the "playback" type as a safe default — this covers the receiver
        // role (no MediaProjection involved) and keeps the service alive immediately on
        // bind. startSending() promotes this to the "mediaProjection" type right before
        // capture actually begins, which is what Android 14+ requires: the type used at
        // startForeground() time must match the capability actually being exercised.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("Ready"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Ready"))
        }
        return START_NOT_STICKY
    }

    // ---- Transport factory -------------------------------------------------------

    /**
     * Builds the concrete AudioTransport for the requested medium/protocol/role.
     * Bluetooth ignores `protocol` (UDP/TCP is a Wi-Fi-path concept; RFCOMM is its own
     * thing) — the UI should hide the protocol selector when Bluetooth is chosen, but
     * this factory doesn't rely on that and just does the sensible thing either way.
     */
    private fun buildTransport(
        isSender: Boolean,
        transportMedium: TransportMedium,
        protocol: SocketProtocol,
        targetHost: String,
        port: Int,
        format: PcmFormat,
        bluetoothDevice: BluetoothDevice?
    ): AudioTransport? {
        return when (transportMedium) {
            TransportMedium.HOTSPOT_WIFI, TransportMedium.WIFI_DIRECT -> {
                when (protocol) {
                    SocketProtocol.UDP ->
                        if (isSender) UdpSenderTransport(targetHost, port) else UdpReceiverTransport(port)
                    SocketProtocol.TCP ->
                        if (isSender) TcpSenderTransport(targetHost, port) else TcpReceiverTransport(port)
                }
            }
            TransportMedium.BLUETOOTH -> {
                val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
                    ?.adapter ?: BluetoothAdapter.getDefaultAdapter()
                if (adapter == null) {
                    Log.e(TAG, "No Bluetooth adapter available on this device")
                    return null
                }
                if (isSender) {
                    if (bluetoothDevice == null) {
                        Log.e(TAG, "Bluetooth sender requires a paired target device")
                        return null
                    }
                    BluetoothSenderTransport(adapter, bluetoothDevice, format.bitDepth)
                } else {
                    BluetoothReceiverTransport(adapter, format.bitDepth)
                }
            }
        }
    }

    // ---- Sender --------------------------------------------------------------

    /** Starts sending: captures system audio via MediaProjection, streams to targetHost. */
    fun startSending(
        mediaProjectionManager: MediaProjectionManager,
        resultCode: Int,
        resultData: Intent,
        targetHost: String,
        targetPort: Int,
        transportMedium: TransportMedium,
        protocol: SocketProtocol,
        format: PcmFormat,
        bluetoothDevice: BluetoothDevice? = null
    ) {
        stopStreaming()
        _connectionState.value = ConnectionState.CONNECTING
        acquireLocks()
        bindProcessToWifiIfNeeded(transportMedium)

        // Re-promote to the mediaProjection type before any capture happens — required
        // on Android 14+, or AudioRecord.Builder().build() with a playback-capture config
        // throws SecurityException even though the service is already foregrounded.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("Starting capture…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        }

        val mediaProjection: MediaProjection =
            mediaProjectionManager.getMediaProjection(resultCode, resultData)
        activeMediaProjection = mediaProjection

        // Without this, the app has no way to learn that the user revoked capture
        // permission via the system's screen/audio-sharing indicator (Android 14+
        // shows a persistent "Stop" action there) — capture would keep failing/no-op
        // silently instead of cleanly transitioning to IDLE/ERROR and releasing locks.
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped by the system/user — halting stream")
                stopStreaming()
            }
        }
        mediaProjectionCallback = callback
        mediaProjection.registerCallback(callback, Handler(Looper.getMainLooper()))

        val engine = AudioCaptureEngine(mediaProjection)
        captureEngine = engine

        streamingJob = serviceScope?.launch {
            var attempt = 0
            while (attempt <= MAX_TRANSIENT_RETRIES) {
                val transport = buildTransport(
                    isSender = true,
                    transportMedium = transportMedium,
                    protocol = protocol,
                    targetHost = targetHost,
                    port = targetPort,
                    format = format,
                    bluetoothDevice = bluetoothDevice
                )
                if (transport == null) {
                    _connectionState.value = ConnectionState.ERROR
                    return@launch
                }
                activeTransport = transport

                val connected = transport.connect()
                if (!connected) {
                    transport.close()
                    if (!retryOrFail(attempt, "connect")) return@launch
                    attempt++
                    continue
                }

                _connectionState.value = ConnectionState.STREAMING
                updateNotification("Streaming ($transportMedium) → $targetHost")

                try {
                    engine.start(format).collect { chunk ->
                        transport.send(chunk.data, chunk.length)
                    }
                    // engine.start()'s flow completing normally (not via exception) means
                    // the user called captureEngine.stop() — that's an intentional stop,
                    // not a drop, so exit the retry loop instead of reconnecting.
                    transport.close()
                    return@launch
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // stopStreaming()/streamingJob.cancel() surfaces here — this is an
                    // intentional user-initiated stop, not a drop. Must rethrow rather
                    // than fall into the retry path below, or a cancel-while-retrying
                    // would log a spurious failure and briefly bounce state through
                    // CONNECTING before actually stopping.
                    transport.close()
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Sending failed: ${e.message}")
                    transport.close()
                    if (!retryOrFail(attempt, "stream")) return@launch
                    attempt++
                    continue
                }
            }
        }
    }

    // ---- Receiver ------------------------------------------------------------

    /** Starts receiving: listens on the socket, plays incoming PCM through speakers. */
    fun startReceiving(
        port: Int,
        transportMedium: TransportMedium,
        protocol: SocketProtocol,
        format: PcmFormat,
        initialVolume: Float,
        safetyBufferMs: Int = 120
    ) {
        stopStreaming()
        _connectionState.value = ConnectionState.CONNECTING
        acquireLocks()
        bindProcessToWifiIfNeeded(transportMedium)

        val playback = AudioPlaybackEngine(applicationContext)
        playback.setSafetyBuffer(safetyBufferMs)
        playback.prepare(format)
        playback.setVolume(initialVolume)
        playbackEngine = playback

        streamingJob = serviceScope?.launch {
            var attempt = 0
            while (attempt <= MAX_TRANSIENT_RETRIES) {
                val transport = buildTransport(
                    isSender = false,
                    transportMedium = transportMedium,
                    protocol = protocol,
                    targetHost = "",
                    port = port,
                    format = format,
                    bluetoothDevice = null
                )
                if (transport == null) {
                    _connectionState.value = ConnectionState.ERROR
                    return@launch
                }
                activeTransport = transport

                _connectionState.value = ConnectionState.STREAMING
                updateNotification("Receiving ($transportMedium) on port $port")

                try {
                    transport.listen().collect { chunk ->
                        playback.write(chunk.data, chunk.length)
                        _streamStats.value = chunk.stats
                    }
                    transport.close()
                    return@launch
                } catch (e: kotlinx.coroutines.CancellationException) {
                    transport.close()
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Receiving failed: ${e.message}")
                    transport.close()
                    if (!retryOrFail(attempt, "listen")) return@launch
                    attempt++
                    continue
                }
            }
        }
    }

    /**
     * Shared retry policy for both roles: a transient failure (WiFi blip, brief
     * Bluetooth hiccup) gets a short backoff and another attempt, up to
     * MAX_TRANSIENT_RETRIES. Beyond that, or on final failure, moves to ERROR and
     * returns false so the caller's loop exits instead of retrying forever.
     */
    private suspend fun retryOrFail(attempt: Int, stage: String): Boolean {
        if (attempt >= MAX_TRANSIENT_RETRIES) {
            Log.e(TAG, "$stage failed after $attempt retries, giving up")
            _connectionState.value = ConnectionState.ERROR
            return false
        }
        _connectionState.value = ConnectionState.CONNECTING
        updateNotification("Connection dropped — retrying (${attempt + 1}/$MAX_TRANSIENT_RETRIES)…")
        delay(RETRY_BACKOFF_MS)
        return true
    }

    fun setVolume(volume: Float) {
        playbackEngine?.setVolume(volume)
    }

    fun setSafetyBuffer(ms: Int) {
        playbackEngine?.setSafetyBuffer(ms)
    }

    // ---- Wake / WiFi locks -----------------------------------------------------

    /**
     * Acquires a partial WakeLock (keeps the CPU running for capture/socket work with
     * the screen off) and, on Wi-Fi transports, a high-performance WifiLock (keeps the
     * radio from dropping into power-save between packets, which otherwise introduces
     * exactly the kind of jitter the playback ring buffer has to compensate for).
     * The WAKE_LOCK permission was already declared in the manifest but nothing ever
     * called PowerManager/WifiManager to use it — this is that missing piece.
     */
    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AudioBridge::StreamingWakeLock"
            )?.apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 60 * 1000L) // 10h safety cap; released explicitly on stop
            }
        }
        if (wifiLock?.isHeld != true) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            // LOW_LATENCY (API 29+, matches this app's minSdk) is the purpose-built mode
            // for exactly this case — minimizing packet latency with the screen off —
            // vs HIGH_PERF's broader "just keep the radio active" semantics.
            wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "AudioBridge::StreamingWifiLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) runCatching { it.release() } }
        wifiLock = null
    }

    // ---- Wi-Fi process binding (Cellular Blackhole fix) ---------------------------

    /**
     * When a phone has both mobile data AND a Wi-Fi-based transport (hotspot or Wi-Fi
     * Direct) active at the same time, Android's default network for this process can
     * still be cellular — sockets opened the normal way (plain Socket()/DatagramSocket(),
     * which is what UdpStreamer/TcpStreamer use) get routed over whichever network the
     * OS currently scores as "default," and that scoring favors a network with
     * validated internet access (cellular) over a local-only Wi-Fi Direct/hotspot link
     * that the OS knows has no path to the wider internet. The practical effect: audio
     * packets silently go out over the cellular radio instead of the Wi-Fi radio the
     * other device is actually listening on — they either vanish entirely or hit
     * inconsistent OEM-specific routing behavior, producing intermittent drops that
     * look exactly like a weak Wi-Fi signal but have nothing to do with signal
     * strength at all.
     *
     * The fix: explicitly request a network with TRANSPORT_WIFI and bind THIS PROCESS
     * to it via ConnectivityManager.bindProcessToNetwork(). That forces every socket
     * this app opens — for as long as the binding is held — onto the Wi-Fi interface
     * specifically, regardless of what the system considers the phone's overall
     * "default" network. This only matters for the two Wi-Fi-based transports;
     * Bluetooth RFCOMM sockets are a direct radio-level connection that never goes
     * through ConnectivityManager's IP network stack, so requesting/binding a Wi-Fi
     * network for a pure-Bluetooth session would be pointless (and could needlessly
     * fail on a device with Wi-Fi radio off entirely while using Bluetooth only).
     */
    private fun bindProcessToWifiIfNeeded(transportMedium: TransportMedium) {
        if (transportMedium == TransportMedium.BLUETOOTH) return
        if (wifiNetworkCallback != null) return // already requested/bound for this session

        val cm = (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager) ?: return
        connectivityManager = cm

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val bound = cm.bindProcessToNetwork(network)
                isProcessBoundToWifi = bound
                Log.i(TAG, "Process bound to Wi-Fi network for streaming: $bound")
            }

            override fun onLost(network: Network) {
                // The Wi-Fi network this process was bound to went away (hotspot or
                // Wi-Fi Direct group torn down, Wi-Fi toggled off mid-session). Unbind
                // rather than leaving the process pinned to a now-dead network, which
                // would black-hole ALL of this app's traffic — not just audio — until
                // something explicitly unbinds it.
                Log.w(TAG, "Bound Wi-Fi network lost — unbinding process")
                runCatching { cm.bindProcessToNetwork(null) }
                isProcessBoundToWifi = false
            }
        }
        wifiNetworkCallback = callback
        runCatching { cm.requestNetwork(request, callback) }
            .onFailure {
                Log.e(TAG, "Failed to request Wi-Fi network for process binding: ${it.message}")
                wifiNetworkCallback = null
                connectivityManager = null
            }
    }

    /** Releases the network request/callback from bindProcessToWifiIfNeeded and
     *  unbinds the process back to the system default network. Safe to call even if
     *  no binding was ever established (e.g. a Bluetooth-only session). */
    private fun unbindProcessFromWifi() {
        val cm = connectivityManager
        wifiNetworkCallback?.let { cb ->
            if (cm != null) runCatching { cm.unregisterNetworkCallback(cb) }
        }
        wifiNetworkCallback = null
        if (isProcessBoundToWifi) {
            runCatching { cm?.bindProcessToNetwork(null) }
            isProcessBoundToWifi = false
        }
        connectivityManager = null
    }

    // ---- Lifecycle -------------------------------------------------------------

    /**
     * Stops any active capture/playback and socket work, but leaves the service itself
     * running in the foreground (used when the user pauses/reconfigures without fully
     * exiting — e.g. switching PCM format before restarting).
     */
    fun stopStreaming() {
        streamingJob?.cancel()
        streamingJob = null

        captureEngine?.stop()
        captureEngine = null

        // Unregister BEFORE calling stop(): stop() synchronously invokes onStop() on
        // any still-registered callback, and our callback itself calls stopStreaming()
        // — leaving it registered here would recurse right back into this function.
        mediaProjectionCallback?.let { cb ->
            runCatching { activeMediaProjection?.unregisterCallback(cb) }
        }
        mediaProjectionCallback = null
        runCatching { activeMediaProjection?.stop() }
        activeMediaProjection = null

        activeTransport?.close()
        activeTransport = null

        playbackEngine?.release()
        playbackEngine = null

        releaseLocks()
        unbindProcessFromWifi()

        _connectionState.value = ConnectionState.IDLE
        updateNotification("Stopped")
    }

    /**
     * Full shutdown: stops streaming AND takes the service out of the foreground,
     * removing its notification and letting Android reclaim it. Without this the
     * service lingers indefinitely after the user stops streaming, since
     * startForeground() was never matched with a stopForeground()/stopSelf().
     */
    fun shutdown() {
        stopStreaming()
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Fires when the user swipes the app away from Recents. shutdown() previously had
     * no call site anywhere in the app, so the foreground service (and its "Ready" /
     * "Stopped" notification) would linger forever once started, even for a user who
     * never explicitly streamed anything. Only shut down if we're not actively
     * streaming/connecting — an active session should keep running in the background,
     * which is the entire point of this being a foreground service.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (_connectionState.value == ConnectionState.IDLE || _connectionState.value == ConnectionState.ERROR) {
            Log.i(TAG, "Task removed while idle — shutting down foreground service")
            shutdown()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopStreaming()
        serviceScope?.cancel()
        serviceScope = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Audio Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows ongoing audio bridge streaming status"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("AudioBridge")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }
}