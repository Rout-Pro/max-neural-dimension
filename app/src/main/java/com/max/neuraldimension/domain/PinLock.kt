package com.max.neuraldimension.domain

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Blueprint Part 19: app PIN lock. The PIN itself is never stored; only a
 * salted PBKDF2-HMAC-SHA256 verifier string is kept in DataStore. Pure Kotlin
 * with no Android dependencies so the whole policy is plain-JVM testable.
 */
object PinLock {

    const val MIN_PIN = 4
    const val MAX_PIN = 8
    const val ITERATIONS = 120_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private const val VERSION = "v1"

    fun isValidPin(pin: String): Boolean =
        pin.length in MIN_PIN..MAX_PIN && pin.all { it.isDigit() }

    /** Builds the stored verifier "v1:iterations:saltB64:hashB64" for a new PIN. */
    fun newVerifier(pin: String, iterations: Int = ITERATIONS, random: SecureRandom = SecureRandom()): String {
        require(isValidPin(pin)) { "PIN must be $MIN_PIN-$MAX_PIN digits" }
        require(iterations > 0) { "iterations must be positive" }
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val hash = pbkdf2(pin, salt, iterations)
        return listOf(VERSION, iterations.toString(), b64(salt), b64(hash)).joinToString(":")
    }

    fun isSet(verifier: String): Boolean = verifier.isNotBlank()

    /**
     * Constant-time verification. Malformed or foreign-format verifiers are
     * simply rejected (false), never treated as an unlocked state.
     */
    fun verify(pin: String, verifier: String): Boolean {
        val parts = verifier.split(":")
        if (parts.size != 4 || parts[0] != VERSION) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        if (iterations <= 0 || iterations > 2_000_000) return false
        val salt = runCatching { unb64(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { unb64(parts[3]) }.getOrNull() ?: return false
        if (expected.isEmpty()) return false
        val actual = pbkdf2(pin, salt, iterations)
        return MessageDigest.isEqual(expected, actual)
    }

    /**
     * Progressive lockout after consecutive failed attempts: the first three
     * tries are immediate, then the required wait between attempts grows
     * 5s -> 15s -> 30s -> 60s cap. Index by the current consecutive-failure
     * count (the count BEFORE the attempt being gated).
     */
    fun backoffDelayMillis(failures: Int): Long = when {
        failures <= 3 -> 0L
        failures == 4 -> 5_000L
        failures == 5 -> 15_000L
        failures == 6 -> 30_000L
        else -> 60_000L
    }

    private fun pbkdf2(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    // java.util.Base64 (minSdk 26): android.util.Base64 is unavailable on the plain JVM.
    private fun b64(b: ByteArray): String = java.util.Base64.getEncoder().withoutPadding().encodeToString(b)
    private fun unb64(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
}
