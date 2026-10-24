package com.dataproxy.rotation

/** State of the privileged helper that controls airplane mode. */
sealed interface HelperStatus {
    data object Checking : HelperStatus
    data class NeedsSetup(val reason: String? = null) : HelperStatus
    data object Ready : HelperStatus
}

/** Current or most recent IP rotation. */
sealed interface RotationStatus {
    data object Idle : RotationStatus
    data class Running(val attempt: Int) : RotationStatus
    data class Succeeded(
        val ipBefore: String,
        val ipAfter: String,
        val attempts: Int,
    ) : RotationStatus
    data class Failed(val reason: String, val attempts: Int) : RotationStatus
    data class Unverified(
        val reason: String,
        val ipBefore: String?,
        val ipAfter: String?,
        val attempts: Int,
    ) : RotationStatus
}

/** Progress of the one-time Wireless debugging setup. */
sealed interface SetupState {
    data object Idle : SetupState
    data object Pairing : SetupState
    data object Connecting : SetupState
    data object Done : SetupState
}

/** Result of the standalone "Check my IP" action. */
sealed interface IpCheckState {
    data object Idle : IpCheckState
    data object Checking : IpCheckState
    data class Success(val ip: String) : IpCheckState
    data class Failure(val message: String) : IpCheckState
}

/** Outcome of submitting a rotation request. */
enum class RotationStart { Accepted, Disabled, Busy, Unsupported }
