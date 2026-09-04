package com.audiobridge.app.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.audiobridge.app.util.DeviceRole
import com.audiobridge.app.util.DiscoveredDevice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "NsdDiscovery"
private const val SERVICE_TYPE = "_audiobridge._tcp."
private const val SERVICE_NAME_PREFIX = "AudioBridge-"

/**
 * Handles both halves of discovery:
 *  - registerService(): announce "I'm here" (used by whichever device is SENDER or RECEIVER)
 *  - discoverServices(): listen for the other device announcing itself
 * Both devices run both halves, so either one can find the other first — no fixed
 * "server must start first" ordering to fumble.
 */
class NsdDiscoveryManager(private val context: Context) {

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    // Without an explicit multicast lock, some devices/hotspot configurations silently
    // suppress incoming mDNS multicast packets to save battery — registerService() and
    // discoverServices() both appear to succeed, but the other device's announcement
    // never actually arrives. This was almost certainly why Hotspot/Wi-Fi discovery was
    // finding nothing despite both devices being correctly connected to the same
    // network. One shared lock covers both registration and discovery since they're
    // always active together in practice.
    private val multicastLock: WifiManager.MulticastLock by lazy {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiManager.createMulticastLock("audiobridge-mdns").apply { setReferenceCounted(true) }
    }

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** Advertise this device on the local network so the other side can find us. */
    fun registerService(deviceName: String, port: Int, role: DeviceRole) {
        // Unregister any previous registration first. Without this, calling
        // registerService() again (e.g. the user tapping "Scan" multiple times)
        // overwrites registrationListener and leaks the previous mDNS advertisement —
        // it keeps broadcasting on the network forever since unregisterService() can
        // only ever reach the most recently stored listener reference.
        unregisterService()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$SERVICE_NAME_PREFIX${deviceName}_${role.name}"
            serviceType = SERVICE_TYPE
            setPort(port)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setAttribute("role", role.name)
            }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Service registered: ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed for ${info.serviceName}: code=$errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "Service unregistered: ${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Unregistration failed: code=$errorCode")
            }
        }
        discoveryListener = listener
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)

        awaitClose {
            runCatching { nsdManager.stopServiceDiscovery(listener) }
            discoveryListener = null
        }
    }

    /**
     * Emits every AudioBridge peer found on the network. Caller filters for the role
     * they're interested in (a SENDER looks for a RECEIVER and vice versa).
     */
    fun discoverServices(): Flow<DiscoveredDevice> = callbackFlow {
        // NSD can call onServiceFound() more than once for the same service name (mDNS
        // re-announce, network refresh) — calling resolveService() again on a name
        // that's already resolving throws "resolveService already active" on many
        // devices. Track in-flight names so a duplicate onServiceFound() is a no-op.
        val resolvingNames = mutableSetOf<String>()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "Discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceName.startsWith(SERVICE_NAME_PREFIX)) return
                if (!resolvingNames.add(service.serviceName)) {
                    Log.i(TAG, "Already resolving ${service.serviceName}, skipping duplicate")
                    return
                }
                Log.i(TAG, "Service found: ${service.serviceName}, resolving...")
                resolveService(
                    service,
                    onFailed = {
                        // Without this, a failed (or synchronously-throwing) resolve
                        // left the name stuck in resolvingNames forever — only the
                        // success path used to remove it. Every later onServiceFound()
                        // for the same still-advertising service would then hit the
                        // dedup check above and be silently skipped, permanently
                        // hiding that peer from discovery for the rest of the session
                        // even though it never actually went away.
                        resolvingNames.remove(service.serviceName)
                    },
                    onResolved = { resolved ->
                        resolvingNames.remove(service.serviceName)
                        trySend(resolved)
                    }
                )
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.i(TAG, "Service lost: ${service.serviceName}")
                resolvingNames.remove(service.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: code=$errorCode")
                close(IllegalStateException("Discovery failed to start: $errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: code=$errorCode")
            }
        }
        discoveryListener = listener
        runCatching { multicastLock.acquire() }
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)

        awaitClose {
            runCatching { nsdManager.stopServiceDiscovery(listener) }
            runCatching { if (multicastLock.isHeld) multicastLock.release() }
            discoveryListener = null
        }
    }

    private fun resolveService(
        service: NsdServiceInfo,
        onFailed: () -> Unit,
        onResolved: (DiscoveredDevice) -> Unit
    ) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed for ${info.serviceName}: code=$errorCode")
                onFailed()
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                // Use "contains" rather than "endsWith": NSD appends a disambiguator like
                // " (2)" to the service name on collision, which would otherwise break a
                // strict suffix match.
                val role = if (info.serviceName.contains(DeviceRole.SENDER.name)) {
                    DeviceRole.SENDER
                } else {
                    DeviceRole.RECEIVER
                }
                val host = info.host?.hostAddress ?: run { onFailed(); return }
                onResolved(
                    DiscoveredDevice(
                        name = info.serviceName.removePrefix(SERVICE_NAME_PREFIX),
                        host = host,
                        port = info.port,
                        role = role
                    )
                )
            }
        }
        // resolveService() throwing synchronously (e.g. "already active" on some OEM
        // stacks despite the dedup above — belt and suspenders) must not crash the
        // discovery flow AND must not leave this name stuck in resolvingNames forever
        // — neither onResolveFailed nor onServiceResolved would ever fire for this
        // attempt otherwise, so onFailed() has to be invoked here too.
        runCatching { nsdManager.resolveService(service, resolveListener) }
            .onFailure {
                Log.e(TAG, "resolveService threw for ${service.serviceName}: ${it.message}")
                onFailed()
            }
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            runCatching { nsdManager.stopServiceDiscovery(it) }
        }
        discoveryListener = null
    }
} 

