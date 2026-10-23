package com.dataproxy.rotation

import android.content.Context
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * Client for the shell helper. Every public operation owns its availability
 * check and repair: it calls the helper, reconnects and relaunches it when
 * needed, then retries once. Callers just invoke the operation and wait.
 */
class PrivilegedHelper(
    private val context: Context,
    private val prefs: RotationPreferences,
    private val adb: WirelessAdb,
) {

    private val mutex = Mutex()
    private val _status = MutableStateFlow<HelperStatus>(
        if (prefs.adbPaired) HelperStatus.Unknown else HelperStatus.NeedsSetup,
    )
    val status: StateFlow<HelperStatus> = _status.asStateFlow()

    suspend fun check(): Result<Unit> = execute(OP_STATUS)

    suspend fun enableAirplaneMode(): Result<Unit> = execute(OP_ENABLE)

    suspend fun disableAirplaneMode(): Result<Unit> = execute(OP_DISABLE)

    suspend fun applyKeepWifiOn(keep: Boolean): Result<Unit> =
        execute(OP_KEEP_WIFI_ON, if (keep) "1" else "0")

    suspend fun pairAndStart(
        pairingPort: Int?,
        pairingCode: String,
        connectPort: Int?,
    ): Result<Unit> = mutex.withLock {
        _status.value = HelperStatus.Checking
        val endpoint = if (pairingPort != null && pairingPort in 1..65535) {
            // Manual port from the setup dialog; adbd is on loopback for
            // same-device pairing when mDNS is unavailable.
            WirelessAdb.Endpoint(LOOPBACK, pairingPort)
        } else {
            adb.discoverPairingEndpoint()
                ?: return@withLock Result.failure(
                    IOException("Could not find the pairing port. Enter it and try again."),
                )
        }
        try {
            adb.pair(endpoint.host, endpoint.port, pairingCode)
        } catch (t: Throwable) {
            _status.value = HelperStatus.NeedsSetup
            return@withLock Result.failure(IOException(describe(t), t))
        }

        prefs.adbPaired = true
        connectPort?.let { prefs.manualConnectPort = it }
        val result = startHelper()
        result.exceptionOrNull()?.let {
            _status.value = failureStatus(it)
        } ?: run {
            _status.value = HelperStatus.Ready
        }
        result
    }

    suspend fun reconnect(connectPort: Int?): Result<Unit> = mutex.withLock {
        _status.value = HelperStatus.Checking
        if (!prefs.adbPaired) {
            _status.value = HelperStatus.NeedsSetup
            return@withLock Result.failure(IOException("IP rotation needs a one-time setup."))
        }
        connectPort?.let { prefs.manualConnectPort = it }
        val result = startHelper()
        result.exceptionOrNull()?.let {
            _status.value = failureStatus(it)
        } ?: run {
            _status.value = HelperStatus.Ready
        }
        result
    }

    private suspend fun execute(operation: String, argument: String? = null): Result<Unit> =
        mutex.withLock {
            _status.value = HelperStatus.Checking
            if (!prefs.adbPaired) {
                _status.value = HelperStatus.NeedsSetup
                return@withLock Result.failure(IOException("IP rotation needs a one-time setup."))
            }

            when (val first = call(operation, argument)) {
                is Call.Ok -> {
                    _status.value = HelperStatus.Ready
                    return@withLock Result.success(Unit)
                }
                is Call.Rejected -> {
                    _status.value = HelperStatus.Ready
                    return@withLock Result.failure(IOException(first.message))
                }
                Call.TransportFailure -> Unit
            }

            val repair = startHelper()
            if (repair.isFailure) {
                _status.value = failureStatus(
                    repair.exceptionOrNull() ?: IOException("Could not reconnect."),
                )
                return@withLock repair
            }

            when (val second = call(operation, argument)) {
                is Call.Ok -> {
                    _status.value = HelperStatus.Ready
                    Result.success(Unit)
                }
                is Call.Rejected -> {
                    _status.value = HelperStatus.Ready
                    Result.failure(IOException(second.message))
                }
                Call.TransportFailure -> {
                    _status.value = HelperStatus.NeedsReconnect(
                        "IP rotation stopped responding.",
                    )
                    Result.failure(IOException("IP rotation stopped responding."))
                }
            }
        }

    private suspend fun startHelper(): Result<Unit> {
        val endpoint = adb.discoverConnectEndpoint()
            ?: prefs.manualConnectPort.takeIf { it > 0 }?.let { WirelessAdb.Endpoint(LOOPBACK, it) }
            ?: return Result.failure(
                IOException("Could not find the connection port. Enter it from Wireless debugging."),
            )

        var manager: AbsAdbConnectionManager? = null
        return try {
            manager = adb.connect(endpoint.host, endpoint.port)
            grantSettingsPermission(manager)
            launchHelper(manager)
            Result.success(Unit)
        } catch (t: Throwable) {
            if (t is AdbAuthenticationFailedException) {
                prefs.adbPaired = false
                Result.failure(IOException("Wireless debugging no longer recognizes DataProxy.", t))
            } else if (t is AdbPairingRequiredException) {
                prefs.adbPaired = false
                Result.failure(IOException("Wireless debugging needs to be paired again.", t))
            } else {
                Result.failure(IOException(describe(t), t))
            }
        } finally {
            runCatching { manager?.close() }
        }
    }

    private suspend fun grantSettingsPermission(manager: AbsAdbConnectionManager) {
        val output = adb.shell(
            manager,
            "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS 2>&1; " +
                "echo EXIT:\$?",
        )
        val exit = Regex("EXIT:(\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull()
        if (exit != 0 || output.contains("Unknown permission", ignoreCase = true)) {
            throw IOException("Could not grant the settings permission.")
        }
    }

    private suspend fun launchHelper(manager: AbsAdbConnectionManager) {
        repeat(LAUNCH_ATTEMPTS) {
            val port = freePort()
            val token = newToken()
            val command = buildString {
                append("CLASSPATH='")
                append(context.applicationInfo.sourceDir)
                append("' nohup /system/bin/app_process /system/bin --nice-name=dataproxy-helper ")
                append(HELPER_MAIN)
                append(' ')
                append(port)
                append(' ')
                append(token)
                append(" </dev/null >/dev/null 2>&1 &")
            }
            adb.shell(manager, command, timeoutMs = LAUNCH_TIMEOUT_MS)

            if (awaitPing(port, token)) {
                prefs.helperPort = port
                prefs.helperToken = token
                return
            }
        }
        throw IOException("IP rotation did not start.")
    }

    private suspend fun call(operation: String, argument: String?): Call = withContext(Dispatchers.IO) {
        val port = prefs.helperPort
        val token = prefs.helperToken
        if (port <= 0 || token.isEmpty()) return@withContext Call.TransportFailure

        val request = buildString {
            append(token)
            append(' ')
            append(operation)
            argument?.let {
                append(' ')
                append(it)
            }
            append('\n')
        }

        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(LOOPBACK, port), CALL_TIMEOUT_MS.toInt())
                socket.soTimeout = CALL_TIMEOUT_MS.toInt()
                socket.getOutputStream().apply {
                    write(request.toByteArray(Charsets.UTF_8))
                    flush()
                }
                val response = BufferedReader(
                    InputStreamReader(socket.getInputStream(), Charsets.UTF_8),
                ).let { readResponse(it) } ?: return@withContext Call.TransportFailure
                when {
                    response == "OK" -> Call.Ok
                    response == "ERR unauthorized" || response == "ERR unknown operation" ->
                        // The port is held by a helper we no longer own or one
                        // that predates this protocol. Relaunch instead of
                        // surfacing it as an operation failure.
                        Call.TransportFailure
                    response.startsWith("ERR ") ->
                        Call.Rejected(response.removePrefix("ERR ").trim())
                    else -> Call.TransportFailure
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "helper call failed: ${t.message}")
            Call.TransportFailure
        }
    }

    private suspend fun awaitPing(port: Int, token: String): Boolean {
        val deadline = System.currentTimeMillis() + PING_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (ping(port, token)) return true
            delay(200)
        }
        return false
    }

    private suspend fun ping(port: Int, token: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(LOOPBACK, port), 500)
                socket.soTimeout = 1_000
                socket.getOutputStream().apply {
                    write("$token $OP_STATUS\n".toByteArray(Charsets.UTF_8))
                    flush()
                }
                val response = BufferedReader(
                    InputStreamReader(socket.getInputStream(), Charsets.UTF_8),
                ).let { readResponse(it) }
                response == "OK"
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun readResponse(reader: BufferedReader): String? {
        val builder = StringBuilder()
        while (true) {
            val value = reader.read()
            if (value < 0) return if (builder.isEmpty()) null else builder.toString()
            if (value == '\n'.code) return builder.toString().trimEnd('\r')
            if (builder.length >= MAX_RESPONSE_BYTES) return null
            builder.append(value.toChar())
        }
    }

    private fun freePort(): Int =
        ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)).use { it.localPort }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    private fun describe(t: Throwable): String = when (t) {
        is AdbAuthenticationFailedException ->
            "Wireless debugging no longer recognizes DataProxy."
        is AdbPairingRequiredException ->
            "Wireless debugging needs to be paired again."
        is java.net.SocketTimeoutException ->
            "Wireless debugging did not respond."
        is java.net.ConnectException ->
            "Could not connect to Wireless debugging."
        else -> t.message ?: "Could not start IP rotation."
    }

    private fun failureStatus(t: Throwable): HelperStatus {
        val needsPairing = generateSequence(t) { it.cause }.any {
            it is AdbAuthenticationFailedException || it is AdbPairingRequiredException
        }
        return if (needsPairing) {
            prefs.adbPaired = false
            HelperStatus.NeedsSetup
        } else {
            HelperStatus.NeedsReconnect(t.message ?: "Could not reconnect.")
        }
    }

    private sealed interface Call {
        data object Ok : Call
        data object TransportFailure : Call
        data class Rejected(val message: String) : Call
    }

    companion object {
        private const val TAG = "PrivilegedHelper"
        private const val LOOPBACK = "127.0.0.1"
        private const val OP_STATUS = "status"
        private const val OP_ENABLE = "enable"
        private const val OP_DISABLE = "disable"
        private const val OP_KEEP_WIFI_ON = "keep_wifi_on"
        private const val HELPER_MAIN = "com.dataproxy.helper.HelperMain"
        private const val CALL_TIMEOUT_MS = 20_000L
        private const val PING_TIMEOUT_MS = 10_000L
        private const val LAUNCH_TIMEOUT_MS = 5_000L
        private const val LAUNCH_ATTEMPTS = 2
        private const val TOKEN_BYTES = 24
        private const val MAX_RESPONSE_BYTES = 4_096
    }
}
