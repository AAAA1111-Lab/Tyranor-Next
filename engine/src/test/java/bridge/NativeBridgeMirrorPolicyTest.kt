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
        assertFalse(NativeBridge.mirrorSessionDegradedForTest)
    }

    @Test
    fun `corrupt or zero-row index marks the session degraded for fail-closed opens`() {
        // R10 #2: a present but unreadable/zero-row index must not silently serve the
        // 0-byte placeholders as empty content; the session is flagged and the open
        // gate fails every mirror-root path while savedata stays local.
        val mirrorRoot = temporary.newFolder("game-corrupt")
        val indexFile = File(temporary.newFolder("idx"), "corrupt.idx")
        indexFile.writeText("not-an-index-header\n")
        NativeBridge.configureSafMirror(indexFile.path, mirrorRoot.path)
        assertTrue(NativeBridge.mirrorSessionDegradedForTest)
        val session = NativeBridge.mirrorSessionForTest
        assertTrue(NativeBridge.mirrorDegradedBlock(session, File(mirrorRoot, "data.xp3").path.lowercase(), File(mirrorRoot, "data.xp3")))
        assertFalse(NativeBridge.mirrorDegradedBlock(session, File(mirrorRoot, "savedata/slot.ksd").path.lowercase(), File(mirrorRoot, "savedata/slot.ksd")))
        assertFalse(NativeBridge.mirrorDegradedBlock(session, "/storage/emulated/0/elsewhere/data.xp3", File("/storage/emulated/0/elsewhere/data.xp3")))
    }

    @Test
    fun `header-only index keeps live local overrides working across the degraded gate`() {
        // Audit of df6984c, P3-1: a fresh rebuild whose rows are all local overrides
        // publishes a header-only index; the degraded gate must not block those live
        // owned files on the next launch (marker + file present → not blocked).
        val mirrorRoot = temporary.newFolder("game-overrides")
        val indexFile = File(temporary.newFolder("idx"), "header-only.idx")
        indexFile.writeText("krkr-saf-index-v2\n")
        val overrideTarget = File(mirrorRoot, "data.xp3").apply { createNewFile() }
        KrMirrorLocalFiles(mirrorRoot).markOverridden(overrideTarget)
        NativeBridge.configureSafMirror(indexFile.path, mirrorRoot.path)
        assertTrue(NativeBridge.mirrorSessionDegradedForTest)
        val session = NativeBridge.mirrorSessionForTest
        assertFalse(NativeBridge.mirrorDegradedBlock(session, overrideTarget.path.lowercase(), overrideTarget))
        // A placeholder without ownership stays blocked.
        val plain = File(mirrorRoot, "plugin.dll").apply { createNewFile() }
        assertTrue(NativeBridge.mirrorDegradedBlock(session, plain.path.lowercase(), plain))
    }

    @Test
    fun `all-override index rows filter to an empty but not degraded session`() {
        // Audit of 84e1d22, M4: a full index whose every row is a live local override
        // loads as documents=∅ with degraded=false (rawCount>0) — routing for the
        // override keys survives through the armed localOverrides, not the documents.
        val mirrorRoot = temporary.newFolder("game-all-override")
        val overrideTarget = File(mirrorRoot, "data.xp3").apply { createNewFile() }
        KrMirrorLocalFiles(mirrorRoot).markOverridden(overrideTarget)
        val indexFile = File(temporary.newFolder("idx"), "all-override.idx")
        indexFile.writeText(buildString {
            KrSafMirror.writeIndexText(
                this,
                listOf(Triple(overrideTarget.absolutePath.lowercase(), "content://stub/document/x", 5L)),
            )
        })
        NativeBridge.configureSafMirror(indexFile.path, mirrorRoot.path)
        assertFalse(NativeBridge.mirrorSessionDegradedForTest)
        val session = NativeBridge.mirrorSessionForTest
        assertTrue(session.shouldRoute(overrideTarget.absolutePath.lowercase()))
        assertFalse(NativeBridge.mirrorDegradedBlock(session, overrideTarget.absolutePath.lowercase(), overrideTarget))
    }
}
