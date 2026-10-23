package com.dataproxy.rotation

import android.content.Context
import android.os.Build
import android.util.Log
import com.dataproxy.network.CellularNetworkProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the rotation preference, the privileged helper, and the one-at-a-time
 * rotation. It is application-scoped so a setup can finish after the user
 * leaves DataProxy for Wireless debugging, and so the helper status survives
 * the proxy being stopped.
 */
class RotationController(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = RotationPreferences(appContext)
    private val adb = WirelessAdb(appContext, AdbKeyStore(appContext))
    private val helper = PrivilegedHelper(appContext, prefs, adb)
    private val cellular = CellularNetworkProvider(appContext)
    private val stun = StunClient(cellular)
    private val checkCellular = CellularNetworkProvider(appContext)
    private val checkStun = StunClient(checkCellular)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _enabled = MutableStateFlow(prefs.enabled)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _delayMs = MutableStateFlow(prefs.delayMs)
    val delayMs: StateFlow<Long> = _delayMs.asStateFlow()

    private val _keepWifiOn = MutableStateFlow(prefs.keepWifiOn)
    val keepWifiOn: StateFlow<Boolean> = _keepWifiOn.asStateFlow()

    private val _server = MutableStateFlow(prefs.server)
    val server: StateFlow<IpCheckServer> = _server.asStateFlow()

    private val _customHost = MutableStateFlow(prefs.customHost)
    val customHost: StateFlow<String> = _customHost.asStateFlow()

    private val _customPort = MutableStateFlow(prefs.customPort)
    val customPort: StateFlow<Int> = _customPort.asStateFlow()

    private val _rotationStatus = MutableStateFlow<RotationStatus>(RotationStatus.Idle)
    val rotationStatus: StateFlow<RotationStatus> = _rotationStatus.asStateFlow()

    private val _setupState = MutableStateFlow<SetupState>(SetupState.Idle)
    val setupState: StateFlow<SetupState> = _setupState.asStateFlow()

    private val _ipCheckState = MutableStateFlow<IpCheckState>(IpCheckState.Idle)
    val ipCheckState: StateFlow<IpCheckState> = _ipCheckState.asStateFlow()

    val helperStatus: StateFlow<HelperStatus> = helper.status

    private val rotationBusy = AtomicBoolean(false)
    private val statusBusy = AtomicBoolean(false)
    private val setupBusy = AtomicBoolean(false)

    @Volatile private var pendingPairingPort: Int? = null
    @Volatile private var pendingConnectPort: Int? = null

    fun setEnabled(value: Boolean) {
        if (value && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (_enabled.value == value) return
        prefs.enabled = value
        _enabled.value = value
        if (value) checkStatus()
    }

    fun setDelaySeconds(seconds: Int) {
        val ms = seconds.coerceIn(
            (RotationPreferences.MIN_DELAY_MS / 1000L).toInt(),
            (RotationPreferences.MAX_DELAY_MS / 1000L).toInt(),
        ) * 1000L
        prefs.delayMs = ms
        _delayMs.value = prefs.delayMs
    }

    fun setKeepWifiOn(value: Boolean) {
        if (_keepWifiOn.value == value) return
        prefs.keepWifiOn = value
        _keepWifiOn.value = value
        if (!_enabled.value || !prefs.adbPaired) return
        scope.launch {
            helper.applyKeepWifiOn(value).exceptionOrNull()?.let {
                Log.d(TAG, "Keep Wi-Fi On apply failed: ${it.message}")
            }
        }
    }

    fun setServer(value: IpCheckServer) {
        if (_server.value == value) return
        prefs.server = value
        _server.value = value
        _ipCheckState.value = IpCheckState.Idle
    }

    fun setCustomEndpoint(host: String, port: Int) {
        prefs.customHost = host
        prefs.customPort = port
        _customHost.value = prefs.customHost
        _customPort.value = prefs.customPort
        _ipCheckState.value = IpCheckState.Idle
    }

    fun checkStatus() {
        if (!_enabled.value) return
        if (_setupState.value is SetupState.Pairing ||
            _setupState.value is SetupState.Connecting
        ) {
            return
        }
        if (!statusBusy.compareAndSet(false, true)) return
        scope.launch {
            try {
                helper.check()
            } finally {
                statusBusy.set(false)
            }
        }
    }

    fun beginSetup(pairingPort: Int?, connectPort: Int?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            _setupState.value = SetupState.Failed("IP rotation requires Android 11 or newer.")
            return
        }
        pendingPairingPort = pairingPort?.takeIf { it in 1..65535 }
        pendingConnectPort = connectPort?.takeIf { it in 1..65535 }

        if (!PairingNotification.canPost(appContext)) {
            _setupState.value = SetupState.Failed(
                "Allow notifications so you can enter the pairing code.",
            )
            return
        }

        _setupState.value = SetupState.Pairing
        runCatching { PairingNotification.show(appContext) }
            .onFailure {
                _setupState.value = SetupState.Failed("Could not show the pairing notification.")
            }
    }

    fun submitPairingCode(code: String) {
        val normalized = code.filterNot { it.isWhitespace() }
        if (normalized.isEmpty()) return
        if (_setupState.value is SetupState.Connecting || _setupState.value is SetupState.Done) return
        if (!setupBusy.compareAndSet(false, true)) return

        _setupState.value = SetupState.Connecting
        PairingNotification.cancel(appContext)
        scope.launch {
            try {
                val result = helper.pairAndStart(pendingPairingPort, normalized, pendingConnectPort)
                if (result.isSuccess) {
                    _setupState.value = SetupState.Done
                    pendingPairingPort = null
                    pendingConnectPort = null
                } else {
                    _setupState.value = SetupState.Failed(
                        result.exceptionOrNull()?.message ?: "Pairing failed.",
                    )
                }
            } finally {
                setupBusy.set(false)
            }
        }
    }

    fun reconnect(connectPort: Int?) {
        if (!_enabled.value) return
        scope.launch {
            helper.reconnect(connectPort?.takeIf { it in 1..65535 })
        }
    }

    fun requestRotation(): RotationStart {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return RotationStart.Unsupported
        if (!_enabled.value) return RotationStart.Disabled
        if (!rotationBusy.compareAndSet(false, true)) return RotationStart.Busy

        _rotationStatus.value = RotationStatus.Running(1)
        scope.launch {
            try {
                runRotation()
            } catch (t: Throwable) {
                val attempt = (_rotationStatus.value as? RotationStatus.Running)?.attempt ?: 0
                Log.d(TAG, "rotation failed: ${t.message}")
                _rotationStatus.value = RotationStatus.Failed(
                    t.message ?: "Could not rotate the IP.",
                    attempt,
                )
            } finally {
                rotationBusy.set(false)
            }
        }
        return RotationStart.Accepted
    }

    fun checkIp() {
        if (_ipCheckState.value is IpCheckState.Checking) return
        _ipCheckState.value = IpCheckState.Checking
        scope.launch {
            try {
                checkCellular.start()
                val ip = try {
                    if (checkCellular.awaitAvailable(CELLULAR_TIMEOUT_MS) == null) {
                        throw IOException("Mobile data is unavailable.")
                    }
                    withContext(Dispatchers.IO) {
                        checkStun.lookup(prefs.effectiveHost(), prefs.effectivePort()).getOrThrow()
                    }
                } finally {
                    checkCellular.stop()
                }
                _ipCheckState.value = IpCheckState.Success(ip)
            } catch (t: Throwable) {
                _ipCheckState.value = IpCheckState.Failure(
                    t.message ?: "Could not check your IP.",
                )
            }
        }
    }

    private suspend fun runRotation() {
        val delayMs = prefs.delayMs
        val host = prefs.effectiveHost()
        val port = prefs.effectivePort()
        cellular.start()
        try {
            val oldNetwork = cellular.awaitAvailable(CELLULAR_TIMEOUT_MS)
            if (oldNetwork == null) {
                fail("Mobile data is unavailable.", 0)
                return
            }

            val before = stun.lookup(host, port)
            val beforeIp = before.getOrNull()
            before.exceptionOrNull()?.let {
                Log.d(TAG, "IP check before rotation failed: ${it.message}")
            }

            val keep = helper.applyKeepWifiOn(prefs.keepWifiOn)
            if (keep.isFailure) {
                fail(keep.exceptionOrNull()?.message ?: "Could not apply Keep Wi-Fi On.", 0)
                return
            }

            var lastReason: String? = null
            for (attempt in 1..MAX_ATTEMPTS) {
                _rotationStatus.value = RotationStatus.Running(attempt)
                // Set before the call: the command may reach the device even
                // when the reply is lost, so a failed enable still has to be
                // followed by a best-effort disable.
                var airplaneMayBeOn = true
                try {
                    val enabled = helper.enableAirplaneMode()
                    if (enabled.isFailure) {
                        fail(
                            enabled.exceptionOrNull()?.message
                                ?: "Could not turn on airplane mode.",
                            attempt,
                        )
                        return
                    }
                    delay(delayMs)
                    val disabled = helper.disableAirplaneMode()
                    if (disabled.isFailure) {
                        fail(
                            disabled.exceptionOrNull()?.message
                                ?: "Could not turn off airplane mode.",
                            attempt,
                        )
                        return
                    }
                    airplaneMayBeOn = false
                } finally {
                    if (airplaneMayBeOn) {
                        withContext(NonCancellable) {
                            helper.disableAirplaneMode()
                        }
                    }
                }

                val newNetwork = withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                    cellular.state.first { state ->
                        state is CellularNetworkProvider.State.Available &&
                            state.network != oldNetwork
                    }
                }
                if (newNetwork == null) {
                    fail("Mobile data did not come back.", attempt)
                    return
                }

                val after = stun.lookup(host, port)
                val afterIp = after.getOrNull()
                if (afterIp == null) {
                    unverified(
                        after.exceptionOrNull()?.message ?: "Could not check the public IP.",
                        beforeIp,
                        null,
                        attempt,
                    )
                    return
                }
                if (beforeIp == null) {
                    unverified(
                        "Could not check the public IP before rotation.",
                        null,
                        afterIp,
                        attempt,
                    )
                    return
                }
                if (afterIp != beforeIp) {
                    _rotationStatus.value = RotationStatus.Succeeded(beforeIp, afterIp, attempt)
                    return
                }
                lastReason = "The public IP did not change."
            }

            fail(lastReason ?: "The public IP did not change.", MAX_ATTEMPTS)
        } finally {
            cellular.stop()
        }
    }

    private fun fail(reason: String, attempts: Int) {
        _rotationStatus.value = RotationStatus.Failed(reason, attempts)
    }

    private fun unverified(
        reason: String,
        ipBefore: String?,
        ipAfter: String?,
        attempts: Int,
    ) {
        _rotationStatus.value = RotationStatus.Unverified(
            reason = reason,
            ipBefore = ipBefore,
            ipAfter = ipAfter,
            attempts = attempts,
        )
    }

    companion object {
        private const val TAG = "RotationController"
        private const val MAX_ATTEMPTS = 3
        private const val CELLULAR_TIMEOUT_MS = 15_000L
        private const val NETWORK_TIMEOUT_MS = 20_000L
    }
}
