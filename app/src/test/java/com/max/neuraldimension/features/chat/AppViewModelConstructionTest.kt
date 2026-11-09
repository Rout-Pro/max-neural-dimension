package com.max.neuraldimension.features.chat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Feasibility checkpoint for the JVM pixel route: the production AppViewModel
 * eagerly starts TTS (voice.init()) and Room collectors in init, so full-screen
 * Compose captures need to know whether it constructs under Robolectric at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppViewModelConstructionTest {

    @Test
    fun vm_constructs_on_jvm() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = AppViewModel(app)
        assertNotNull(vm.settingsRepo)
    }
}
