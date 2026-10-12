package com.tyranor.next.core.engine.graphics

import com.core.engine.LaunchContract
import com.core.gl.GraphicsFilterChain
import com.tyranor.next.core.settings.*
import org.junit.Assert.*
import org.junit.Test

class GraphicsLaunchPolicyTest {
    private val saved = GraphicsSettings(true, listOf(GraphicsNode("m"), GraphicsNode("deblur", false), GraphicsNode("lanczos")), true)

    @Test fun supportedActualExtrasPreserveOrderAndPauseAndEnableAnalyzer() {
        val extras = GraphicsLaunchPolicy.extras(saved, GraphicsCapabilities.forRuntime(GraphicsFamily.ARTEMIS, "official", "6"))
        assertEquals("v1:m,!deblur,lanczos", extras[LaunchContract.GRAPHICS_FILTER_CHAIN])
        assertEquals(true, extras[LaunchContract.GRAPHICS_FILTER_ENABLED])
        assertEquals(true, extras[LaunchContract.GRAPHICS_FILTER_ANALYZER])
        assertEquals(3, GraphicsFilterChain.parse(extras[LaunchContract.GRAPHICS_FILTER_CHAIN] as String)!!.steps().size)
    }

    @Test fun unsupportedKernelsCarryDisabledContractWithoutSwitchingOrBorrowingSettings() {
        for (capability in listOf(
            GraphicsCapabilities.forRuntime(GraphicsFamily.KR, "krkrsdl3"),
            GraphicsCapabilities.forRuntime(GraphicsFamily.ARTEMIS, "official", "2"),
        )) {
            val extras = GraphicsLaunchPolicy.extras(saved, capability)
            assertEquals(false, extras[LaunchContract.GRAPHICS_FILTER_ENABLED])
            assertEquals(false, extras[LaunchContract.GRAPHICS_FILTER_ANALYZER])
            assertEquals("v1:m,!deblur,lanczos", extras[LaunchContract.GRAPHICS_FILTER_CHAIN])
            assertEquals(setOf(LaunchContract.GRAPHICS_FILTER_CHAIN, LaunchContract.GRAPHICS_FILTER_ENABLED,
                LaunchContract.GRAPHICS_FILTER_ANALYZER, LaunchContract.GRAPHICS_FILTER_ANALYZER_POSITION,
                LaunchContract.GRAPHICS_FILTER_REFRESH_INTERVAL_MS), extras.keys)
        }
        val neutral = GraphicsLaunchPolicy.disabledExtras()
        assertEquals("v1:", neutral[LaunchContract.GRAPHICS_FILTER_CHAIN])
        assertEquals(false, neutral[LaunchContract.GRAPHICS_FILTER_ENABLED])
        assertEquals(false, neutral[LaunchContract.GRAPHICS_FILTER_ANALYZER])
    }

    @Test fun siglusUsesTheSharedOrderedChainWithoutChangingItsRuntime() {
        val capability = GraphicsCapabilities.forRuntime(GraphicsFamily.SIGLUS)
        val extras = GraphicsLaunchPolicy.extras(saved, capability)
        assertTrue(capability.supported)
        assertEquals("v1:m,!deblur,lanczos", extras[LaunchContract.GRAPHICS_FILTER_CHAIN])
        assertEquals(true, extras[LaunchContract.GRAPHICS_FILTER_ENABLED])
        assertEquals(true, extras[LaunchContract.GRAPHICS_FILTER_ANALYZER])
        val disabled = GraphicsLaunchPolicy.extras(saved.copy(enabled = false), capability)
        assertEquals(false, disabled[LaunchContract.GRAPHICS_FILTER_ENABLED])
        assertEquals(false, disabled[LaunchContract.GRAPHICS_FILTER_ANALYZER])
    }

