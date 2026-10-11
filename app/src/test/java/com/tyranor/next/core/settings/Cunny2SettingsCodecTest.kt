package com.tyranor.next.core.settings

import com.core.gl.GraphicsFilterChain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Cunny2SettingsCodecTest {
    private val codec = GraphicsSettingsCodec

    @Test fun retiredHeavyPresetsDoNotEraseOtherNodesOrFlags() {
        val json = JSONObject().put("version", 2).put("enabled", true).put("monitor", true)
            .put("chain", JSONArray().put(JSONObject().put("id", "cunny_4x32_nvl").put("enabled", true))
                .put(JSONObject().put("id", "s").put("enabled", true))
                .put(JSONObject().put("id", "cunny_8x32_nvl").put("enabled", true)))
        assertEquals(listOf(GraphicsNode("s")), codec.decodeSettings(json.toString())!!.chain)
        assertEquals(listOf(GraphicsNode("s")), codec.decodeOverride(json)!!.chain)
        assertFalse(codec.IDS.contains("cunny_4x32_nvl"))
        assertFalse(codec.IDS.contains("cunny_8x32_nvl"))
    }

}
