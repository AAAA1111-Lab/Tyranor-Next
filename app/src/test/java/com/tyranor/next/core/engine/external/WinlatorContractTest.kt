package com.tyranor.next.core.engine.external

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Winlator 外置启动下发参数映射：空值不下发、box64Preset 走 overrides JSON、save 仅在开启时下发。
 */
class WinlatorContractTest {

    /** 未配置参数时保持空载荷，由接收端使用容器配置。 */
    @Test
    fun emptyOptionsProduceNoExtras() {
        assertTrue(WinlatorContract.extras(WinlatorContract.LaunchOptions()).isEmpty())
    }

    /** 空白字符串、零容器 ID 与关闭的 save 均不形成显式覆盖。 */
    @Test
    fun blankStringsAndZeroIdAreSkipped() {
        val extras = WinlatorContract.extras(
            WinlatorContract.LaunchOptions(
                containerId = 0,
                containerName = "   ",
                graphicsDriver = "",
                dxwrapper = " ",
                screenSize = "",
                lcAll = "",
                tz = "",
                box64Preset = "",
                save = false,
            ),
        )

        assertTrue(extras.isEmpty())
    }

    /** 已配置值映射到既有协议键，保留容器 ID 的整数类型。 */
    @Test
    fun valuesAreMappedToProtocolKeys() {
        val extras = WinlatorContract.extras(
            WinlatorContract.LaunchOptions(
                containerId = 3,
                containerName = "容器-1",
                graphicsDriver = "turnip,zink",
                dxwrapper = "dxvk",
                screenSize = "1600x900",
                lcAll = "ja_JP.UTF-8",
                tz = "Asia/Tokyo",
            ),
        )

        assertEquals(3, extras[WinlatorContract.EXTRA_CONTAINER_ID])
        assertEquals("容器-1", extras[WinlatorContract.EXTRA_CONTAINER_NAME])
        assertEquals("turnip,zink", extras[WinlatorContract.EXTRA_GRAPHICS_DRIVER])
        assertEquals("dxvk", extras[WinlatorContract.EXTRA_DXWRAPPER])
        assertEquals("1600x900", extras[WinlatorContract.EXTRA_SCREEN_SIZE])
        assertEquals("ja_JP.UTF-8", extras[WinlatorContract.EXTRA_LC_ALL])
        assertEquals("Asia/Tokyo", extras[WinlatorContract.EXTRA_TZ])
        assertFalse(extras.containsKey(WinlatorContract.EXTRA_SAVE))
    }

    /** Box64 预设放入 overrides JSON，而不是新增顶层 Intent 参数。 */
    @Test
    fun box64PresetIsSentThroughOverridesJson() {
        val extras = WinlatorContract.extras(
            WinlatorContract.LaunchOptions(box64Preset = "PERFORMANCE"),
        )

        assertEquals(
            """{"${WinlatorContract.OVERRIDE_BOX64_PRESET}":"PERFORMANCE"}""",
            extras[WinlatorContract.EXTRA_OVERRIDES],
        )
    }

    /** 仅在开启保存时下发 save=true，关闭时不发送该参数。 */
    @Test
    fun saveIsSentOnlyWhenEnabled() {
        assertEquals(true, WinlatorContract.extras(WinlatorContract.LaunchOptions(save = true))[WinlatorContract.EXTRA_SAVE])
        assertFalse(WinlatorContract.extras(WinlatorContract.LaunchOptions(save = false)).containsKey(WinlatorContract.EXTRA_SAVE))
    }

    /** 两种显式预设沿用 wined3d 协议，并把 renderer 放在首个配置槽。 */
    @Test
    fun explicitWineD3dPresetsUseTheExistingWrapperAndFirstConfigSlot() {
        for ((preset, renderer) in listOf("wined3d_gl" to "gl", "wined3d_gdi" to "gdi")) {
            val extras = WinlatorContract.extras(WinlatorContract.LaunchOptions(dxwrapper = preset))
            val overrides = JSONObject(extras[WinlatorContract.EXTRA_OVERRIDES] as String)

            assertEquals("wined3d", extras[WinlatorContract.EXTRA_DXWRAPPER])
            assertEquals("renderer=$renderer", overrides.getString("dxwrapperConfig"))
            assertEquals(1, overrides.length())
            assertFalse(extras.containsKey(WinlatorContract.EXTRA_SAVE))
        }
    }

    /** 渲染预设与 Box64 共存，开启 save 时保留持久化标志。 */
    @Test
    fun rendererPresetAndBox64AreMergedIntoOneOverridesObject() {
        for ((preset, renderer) in listOf("wined3d_gl" to "gl", "wined3d_gdi" to "gdi")) {
            val extras = WinlatorContract.extras(
                WinlatorContract.LaunchOptions(dxwrapper = preset, box64Preset = "CUSTOM-3", save = true),
            )
            val overrides = JSONObject(extras[WinlatorContract.EXTRA_OVERRIDES] as String)

            assertEquals("wined3d", extras[WinlatorContract.EXTRA_DXWRAPPER])
            assertEquals("renderer=$renderer", overrides.getString("dxwrapperConfig"))
            assertEquals("CUSTOM-3", overrides.getString("box64Preset"))
            assertEquals(2, overrides.length())
            assertEquals(true, extras[WinlatorContract.EXTRA_SAVE])
        }
    }

    /** 旧选项不覆盖容器渲染器，即使同时指定 Box64 预设。 */
    @Test
    fun legacyWrappersDoNotOverrideTheContainerRenderer() {
        for (wrapper in listOf("", "dxvk", "wined3d")) {
            val extras = WinlatorContract.extras(WinlatorContract.LaunchOptions(dxwrapper = wrapper))
            assertFalse(extras.containsKey(WinlatorContract.EXTRA_OVERRIDES))
            if (wrapper.isEmpty()) assertFalse(extras.containsKey(WinlatorContract.EXTRA_DXWRAPPER))
            else assertEquals(wrapper, extras[WinlatorContract.EXTRA_DXWRAPPER])

            val withBox64 = WinlatorContract.extras(
                WinlatorContract.LaunchOptions(dxwrapper = wrapper, box64Preset = "STABILITY"),
            )
            val overrides = JSONObject(withBox64[WinlatorContract.EXTRA_OVERRIDES] as String)
            assertFalse(overrides.has("dxwrapperConfig"))
            assertEquals("STABILITY", overrides.getString("box64Preset"))
        }
    }
}
