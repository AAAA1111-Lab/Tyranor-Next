package com.tyranor.next.core.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** 单游戏 Winlator 运行模式：1.61 旧数据（null / "" / 值）的迁移推断。 */
class WinlatorModeMigrationTest {
    private fun build(vararg pairs: Pair<String, Any>): JSONObject = JSONObject().apply {
        pairs.forEach { (key, value) -> put(key, value) }
    }

    /** 六个参数字段都存在且全为空串（1.61「跟随容器」的形态），可再叠加其它旧字段。 */
    private fun buildClearedParams(vararg extra: Pair<String, Any>): JSONObject = JSONObject().apply {
        put(PerGameSettingsStore.F_WINLATOR_GRAPHICS_DRIVER, "")
        put(PerGameSettingsStore.F_WINLATOR_DXWRAPPER, "")
        put(PerGameSettingsStore.F_WINLATOR_SCREEN_SIZE, "")
        put(PerGameSettingsStore.F_WINLATOR_LC_ALL, "")
        put(PerGameSettingsStore.F_WINLATOR_TZ, "")
        put(PerGameSettingsStore.F_WINLATOR_BOX64_PRESET, "")
        extra.forEach { (key, value) -> put(key, value) }
    }

    private fun infer(json: JSONObject): String = PerGameSettingsStore.inferWinlatorMode(json)

    @Test
    fun explicitModeBeatsLegacyFields() {
        val json = build(
            PerGameSettingsStore.F_WINLATOR_MODE to PerGameSettingsStore.WINLATOR_MODE_GLOBAL,
            PerGameSettingsStore.F_WINLATOR_GRAPHICS_DRIVER to "vulkan",
        )
        assertEquals(PerGameSettingsStore.WINLATOR_MODE_GLOBAL, infer(json))
    }

    @Test
    fun emptyRecordFollowsGlobal() {
        assertEquals(PerGameSettingsStore.WINLATOR_MODE_GLOBAL, infer(JSONObject()))
    }

    @Test
    fun nonEmptyParameterIsCustom() {
        val json = build(PerGameSettingsStore.F_WINLATOR_GRAPHICS_DRIVER to "vulkan")
        assertEquals(PerGameSettingsStore.WINLATOR_MODE_CUSTOM, infer(json))
    }

    @Test
    fun containerOnlyRecordIsCustom() {
        val json = build(PerGameSettingsStore.F_WINLATOR_CONTAINER_ID to "3")
        assertEquals(PerGameSettingsStore.WINLATOR_MODE_CUSTOM, infer(json))
    }

    @Test
    fun allParametersClearedFollowsContainer() {
        assertEquals(PerGameSettingsStore.WINLATOR_MODE_CONTAINER, infer(buildClearedParams()))
    }

    @Test
    fun partiallyClearedParametersAreCustom() {
        val json = build(
            PerGameSettingsStore.F_WINLATOR_GRAPHICS_DRIVER to "",
            PerGameSettingsStore.F_WINLATOR_DXWRAPPER to "",
        )
        assertEquals(PerGameSettingsStore.WINLATOR_MODE_CUSTOM, infer(json))
    }

    @Test
    fun saveOnlyRecordKeepsItsOverride() {
        val json = build(PerGameSettingsStore.F_WINLATOR_SAVE to true)
        assertEquals(PerGameSettingsStore.WINLATOR_MODE_CUSTOM, infer(json))
    }

    @Test
    fun allParametersClearedWithSaveStillFollowsContainer() {
        val json = buildClearedParams(PerGameSettingsStore.F_WINLATOR_SAVE to true)
        assertEquals(PerGameSettingsStore.WINLATOR_MODE_CONTAINER, infer(json))
    }

    @Test
    fun legacyCustomFlagMapsToCustomOrContainer() {
        assertEquals(
            PerGameSettingsStore.WINLATOR_MODE_CUSTOM,
            infer(build(PerGameSettingsStore.F_WINLATOR_CUSTOM to true)),
        )
        assertEquals(
            PerGameSettingsStore.WINLATOR_MODE_CONTAINER,
            infer(build(PerGameSettingsStore.F_WINLATOR_CUSTOM to false)),
        )
    }
}
