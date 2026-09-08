package com.audiobridge.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address

/**
 * Resolves the other device's IP without relying on mDNS broadcast/discovery.
 *
 * When your Moto is a client on the Lenovo's hotspot (or the Wi-Fi Direct group
 * client), the Lenovo is the network's gateway - its IP is directly queryable from
 * DHCP/LinkProperties. This is a fallback path alongside NSD discovery, not a
 * replacement: mDNS still works fine on most hotspot setups, but some OEM hotspot
 * implementations block multicast, which silently breaks NSD with no clear error.
 * Gateway-IP resolution has no such dependency.
 */
object NetworkUtils {

    /**
     * Best-effort gateway IP lookup. Tries the modern ConnectivityManager/LinkProperties
     * path first (works on API 23+, no deprecated APIs), falls back to the legacy
     * WifiManager.getDhcpInfo() path for older behavior/edge cases.
     */
    fun getGatewayIpAddress(context: Context): String? {
        modernGatewayLookup(context)?.let { return it }
        return legacyDhcpGatewayLookup(context)
    }

    private fun modernGatewayLookup(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network: Network = cm.activeNetwork ?: return null
        val linkProperties: LinkProperties = cm.getLinkProperties(network) ?: return null

        val defaultRoute = linkProperties.routes.firstOrNull { it.isDefaultRoute }
        val gateway = defaultRoute?.gateway
        return if (gateway is Inet4Address) gateway.hostAddress else null
    }

    @Suppress("DEPRECATION")
    private fun legacyDhcpGatewayLookup(context: Context): String? {
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
            val dhcpInfo = wifiManager.dhcpInfo ?: return null
            val gatewayInt = dhcpInfo.gateway
            if (gatewayInt == 0) return null
            String.format(
                "%d.%d.%d.%d",
                gatewayInt and 0xff,
                gatewayInt shr 8 and 0xff,
                gatewayInt shr 16 and 0xff,
                gatewayInt shr 24 and 0xff
            )
        } catch (e: Exception) {
            null
        }
    }

    fun isOnWifiNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }
}
 