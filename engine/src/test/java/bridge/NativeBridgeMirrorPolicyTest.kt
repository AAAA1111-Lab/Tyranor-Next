package bridge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBridgeMirrorPolicyTest {
    @After
    fun clearMirrorConfiguration() {
        NativeBridge.configureSafMirror(null)
    }

    @Test
    fun `mirror assets accept read intent and reject every modifying mode`() {
        assertTrue(NativeBridge.mirrorReadAllowed(NativeBridge.FileAccess.READ_ONLY, false, false))
        assertTrue(NativeBridge.mirrorReadAllowed(NativeBridge.FileAccess.READ_WRITE, false, false))
        assertFalse(NativeBridge.mirrorReadAllowed(NativeBridge.FileAccess.WRITE_ONLY, false, false))
        for (access in NativeBridge.FileAccess.entries) {
            assertFalse(NativeBridge.mirrorReadAllowed(access, true, false))
            assertFalse(NativeBridge.mirrorReadAllowed(access, false, true))
            assertFalse(NativeBridge.mirrorReadAllowed(access, true, true))
        }
    }

    @Test
    fun `provider mapping preserves contents unless truncation is explicit`() {
        assertEquals("r", NativeBridge.pfdModeFor(NativeBridge.FileAccess.READ_ONLY, false, false))
        assertEquals("rw", NativeBridge.pfdModeFor(NativeBridge.FileAccess.WRITE_ONLY, false, false))
        assertEquals("rw", NativeBridge.pfdModeFor(NativeBridge.FileAccess.READ_WRITE, false, false))
        assertEquals("wt", NativeBridge.pfdModeFor(NativeBridge.FileAccess.WRITE_ONLY, false, true))
        assertEquals("rwt", NativeBridge.pfdModeFor(NativeBridge.FileAccess.READ_WRITE, false, true))
        assertEquals("wa", NativeBridge.pfdModeFor(NativeBridge.FileAccess.WRITE_ONLY, true, false))
        assertEquals("rw", NativeBridge.pfdModeFor(NativeBridge.FileAccess.READ_WRITE, true, false))
    }

    @Test
    fun `metadata agrees with available bytes and preserves unknown v1 sizes`() {
        assertEquals(123L, NativeBridge.mirrorMetadataSize(123L, 0L, false))
        assertEquals(0L, NativeBridge.mirrorMetadataSize(0L, 0L, false))
        assertEquals(-1L, NativeBridge.mirrorMetadataSize(-1L, 0L, false))
        assertEquals(-1L, NativeBridge.mirrorMetadataSize(-2L, 0L, false))
        assertEquals(-1L, NativeBridge.mirrorMetadataSize(123L, 7L, false))
        assertEquals(-1L, NativeBridge.mirrorMetadataSize(0L, 7L, false))
        assertEquals(-1L, NativeBridge.mirrorMetadataSize(123L, 0L, true))
        assertEquals(-1L, NativeBridge.mirrorMetadataSize(0L, 0L, true))
    }

    @Test
    fun `configuring a launch clears failed metadata suppression`() {
        failures().add("/mirror/plugin.dll")
        NativeBridge.configureSafMirror(null)
        assertTrue(failures().isEmpty())
        assertEquals(-1L, NativeBridge.metadataSize(null))
    }

    @Test
    fun `late old provider failure cannot poison newly configured launch`() {
        val oldDocuments = HashMap<String, KrSafMirror.MirrorEntry>()
        val documentsField = NativeBridge::class.java.getDeclaredField("SAF_DOCUMENTS")
        documentsField.isAccessible = true
        documentsField.set(null, oldDocuments)
        val record = NativeBridge::class.java.getDeclaredMethod(
            "recordMirrorReadResult", Map::class.java, String::class.java, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        record.invoke(NativeBridge, oldDocuments, "/mirror/plugin.dll", false)
        assertTrue(failures().contains("/mirror/plugin.dll"))
        record.invoke(NativeBridge, oldDocuments, "/mirror/plugin.dll", true)
        assertFalse(failures().contains("/mirror/plugin.dll"))
        NativeBridge.configureSafMirror(null)
        record.invoke(NativeBridge, oldDocuments, "/mirror/plugin.dll", false)
        assertFalse(failures().contains("/mirror/plugin.dll"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun failures(): MutableSet<String> = NativeBridge::class.java.getDeclaredField("SAF_READ_FAILED")
        .apply { isAccessible = true }.get(null) as MutableSet<String>
}
