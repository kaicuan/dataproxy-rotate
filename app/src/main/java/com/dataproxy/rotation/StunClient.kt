package com.dataproxy.rotation

import android.util.Log
import com.dataproxy.network.CellularNetworkProvider
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom

/**
 * Minimal RFC 5389 binding client. The socket is bound to the cellular
 * network by [CellularNetworkProvider], so the reported address is the
 * carrier-facing public IP, never the Wi-Fi one.
 */
class StunClient(private val cellular: CellularNetworkProvider) {

    fun lookup(host: String, port: Int): Result<String> {
        if (host.isBlank()) {
            return Result.failure(IOException("No IP check server is configured."))
        }
        return runCatching {
            val addresses = cellular.resolveAll(host)
            if (addresses.isEmpty()) {
                throw IOException("Could not resolve $host.")
            }
            val transactionId = ByteArray(TRANSACTION_ID_SIZE).also {
                SecureRandom().nextBytes(it)
            }
            val request = buildRequest(transactionId)

            var last: IOException? = null
            for (address in addresses) {
                try {
                    return@runCatching exchange(address, port, transactionId, request)
                } catch (e: IOException) {
                    Log.d(TAG, "STUN query via ${address.hostAddress} failed: ${e.message}")
                    last = e
                }
            }
            throw last ?: IOException("No response from $host.")
        }
    }

    private fun exchange(
        address: InetAddress,
        port: Int,
        transactionId: ByteArray,
        request: ByteArray,
    ): String {
        val socket: DatagramSocket = cellular.createBoundDatagramSocket()
        try {
            socket.soTimeout = TIMEOUT_MS
            socket.send(DatagramPacket(request, request.size, InetSocketAddress(address, port)))

            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            val buffer = ByteArray(MAX_RESPONSE)
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) throw SocketTimeoutException("The IP check timed out.")
                socket.soTimeout = remaining.toInt()
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                parse(packet.data, packet.length, transactionId)?.let { return it }
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun buildRequest(transactionId: ByteArray): ByteArray {
        val out = ByteArray(HEADER_SIZE)
        out[0] = 0x00
        out[1] = 0x01
        // Length stays 0: a binding request has no attributes.
        out[4] = MAGIC[0]
        out[5] = MAGIC[1]
        out[6] = MAGIC[2]
        out[7] = MAGIC[3]
        transactionId.copyInto(out, 8)
        return out
    }

    private fun parse(buf: ByteArray, len: Int, transactionId: ByteArray): String? {
        if (len < HEADER_SIZE) return null
        if (u16(buf, 0) != BINDING_SUCCESS) return null
        if (u16(buf, 2) + HEADER_SIZE > len) return null
        for (i in 0 until 4) {
            if (buf[4 + i] != MAGIC[i]) return null
        }
        for (i in transactionId.indices) {
            if (buf[8 + i] != transactionId[i]) return null
        }

        var offset = HEADER_SIZE
        val end = HEADER_SIZE + u16(buf, 2)
        while (offset + 4 <= end) {
            val type = u16(buf, offset)
            val length = u16(buf, offset + 2)
            val value = offset + 4
            if (value + length > end) return null
            if (type == XOR_MAPPED_ADDRESS) {
                parseAddress(buf, value, length, transactionId, xor = true)?.let { return it }
            }
            if (type == MAPPED_ADDRESS) {
                parseAddress(buf, value, length, transactionId, xor = false)?.let { return it }
            }
            offset = value + ((length + 3) and 3.inv())
        }
        return null
    }

    private fun parseAddress(
        buf: ByteArray,
        offset: Int,
        length: Int,
        transactionId: ByteArray,
        xor: Boolean,
    ): String? {
        if (length < 4) return null
        val family = buf[offset + 1].toInt() and 0xFF
        val port = u16(buf, offset + 2).let { if (xor) it xor XOR_PORT else it }
        val size = when (family) {
            FAMILY_IPV4 -> 4
            FAMILY_IPV6 -> 16
            else -> return null
        }
        if (length < 4 + size) return null

        val address = buf.copyOfRange(offset + 4, offset + 4 + size)
        if (xor) {
            val mask = ByteArray(size)
            MAGIC.copyInto(mask)
            if (size == 16) {
                transactionId.copyInto(mask, MAGIC.size)
            }
            for (i in address.indices) {
                address[i] = (address[i].toInt() xor mask[i].toInt()).toByte()
            }
        }
        val host = runCatching {
            InetAddress.getByAddress(address).hostAddress
        }.getOrNull() ?: return null
        return if (port > 0) host else null
    }

    private fun u16(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    companion object {
        private const val TAG = "StunClient"
        private const val HEADER_SIZE = 20
        private const val TRANSACTION_ID_SIZE = 12
        private const val MAX_RESPONSE = 1500
        private const val TIMEOUT_MS = 5_000

        private const val BINDING_SUCCESS = 0x0101
        private const val MAPPED_ADDRESS = 0x0001
        private const val XOR_MAPPED_ADDRESS = 0x0020
        private const val FAMILY_IPV4 = 0x01
        private const val FAMILY_IPV6 = 0x02
        private const val XOR_PORT = 0x2112

        private val MAGIC = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)
    }
}
