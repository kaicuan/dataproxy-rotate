package com.dataproxy.proxy

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

internal data class HttpLine(val text: String, val raw: ByteArray)

internal data class HttpHeader(val name: String, val value: String) {
    val key: String get() = name.lowercase()
}

internal class HttpProtocolException(
    val status: Int,
    message: String,
) : IOException(message)

internal object HttpMessage {
    private const val MAX_LINE_BYTES = 8_192
    private const val MAX_HEADER_BYTES = 32_768
    private const val BUFFER_SIZE = 16 * 1024
    private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

    fun readLine(input: InputStream, maxBytes: Int = MAX_LINE_BYTES): HttpLine? {
        val raw = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) {
                if (raw.size() == 0) return null
                throw EOFException("incomplete HTTP line")
            }
            raw.write(value)
            if (raw.size() > maxBytes) throw HttpProtocolException(431, "HTTP line too long")
            if (value == '\n'.code) break
        }
        val bytes = raw.toByteArray()
        val end = if (bytes.size >= 2 && bytes[bytes.lastIndex - 1] == '\r'.code.toByte()) {
            bytes.size - 2
        } else {
            bytes.size - 1
        }
        return HttpLine(String(bytes, 0, end, StandardCharsets.ISO_8859_1), bytes)
    }

    fun readHeaders(input: InputStream): List<HttpHeader> {
        val headers = ArrayList<HttpHeader>()
        var total = 0
        while (true) {
            val line = readLine(input) ?: throw EOFException("incomplete HTTP headers")
            total += line.raw.size
            if (total > MAX_HEADER_BYTES) throw HttpProtocolException(431, "HTTP headers too large")
            if (line.text.isEmpty()) return headers
            if (line.text.first() == ' ' || line.text.first() == '\t') {
                throw HttpProtocolException(400, "obsolete folded header")
            }
            val colon = line.text.indexOf(':')
            if (colon <= 0) throw HttpProtocolException(400, "malformed HTTP header")
            val name = line.text.substring(0, colon)
            if (!HEADER_NAME.matches(name)) throw HttpProtocolException(400, "malformed HTTP header name")
            val value = line.text.substring(colon + 1).trim(' ', '\t')
            if (value.any { it == '\r' || it == '\n' || (it.code < 0x20 && it != '\t') }) {
                throw HttpProtocolException(400, "malformed HTTP header value")
            }
            headers += HttpHeader(name, value)
        }
    }

    fun values(headers: List<HttpHeader>, name: String): List<String> =
        headers.filter { it.key == name.lowercase() }.map { it.value }

    /** Validates duplicate Content-Length values and returns the one effective length. */
    fun contentLength(headers: List<HttpHeader>): Long? {
        val values = values(headers, "content-length")
            .flatMap { it.split(',') }
            .map { it.trim() }
        if (values.isEmpty()) return null
        val lengths = values.map { value ->
            if (value.isEmpty() || value.any { !it.isDigit() }) {
                throw HttpProtocolException(400, "invalid Content-Length")
            }
            value.toLongOrNull() ?: throw HttpProtocolException(400, "invalid Content-Length")
        }
        if (lengths.distinct().size != 1) throw HttpProtocolException(400, "conflicting Content-Length")
        return lengths.first()
    }

    fun transferCodings(headers: List<HttpHeader>): List<String> =
        values(headers, "transfer-encoding")
            .flatMap { it.split(',') }
            .map { it.trim().substringBefore(';').trim().lowercase() }
            .also { codings ->
                if (codings.any { it.isEmpty() }) {
                    throw HttpProtocolException(400, "invalid Transfer-Encoding")
                }
            }

    fun connectionTokens(headers: List<HttpHeader>): Set<String> =
        (values(headers, "connection") + values(headers, "proxy-connection"))
            .flatMap { it.split(',') }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun writeLine(output: OutputStream, line: String) {
        output.write(line.toByteArray(StandardCharsets.ISO_8859_1))
        output.write(CRLF)
    }

    fun copyExactly(
        input: InputStream,
        output: OutputStream,
        length: Long,
        onBytes: (Int) -> Unit = {},
    ) {
        var remaining = length
        val buffer = ByteArray(BUFFER_SIZE)
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) throw EOFException("unexpected end of HTTP body")
            if (n == 0) continue
            output.write(buffer, 0, n)
            onBytes(n)
            remaining -= n
        }
    }

    /** Copies one complete chunked body, including its framing and trailers. */
    fun copyChunkedBody(
        input: InputStream,
        output: OutputStream,
        onBytes: (Int) -> Unit = {},
    ) {
        var trailerBytes = 0
        while (true) {
            val line = readLine(input) ?: throw EOFException("incomplete chunked body")
            val sizeText = line.text.substringBefore(';').trim()
            if (!HEX_SIZE.matches(sizeText)) {
                throw HttpProtocolException(400, "invalid chunk size")
            }
            val size = sizeText.toLongOrNull(16)
                ?: throw HttpProtocolException(400, "invalid chunk size")
            output.write(line.raw)
            onBytes(line.raw.size)
            if (size == 0L) {
                while (true) {
                    val trailer = readLine(input) ?: throw EOFException("incomplete chunk trailers")
                    trailerBytes += trailer.raw.size
                    if (trailerBytes > MAX_HEADER_BYTES) {
                        throw HttpProtocolException(400, "chunk trailers too large")
                    }
                    if (trailer.text.isNotEmpty()) {
                        val colon = trailer.text.indexOf(':')
                        val name = trailer.text.substring(0, colon.coerceAtLeast(0))
                        val value = if (colon > 0) trailer.text.substring(colon + 1) else ""
                        if (colon <= 0 || !HEADER_NAME.matches(name) ||
                            value.any { it == '\r' || it == '\n' || (it.code < 0x20 && it != '\t') } ||
                            name.lowercase() in FORBIDDEN_TRAILERS
                        ) {
                            throw HttpProtocolException(400, "malformed or forbidden chunk trailer")
                        }
                    }
                    output.write(trailer.raw)
                    onBytes(trailer.raw.size)
                    if (trailer.text.isEmpty()) break
                }
                output.flush()
                return
            }
            copyExactly(input, output, size, onBytes)
            val ending = ByteArray(2)
            var offset = 0
            while (offset < ending.size) {
                val n = input.read(ending, offset, ending.size - offset)
                if (n < 0) throw EOFException("incomplete chunk terminator")
                offset += n
            }
            if (ending[0] != '\r'.code.toByte() || ending[1] != '\n'.code.toByte()) {
                throw HttpProtocolException(400, "invalid chunk terminator")
            }
            output.write(ending)
            onBytes(ending.size)
        }
    }

    fun copyToEnd(input: InputStream, output: OutputStream, onBytes: (Int) -> Unit = {}) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            output.write(buffer, 0, n)
            onBytes(n)
        }
        output.flush()
    }

    private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
    private val HEX_SIZE = Regex("[0-9A-Fa-f]+")
    private val FORBIDDEN_TRAILERS = setOf(
        "connection", "content-length", "host", "keep-alive", "proxy-authorization",
        "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade",
    )
}
