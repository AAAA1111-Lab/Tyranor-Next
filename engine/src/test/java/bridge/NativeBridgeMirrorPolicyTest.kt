package bridge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeBridgeMirrorPolicyTest {
    @get:Rule
    val temporary = TemporaryFolder()

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
    fun `mirror asset case folding matches ROOT locale including Unicode`() {
        assertEquals("plugin/gamepad.dll", NativeBridge.normalizeMirrorCase("Plugin/GamePad.dll"))
        assertEquals("äset/σcript.dll", NativeBridge.normalizeMirrorCase("Äset/Σcript.DLL"))
    }

    @Test
    fun `missing mirror root loads an empty session instead of a fail-closed router`() {
        // R9-#16: a caller omitting the mirror root extra must not arm a session whose
        // router fails closed for every key — that would turn all opens (savedata too)
        // into EIO. The guard loads nothing, so opens fall through to normal routing.
        // (A positive routing case needs android.net.Uri and stays device-verified.)
        NativeBridge.configureSafMirror(null, null)
        assertEquals(-1L, NativeBridge.metadataSize("/data/user/0/x/games/game-k/data.xp3"))
    }
}
