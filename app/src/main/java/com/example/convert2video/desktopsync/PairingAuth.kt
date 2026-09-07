package com.example.convert2video.desktopsync

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal fun generatePairingToken(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(32)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

internal fun bearerTokenFromHeader(header: String?): String? {
    val value = header ?: return null
    if (!value.startsWith("Bearer ")) return null
    val token = value.removePrefix("Bearer ")
    if (token.isBlank() || token.any(Char::isWhitespace)) return null
    return token
}

internal fun bearerTokenMatches(expected: String?, authorizationHeader: String?): Boolean {
    val supplied = bearerTokenFromHeader(authorizationHeader) ?: return false
    val expectedBytes = expected?.toByteArray(StandardCharsets.UTF_8) ?: return false
    val suppliedBytes = supplied.toByteArray(StandardCharsets.UTF_8)
    return MessageDigest.isEqual(expectedBytes, suppliedBytes)
}
