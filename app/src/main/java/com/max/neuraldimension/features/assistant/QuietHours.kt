package com.max.neuraldimension.features.assistant

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Blueprint Part 17: quiet-hours policy and daily-briefing schedule math.
 *
 * Pure JVM functions over explicit instants and zones, so unit tests exercise the
 * exact production policy rather than a copy. The quiet window is [startMin, endMin)
 * in local minutes-of-day; it wraps midnight when startMin > endMin, and
 * startMin == endMin means an empty window (never quiet). All calendar math goes
 * through ZonedDateTime so DST transitions and timezone changes stay correct
 * (a target time that falls in a DST gap shifts forward per java.time rules).
 */
object QuietHours {

    private fun zoned(nowMillis: Long, zone: ZoneId): ZonedDateTime =
        Instant.ofEpochMilli(nowMillis).atZone(zone)

    fun isQuiet(nowMillis: Long, enabled: Boolean, startMin: Int, endMin: Int, zone: ZoneId): Boolean {
        if (!enabled || startMin == endMin) return false
        val t = zoned(nowMillis, zone).let { it.hour * 60 + it.minute }
        return if (startMin < endMin) t in startMin until endMin else t >= startMin || t < endMin
    }

    /**
     * Next instant at or after [nowMillis] when notifications are allowed.
     * Returns [nowMillis] unchanged when the window is not active.
     */
    fun nextAllowedFireMillis(nowMillis: Long, enabled: Boolean, startMin: Int, endMin: Int, zone: ZoneId): Long {
        if (!isQuiet(nowMillis, enabled, startMin, endMin, zone)) return nowMillis
        val now = zoned(nowMillis, zone)
        val endTime = LocalTime.ofSecondOfDay(endMin * 60L)
        var end = now.toLocalDate().atTime(endTime).atZone(zone)
        if (!end.isAfter(now)) end = now.toLocalDate().plusDays(1).atTime(endTime).atZone(zone)
        return end.toInstant().toEpochMilli()
    }

    /**
     * Next strictly-future occurrence of a local clock time (daily briefing).
     * Recomputed from the clock on every scheduling pass, so a chained one-shot
     * schedule cannot accumulate drift the way a fixed period would.
     */
    fun nextOccurrenceMillis(nowMillis: Long, minOfDay: Int, zone: ZoneId): Long {
        val now = zoned(nowMillis, zone)
        val time = LocalTime.ofSecondOfDay(minOfDay * 60L)
        var next = now.toLocalDate().atTime(time).atZone(zone)
        if (!next.isAfter(now)) next = now.toLocalDate().plusDays(1).atTime(time).atZone(zone)
        return next.toInstant().toEpochMilli()
    }
}
