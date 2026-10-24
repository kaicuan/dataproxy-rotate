package com.dataproxy.rotation

import android.content.Context
import android.content.SharedPreferences
import com.dataproxy.service.ProxyService

class RotationPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(ProxyService.PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var delayMs: Long
        get() = prefs.getLong(KEY_DELAY_MS, DEFAULT_DELAY_MS)
        set(value) = prefs.edit().putLong(KEY_DELAY_MS, value.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)).apply()

    var keepWifiOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_WIFI_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_WIFI_ON, value).apply()

    var server: IpCheckServer
        get() = IpCheckServer.fromKey(prefs.getString(KEY_SERVER, null))
        set(value) = prefs.edit().putString(KEY_SERVER, value.key).apply()

    var customHost: String
        get() = prefs.getString(KEY_CUSTOM_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_HOST, value.trim()).apply()

    var customPort: Int
        get() = prefs.getInt(KEY_CUSTOM_PORT, DEFAULT_STUN_PORT)
        set(value) = prefs.edit().putInt(KEY_CUSTOM_PORT, value.coerceIn(1, 65535)).apply()

    var adbPaired: Boolean
        get() = prefs.getBoolean(KEY_ADB_PAIRED, false)
        set(value) = prefs.edit().putBoolean(KEY_ADB_PAIRED, value).apply()

    var helperPort: Int
        get() = prefs.getInt(KEY_HELPER_PORT, -1)
        set(value) = prefs.edit().putInt(KEY_HELPER_PORT, value).apply()

    var helperToken: String
        get() = prefs.getString(KEY_HELPER_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HELPER_TOKEN, value).apply()

    fun effectiveHost(): String = when (val s = server) {
        IpCheckServer.Nextcloud -> s.host
        IpCheckServer.Cloudflare -> s.host
        IpCheckServer.Google -> s.host
        IpCheckServer.Custom -> customHost
    }

    fun effectivePort(): Int = if (server == IpCheckServer.Custom) customPort else DEFAULT_STUN_PORT

    companion object {
        private const val KEY_ENABLED = "rotation_enabled"
        private const val KEY_DELAY_MS = "rotation_delay_ms"
        private const val KEY_KEEP_WIFI_ON = "rotation_keep_wifi_on"
        private const val KEY_SERVER = "rotation_ip_server"
        private const val KEY_CUSTOM_HOST = "rotation_custom_host"
        private const val KEY_CUSTOM_PORT = "rotation_custom_port"
        private const val KEY_ADB_PAIRED = "rotation_adb_paired"
        private const val KEY_HELPER_PORT = "rotation_helper_port"
        private const val KEY_HELPER_TOKEN = "rotation_helper_token"
        const val DEFAULT_DELAY_MS = 1_000L
        const val MIN_DELAY_MS = 1_000L
        const val MAX_DELAY_MS = 60_000L
        const val DEFAULT_STUN_PORT = 3478
    }
}

enum class IpCheckServer(
    val key: String,
    val label: String,
    val host: String,
) {
    Nextcloud("nextcloud", "Nextcloud", "stun.nextcloud.com"),
    Cloudflare("cloudflare", "Cloudflare", "stun.cloudflare.com"),
    Google("google", "Google", "stun.l.google.com"),
    Custom("custom", "Custom", ""),
    ;

    companion object {
        fun fromKey(key: String?): IpCheckServer =
            entries.firstOrNull { it.key == key } ?: Nextcloud
    }
}
