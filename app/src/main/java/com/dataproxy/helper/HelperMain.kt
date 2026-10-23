package com.dataproxy.helper

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs as the shell user (UID 2000) through app_process. It owns airplane
 * mode and the global Wi-Fi radio setting, and exposes them over a
 * loopback-only TCP socket guarded by a per-launch token.
 *
 * This class deliberately has no Android dependencies: it is loaded straight
 * from the DataProxy APK by app_process, outside the app runtime.
 */
object HelperMain {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) return
        val port = args[0].toIntOrNull() ?: return
        val token = args[1]
        if (token.isEmpty()) return

        val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "dataproxy-helper-client").apply { isDaemon = true }
        }
        val server = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
        while (true) {
            val client = try {
                server.accept()
            } catch (_: Throwable) {
                Thread.sleep(200)
                continue
            }
            executor.execute { handle(client, token) }
        }
    }

    private fun handle(socket: Socket, token: String) {
        socket.use {
            runCatching {
                it.soTimeout = REQUEST_TIMEOUT_MS
                val reader = BufferedReader(InputStreamReader(it.getInputStream(), StandardCharsets.UTF_8))
                val writer = it.getOutputStream()
                val line = readLine(reader) ?: return@runCatching
                val parts = line.trim().split(" ", limit = 3)
                if (parts.size < 2 || !sameToken(token, parts[0])) {
                    write(writer, "ERR unauthorized")
                    return@runCatching
                }
                val response = when (parts[1]) {
                    "status" -> "OK"
                    "enable" -> airplaneMode("enable")
                    "disable" -> airplaneMode("disable")
                    "keep_wifi_on" -> {
                        val keep = parts.getOrNull(2)?.trim() == "1"
                        applyKeepWifiOn(keep)
                    }
                    else -> "ERR unknown operation"
                }
                write(writer, response)
            }
        }
    }

    private fun airplaneMode(action: String): String {
        val result = run("/system/bin/cmd", "connectivity", "airplane-mode", action)
        return if (result.exit == 0) "OK" else "ERR ${clean(result.output, "airplane mode failed")}"
    }

    private fun applyKeepWifiOn(keep: Boolean): String {
        val current = run("/system/bin/settings", "get", "global", "airplane_mode_radios")
        if (current.exit != 0) {
            return "ERR ${clean(current.output, "could not read the Wi-Fi radio setting")}"
        }
        val value = current.output.trim()
        if (value.isEmpty() || value == "null") {
            return "ERR the Wi-Fi radio setting is unavailable"
        }

        val radios = value.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val desired = if (keep) {
            radios.filter { it != WIFI }
        } else {
            (radios + WIFI).distinct()
        }
        val encoded = desired.joinToString(",")

        val write = run("/system/bin/settings", "put", "global", "airplane_mode_radios", encoded)
        if (write.exit != 0) {
            return "ERR ${clean(write.output, "could not change the Wi-Fi radio setting")}"
        }

        val readBack = run("/system/bin/settings", "get", "global", "airplane_mode_radios")
        if (readBack.exit != 0) {
            return "ERR ${clean(readBack.output, "could not confirm the change")}"
        }
        val actual = readBack.output.trim()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (actual.toSet() == desired.toSet()) "OK" else "ERR the Wi-Fi radio setting did not change"
    }

    private fun readLine(reader: BufferedReader): String? {
        val builder = StringBuilder()
        while (true) {
            val value = reader.read()
            if (value < 0) return if (builder.isEmpty()) null else builder.toString()
            if (value == '\n'.code) return builder.toString().trimEnd('\r')
            if (builder.length >= MAX_REQUEST_BYTES) return null
            builder.append(value.toChar())
        }
    }

    private fun run(vararg command: String): Result {
        val process = try {
            ProcessBuilder(*command).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            return Result(-1, t.message ?: "could not run the command")
        }
        val output = StringBuffer()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { input ->
                    val buffer = CharArray(1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.append(buffer, 0, read)
                    }
                }
            }
        }.apply {
            name = "dataproxy-helper-cmd"
            isDaemon = true
            start()
        }
        val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.join(1_000)
            return Result(-1, "command timed out")
        }
        reader.join(1_000)
        return Result(process.exitValue(), output.toString())
    }

    private fun write(writer: java.io.OutputStream, value: String) {
        writer.write((value.replace('\n', ' ') + "\n").toByteArray(StandardCharsets.UTF_8))
        writer.flush()
    }

    private fun sameToken(expected: String, supplied: String): Boolean =
        MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            supplied.toByteArray(StandardCharsets.UTF_8),
        )

    private fun clean(value: String, fallback: String): String =
        value.trim().replace('\n', ' ').ifEmpty { fallback }

    private data class Result(val exit: Int, val output: String)

    private const val WIFI = "wifi"
    private const val REQUEST_TIMEOUT_MS = 5_000
    private const val COMMAND_TIMEOUT_SECONDS = 15L
    private const val MAX_REQUEST_BYTES = 512
}
