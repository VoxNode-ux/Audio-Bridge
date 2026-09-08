package com.audiobridge.app.util

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "audiobridge_prefs")

/**
 * Persists the last-used connection config so the app can auto-reconnect on launch
 * without you having to re-pick role/transport/device every time.
 */
class PreferencesManager(private val context: Context) {

    private object Keys {
        val ROLE = stringPreferencesKey("role")
        val TRANSPORT = stringPreferencesKey("transport")
        val PROTOCOL = stringPreferencesKey("protocol")
        val PCM_FORMAT = stringPreferencesKey("pcm_format")
        val LAST_DEVICE_NAME = stringPreferencesKey("last_device_name")
        val LAST_DEVICE_HOST = stringPreferencesKey("last_device_host")
        val LAST_DEVICE_PORT = intPreferencesKey("last_device_port")
        val VOLUME = floatPreferencesKey("volume")
        val SAFETY_BUFFER_MS = intPreferencesKey("safety_buffer_ms")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
    }

    // DataStore's own documented contract: `.data` throws IOException from its Flow
    // if the on-disk preferences file is corrupted or unreadable. Left unhandled,
    // that exception used to propagate straight out into whatever collects this Flow
    // — MainViewModel's init{} block, via `viewModelScope.launch { configFlow.collect
    // {...} }` — which has no exception handler of its own. An uncaught exception in
    // a launched child coroutine crashes the app outright, and since the file doesn't
    // fix itself, that crash would repeat on every future launch. Falling back to
    // emptyPreferences() on IOException means the app just starts from defaults, the
    // same as a genuine first launch — any other exception type still propagates
    // rather than being silently hidden.
    val configFlow: Flow<ConnectionConfig> = context.dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            ConnectionConfig(
                role = prefs[Keys.ROLE]?.let { runCatching { DeviceRole.valueOf(it) }.getOrNull() }
                    ?: DeviceRole.SENDER,
                transport = prefs[Keys.TRANSPORT]?.let { runCatching { TransportMedium.valueOf(it) }.getOrNull() }
                    ?: TransportMedium.HOTSPOT_WIFI,
                protocol = prefs[Keys.PROTOCOL]?.let { runCatching { SocketProtocol.valueOf(it) }.getOrNull() }
                    ?: SocketProtocol.UDP,
                pcmFormat = prefs[Keys.PCM_FORMAT]?.let { runCatching { PcmFormat.valueOf(it) }.getOrNull() }
                    ?: PcmFormat.PCM_16_48,
                lastDeviceName = prefs[Keys.LAST_DEVICE_NAME] ?: "",
                lastDeviceHost = prefs[Keys.LAST_DEVICE_HOST] ?: "",
                lastDevicePort = prefs[Keys.LAST_DEVICE_PORT] ?: 0,
                volume = prefs[Keys.VOLUME] ?: 1.0f,
                safetyBufferMs = prefs[Keys.SAFETY_BUFFER_MS] ?: 120
            )
        }

    val autoReconnectEnabled: Flow<Boolean> = context.dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            prefs[Keys.AUTO_RECONNECT] ?: true
        }

    suspend fun saveConfig(config: ConnectionConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ROLE] = config.role.name
            prefs[Keys.TRANSPORT] = config.transport.name
            prefs[Keys.PROTOCOL] = config.protocol.name
            prefs[Keys.PCM_FORMAT] = config.pcmFormat.name
            prefs[Keys.LAST_DEVICE_NAME] = config.lastDeviceName
            prefs[Keys.LAST_DEVICE_HOST] = config.lastDeviceHost
            prefs[Keys.LAST_DEVICE_PORT] = config.lastDevicePort
            prefs[Keys.VOLUME] = config.volume
            prefs[Keys.SAFETY_BUFFER_MS] = config.safetyBufferMs
        }
    }

    suspend fun setAutoReconnect(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.AUTO_RECONNECT] = enabled }
    }
}
 
