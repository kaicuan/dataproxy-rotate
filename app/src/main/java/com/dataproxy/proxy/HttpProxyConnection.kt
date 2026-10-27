package com.dataproxy.proxy

import android.util.Log
import com.dataproxy.network.CellularNetworkProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/** One HTTP proxy request, or one HTTP control request, on the shared listener. */
internal class HttpProxyConnection(
    private val clientSocket: Socket,
    clientInput: InputStream,
    private val cellular: CellularNetworkProvider,
    private val registry: ConnectionRegistry,
    private val scope: CoroutineScope,
    private val authProvider: () -> AuthConfig,
    private val controlHandler: (suspend (InputStream, OutputStream) -> Unit)?,
) : ManagedProxyConnection {

    private val input = BufferedInputStream(clientInput)
    private val closed = AtomicBoolean(false)
    @Volatile private var outbound: Socket? = null
    @Volatile private var entry: ConnectionRegistry.Connection? = null
    @Volatile private var finalResponseStarted = false
    @Volatile private var handlingControl = false

    override fun handle(): Job = scope.launch(RelayDispatcher) {
        try {
            serve()
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpProtocolException) {
            if (!handlingControl && !finalResponseStarted && !closed.get()) {
                sendError(e.status, e.message ?: "Invalid HTTP request.")
            }
        } catch (e: ProxyConnectException) {
            if (!finalResponseStarted && !closed.get()) sendError(e.status, e.message ?: "Upstream failed.")
        } catch (e: SocketTimeoutException) {
            if (!handlingControl && !finalResponseStarted && !closed.get()) {
                sendError(408, "The HTTP request timed out.")
            }
        } catch (e: Exception) {
            Log.d(TAG, "HTTP proxy connection error: ${e.message}")
            if (!handlingControl && !finalResponseStarted && !closed.get()) {
                sendError(502, "The upstream connection failed.")
            }
        } finally {
            abort()
            entry?.let(registry::close)
        }
    }

    override fun abort() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { clientSocket.close() }
        runCatching { outbound?.close() }
    }

    private suspend fun serve() {
        clientSocket.tcpNoDelay = true
        clientSocket.soTimeout = HEADER_TIMEOUT_MS

        val requestLine = HttpMessage.readLine(input)
            ?: return
        val request = parseRequestLine(requestLine.text)

        // The rotation API uses origin-form paths on this same listener. Replay
        // the line so its existing handler remains the sole owner of that API.
        if (request.target.startsWith('/') && controlHandler != null) {
            handlingControl = true
            clientSocket.soTimeout = CONTROL_TIMEOUT_MS
            controlHandler.invoke(
                java.io.SequenceInputStream(ByteArrayInputStream(requestLine.raw), input),
                clientSocket.getOutputStream(),
            )
            return
        }

        val headers = HttpMessage.readHeaders(input)
        clientSocket.soTimeout = 0

        val auth = authProvider()
        if (auth.enabled) {
            val supplied = HttpMessage.values(headers, "proxy-authorization")
            if (supplied.size != 1 || !ProxyBasicAuth.isAuthorized(supplied.singleOrNull(), auth)) {
                sendError(
                    status = 407,
                    message = "Proxy authentication required.",
                    extraHeaders = listOf("Proxy-Authenticate: Basic realm=\"DataProxy\""),
                )
                return
            }
        }

        if (headers.any { it.key == "upgrade" } || "upgrade" in HttpMessage.connectionTokens(headers)) {
            throw HttpProtocolException(501, "HTTP protocol upgrades are not supported.")
        }

        val contentLength = HttpMessage.contentLength(headers)
        val transferCodings = HttpMessage.transferCodings(headers)
        if (contentLength != null && transferCodings.isNotEmpty()) {
            throw HttpProtocolException(400, "Content-Length and Transfer-Encoding cannot be combined.")
        }
        if (transferCodings.isNotEmpty() && transferCodings.last() != "chunked") {
            throw HttpProtocolException(501, "Only chunked request bodies are supported.")
        }

        val expectsContinue = requestExpectContinue(headers)
        if (expectsContinue == null) {
            sendError(417, "The request expectation is not supported.")
            return
        }

        if (request.method == "CONNECT") {
            if ((contentLength ?: 0L) != 0L || transferCodings.isNotEmpty()) {
                throw HttpProtocolException(400, "CONNECT requests cannot contain a body.")
            }
            serveConnect(request)
            return
        }

        serveHttp(request, headers, contentLength, transferCodings, expectsContinue)
    }

    private suspend fun serveConnect(request: RequestLine) {
        val target = parseAuthority(request.target, defaultPort = null)
        val remote = openRemote(target)
        openRegistryEntry(target.display)

        val output = clientSocket.getOutputStream()
        val onDown: (Int) -> Unit = { n -> recordDown(n) }
        writeTrackedLine(output, "HTTP/1.1 200 Connection Established", onDown)
        writeTrackedLine(output, "Proxy-Agent: DataProxy", onDown)
        output.write(CRLF)
        onDown(CRLF.size)
        output.flush()
        finalResponseStarted = true

        clientSocket.soTimeout = 0
        remote.soTimeout = 0
        runTunnel(remote)
    }

    private suspend fun serveHttp(
        request: RequestLine,
        headers: List<HttpHeader>,
        contentLength: Long?,
        transferCodings: List<String>,
        expectsContinue: Boolean,
    ) {
        val target = parseHttpUrl(request.target)
        val remote = openRemote(target.destination)
        openRegistryEntry(target.destination.display)

        val remoteOutput = remote.getOutputStream()
        val onUp: (Int) -> Unit = { n -> recordUp(n) }
        writeUpstreamRequest(remoteOutput, request, headers, target, contentLength, transferCodings, onUp)

        if (expectsContinue) {
            val clientOutput = clientSocket.getOutputStream()
            val interim = "HTTP/1.1 100 Continue\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1)
            clientOutput.write(interim)
                recordDown(interim.size)
            clientOutput.flush()
        }

        clientSocket.soTimeout = BODY_TIMEOUT_MS
        when {
            transferCodings.isNotEmpty() -> HttpMessage.copyChunkedBody(input, remoteOutput, onUp)
            contentLength != null && contentLength > 0 ->
                HttpMessage.copyExactly(input, remoteOutput, contentLength, onUp)
        }
        remoteOutput.flush()
        runCatching { remote.shutdownOutput() }
        clientSocket.soTimeout = 0

        remote.soTimeout = RESPONSE_HEADER_TIMEOUT_MS
        var response = readResponseHead(remote.getInputStream())
        while (response.status in 100..199 && response.status != 101) {
            writeResponseHead(clientSocket.getOutputStream(), response, close = false) { n ->
                recordDown(n)
            }
            response = readResponseHead(remote.getInputStream())
        }
        if (response.status == 101) {
            throw ProxyConnectException(502, "The upstream attempted an unsupported protocol upgrade.")
        }

        remote.soTimeout = 0
        val clientOutput = clientSocket.getOutputStream()
        writeResponseHead(clientOutput, response, close = true) { n ->
            recordDown(n)
        }
        finalResponseStarted = true

        val noBody = request.method == "HEAD" || response.status == 204 ||
            response.status == 205 || response.status == 304
        if (noBody) return

        val onDown: (Int) -> Unit = { n -> recordDown(n) }
        when {
            response.transferCodings.lastOrNull() == "chunked" ->
                HttpMessage.copyChunkedBody(remote.getInputStream(), clientOutput, onDown)
            response.contentLength != null ->
                HttpMessage.copyExactly(remote.getInputStream(), clientOutput, response.contentLength, onDown)
            else -> HttpMessage.copyToEnd(remote.getInputStream(), clientOutput, onDown)
        }
        clientOutput.flush()
    }

    private fun writeUpstreamRequest(
        output: OutputStream,
        request: RequestLine,
        headers: List<HttpHeader>,
        target: HttpTarget,
        contentLength: Long?,
        transferCodings: List<String>,
        onBytes: (Int) -> Unit,
    ) {
        val nominated = HttpMessage.connectionTokens(headers)
        val remove = HOP_BY_HOP + nominated + setOf(
            "host", "proxy-authorization", "content-length", "transfer-encoding", "expect",
        )

        writeTrackedLine(output, "${request.method} ${target.originForm} ${request.version}", onBytes)
        headers.filterNot { it.key in remove }.forEach {
            writeTrackedLine(output, "${it.name}: ${it.value}", onBytes)
        }
        writeTrackedLine(output, "Host: ${target.destination.hostHeader}", onBytes)
        if (contentLength != null) writeTrackedLine(output, "Content-Length: $contentLength", onBytes)
        if (transferCodings.isNotEmpty()) {
            val original = HttpMessage.values(headers, "transfer-encoding").joinToString(", ")
            writeTrackedLine(output, "Transfer-Encoding: $original", onBytes)
        }
        writeTrackedLine(output, "Connection: close", onBytes)
        output.write(CRLF)
        onBytes(CRLF.size)
        output.flush()
    }

    private suspend fun openRemote(target: Destination): Socket {
        if (closed.get()) throw CancellationException("connection closed")

        val candidates = try {
            target.literal?.let(::listOf) ?: withContext(RelayDispatcher) { cellular.resolveAll(target.host) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            throw ProxyConnectException(503, "Cellular data is unavailable.")
        } catch (e: Exception) {
            throw ProxyConnectException(502, "The destination name could not be resolved over cellular.")
        }
        if (candidates.isEmpty()) throw ProxyConnectException(502, "The destination name could not be resolved.")

        val usable = if (cellular.hasIpv6()) candidates
        else candidates.filterNot { it is Inet6Address }
        if (usable.isEmpty()) throw ProxyConnectException(502, "The destination is unreachable over cellular.")

        var lastError: IOException? = null
        for (address in usable) {
            if (closed.get()) throw CancellationException("connection closed")
            var remote: Socket? = null
            try {
                remote = cellular.createBoundSocket().apply {
                    tcpNoDelay = true
                    soTimeout = RESPONSE_HEADER_TIMEOUT_MS
                }
                outbound = remote
                if (closed.get()) throw CancellationException("connection closed")
                withContext(RelayDispatcher) {
                    remote.connect(InetSocketAddress(address, target.port), CONNECT_TIMEOUT_MS)
                }
                if (closed.get()) throw CancellationException("connection closed")
                runCatching { remote.keepAlive = true }
                return remote
            } catch (e: CancellationException) {
                runCatching { remote?.close() }
                throw e
            } catch (e: IllegalStateException) {
                runCatching { remote?.close() }
                outbound = null
                throw ProxyConnectException(503, "Cellular data is unavailable.")
            } catch (e: IOException) {
                lastError = e
                runCatching {
                    remote?.setSoLinger(true, 0)
                    remote?.close()
                }
                if (outbound === remote) outbound = null
            }
        }

        if (lastError is SocketTimeoutException) {
            throw ProxyConnectException(504, "The destination connection timed out.")
        }
        throw ProxyConnectException(502, "Could not connect to the destination.")
    }

    private suspend fun runTunnel(remote: Socket) = withContext(RelayDispatcher) {
        val clientOutput = clientSocket.getOutputStream()
        val remoteOutput = remote.getOutputStream()
        val tracker = entry
        val up = async {
            copyTunnel(input, remoteOutput, remote) { n -> tracker?.let { registry.recordUp(it, n) }; Unit }
        }
        val down = async {
            copyTunnel(remote.getInputStream(), clientOutput, clientSocket) { n ->
                tracker?.let { registry.recordDown(it, n) }
                Unit
            }
        }
        runCatching { listOf(up, down).awaitAll() }
    }

    private fun copyTunnel(
        source: InputStream,
        sink: OutputStream,
        sinkSocket: Socket,
        onBytes: (Int) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val n = source.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                sink.write(buffer, 0, n)
                sink.flush()
                onBytes(n)
            }
        } catch (_: IOException) {
            // The peer closed its side of the tunnel.
        } finally {
            runCatching { sink.flush() }
            runCatching { if (!sinkSocket.isOutputShutdown) sinkSocket.shutdownOutput() }
        }
    }

    private fun readResponseHead(input: InputStream): ResponseHead {
        try {
            val line = HttpMessage.readLine(input)
                ?: throw EOFException("upstream closed before sending a response")
            val parts = line.text.split(' ', limit = 3)
            if (parts.size < 2 || parts[0] !in HTTP_VERSIONS || parts[1].length != 3 ||
                parts[1].any { !it.isDigit() }
            ) {
                throw IOException("malformed upstream status line")
            }
            val status = parts[1].toInt()
            if (status !in 100..599) throw IOException("invalid upstream status code")
            val headers = HttpMessage.readHeaders(input)
            val contentLength = HttpMessage.contentLength(headers)
            val transferCodings = HttpMessage.transferCodings(headers)
            if (contentLength != null && transferCodings.isNotEmpty()) {
                throw IOException("upstream sent conflicting body framing")
            }
            if (transferCodings.count { it == "chunked" } > 1 ||
                ("chunked" in transferCodings && transferCodings.last() != "chunked")
            ) {
                throw IOException("upstream sent invalid Transfer-Encoding")
            }
            return ResponseHead(line.text, status, headers, contentLength, transferCodings)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw ProxyConnectException(504, "The upstream response timed out.")
        } catch (e: Exception) {
            throw ProxyConnectException(502, "The upstream returned an invalid HTTP response.")
        }
    }

    private fun writeResponseHead(
        output: OutputStream,
        response: ResponseHead,
        close: Boolean,
        onBytes: (Int) -> Unit = {},
    ) {
        val nominated = HttpMessage.connectionTokens(response.headers)
        val remove = HOP_BY_HOP + nominated + setOf("content-length", "transfer-encoding")
        writeTrackedLine(output, response.statusLine, onBytes)
        response.headers.filterNot { it.key in remove }.forEach {
            writeTrackedLine(output, "${it.name}: ${it.value}", onBytes)
        }
        if (response.contentLength != null) {
            writeTrackedLine(output, "Content-Length: ${response.contentLength}", onBytes)
        }
        if (response.transferCodings.isNotEmpty()) {
            val value = HttpMessage.values(response.headers, "transfer-encoding").joinToString(", ")
            writeTrackedLine(output, "Transfer-Encoding: $value", onBytes)
        }
        if (close) writeTrackedLine(output, "Connection: close", onBytes)
        output.write(CRLF)
        onBytes(CRLF.size)
        output.flush()
    }

    private fun writeTrackedLine(output: OutputStream, line: String, onBytes: (Int) -> Unit) {
        HttpMessage.writeLine(output, line)
        onBytes(line.toByteArray(StandardCharsets.ISO_8859_1).size + CRLF.size)
    }

    private fun openRegistryEntry(target: String) {
        val peer = (clientSocket.remoteSocketAddress as? InetSocketAddress)?.address
        val clientPort = (clientSocket.remoteSocketAddress as? InetSocketAddress)?.port ?: 0
        val opened = registry.open(
            clientHost = peer?.hostAddress ?: "unknown",
            clientPort = clientPort,
            target = target,
        )
        entry = opened
        if (closed.get()) {
            registry.close(opened)
            entry = null
            throw CancellationException("connection closed")
        }
    }

    private fun recordUp(bytes: Int) {
        entry?.let { registry.recordUp(it, bytes) }
    }

    private fun recordDown(bytes: Int) {
        entry?.let { registry.recordDown(it, bytes) }
    }

    private fun sendError(status: Int, message: String, extraHeaders: List<String> = emptyList()) {
        if (closed.get() || finalResponseStarted) return
        finalResponseStarted = true
        val payload = "$message\n".toByteArray(StandardCharsets.UTF_8)
        runCatching {
            val output = clientSocket.getOutputStream()
            HttpMessage.writeLine(output, "HTTP/1.1 $status ${reason(status)}")
            HttpMessage.writeLine(output, "Content-Type: text/plain; charset=utf-8")
            HttpMessage.writeLine(output, "Content-Length: ${payload.size}")
            HttpMessage.writeLine(output, "Connection: close")
            extraHeaders.forEach { HttpMessage.writeLine(output, it) }
            output.write(CRLF)
            output.write(payload)
            output.flush()
        }
    }

    private fun parseRequestLine(text: String): RequestLine {
        val first = text.indexOf(' ')
        val second = if (first >= 0) text.indexOf(' ', first + 1) else -1
        if (first <= 0 || second <= first + 1 || second == text.lastIndex ||
            text.indexOf(' ', second + 1) >= 0
        ) {
            throw HttpProtocolException(400, "Malformed HTTP request line.")
        }
        val method = text.substring(0, first)
        val target = text.substring(first + 1, second)
        val version = text.substring(second + 1)
        if (!METHOD_TOKEN.matches(method) || target.any { it.code < 0x21 || it.code == 0x7f }) {
            throw HttpProtocolException(400, "Malformed HTTP request line.")
        }
        if (version !in HTTP_VERSIONS) throw HttpProtocolException(505, "HTTP version not supported.")
        return RequestLine(method, target, version)
    }

    private fun requestExpectContinue(headers: List<HttpHeader>): Boolean? {
        val expectations = HttpMessage.values(headers, "expect")
            .flatMap { it.split(',') }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        if (expectations.isEmpty()) return false
        return if (expectations.all { it == "100-continue" }) true else null
    }

    private fun parseHttpUrl(target: String): HttpTarget {
        val uri = try {
            URI(target)
        } catch (_: Exception) {
            throw HttpProtocolException(400, "Malformed proxy URL.")
        }
        if (!uri.isAbsolute || !uri.scheme.equals("http", ignoreCase = true) ||
            uri.rawAuthority.isNullOrEmpty() || uri.rawUserInfo != null || uri.rawFragment != null
        ) {
            val message = if (uri.scheme.equals("https", ignoreCase = true)) {
                "Use CONNECT for HTTPS destinations."
            } else {
                "HTTP proxy requests must use an absolute http:// URL."
            }
            throw HttpProtocolException(400, message)
        }
        val destination = parseAuthority(uri.rawAuthority, defaultPort = 80)
        val rawPath = uri.rawPath.orEmpty().ifEmpty { "/" }
        if (!rawPath.startsWith('/')) throw HttpProtocolException(400, "Malformed proxy URL path.")
        val originForm = rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
        return HttpTarget(destination, originForm)
    }

    private fun parseAuthority(authority: String, defaultPort: Int?): Destination {
        if (authority.isEmpty() || authority.any { it.isWhitespace() || it == '@' || it == '/' || it == '?' || it == '#' }) {
            throw HttpProtocolException(400, "Malformed destination authority.")
        }

        val rawHost: String
        val portText: String?
        if (authority.startsWith('[')) {
            val close = authority.indexOf(']')
            if (close <= 1) throw HttpProtocolException(400, "Malformed IPv6 destination.")
            rawHost = authority.substring(1, close)
            val suffix = authority.substring(close + 1)
            if (suffix.isEmpty()) {
                portText = null
            } else if (suffix.startsWith(':') && suffix.length > 1) {
                portText = suffix.substring(1)
            } else {
                throw HttpProtocolException(400, "Malformed destination port.")
            }
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon >= 0) {
                if (authority.indexOf(':') != colon) {
                    throw HttpProtocolException(400, "IPv6 destinations must use brackets.")
                }
                rawHost = authority.substring(0, colon)
                portText = authority.substring(colon + 1).takeIf { it.isNotEmpty() }
                    ?: throw HttpProtocolException(400, "Malformed destination port.")
            } else {
                rawHost = authority
                portText = null
            }
        }

        if (rawHost.isEmpty()) throw HttpProtocolException(400, "Destination host is missing.")
        val port = when {
            portText != null -> {
                if (portText.any { !it.isDigit() }) throw HttpProtocolException(400, "Invalid destination port.")
                portText.toIntOrNull()?.takeIf { it in 1..65535 }
                    ?: throw HttpProtocolException(400, "Invalid destination port.")
            }
            defaultPort != null -> defaultPort
            else -> throw HttpProtocolException(400, "CONNECT requires an explicit port.")
        }

        val literal: InetAddress?
        val host: String
        val ipv6: Boolean
        if (rawHost.contains(':')) {
            if ('%' in rawHost) throw HttpProtocolException(400, "IPv6 zone identifiers are not supported.")
            val address = runCatching { InetAddress.getByName(rawHost) }.getOrNull()
            if (address !is Inet6Address) throw HttpProtocolException(400, "Invalid IPv6 destination.")
            literal = address
            host = address.hostAddress ?: rawHost
            ipv6 = true
        } else {
            host = try {
                IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES).lowercase()
            } catch (_: Exception) {
                throw HttpProtocolException(400, "Invalid destination host.")
            }
            if (host.isEmpty() || host.length > 253) throw HttpProtocolException(400, "Invalid destination host.")
            literal = parseIpv4Literal(host)
            ipv6 = false
        }

        val hostForHeader = if (ipv6) "[$host]" else host
        val hostHeader = if (defaultPort != null && port == defaultPort) {
            hostForHeader
        } else {
            "$hostForHeader:$port"
        }
        return Destination(host, port, literal, hostHeader, "$hostForHeader:$port")
    }

    private fun parseIpv4Literal(host: String): InetAddress? {
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { part -> part.isEmpty() || part.any { !it.isDigit() } }) {
            if (host.all { it.isDigit() || it == '.' }) {
                throw HttpProtocolException(400, "Invalid IPv4 destination.")
            }
            return null
        }
        val octets = parts.map { it.toIntOrNull()?.takeIf { value -> value in 0..255 } }
        if (octets.any { it == null }) throw HttpProtocolException(400, "Invalid IPv4 destination.")
        return InetAddress.getByAddress(ByteArray(4) { octets[it]!!.toByte() })
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        407 -> "Proxy Authentication Required"
        408 -> "Request Timeout"
        417 -> "Expectation Failed"
        431 -> "Request Header Fields Too Large"
        501 -> "Not Implemented"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        505 -> "HTTP Version Not Supported"
        else -> "Error"
    }

    private data class RequestLine(val method: String, val target: String, val version: String)
    private data class HttpTarget(val destination: Destination, val originForm: String)
    private data class Destination(
        val host: String,
        val port: Int,
        val literal: InetAddress?,
        val hostHeader: String,
        val display: String,
    )
    private data class ResponseHead(
        val statusLine: String,
        val status: Int,
        val headers: List<HttpHeader>,
        val contentLength: Long?,
        val transferCodings: List<String>,
    )

    private class ProxyConnectException(val status: Int, message: String) : IOException(message)

    companion object {
        private const val TAG = "HttpProxy"
        private const val HEADER_TIMEOUT_MS = 30_000
        private const val CONTROL_TIMEOUT_MS = 30_000
        private const val BODY_TIMEOUT_MS = 60_000
        private const val RESPONSE_HEADER_TIMEOUT_MS = 30_000
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val BUFFER_SIZE = 16 * 1024
        private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
        private val HTTP_VERSIONS = setOf("HTTP/1.0", "HTTP/1.1")
        private val METHOD_TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        private val HOP_BY_HOP = setOf(
            "connection", "proxy-connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "upgrade",
        )
    }
}
