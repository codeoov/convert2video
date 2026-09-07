package com.example.convert2video.desktopsync

/** A single desktop pairing request waiting for an explicit foreground approval. */
data class PairingRequest(
    val requestId: Long,
    val deviceName: String,
    val remoteHost: String,
    val requestedAtEpochMillis: Long,
)

internal sealed interface PairingDecision {
    data class Approved(val token: String) : PairingDecision

    data object Denied : PairingDecision

    data object TimedOut : PairingDecision

    data object InProgress : PairingDecision

    data object Backgrounded : PairingDecision

    data object ServiceStopped : PairingDecision

    data object StorageFailed : PairingDecision
}

internal const val PAIR_REQUEST_ROUTE = "/pair/request"
internal const val WHO_AM_I_ROUTE = "/whoami"
internal const val UPLOAD_ROUTE = "/upload"
internal const val PAIRING_REQUEST_TIMEOUT_MILLIS = 60_000L
internal const val PAIRING_REQUEST_MAX_BODY_BYTES = 16 * 1024L
internal const val MAX_UPLOAD_BYTES = 500L * 1024 * 1024

internal const val UPLOAD_FILE_FIELD = "file"
internal const val UPLOAD_ORIGINAL_FILE_NAME_FIELD = "originalFileName"
internal const val UPLOAD_FORMAT_FIELD = "format"
internal const val UPLOAD_DURATION_MS_FIELD = "durationMs"
internal const val UPLOAD_FORMAT_WAV = "wav"

internal const val ERROR_BAD_REQUEST = "bad_request"
internal const val ERROR_UNAUTHORIZED = "unauthorized"
internal const val ERROR_PAYLOAD_TOO_LARGE = "too_large"
internal const val ERROR_INTERNAL = "internal"
