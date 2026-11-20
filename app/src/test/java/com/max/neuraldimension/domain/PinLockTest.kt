package com.max.neuraldimension.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Plain-JVM tests for the Part 19 PIN verifier policy: PBKDF2 hashing,
 * constant-time verification, malformed-input rejection and the progressive
 * lockout schedule. No Android, no Robolectric.
 */
class PinLockTest {

    @Test
    fun verifier_round_trips_correct_pin() {
        val v = PinLock.newVerifier("1234")
        assertTrue(PinLock.verify("1234", v))
    }

    @Test
    fun wrong_pin_is_rejected() {
        val v = PinLock.newVerifier("1234")
        assertFalse(PinLock.verify("1235", v))
        assertFalse(PinLock.verify("12345", v))
        assertFalse(PinLock.verify("", v))
    }

    @Test
    fun malformed_or_empty_verifier_is_never_valid() {
        assertFalse(PinLock.verify("1234", ""))
        assertFalse(PinLock.verify("1234", "garbage"))
        assertFalse(PinLock.verify("1234", "v1:notanumber:AAAA:BBBB"))
        assertFalse(PinLock.verify("1234", "v0:120000:AAAA:BBBB"))
        assertFalse(PinLock.verify("1234", "v1:120000:!!not-base64!!:BBBB"))
        // Zero / absurd iteration counts are rejected before any PBKDF2 work.
        assertFalse(PinLock.verify("1234", "v1:0:AAAA:BBBB"))
        assertFalse(PinLock.verify("1234", "v1:999999999:AAAA:BBBB"))
    }

    @Test
    fun same_pin_produces_different_verifiers_via_random_salt() {
        val a = PinLock.newVerifier("4242")
        val b = PinLock.newVerifier("4242")
        assertNotEquals(a, b)
        assertTrue(PinLock.verify("4242", a))
        assertTrue(PinLock.verify("4242", b))
    }

    @Test
    fun verifier_format_is_versioned_and_pin_free() {
        val v = PinLock.newVerifier("987654", iterations = 1000)
        val parts = v.split(":")
        assertEquals(4, parts.size)
        assertEquals("v1", parts[0])
        assertEquals("1000", parts[1])
        assertFalse(v.contains("987654"))
    }

    @Test
    fun invalid_pin_shapes_are_rejected() {
        listOf("12", "123", "123456789", "12a4", " 1234", "1234 ").forEach { pin ->
            assertFalse("expected invalid: '$pin'", PinLock.isValidPin(pin))
            try {
                PinLock.newVerifier(pin)
                fail("newVerifier accepted invalid pin '$pin'")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertTrue(PinLock.isValidPin("1234"))
        assertTrue(PinLock.isValidPin("12345678"))
    }

    @Test
    fun backoff_schedule_is_progressive_and_capped() {
        assertEquals(0L, PinLock.backoffDelayMillis(0))
        assertEquals(0L, PinLock.backoffDelayMillis(1))
        assertEquals(0L, PinLock.backoffDelayMillis(3))
        assertEquals(5_000L, PinLock.backoffDelayMillis(4))
        assertEquals(15_000L, PinLock.backoffDelayMillis(5))
        assertEquals(30_000L, PinLock.backoffDelayMillis(6))
        assertEquals(60_000L, PinLock.backoffDelayMillis(7))
        assertEquals(60_000L, PinLock.backoffDelayMillis(100))
        // Monotonic non-decreasing across the whole range.
        var prev = -1L
        for (f in 0..50) {
            val d = PinLock.backoffDelayMillis(f)
            assertTrue("backoff decreased at $f", d >= prev)
            prev = d
        }
    }
}
