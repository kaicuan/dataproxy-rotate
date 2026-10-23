package com.dataproxy.rotation

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * ADB client used only to bootstrap and repair the privileged helper.
 * Pairing and connection target the phone's own adbd, discovered via mDNS
 * or entered manually, then reached on the advertised address.
 */
class WirelessAdb(
    private val context: Context,
    private val keyStore: AdbKeyStore,
) {

    suspend fun pair(host: String, port: Int, code: String) = withContext(Dispatchers.IO) {
        val manager = DataProxyAdbManager(keyStore.ensure())
        try {
            manager.pair(host, port, code)
        } finally {
            runCatching { manager.close() }
        }
    }

    suspend fun connect(host: String, port: Int): AbsAdbConnectionManager =
        withContext(Dispatchers.IO) {
            val manager = DataProxyAdbManager(keyStore.ensure())
            try {
                if (!manager.connect(host, port)) {
                    throw IOException("Wireless debugging did not accept the connection.")
                }
                manager
            } catch (t: Throwable) {
                runCatching { manager.close() }
                throw t
            }
        }

    suspend fun shell(
        manager: AbsAdbConnectionManager,
        command: String,
        timeoutMs: Long = SHELL_TIMEOUT_MS,
    ): String = withContext(Dispatchers.IO) {
        val stream = manager.openStream("shell:$command")
        try {
            readAll(stream, timeoutMs)
        } finally {
            runCatching { stream.close() }
        }
    }

    suspend fun discoverPairingEndpoint(timeoutMs: Long = DISCOVERY_TIMEOUT_MS): Endpoint? =
        discover(AdbMdns.SERVICE_TYPE_TLS_PAIRING, timeoutMs)

    suspend fun discoverConnectEndpoint(timeoutMs: Long = DISCOVERY_TIMEOUT_MS): Endpoint? =
        discover(AdbMdns.SERVICE_TYPE_TLS_CONNECT, timeoutMs)

    private suspend fun discover(type: String, timeoutMs: Long): Endpoint? =
        withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine<Endpoint> { continuation ->
                    var mdns: AdbMdns? = null
                    try {
                        mdns = AdbMdns(context, type) { host, port ->
                            // adbd on the same device is reachable on loopback;
                            // the mDNS host is the LAN address of this phone.
                            val address = host?.hostAddress ?: LOOPBACK
                            if (port > 0 && continuation.isActive) {
                                runCatching { mdns?.stop() }
                                continuation.resume(Endpoint(address, port))
                            }
                        }.also { it.start() }
                        continuation.invokeOnCancellation {
                            runCatching { mdns?.stop() }
                        }
                    } catch (t: Throwable) {
                        runCatching { mdns?.stop() }
                        if (continuation.isActive) continuation.resume(Endpoint(LOOPBACK, -1))
                    }
                }
            }
        }?.takeIf { it.port > 0 }

    data class Endpoint(val host: String, val port: Int)

    private suspend fun readAll(stream: AdbStream, timeoutMs: Long): String = coroutineScope {
        val output = ByteArrayOutputStream()
        val reader = launch(Dispatchers.IO) {
            try {
                val input = stream.openInputStream()
                val buffer = ByteArray(READ_BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            } catch (_: IOException) {
                // The shell closed the stream.
            }
        }
        if (withTimeoutOrNull(timeoutMs) { reader.join() } == null) {
            runCatching { stream.close() }
            withTimeoutOrNull(1_000) { reader.join() }
            reader.cancel()
        }
        output.toString(Charsets.UTF_8.name())
    }

    /**
     * The library destroys the private key when a manager is closed, so each
     * manager gets fresh decodes of the persisted bytes instead of a shared
     * key instance.
     */
    private class DataProxyAdbManager(key: AdbKey) : AbsAdbConnectionManager() {
        private val privateKeyBytes: ByteArray = key.privateKey.encoded
        private val certificateBytes: ByteArray = key.certificate.encoded

        init {
            setApi(Build.VERSION.SDK_INT)
            setTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            setThrowOnUnauthorised(true)
        }

        override fun getPrivateKey(): PrivateKey =
            KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))

        override fun getCertificate(): Certificate =
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certificateBytes))

        override fun getDeviceName(): String = "DataProxy"
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val DISCOVERY_TIMEOUT_MS = 8_000L
        private const val SHELL_TIMEOUT_MS = 10_000L
        private const val READ_BUFFER = 4_096
    }
}
