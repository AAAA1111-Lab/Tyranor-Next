package bridge

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KrMirrorOpenRouterTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `update open copies source and returns writable local descriptor`() {
        val fixture = fixture()
        val fd = fixture.router().open(fixture.key, fixture.file, KrMirrorOpenRouter.Intent.UPDATE)
        assertEquals(20, fd)
        assertEquals("source", fixture.file.readText())
        assertTrue(fixture.state.isOverridden(fixture.file))
        assertTrue(fixture.sourceOpens.isEmpty())
        assertEquals(listOf(KrMirrorOpenRouter.Intent.UPDATE), fixture.localOpens)
    }

    @Test
    fun `read only source open creates no state directory or lock files`() {
        val fixture = fixture()
        // Read routing must work even if writable state cannot be created.
        File(fixture.root, ".tyranor-mirror-state").writeText("not a directory")
        assertEquals(10, fixture.router().open(fixture.key, fixture.file, KrMirrorOpenRouter.Intent.READ_ONLY))
        assertEquals(listOf("source"), fixture.sourceOpens)
        assertTrue(fixture.localOpens.isEmpty())
        assertEquals(0L, fixture.file.length())
    }

    @Test
    fun `read only local override opens local without creating ownership state`() {
        val fixture = fixture()
        fixture.file.writeText("local")
        assertEquals(20, fixture.router().open(fixture.key, fixture.file, KrMirrorOpenRouter.Intent.READ_ONLY))
        assertEquals(listOf(KrMirrorOpenRouter.Intent.READ_ONLY), fixture.localOpens)
        assertFalse(File(fixture.root, ".tyranor-mirror-state").exists())
        assertTrue(fixture.sourceOpens.isEmpty())
    }

    @Test
    fun `failed source descriptor is authoritative and cannot read empty placeholder`() {
        val fixture = fixture(sourceFd = -1)
        assertEquals(KrMirrorOpenRouter.FAILED, fixture.router().open(fixture.key, fixture.file, KrMirrorOpenRouter.Intent.READ_ONLY))
        assertTrue(fixture.session.readFailed(fixture.key))
        assertTrue(fixture.localOpens.isEmpty())
    }

    @Test
    fun `oversized update source downgrades to read only SAF descriptor`() {
        val fixture = fixture(size = KrMirrorLocalFiles.MAX_COPY_BYTES + 1)
        assertEquals(10, fixture.router().open(fixture.key, fixture.file, KrMirrorOpenRouter.Intent.UPDATE))
        assertEquals(listOf("source"), fixture.sourceOpens)
        assertEquals(0, fixture.streamOpens)
        assertTrue(fixture.localOpens.isEmpty())
        assertFalse(fixture.state.isOverridden(fixture.file))
        assertTrue(fixture.diagnostics.any { it.contains("read-only") })
    }

    @Test
    fun `copy provider failure downgrades without exposing partial local file`() {
        val fixture = fixture(streamFails = true)
        assertEquals(10, fixture.router().open(fixture.key, fixture.file, KrMirrorOpenRouter.Intent.UPDATE))
        assertEquals(0L, fixture.file.length())
        assertFalse(fixture.state.isOverridden(fixture.file))
        assertTrue(fixture.localOpens.isEmpty())
    }

    @Test
    fun `unwritable state still permits update read only fallback`() {
        val fixture = fixture()
        File(fixture.root, ".tyranor-mirror-state").writeText("blocks locks")
        assertEquals(10, fixture.router().open(fixture.key, fixture.file, KrMirrorOpenRouter.Intent.UPDATE))
        assertTrue(fixture.localOpens.isEmpty())
    }

    @Test
    fun `write ownership failure closes local descriptor and fails closed`() {
        val fixture = fixture()
        val stateDirectory = File(fixture.root, ".tyranor-mirror-state").apply { mkdirs() }
        File(stateDirectory, "local-overrides").writeText("blocks markers")
        assertEquals(KrMirrorOpenRouter.FAILED, fixture.router().open(fixture.key, fixture.file, KrMirrorOpenRouter.Intent.WRITE))
        assertEquals(listOf(20), fixture.closed)
        assertTrue(fixture.sourceOpens.isEmpty())
    }

    @Test
    fun `invalid local descriptor does not promote source route or ownership`() {
        val fixture = fixture(localFd = -1)
        assertEquals(KrMirrorOpenRouter.FAILED, fixture.router().open(fixture.key, fixture.file, KrMirrorOpenRouter.Intent.WRITE))
        assertFalse(fixture.state.isOverridden(fixture.file))
        assertEquals("source", fixture.session.entry(fixture.key))
        assertTrue(fixture.closed.isEmpty())
    }

    @Test
    fun `late old source completion cannot overwrite a new active launch read`() {
        val fixture = fixture()
        val active = AtomicReference(fixture.session)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val oldRouter = fixture.router {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            -1
        }
        try {
            val oldRead = executor.submit<Int> {
                oldRouter.open(fixture.key, fixture.file, KrMirrorOpenRouter.Intent.READ_ONLY)
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val current = KrMirrorReadSession(mapOf(fixture.key to "current"), fixture.state)
            active.set(current)
            val currentRouter = KrMirrorOpenRouter(
                session = active.get(),
                sourceSize = { 7L },
                sourceStream = { ByteArrayInputStream("current".toByteArray()) },
                sourceDescriptor = { 30 },
                localDescriptor = { _, _ -> 40 },
                closeDescriptor = {},
                diagnostic = { _, _ -> },
            )
            assertEquals(30, currentRouter.open(fixture.key, fixture.file, KrMirrorOpenRouter.Intent.READ_ONLY))
            release.countDown()
            assertEquals(KrMirrorOpenRouter.FAILED, oldRead.get(5, TimeUnit.SECONDS))
            assertTrue(fixture.session.readFailed(fixture.key))
            assertFalse(active.get().readFailed(fixture.key))
            assertEquals("current", active.get().entry(fixture.key))
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `marker added by another helper is observed after initial negative ownership check`() {
        val fixture = fixture()
        assertFalse(fixture.session.isLocal(fixture.key, fixture.file))
        KrMirrorLocalFiles(fixture.root).markOverridden(fixture.file)
        assertTrue(fixture.session.isLocal(fixture.key, fixture.file))
        assertEquals(20, fixture.router().open(fixture.key, fixture.file, KrMirrorOpenRouter.Intent.READ_ONLY))
        assertTrue(fixture.sourceOpens.isEmpty())
    }

    private fun fixture(
        size: Long = 6,
        sourceFd: Int = 10,
        localFd: Int = 20,
        streamFails: Boolean = false,
    ): Fixture = Fixture(temporary.newFolder(), size, sourceFd, localFd, streamFails)

    private class Fixture(
        val root: File,
        val size: Long,
        val sourceFd: Int,
        val localFd: Int,
        val streamFails: Boolean,
    ) {
        val file = File(root, "asset").apply { createNewFile() }
        val key = file.absolutePath.lowercase(Locale.ROOT)
        val state = KrMirrorLocalFiles(root)
        val session = KrMirrorReadSession(mapOf(key to "source"), state)
        val sourceOpens = mutableListOf<String>()
        val localOpens = mutableListOf<KrMirrorOpenRouter.Intent>()
        val closed = mutableListOf<Int>()
        val diagnostics = mutableListOf<String>()
        var streamOpens = 0

        fun router(sourceDescriptorOverride: (() -> Int)? = null) = KrMirrorOpenRouter(
            session = session,
            sourceSize = { size },
            sourceStream = {
                streamOpens++
                if (streamFails) throw IOException("Provider disconnected")
                ByteArrayInputStream("source".toByteArray())
            },
            sourceDescriptor = { source -> sourceOpens.add(source); sourceDescriptorOverride?.invoke() ?: sourceFd },
            localDescriptor = { _, intent -> localOpens.add(intent); localFd },
            closeDescriptor = { closed.add(it) },
            diagnostic = { message, _ -> diagnostics.add(message) },
        )
    }
}
