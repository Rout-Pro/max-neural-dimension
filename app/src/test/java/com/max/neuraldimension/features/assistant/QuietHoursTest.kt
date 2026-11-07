package com.max.neuraldimension.features.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Unit tests for the production quiet-hours policy and briefing schedule math
 * ([QuietHours]) - the same functions the workers call, driven by fixed
 * instants and explicit zones, including DST boundaries.
 */
class QuietHoursTest {

    private val ist: ZoneId = ZoneId.of("Asia/Kolkata")
    private val ny: ZoneId = ZoneId.of("America/New_York")

    private fun at(zone: ZoneId, date: String, time: String): Long =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zone).toInstant().toEpochMilli()

    // 22:00 -> 07:00 window
    private val start = 22 * 60
    private val end = 7 * 60

    @Test
    fun disabled_is_never_quiet() {
        val now = at(ist, "2026-11-07", "23:30")
        assertFalse(QuietHours.isQuiet(now, false, start, end, ist))
        assertEquals(now, QuietHours.nextAllowedFireMillis(now, false, start, end, ist))
    }

    @Test
    fun midnight_wrapping_window() {
        assertTrue(QuietHours.isQuiet(at(ist, "2026-11-07", "23:30"), true, start, end, ist))
        assertTrue(QuietHours.isQuiet(at(ist, "2026-11-08", "00:00"), true, start, end, ist))
        assertTrue(QuietHours.isQuiet(at(ist, "2026-11-08", "06:59"), true, start, end, ist))
        assertFalse(QuietHours.isQuiet(at(ist, "2026-11-08", "07:00"), true, start, end, ist))
        assertFalse(QuietHours.isQuiet(at(ist, "2026-11-07", "21:59"), true, start, end, ist))
    }

    @Test
    fun equal_bounds_mean_empty_window() {
        val now = at(ist, "2026-11-07", "23:30")
        assertFalse(QuietHours.isQuiet(now, true, start, start, ist))
        assertEquals(now, QuietHours.nextAllowedFireMillis(now, true, start, start, ist))
    }

    @Test
    fun non_wrapping_window() {
        assertTrue(QuietHours.isQuiet(at(ist, "2026-11-07", "13:00"), true, 12 * 60, 14 * 60, ist))
        assertFalse(QuietHours.isQuiet(at(ist, "2026-11-07", "11:59"), true, 12 * 60, 14 * 60, ist))
        assertFalse(QuietHours.isQuiet(at(ist, "2026-11-07", "14:00"), true, 12 * 60, 14 * 60, ist))
    }

    @Test
    fun deferral_late_evening_lands_next_morning() {
        val now = at(ist, "2026-11-07", "23:30")
        val allowed = QuietHours.nextAllowedFireMillis(now, true, start, end, ist)
        assertEquals(at(ist, "2026-11-08", "07:00"), allowed)
    }

    @Test
    fun deferral_early_morning_lands_same_morning() {
        val now = at(ist, "2026-11-08", "03:00")
        val allowed = QuietHours.nextAllowedFireMillis(now, true, start, end, ist)
        assertEquals(at(ist, "2026-11-08", "07:00"), allowed)
    }

    @Test
    fun deferral_outside_window_is_identity() {
        val now = at(ist, "2026-11-07", "12:00")
        assertEquals(now, QuietHours.nextAllowedFireMillis(now, true, start, end, ist))
    }

    @Test
    fun next_occurrence_today_when_time_is_ahead() {
        val now = at(ist, "2026-11-07", "07:30")
        assertEquals(at(ist, "2026-11-07", "08:00"), QuietHours.nextOccurrenceMillis(now, 8 * 60, ist))
    }

    @Test
    fun next_occurrence_tomorrow_when_time_passed() {
        val now = at(ist, "2026-11-07", "10:00")
        assertEquals(at(ist, "2026-11-08", "08:00"), QuietHours.nextOccurrenceMillis(now, 8 * 60, ist))
    }

    @Test
    fun next_occurrence_is_strictly_future_at_exact_match() {
        val now = at(ist, "2026-11-07", "08:00")
        assertEquals(at(ist, "2026-11-08", "08:00"), QuietHours.nextOccurrenceMillis(now, 8 * 60, ist))
    }

    @Test
    fun dst_spring_forward_stays_on_calendar_times() {
        // 2026-03-08: America/New_York skips 02:00 -> 03:00.
        val now = at(ny, "2026-03-08", "01:30")
        assertTrue(QuietHours.isQuiet(now, true, start, end, ny))
        val allowed = QuietHours.nextAllowedFireMillis(now, true, start, end, ny)
        val allowedZoned = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(allowed), ny)
        assertEquals(LocalDate.parse("2026-03-08"), allowedZoned.toLocalDate())
        assertEquals(LocalTime.parse("07:00"), allowedZoned.toLocalTime())
        // Deferred duration must be ~4.5 clock hours, not a crashed or negative value.
        val hours = (allowed - now) / 3_600_000.0
        assertTrue(hours in 4.0..6.0)
    }

    @Test
    fun dst_fall_back_defers_to_same_morning() {
        // 2026-11-01: America/New_York repeats 01:00 -> 02:00.
        val now = at(ny, "2026-11-01", "01:30")
        assertTrue(QuietHours.isQuiet(now, true, 0, 6 * 60, ny))
        val allowed = QuietHours.nextAllowedFireMillis(now, true, 0, 6 * 60, ny)
        val allowedZoned = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(allowed), ny)
        assertEquals(LocalDate.parse("2026-11-01"), allowedZoned.toLocalDate())
        assertEquals(LocalTime.parse("06:00"), allowedZoned.toLocalTime())
    }
}
