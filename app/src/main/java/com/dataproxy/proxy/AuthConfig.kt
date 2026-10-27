package com.dataproxy.proxy

/**
 * Shared proxy username/password auth settings.
 *
 * SOCKS5 uses RFC 1929; HTTP proxy requests use Basic proxy authentication.
 * Both validate against [username] / [password].
 */
data class AuthConfig(
    val enabled: Boolean,
    val username: String,
    val password: String,
) {
    companion object {
        val Disabled = AuthConfig(enabled = false, username = "", password = "")
    }
}
