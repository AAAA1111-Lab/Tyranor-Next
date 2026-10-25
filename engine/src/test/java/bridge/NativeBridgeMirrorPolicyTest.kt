package bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBridgeMirrorPolicyTest {
    @Test
    fun `mirror source reads exclude explicit writes while update mode requires copy up`() {
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
    fun `new launch state is isolated from late old read completions and promotions`() {
        val old = KrMirrorReadSession(mapOf("plugin" to "old"))
        old.recordRead("plugin", false)
        assertTrue(old.readFailed("plugin"))
        old.recordRead("plugin", true)
        assertFalse(old.readFailed("plugin"))
        val current = KrMirrorReadSession(mapOf("plugin" to "new"))
        old.recordRead("plugin", false)
        old.promote("plugin")
        assertFalse(current.readFailed("plugin"))
        assertEquals("new", current.entry("plugin"))
        assertEquals(null, old.entry("plugin"))
    }

    @Test
    fun `mirror asset case folding matches ROOT locale including Unicode`() {
        assertEquals("plugin/gamepad.dll", NativeBridge.normalizeMirrorCase("Plugin/GamePad.dll"))
        assertEquals("äset/σcript.dll", NativeBridge.normalizeMirrorCase("Äset/Σcript.DLL"))
    }
}
