package com.dataproxy.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dataproxy.DataProxyApplication
import com.dataproxy.network.CellularNetworkProvider
import com.dataproxy.network.CellularTechMonitor
import com.dataproxy.network.NetworkInterfaceLister
import com.dataproxy.proxy.ConnectionRegistry
import com.dataproxy.proxy.Socks5Server
import com.dataproxy.proxy.SpeedSampler
import com.dataproxy.rotation.HelperStatus
import com.dataproxy.rotation.IpCheckServer
import com.dataproxy.rotation.IpCheckState
import com.dataproxy.rotation.RotationController
import com.dataproxy.rotation.RotationStatus
import com.dataproxy.rotation.SetupState
import com.dataproxy.service.ProxyService
import com.dataproxy.ui.theme.ThemeMode
import com.dataproxy.util.AntiKillPreferences
import com.dataproxy.util.RateUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs: SharedPreferences =
        app.getSharedPreferences(ProxyService.PREFS_NAME, Context.MODE_PRIVATE)

    private val _bindAddress = MutableStateFlow(
        prefs.getString(ProxyService.PREF_BIND_ADDRESS, "0.0.0.0") ?: "0.0.0.0"
    )
    val bindAddress: StateFlow<String> = _bindAddress.asStateFlow()

    private val _port = MutableStateFlow(prefs.getInt(ProxyService.PREF_PORT, ProxyService.DEFAULT_PORT))
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _interfaces = MutableStateFlow<List<NetworkInterfaceLister.Candidate>>(emptyList())
    val interfaces: StateFlow<List<NetworkInterfaceLister.Candidate>> = _interfaces.asStateFlow()

    private val _serviceState = MutableStateFlow<ProxyService.State>(ProxyService.State.Stopped)
    val serviceState: StateFlow<ProxyService.State> = _serviceState.asStateFlow()

    private val _devices = MutableStateFlow<List<ConnectionRegistry.DeviceSummary>>(emptyList())
    val devices: StateFlow<List<ConnectionRegistry.DeviceSummary>> = _devices.asStateFlow()

    private val _totals = MutableStateFlow(ConnectionRegistry.Totals(0L, 0L, 0))
    val totals: StateFlow<ConnectionRegistry.Totals> = _totals.asStateFlow()

    private val _rates = MutableStateFlow(SpeedSampler.Rates(0L, 0L))
    val rates: StateFlow<SpeedSampler.Rates> = _rates.asStateFlow()

    private val _cellular =
        MutableStateFlow<CellularNetworkProvider.State>(CellularNetworkProvider.State.Idle)
    val cellular: StateFlow<CellularNetworkProvider.State> = _cellular.asStateFlow()

    private val techMonitor = CellularTechMonitor(app).also { it.start(viewModelScope) }
    val cellularTech: StateFlow<CellularTechMonitor.TechState> = techMonitor.state

    private val _authEnabled = MutableStateFlow(
        prefs.getBoolean(ProxyService.PREF_AUTH_ENABLED, false)
    )
    val authEnabled: StateFlow<Boolean> = _authEnabled.asStateFlow()

    private val _authUsername = MutableStateFlow(
        prefs.getString(ProxyService.PREF_AUTH_USERNAME, "") ?: ""
    )
    val authUsername: StateFlow<String> = _authUsername.asStateFlow()

    private val _authPassword = MutableStateFlow(
        prefs.getString(ProxyService.PREF_AUTH_PASSWORD, "") ?: ""
    )
    val authPassword: StateFlow<String> = _authPassword.asStateFlow()

    private val _maxConnections = MutableStateFlow(
        prefs.getInt(ProxyService.PREF_MAX_CONNECTIONS, Socks5Server.DEFAULT_MAX_CONNECTIONS)
    )
    val maxConnections: StateFlow<Int> = _maxConnections.asStateFlow()

    private val _themeMode = MutableStateFlow(
        ThemeMode.fromKey(prefs.getString(KEY_THEME_MODE, null))
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _rateUnit = MutableStateFlow(
        RateUnit.fromKey(prefs.getString(ProxyService.PREF_RATE_UNIT, null))
    )
    val rateUnit: StateFlow<RateUnit> = _rateUnit.asStateFlow()

    private val _autoStartOnBoot = MutableStateFlow(AntiKillPreferences.autoStartOnBoot(app))
    val autoStartOnBoot: StateFlow<Boolean> = _autoStartOnBoot.asStateFlow()

    val rotation: RotationController = (app as DataProxyApplication).rotationController
    val rotationEnabled: StateFlow<Boolean> = rotation.enabled
    val rotationDelayMs: StateFlow<Long> = rotation.delayMs
    val rotationKeepWifiOn: StateFlow<Boolean> = rotation.keepWifiOn
    val rotationServer: StateFlow<IpCheckServer> = rotation.server
    val rotationCustomHost: StateFlow<String> = rotation.customHost
    val rotationCustomPort: StateFlow<Int> = rotation.customPort
    val rotationStatus: StateFlow<RotationStatus> = rotation.rotationStatus
    val rotationSetupState: StateFlow<SetupState> = rotation.setupState
    val rotationIpCheckState: StateFlow<IpCheckState> = rotation.ipCheckState
    val rotationHelperStatus: StateFlow<HelperStatus> = rotation.helperStatus

    private var bound: ProxyService? = null
    private val collectors = mutableListOf<Job>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? ProxyService.LocalBinder)?.service ?: return
            bound = service
            mirror(service)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = null
            collectors.forEach { it.cancel() }
            collectors.clear()
        }
    }

    private fun mirror(service: ProxyService) {
        collectors.forEach { it.cancel() }
        collectors.clear()
        collectors += service.state.onEach { _serviceState.value = it }.launchIn(viewModelScope)
        collectors += service.devices.onEach { _devices.value = it }.launchIn(viewModelScope)
        collectors += service.totals.onEach { _totals.value = it }.launchIn(viewModelScope)
        collectors += service.rates.onEach { _rates.value = it }.launchIn(viewModelScope)
        collectors += service.cellularState.onEach { _cellular.value = it }.launchIn(viewModelScope)
    }

    fun bind() {
        val ctx = getApplication<Application>()
        ctx.bindService(
            Intent(ctx, ProxyService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    fun unbind() {
        val ctx = getApplication<Application>()
        runCatching { ctx.unbindService(connection) }
        collectors.forEach { it.cancel() }
        collectors.clear()
        bound = null
    }

    fun refreshInterfaces() {
        _interfaces.value = NetworkInterfaceLister.list()
    }

    fun selectBindAddress(addr: String) {
        _bindAddress.value = addr
        prefs.edit().putString(ProxyService.PREF_BIND_ADDRESS, addr).apply()
    }

    fun selectPort(p: Int) {
        _port.value = p
        prefs.edit().putInt(ProxyService.PREF_PORT, p).apply()
    }

    fun setAuthEnabled(enabled: Boolean) {
        _authEnabled.value = enabled
        prefs.edit().putBoolean(ProxyService.PREF_AUTH_ENABLED, enabled).apply()
    }

    fun setAuthCredentials(username: String, password: String) {
        _authUsername.value = username
        _authPassword.value = password
        prefs.edit()
            .putString(ProxyService.PREF_AUTH_USERNAME, username)
            .putString(ProxyService.PREF_AUTH_PASSWORD, password)
            .apply()
    }

    /** Clamped to 0..9999. 0 means unlimited, same sentinel Socks5Server reads. */
    fun setMaxConnections(value: Int) {
        val clamped = value.coerceIn(0, MAX_CONNECTIONS_LIMIT)
        _maxConnections.value = clamped
        prefs.edit().putInt(ProxyService.PREF_MAX_CONNECTIONS, clamped).apply()
    }

    fun setAutoStartOnBoot(enabled: Boolean) {
        AntiKillPreferences.setAutoStartOnBoot(getApplication(), enabled)
        _autoStartOnBoot.value = enabled
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString(KEY_THEME_MODE, mode.key).apply()
    }

    fun cycleRateUnit() {
        val next = if (_rateUnit.value == RateUnit.BytesPerSecond) RateUnit.Mbps else RateUnit.BytesPerSecond
        _rateUnit.value = next
        prefs.edit().putString(ProxyService.PREF_RATE_UNIT, next.key).apply()
    }

    fun setRotationEnabled(enabled: Boolean) = rotation.setEnabled(enabled)

    fun setRotationDelaySeconds(seconds: Int) = rotation.setDelaySeconds(seconds)

    fun setRotationKeepWifiOn(enabled: Boolean) = rotation.setKeepWifiOn(enabled)

    fun setRotationServer(server: IpCheckServer) = rotation.setServer(server)

    fun setRotationCustomEndpoint(host: String, port: Int) =
        rotation.setCustomEndpoint(host, port)

    fun checkRotationStatus() = rotation.checkStatus()

    fun requestRotationTest() {
        rotation.requestRotation()
    }

    fun beginRotationSetup(pairingPort: Int?, connectPort: Int?) =
        rotation.beginSetup(pairingPort, connectPort)

    fun reconnectRotation(connectPort: Int?) = rotation.reconnect(connectPort)

    fun checkRotationIp() = rotation.checkIp()

    fun start() {
        runCatching {
            val ctx = getApplication<Application>()
            val intent = ProxyService.startIntent(ctx, _bindAddress.value, _port.value)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }

    fun stop() {
        val ctx = getApplication<Application>()
        ctx.startService(ProxyService.stopIntent(ctx))
    }

    override fun onCleared() {
        super.onCleared()
        techMonitor.stop()
        unbind()
    }

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"

        /** Upper bound for the user-configurable connection cap. */
        const val MAX_CONNECTIONS_LIMIT = 9999
    }
}
