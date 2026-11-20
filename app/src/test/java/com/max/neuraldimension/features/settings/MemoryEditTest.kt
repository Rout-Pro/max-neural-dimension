package com.max.neuraldimension.features.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.max.neuraldimension.data.prefs.MaxSettings
import com.max.neuraldimension.data.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Device-free (Robolectric) evidence for the Part 13 memory-editing gap fix:
 * the exact replace semantics the Settings edit dialog applies to the saved
 * facts set - replace one fact in place, preserve the rest, never duplicate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryEditTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun edit_replaces_one_fact_and_preserves_the_rest() {
        val repo = SettingsRepository(app)
        runBlocking(Dispatchers.IO) {
            repo.update { MaxSettings(memories = setOf("likes tea", "birthday Oct 17", "lives in Bhubaneswar")) }
            // The dialog's save action: remove original, add trimmed replacement.
            repo.update { it.copy(memories = it.memories - "likes tea" + "likes masala chai") }
            val s = repo.settings.first()
            assertEquals(setOf("birthday Oct 17", "lives in Bhubaneswar", "likes masala chai"), s.memories)
            assertFalse(s.memories.contains("likes tea"))
        }
    }

    @Test
    fun edit_to_an_existing_fact_never_duplicates() {
        val repo = SettingsRepository(app)
        runBlocking(Dispatchers.IO) {
            repo.update { MaxSettings(memories = setOf("a", "b")) }
            repo.update { it.copy(memories = it.memories - "a" + "b") }
            val s = repo.settings.first()
            assertEquals(setOf("b"), s.memories)
            assertEquals(1, s.memories.size)
            assertTrue(s.memories.contains("b"))
        }
    }
}
