package com.max.neuraldimension.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-free evidence for the Part 13 context-management policy: turn cap,
 * character budget with oldest-first eviction, the newest turn always kept,
 * and order preservation. Pure JVM.
 */
class ChatContextTest {

    private fun turns(n: Int, chars: Int = 10) =
        (1..n).map { ChatTurn(if (it % 2 == 1) "user" else "assistant", "m$it-" + "x".repeat(chars)) }

    @Test
    fun within_limits_is_unchanged() {
        val t = turns(6)
        val w = ChatContext.window(t)
        assertEquals(t, w)
    }

    @Test
    fun turn_cap_keeps_newest_in_order() {
        val t = turns(40)
        val w = ChatContext.window(t)
        assertEquals(ChatContext.MAX_TURNS, w.size)
        assertEquals(t.takeLast(ChatContext.MAX_TURNS), w)
        // Roles and text untouched, chronological order preserved.
        assertEquals(t.last(), w.last())
    }

    @Test
    fun char_budget_drops_oldest_first() {
        // 24 turns of 1000 chars = 24k > 16k budget: oldest evicted until under.
        val t = turns(24, chars = 1000)
        val w = ChatContext.window(t)
        assertTrue(w.size < 24)
        assertTrue(w.sumOf { it.text.length } <= ChatContext.MAX_CHARS)
        assertEquals(t.last(), w.last())
        // Kept turns are a contiguous newest suffix.
        assertEquals(t.takeLast(w.size), w)
    }

    @Test
    fun newest_turn_always_survives_and_is_bounded() {
        val huge = ChatTurn("user", "y".repeat(50_000))
        val w = ChatContext.window(listOf(ChatTurn("assistant", "old"), huge))
        assertEquals(1, w.size)
        assertEquals("user", w[0].role)
        assertEquals(ChatContext.MAX_CHARS, w[0].text.length)

        val alone = ChatContext.window(listOf(huge))
        assertEquals(1, alone.size)
        assertEquals(ChatContext.MAX_CHARS, alone[0].text.length)
    }
}
