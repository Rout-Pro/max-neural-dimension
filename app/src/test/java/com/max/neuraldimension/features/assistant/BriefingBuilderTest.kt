package com.max.neuraldimension.features.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the production briefing text composition ([BriefingBuilder.compose])
 * - the same function the worker's Room-reading wrapper feeds.
 */
class BriefingBuilderTest {

    @Test
    fun plural_tasks() {
        assertEquals("2 open tasks", BriefingBuilder.compose(2, null, null, 0, 0))
    }

    @Test
    fun singular_task() {
        assertEquals("1 open task", BriefingBuilder.compose(1, null, null, 0, 0))
    }

    @Test
    fun reminder_without_habits() {
        assertEquals(
            "0 open tasks - next reminder: standup in 0m",
            BriefingBuilder.compose(0, "standup", 0, 0, 0)
        )
    }

    @Test
    fun full_composition_ordering() {
        assertEquals(
            "3 open tasks - next reminder: call mom in 45m - habits 1/3 done today",
            BriefingBuilder.compose(3, "call mom", 45, 1, 3)
        )
    }

    @Test
    fun negative_minutes_clamped_to_zero() {
        assertEquals(
            "0 open tasks - next reminder: x in 0m",
            BriefingBuilder.compose(0, "x", -5, 0, 0)
        )
    }

    @Test
    fun habits_omitted_when_none_exist() {
        assertEquals("0 open tasks", BriefingBuilder.compose(0, null, null, 2, 0))
    }
}