    @Test fun masterOffEmptyPausedAndInvalidChainsNeverRequestAnalyzer() {
        val capability = GraphicsCapabilities.forRuntime(GraphicsFamily.KR, "kirikiri2")
        for (settings in listOf(saved.copy(enabled = false), saved.copy(chain = emptyList()),
            saved.copy(chain = listOf(GraphicsNode("s", false))), saved.copy(chain = listOf(GraphicsNode("invalid"))))) {
            val extras = GraphicsLaunchPolicy.extras(settings, capability)
            assertEquals(false, extras[LaunchContract.GRAPHICS_FILTER_ENABLED])
            assertEquals(false, extras[LaunchContract.GRAPHICS_FILTER_ANALYZER])
            assertNotNull(GraphicsFilterChain.parse(extras[LaunchContract.GRAPHICS_FILTER_CHAIN] as String))
        }
        assertEquals(true, GraphicsLaunchPolicy.extras(saved, capability)[LaunchContract.GRAPHICS_FILTER_ANALYZER])
    }

    @Test fun inheritedGameRequestsUseTheResolvedMasterAndChain() {
        val capability = GraphicsCapabilities.forRuntime(GraphicsFamily.ARTEMIS, "official", "6")
        val gameOff = GraphicsSettingsCodec.resolve(saved, GraphicsOverride(enabled = false, analyzer = true))
        assertEquals(false, GraphicsLaunchPolicy.extras(gameOff, capability)[LaunchContract.GRAPHICS_FILTER_ANALYZER])
        val gameOn = GraphicsSettingsCodec.resolve(saved.copy(enabled = false), GraphicsOverride(enabled = true))
        assertEquals(true, GraphicsLaunchPolicy.extras(gameOn, capability)[LaunchContract.GRAPHICS_FILTER_ANALYZER])
    }
    @Test fun analyzerIsGatedForUnsupportedOrInactiveHosts() {
        val settings = saved.copy(analyzer = true)
        val supported = GraphicsCapabilities.forRuntime(GraphicsFamily.SIGLUS)
        val extras = GraphicsLaunchPolicy.extras(settings, supported)
        assertEquals(true, extras[LaunchContract.GRAPHICS_FILTER_ANALYZER])
        for (capability in listOf(supported, GraphicsCapabilities.forRuntime(GraphicsFamily.KR, "krkrsdl3"))) {
            assertEquals(false, GraphicsLaunchPolicy.extras(settings.copy(enabled = false), capability)[LaunchContract.GRAPHICS_FILTER_ANALYZER])
        }
        assertEquals(false, GraphicsLaunchPolicy.extras(settings, GraphicsCapabilities.forRuntime(GraphicsFamily.ARTEMIS, "official", "2"))[LaunchContract.GRAPHICS_FILTER_ANALYZER])
    }


    @Test fun autoKernelPreservesMigratedModeAndUnsupportedVersionsStayDisabled() {
        val migrated = GraphicsSettingsCodec.legacyGlobal(GraphicsFamily.KR, krMode = "m")
        for (kernel in listOf("auto", "kirikiri2")) {
            for (version in listOf("auto", "1.3.9", "1.3.4")) {
                val capability = GraphicsCapabilities.forRuntime(GraphicsFamily.KR, kernel, version)
                assertTrue(capability.supported)
                val extras = GraphicsLaunchPolicy.extras(migrated, capability)
                assertEquals(true, extras[LaunchContract.GRAPHICS_FILTER_ENABLED])
                assertEquals(GraphicsSettingsCodec.encodeChain(migrated.chain), extras[LaunchContract.GRAPHICS_FILTER_CHAIN])
            }
            for (version in listOf("1.2.6", "unknown")) {
                val capability = GraphicsCapabilities.forRuntime(GraphicsFamily.KR, kernel, version)
                assertFalse(capability.supported)
                assertEquals(false, GraphicsLaunchPolicy.extras(migrated, capability)[LaunchContract.GRAPHICS_FILTER_ENABLED])
            }
        }
    }
}
