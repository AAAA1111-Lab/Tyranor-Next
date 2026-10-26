package bridge

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KrMirrorReadSessionTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `successful rename migrates empty override and unlink clears marker and source route`() {
        val root = temporary.newFolder()
        val from = File(root, "from").apply { createNewFile() }
        val to = File(root, "to")
        val fromKey = from.absolutePath.lowercase(Locale.ROOT)
        val toKey = to.absolutePath.lowercase(Locale.ROOT)
        val state = KrMirrorLocalFiles(root)
        state.markOverridden(from)
        val session = KrMirrorReadSession(mapOf(fromKey to "source", toKey to "other"), state)
        Files.move(from.toPath(), to.toPath())

        session.synchronizeMutation(fromKey, from, toKey, to)

        assertFalse(state.isOverridden(from))
        assertTrue(state.isOverridden(to))
        assertNull(session.sourceEntry(fromKey))
        assertNull(session.entry(toKey))
        assertTrue(to.delete())
        session.synchronizeMutation(toKey, to)
        assertFalse(state.isOverridden(to))
        assertNull(session.sourceEntry(toKey))
    }

    @Test
    fun `unindexed empty temporary rename replaces indexed source with local empty content`() {
        val root = temporary.newFolder()
        val from = File(root, "temporary").apply { createNewFile() }
        val to = File(root, "asset").apply { createNewFile() }
        val fromKey = from.absolutePath.lowercase(Locale.ROOT)
        val toKey = to.absolutePath.lowercase(Locale.ROOT)
        val state = KrMirrorLocalFiles(root)
        val session = KrMirrorReadSession(mapOf(toKey to "source"), state)
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)

        session.synchronizeMutation(fromKey, from, toKey, to)

        assertTrue(state.isOverridden(to))
        assertNull(session.entry(toKey))
        assertEquals(0L, to.length())
    }

    @Test
    fun `empty temporary rename from outside mirror still promotes valid local destination`() {
        val root = temporary.newFolder()
        val from = temporary.newFile("outside-temp")
        val to = File(root, "asset").apply { createNewFile() }
        val toKey = to.absolutePath.lowercase(Locale.ROOT)
        val state = KrMirrorLocalFiles(root)
        val session = KrMirrorReadSession(mapOf(toKey to "source"), state)
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)

        session.synchronizeMutation(null, null, toKey, to)

        assertTrue(state.isOverridden(to))
        assertNull(session.entry(toKey))
        assertEquals(0L, to.length())
    }

    @Test
    fun `no op rename leaves empty source placeholder unpromoted`() {
        val root = temporary.newFolder()
        val file = File(root, "asset").apply { createNewFile() }
        val key = file.absolutePath.lowercase(Locale.ROOT)
        val state = KrMirrorLocalFiles(root)
        val session = KrMirrorReadSession(mapOf(key to "source"), state)
        Files.move(file.toPath(), file.toPath())

        session.synchronizeMutation(key, file, key, file)

        assertFalse(state.isOverridden(file))
        assertEquals("source", session.entry(key))
        assertFalse(File(root, ".tyranor-mirror-state").exists())
    }

    @Test
    fun `directory rename destination is not promoted as ordinary local file`() {
        val root = temporary.newFolder()
        val destination = File(root, "directory").apply { mkdir() }
        val key = destination.absolutePath.lowercase(Locale.ROOT)
        val state = KrMirrorLocalFiles(root)
        val session = KrMirrorReadSession(mapOf(key to "source"), state)

        session.synchronizeMutation(null, null, key, destination)

        assertFalse(state.isOverridden(destination))
        assertEquals("source", session.entry(key))
    }

    @Test
    fun `session owns immutable snapshot independent of caller mutations`() {
        val documents = mutableMapOf("asset" to "source")
        val session = KrMirrorReadSession(documents)
        documents["asset"] = "replacement"
        documents.clear()
        assertEquals("source", session.entry("asset"))
    }

    @Test
    fun `promotion removes source route and stale read failure only for same key`() {
        val session = KrMirrorReadSession(mapOf("asset" to 1, "other" to 2))
        session.recordRead("asset", false)
        session.recordRead("other", false)
        session.promote("asset")
        assertNull(session.entry("asset"))
        assertFalse(session.readFailed("asset"))
        assertEquals(2, session.entry("other"))
        assertTrue(session.readFailed("other"))
    }

    @Test
    fun `successful retry clears failure without dropping source route`() {
        val session = KrMirrorReadSession(mapOf("asset" to "uri"))
        session.recordRead("asset", false)
        assertTrue(session.readFailed("asset"))
        session.recordRead("asset", true)
        assertFalse(session.readFailed("asset"))
        assertEquals("uri", session.entry("asset"))
    }
}
