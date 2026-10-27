package com.dataproxy.proxy

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** Shared HTTP Basic credential check for proxy requests and the control API. */
object ProxyBasicAuth {
    fun isAuthorized(header: String?, auth: AuthConfig): Boolean {
        if (auth.username.isEmpty() || auth.password.isEmpty()) return false
        if (header == null || !header.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = runCatching {
            String(
                Base64.getDecoder().decode(header.substring("Basic ".length).trim()),
                StandardCharsets.UTF_8,
            )
        }.getOrNull() ?: return false
        val expected = "${auth.username}:${auth.password}"
        return MessageDigest.isEqual(
            decoded.toByteArray(StandardCharsets.UTF_8),
            expected.toByteArray(StandardCharsets.UTF_8),
        )
    }
}
