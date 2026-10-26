package com.tyranor.next.core.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** [EngineSettingsResolver.resolveWinlatorSettings] 的三模式表驱动回归。 */
class EngineSettingsResolverWinlatorTest {
    private val global = EngineSettingsStore.Winlator(
        containerId = 5,
        containerName = "全局容器",
        graphicsDriver = "vulkan",
        dxwrapper = "wined3d",
        screenSize = "1280x720",
        lcAll = "ja_JP.UTF-8",
        tz = "Asia/Tokyo",
        box64Preset = "performance",
        save = true,
    )

    private fun clearedLegacyJson(): JSONObject = JSONObject().apply {
        put(PerGameSettingsStore.F_WINLATOR_GRAPHICS_DRIVER, "")
        put(PerGameSettingsStore.F_WINLATOR_DXWRAPPER, "")
        put(PerGameSettingsStore.F_WINLATOR_SCREEN_SIZE, "")
        put(PerGameSettingsStore.F_WINLATOR_LC_ALL, "")
        put(PerGameSettingsStore.F_WINLATOR_TZ, "")
        put(PerGameSettingsStore.F_WINLATOR_BOX64_PRESET, "")
    }

    @Test
    fun globalModeReturnsGlobalAsIs() {
        val resolved = EngineSettingsResolver.resolveWinlatorSettings(global, JSONObject())
        assertEquals(global, resolved)
    }

    @Test
    fun containerModeClearsEveryParameterAndSave() {
        val json = clearedLegacyJson()
        assertEquals(
            PerGameSettingsStore.WINLATOR_MODE_CONTAINER,
            PerGameSettingsStore.inferWinlatorMode(json),
        )

        val resolved = EngineSettingsResolver.resolveWinlatorSettings(global, json)

        // 六个参数被强制置空：不下发，沿用容器配置。
        assertEquals("", resolved.graphicsDriver)
        assertEquals("", resolved.dxwrapper)
        assertEquals("", resolved.screenSize)
        assertEquals("", resolved.lcAll)
        assertEquals("", resolved.tz)
        assertEquals("", resolved.box64Preset)
        // 容器模式固定不写回容器配置。
        assertEquals(false, resolved.save)
        // 容器 ID / 名称先继承全局。
        assertEquals(5, resolved.containerId)
        assertEquals("全局容器", resolved.containerName)
    }

    @Test
    fun containerModeLetsPerGameContainerWinOverGlobal() {
        val json = clearedLegacyJson().apply {
            put(PerGameSettingsStore.F_WINLATOR_CONTAINER_ID, "3")
            put(PerGameSettingsStore.F_WINLATOR_CONTAINER_NAME, "单游戏容器")
        }

        val resolved = EngineSettingsResolver.resolveWinlatorSettings(global, json)

        assertEquals(3, resolved.containerId)
        assertEquals("单游戏容器", resolved.containerName)
    }

    @Test
    fun customModeAppliesOverrideValues() {
        val json = JSONObject().apply {
            put(PerGameSettingsStore.F_WINLATOR_MODE, PerGameSettingsStore.WINLATOR_MODE_CUSTOM)
            put(PerGameSettingsStore.F_WINLATOR_SCREEN_SIZE, "1920x1080")
            put(PerGameSettingsStore.F_WINLATOR_SAVE, true)
        }

        val resolved = EngineSettingsResolver.resolveWinlatorSettings(global, json)

        assertEquals("1920x1080", resolved.screenSize)
        assertEquals(true, resolved.save)
        assertEquals(5, resolved.containerId)
    }
}
