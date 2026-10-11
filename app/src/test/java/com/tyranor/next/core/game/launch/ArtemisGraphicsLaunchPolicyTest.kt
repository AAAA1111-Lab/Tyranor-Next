package com.tyranor.next.core.game.launch

import com.core.engine.LaunchContract
import com.core.gl.Anime4kRuntime
import com.tyranor.next.core.settings.EngineSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtemisGraphicsLaunchPolicyTest {
    @Test fun filterOffKeepsChosenModeWithoutDebugExtras() {
        for (mode in EngineSettingsStore.ART_ANIME4K_MODES) {
            val extras = ArtemisGraphicsLaunchPolicy.extras(false, mode)
            assertEquals(false, extras[LaunchContract.ARTEMIS_ANIME4K_ENABLED])
            assertEquals(mode, extras[Anime4kRuntime.EXTRA_MODE])
            assertEquals(2, extras.size)
        }
    }
    @Test fun reenabledFilterKeepsChosenMode() {
        val disabled = ArtemisGraphicsLaunchPolicy.extras(false, "soft_m")
        val enabled = ArtemisGraphicsLaunchPolicy.extras(true, "soft_m")
        assertEquals(false, disabled[LaunchContract.ARTEMIS_ANIME4K_ENABLED])
        assertEquals(true, enabled[LaunchContract.ARTEMIS_ANIME4K_ENABLED])
        assertEquals("soft_m", enabled[Anime4kRuntime.EXTRA_MODE])
    }
}
