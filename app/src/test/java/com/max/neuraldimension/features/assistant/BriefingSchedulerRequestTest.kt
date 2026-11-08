package com.max.neuraldimension.features.assistant

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Scheduler-construction tests for [BriefingScheduler.buildRequest] - the
 * production request builder used by both reconcile (REPLACE) and chain (KEEP).
 * Distinct from the QuietHours policy-math tests: these cover what the
 * orchestration actually enqueues (generation token, tag, delay).
 */
@RunWith(RobolectricTestRunner::class)
class BriefingSchedulerRequestTest {

    @Test
    fun request_carries_generation_tag_and_delay() {
        val req = BriefingScheduler.buildRequest(7, 60_000)
        assertEquals(7, req.workSpec.input.getInt(BriefingScheduler.KEY_GENERATION, -1))
        assertTrue(req.tags.contains(BriefingScheduler.TAG))
        assertEquals(60_000L, req.workSpec.initialDelay)
    }

    @Test
    fun negative_delay_is_clamped_to_zero() {
        val req = BriefingScheduler.buildRequest(1, -5_000)
        assertEquals(0L, req.workSpec.initialDelay)
    }

    @Test
    fun missing_generation_defaults_to_minus_one() {
        // A request built without the key must never match a stored generation
        // (which starts at 0), so legacy/unknown work is always treated as stale.
        val req = androidx.work.OneTimeWorkRequestBuilder<BriefingWorker>().build()
        assertEquals(-1, req.workSpec.input.getInt(BriefingScheduler.KEY_GENERATION, -1))
    }
}
