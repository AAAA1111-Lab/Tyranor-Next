package bridge

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KrMirrorLocalFilesTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `copy up publishes complete source before writable local access`() {
        val root = temporary.newFolder("mirror")
        val target = File(root, "plugin.dll").apply { createNewFile() }
        val source = ByteArray(130_007) { (it % 251).toByte() }
        val local = KrMirrorLocalFiles(root)

        local.copyUp(target, source.size.toLong()) { ByteArrayInputStream(source) }

        assertArrayEquals(source, target.readBytes())
        assertTrue(local.isOverridden(target))
        target.writeText("local edit")
        local.copyUp(target, source.size.toLong()) { fail("Must not reread source"); ByteArrayInputStream(source) }
        assertEquals("local edit", target.readText())
    }

    @Test
    fun `truncated local override survives new helper and root relocation`() {
        val root = temporary.newFolder("original")
        val target = File(root, "config.ksd").apply { createNewFile() }
        KrMirrorLocalFiles(root).copyUp(target, 4) { ByteArrayInputStream("data".toByteArray()) }
        target.writeBytes(byteArrayOf())
        val restoredRoot = File(temporary.root, "restored")
        assertTrue(root.copyRecursively(restoredRoot))
        val restoredTarget = File(restoredRoot, target.name)
        val restored = KrMirrorLocalFiles(restoredRoot)

        assertTrue(restored.isOverridden(restoredTarget))
        restored.copyUp(restoredTarget, 4) { fail("Restored empty override must remain empty"); ByteArrayInputStream(byteArrayOf()) }
        assertEquals(0L, restoredTarget.length())
    }

    @Test
    fun `folded index key restores real uppercase legacy root without widening path boundary`() {
        val root = temporary.newFolder("OldTitle-Key")
        val target = File(root, "config.ksd").apply { createNewFile() }
        val local = KrMirrorLocalFiles(root)
        local.markOverridden(target)
        val key = target.absolutePath.lowercase(java.util.Locale.ROOT)
        val resolved = local.fileForIndexKey(key)
        assertEquals(target, resolved)
        assertTrue(local.isOverridden(requireNotNull(resolved)))
        assertEquals(null, local.fileForIndexKey(root.absolutePath + "-other/config.ksd"))
        assertEquals(null, local.fileForIndexKey(root.absolutePath + "/../outside"))
        expectIOException { local.isOverridden(temporary.newFile("outside-marker")) }
    }

    @Test
    fun `root cleanup also removes local ownership state`() {
        val root = temporary.newFolder("recreated")
        val target = File(root, "config.ksd").apply { createNewFile() }
        KrMirrorLocalFiles(root).markOverridden(target)
        assertTrue(root.deleteRecursively())
        assertTrue(root.mkdirs())
        target.createNewFile()
        assertFalse(KrMirrorLocalFiles(root).isOverridden(target))
    }

    @Test
    fun `changed source size publishes complete actual content and reports mismatch`() {
        val root = temporary.newFolder("mismatch")
        val local = KrMirrorLocalFiles(root)
        for ((name, expected) in listOf("short" to 9L, "long" to 2L)) {
            val target = File(root, name).apply { createNewFile() }
            var reported: Pair<Long, Long>? = null
            local.copyUp(target, expected, { before, after -> reported = before to after }) {
                ByteArrayInputStream("data".toByteArray())
            }
            assertEquals("data", target.readText())
            assertEquals(expected to 4L, reported)
            assertTrue(local.isOverridden(target))
        }
    }

    @Test
    fun `known oversized source is refused before source IO`() {
        val root = temporary.newFolder("known-limit")
        val target = File(root, "asset").apply { createNewFile() }
        val local = KrMirrorLocalFiles(root)
        expectIOException {
            local.copyUp(target, KrMirrorLocalFiles.MAX_COPY_BYTES + 1) {
                fail("Oversized source must not be opened")
                ByteArrayInputStream(byteArrayOf())
            }
        }
        assertEquals(0L, target.length())
        assertFalse(local.isOverridden(target))
    }

    @Test
    fun `unknown oversized source leaves no partial copy or ownership marker`() {
        val root = temporary.newFolder("unknown-limit")
        val target = File(root, "asset").apply { createNewFile() }
        val local = KrMirrorLocalFiles(root)
        expectIOException {
            local.copyUp(target, -1) {
                object : InputStream() {
                    var remaining = KrMirrorLocalFiles.MAX_COPY_BYTES + 1
                    override fun read(): Int = if (remaining-- > 0) 0 else -1
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (remaining <= 0) return -1
                        val count = minOf(length.toLong(), remaining).toInt()
                        remaining -= count
                        return count
                    }
                }
            }
        }
        assertEquals(0L, target.length())
        assertFalse(local.isOverridden(target))
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith(".mirror-copy-") })
    }

    @Test
    fun `index alias resolves through symlink root and rejects traversal`() {
        val root = temporary.newFolder("real-root")
        val target = File(root, "config.ksd").apply { createNewFile() }
        val alias = File(temporary.root, "alias-root")
        try {
            Files.createSymbolicLink(alias.toPath(), root.toPath())
        } catch (error: Exception) {
            assumeNoException("Symbolic links unavailable on this host", error)
        }
        val local = KrMirrorLocalFiles(alias)
        local.markOverridden(target)
        val aliasKey = File(alias, target.name).absolutePath.lowercase(java.util.Locale.ROOT)
        val realKey = target.absolutePath.lowercase(java.util.Locale.ROOT)
        assertEquals(target.canonicalFile, local.fileForIndexKey(aliasKey)?.canonicalFile)
        assertEquals(target.canonicalFile, local.fileForIndexKey(realKey)?.canonicalFile)
        assertTrue(aliasKey.replace(File.separatorChar, '/') in local.snapshotOverrideKeys())
        assertTrue(realKey.replace(File.separatorChar, '/') in local.snapshotOverrideKeys())
        assertEquals(null, local.fileForIndexKey(alias.absolutePath + "/../outside"))
    }

    @Test
    fun `missing target clears ownership but live empty override remains`() {
        val root = temporary.newFolder("deleted-override")
        val target = File(root, "asset").apply { createNewFile() }
        val local = KrMirrorLocalFiles(root)
        local.markOverridden(target)
        assertFalse(local.clearMissingOverride(target))
        assertTrue(local.isOverridden(target))
        assertTrue(target.delete())
        assertTrue(local.clearMissingOverride(target))
        target.createNewFile()
        assertFalse(local.isOverridden(target))
    }

    @Test
    fun `legacy marker snapshot preserves empty overrides with candidate index keys`() {
        val root = temporary.newFolder("legacy-marker")
        val target = File(root, "asset").apply { createNewFile() }
        val local = KrMirrorLocalFiles(root)
        val marker = local.ownershipMarker(target)
        marker.parentFile!!.mkdirs()
        marker.writeBytes(byteArrayOf(1))
        val key = target.absolutePath.lowercase(java.util.Locale.ROOT)
        assertTrue(local.isOverridden(target))
        assertTrue(key.replace(File.separatorChar, '/') in local.snapshotOverrideKeys(listOf(key)))
    }

    @Test
    fun `source read exception never publishes partial local contents`() {
        val root = temporary.newFolder("read-failure")
        val target = File(root, "asset").apply { createNewFile() }
        val local = KrMirrorLocalFiles(root)
        expectIOException {
            local.copyUp(target, -1) {
                object : InputStream() {
                    override fun read(): Int = throw IOException("provider disconnected")
                }
            }
        }
        assertEquals(0L, target.length())
        assertFalse(local.isOverridden(target))
    }

    @Test
    fun `marker publication failure rolls back copied placeholder`() {
        val root = temporary.newFolder("marker-failure")
        val target = File(root, "asset").apply { createNewFile() }
        val state = File(root, ".tyranor-mirror-state").apply { mkdirs() }
        File(state, "local-overrides").writeText("blocks marker directory")
        val local = KrMirrorLocalFiles(root)

        expectIOException { local.copyUp(target, 4) { ByteArrayInputStream("data".toByteArray()) } }

        assertEquals(0L, target.length())
        assertFalse(local.isOverridden(target))
    }

    @Test
    fun `copy up refuses paths outside selected mirror root`() {
        val root = temporary.newFolder("contained")
        val outside = temporary.newFile("outside")
        expectIOException { KrMirrorLocalFiles(root).copyUp(outside, 4) { ByteArrayInputStream("data".toByteArray()) } }
        assertEquals(0L, outside.length())
    }

    @Test
    fun `concurrent same key copy up opens source only once across instances`() {
        val root = temporary.newFolder("same-key")
        val target = File(root, "asset").apply { createNewFile() }
        val opens = AtomicInteger()
        runConcurrently(8) {
            KrMirrorLocalFiles(root).copyUp(target, 4) {
                opens.incrementAndGet()
                ByteArrayInputStream("data".toByteArray())
            }
        }
        assertEquals(1, opens.get())
        assertEquals("data", target.readText())
    }

    @Test
    fun `concurrent distinct first keys share newly created state directories safely`() {
        val root = temporary.newFolder("distinct-keys")
        runConcurrently(12) { index ->
            val target = File(root, "asset-$index").apply { createNewFile() }
            val local = KrMirrorLocalFiles(root)
            local.copyUp(target, 4) { ByteArrayInputStream("data".toByteArray()) }
            assertTrue(local.isOverridden(target))
            assertEquals("data", target.readText())
        }
    }

    @Test
    fun `interrupted copy preserves interruption and leaves original placeholder`() {
        val root = temporary.newFolder("interrupted")
        val target = File(root, "asset").apply { createNewFile() }
        val local = KrMirrorLocalFiles(root)
        try {
            Thread.currentThread().interrupt()
            expectIOException { local.copyUp(target, 4) { ByteArrayInputStream("data".toByteArray()) } }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        assertEquals(0L, target.length())
        assertFalse(local.isOverridden(target))
    }

    private fun expectIOException(action: () -> Unit) {
        try {
            action()
            fail("Expected IOException")
        } catch (_: IOException) {
        }
    }

    private fun runConcurrently(count: Int, action: (Int) -> Unit) {
        val executor = Executors.newFixedThreadPool(count)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until count).map { index ->
                executor.submit {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    action(index)
                }
            }
            start.countDown()
            futures.forEach { it.get(15, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
