package com.audiobridge.app.discovery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log

private const val TAG = "BluetoothPairedDevices"

data class PairedBluetoothDevice(
    val name: String,
    val address: String,
    val device: BluetoothDevice
)

class BluetoothPairedDevicesManager(private val context: Context) {

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    fun isBluetoothAvailable(): Boolean = adapter != null

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<PairedBluetoothDevice> {
        val bondedDevices = try {
            adapter?.bondedDevices
        } catch (e: SecurityException) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission denied when reading bonded devices: ${e.message}")
            null
        } ?: return emptyList()

        return try {
            bondedDevices.map {
                PairedBluetoothDevice(
                    name = it.name ?: it.address,
                    address = it.address,
                    device = it
                )
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission denied when reading device details: ${e.message}")
            emptyList()
        }
    }
}
