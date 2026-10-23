package com.dataproxy.rotation

import android.util.Base64
import com.dataproxy.proxy.AuthConfig
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * HTTP control served on the SOCKS5 listener. It speaks just enough HTTP/1.1
 * for one request per connection:
 *
 *   POST /rotate  -> 202 while the rotation runs in the background
 *   GET  /status  -> 200 with the current or most recent rotation
 *
 * When SOCKS auth is on, the same credentials are required via Basic auth.
 */
class RotationHttpHandler(
    private val controller: RotationController,
    private val authProvider: () -> AuthConfig,
) {

    suspend fun handle(input: InputStream, output: OutputStream) {
        val reader = BufferedReader(InputStreamReader(input, StandardCharsets.ISO_8859_1))
        try {
            serve(reader, output)
        } catch (_: LineTooLong) {
            respond(output, 431, JSONObject().put("error", "Request headers too large."))
        }
    }

    private fun serve(reader: BufferedReader, output: OutputStream) {
        val requestLine = readLine(reader)
        if (requestLine == null) {
            respond(output, 400, JSONObject().put("error", "Bad request."))
            return
        }
        val parts = requestLine.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/")) {
            respond(output, 400, JSONObject().put("error", "Bad request."))
            return
        }
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?')

        var authorization: String? = null
        var headerBytes = 0
        while (true) {
            val line = readLine(reader) ?: break
            if (line.isEmpty()) break
            headerBytes += line.length
            if (headerBytes > MAX_HEADER_BYTES) {
                respond(output, 431, JSONObject().put("error", "Request headers too large."))
                return
            }
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim()
            if (name.equals("Authorization", ignoreCase = true)) {
                authorization = line.substring(colon + 1).trim()
            }
        }

        val auth = authProvider()
        if (auth.enabled && !isAuthorized(authorization, auth)) {
            respond(
                output,
                401,
                JSONObject().put("error", "Authentication required."),
                extraHeaders = listOf("WWW-Authenticate: Basic realm=\"DataProxy\""),
            )
            return
        }

        when {
            method == "POST" && path == "/rotate" -> {
                when (controller.requestRotation()) {
                    RotationStart.Accepted ->
                        respond(output, 202, JSONObject().put("state", "accepted"))
                    RotationStart.Busy ->
                        respond(output, 409, JSONObject().put("error", "A rotation is already running."))
                    RotationStart.Disabled ->
                        respond(output, 409, JSONObject().put("error", "IP rotation is off."))
                    RotationStart.Unsupported ->
                        respond(
                            output,
                            409,
                            JSONObject().put("error", "IP rotation requires Android 11 or newer."),
                        )
                }
            }
            method == "GET" && path == "/status" -> {
                respond(output, 200, statusJson())
            }
            path == "/rotate" -> {
                respond(
                    output,
                    405,
                    JSONObject().put("error", "Use POST."),
                    extraHeaders = listOf("Allow: POST"),
                )
            }
            path == "/status" -> {
                respond(
                    output,
                    405,
                    JSONObject().put("error", "Use GET."),
                    extraHeaders = listOf("Allow: GET"),
                )
            }
            else -> respond(output, 404, JSONObject().put("error", "Not found."))
        }
    }

    private fun statusJson(): JSONObject {
        val json = JSONObject()
        when (val status = controller.rotationStatus.value) {
            is RotationStatus.Idle -> json.put("state", "idle")
            is RotationStatus.Running -> json
                .put("state", "running")
                .put("attempt", status.attempt)
                .put("attempts", MAX_ATTEMPTS)
            is RotationStatus.Succeeded -> json
                .put("state", "succeeded")
                .put("ipBefore", status.ipBefore)
                .put("ipAfter", status.ipAfter)
                .put("attempts", status.attempts)
            is RotationStatus.Failed -> json
                .put("state", "failed")
                .put("reason", status.reason)
                .put("attempts", status.attempts)
            is RotationStatus.Unverified -> {
                json
                    .put("state", "unverified")
                    .put("reason", status.reason)
                    .put("attempts", status.attempts)
                status.ipBefore?.let { json.put("ipBefore", it) }
                status.ipAfter?.let { json.put("ipAfter", it) }
            }
        }
        return json
    }

    private fun isAuthorized(header: String?, auth: AuthConfig): Boolean {
        if (auth.username.isEmpty() || auth.password.isEmpty()) return false
        if (header == null || !header.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = runCatching {
            String(
                Base64.decode(header.substring("Basic ".length).trim(), Base64.NO_WRAP),
                StandardCharsets.UTF_8,
            )
        }.getOrNull() ?: return false
        val expected = "${auth.username}:${auth.password}"
        return MessageDigest.isEqual(
            decoded.toByteArray(StandardCharsets.UTF_8),
            expected.toByteArray(StandardCharsets.UTF_8),
        )
    }

    /**
     * Read one CRLF line with a hard cap. BufferedReader.readLine() grows
     * without bound, and this listener is reachable by any client on the LAN.
     */
    private fun readLine(reader: BufferedReader): String? {
        val builder = StringBuilder()
        while (true) {
            val value = reader.read()
            if (value < 0) return if (builder.isEmpty()) null else builder.toString()
            if (value == '\n'.code) return builder.toString().trimEnd('\r')
            if (builder.length >= MAX_HEADER_BYTES) throw LineTooLong()
            builder.append(value.toChar())
        }
    }

    private class LineTooLong : Exception()

    private fun respond(
        output: OutputStream,
        code: Int,
        body: JSONObject,
        extraHeaders: List<String> = emptyList(),
    ) {
        val payload = body.toString().toByteArray(StandardCharsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ")
            append(code)
            append(' ')
            append(reason(code))
            append("\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ")
            append(payload.size)
            append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            extraHeaders.forEach {
                append(it)
                append("\r\n")
            }
            append("\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)

        output.write(head)
        output.write(payload)
        output.flush()
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        431 -> "Request Header Fields Too Large"
        else -> "Error"
    }

    companion object {
        private const val MAX_HEADER_BYTES = 32_768
        private const val MAX_ATTEMPTS = 3
    }
}
