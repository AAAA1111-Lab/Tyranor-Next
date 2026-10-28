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
    fun `oversized mutating source fails closed without exposing placeholder`() {
        val fixture = fixture(size = KrMirrorLocalFiles.MAX_COPY_BYTES + 1)
        // Mutating opens copy up first; an oversized source must fail the open (EIO at
        // the native layer) instead of silently serving an empty placeholder. The size
        // check fires before any provider stream is opened or descriptor handed out.
        assertEquals(KrMirrorOpenRouter.FAILED, fixture.router().open(fixture.key, fixture.file, KrMirrorOpenRouter.Intent.UPDATE))
        assertTrue(fixture.sourceOpens.isEmpty())
        assertEquals(0, fixture.streamOpens)
        assertTrue(fixture.localOpens.isEmpty())
        assertFalse(fixture.state.isOverridden(fixture.file))
        assertEquals(0L, fixture.file.length())
    }

    @Test
    fun `copy provider failure fails closed without exposing partial local file`() {
        val fixture = fixture(streamFails = true)
        assertEquals(KrMirrorOpenRouter.FAILED, fixture.router().open(fixture.key, fixture.file, KrMirrorOpenRouter.Intent.UPDATE))
        assertEquals(0L, fixture.file.length())
        assertFalse(fixture.state.isOverridden(fixture.file))
        assertTrue(fixture.localOpens.isEmpty())
        assertEquals("source", fixture.session.entry(fixture.key))
    }

    @Test
    fun `unwritable state fails mutating open without falling back to source`() {
        val fixture = fixture()
        File(fixture.root, ".tyranor-mirror-state").writeText("blocks locks")
        assertEquals(KrMirrorOpenRouter.FAILED, fixture.router().open(fixture.key, fixture.file, KrMirrorOpenRouter.Intent.UPDATE))
        assertTrue(fixture.localOpens.isEmpty())
        assertEquals("source", fixture.session.entry(fixture.key))
    }

    @Test
    fun `write open copies source first so crashes cannot shadow it`() {
        val fixture = fixture()
        // WRITE (no truncation) also copies up before handing out the fd: a crash after
        // open leaves a real local copy, never a 0-byte file masking the source.
        assertEquals(20, fixture.router().open(fixture.key, fixture.file, KrMirrorOpenRouter.Intent.WRITE))
        assertEquals("source", fixture.file.readText())
        assertTrue(fixture.state.isOverridden(fixture.file))
        assertTrue(fixture.session.isLocal(fixture.key, fixture.file))
    }

    @Test
    fun `blocked marker directory fails the mutating open after copy-up rollback`() {
        val fixture = fixture()
        val stateDirectory = File(fixture.root, ".tyranor-mirror-state").apply { mkdirs() }
        File(stateDirectory, "local-overrides").writeText("blocks markers")
        // Copy-up publishes the local copy, but the ownership marker cannot be created;
        // copyUp rolls the placeholder back (no durable ownership → no fd exposure) and
        // the open fails closed instead of masking the source with a doomed local file.
        assertEquals(KrMirrorOpenRouter.FAILED, fixture.router().open(fixture.key, fixture.file, KrMirrorOpenRouter.Intent.WRITE))
        assertTrue(fixture.closed.isEmpty())
        assertTrue(fixture.sourceOpens.isEmpty())
        assertEquals(0L, fixture.file.length())
        assertFalse(fixture.state.isOverridden(fixture.file))
    }

    @Test
    fun `invalid local descriptor fails closed after durable copy-up ownership`() {
        val fixture = fixture(localFd = -1)
        // copyUp publishes content + ownership marker durably before openLocal runs; the
        // unusable fd fails the open, but the local copy legitimately owns the file.
        assertEquals(KrMirrorOpenRouter.FAILED, fixture.router().open(fixture.key, fixture.file, KrMirrorOpenRouter.Intent.WRITE))
        assertTrue(fixture.state.isOverridden(fixture.file))
        assertTrue(fixture.session.isLocal(fixture.key, fixture.file))
        assertEquals("source", fixture.session.entry(fixture.key))
        assertEquals("source", fixture.file.readText())
        assertTrue(fixture.closed.isEmpty())
    }

    @Test
    fun `read only failure records read failure and closes nothing`() {
        val fixture = fixture(sourceFd = -1)
        assertEquals(KrMirrorOpenRouter.FAILED, fixture.router().open(fixture.key, fixture.file, KrMirrorOpenRouter.Intent.READ_ONLY))
        assertTrue(fixture.session.readFailed(fixture.key))
        assertTrue(fixture.closed.isEmpty())
        assertTrue(fixture.localOpens.isEmpty())
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

    @Test
    fun `pending update cannot resurrect source after unlink completed under the lock`() {
        assertPendingOpenAfterUnlink(KrMirrorOpenRouter.Intent.UPDATE, allowCreate = false)
    }

    @Test
    fun `pending write without create cannot resurrect source after unlink`() {
        assertPendingOpenAfterUnlink(KrMirrorOpenRouter.Intent.WRITE, allowCreate = false)
    }

    @Test
    fun `pending update with create makes a new empty file instead of restoring deleted source`() {
        assertPendingOpenAfterUnlink(KrMirrorOpenRouter.Intent.UPDATE, allowCreate = true)
    }

    @Test
    fun `pending write with create makes a new empty local file after unlink`() {
        assertPendingOpenAfterUnlink(KrMirrorOpenRouter.Intent.WRITE, allowCreate = true)
    }

    @Test
    fun `already tombstoned update stays routed and fails rather than allowing a direct fallback`() {
        val fixture = fixture()
        fixture.session.runMutation(fixture.key, fixture.file) {
            check(fixture.file.delete())
            0L
        }
        assertTrue(fixture.session.shouldRoute(fixture.key))
        assertEquals(KrMirrorOpenRouter.FAILED,
            fixture.router().open(fixture.key, fixture.file, KrMirrorOpenRouter.Intent.UPDATE))
        assertFalse(fixture.file.exists())
        assertEquals(0, fixture.streamOpens)
    }

    @Test
    fun `tombstoned pure read falls back to native ENOENT instead of EIO`() {
        // R9-#11: after a successful unlink, a read-only open of the missing file is a
        // plain miss — the router must not report FAILED/EIO for games probing deleted
        // optional resources; native open reports ENOENT as base did.
        val fixture = fixture()
        fixture.session.runMutation(fixture.key, fixture.file) {
            check(fixture.file.delete())
            0L
        }
        assertTrue(fixture.session.shouldRoute(fixture.key))
        assertEquals(KrMirrorOpenRouter.NOT_ROUTED,
            fixture.router().open(fixture.key, fixture.file, KrMirrorOpenRouter.Intent.READ_ONLY))
        assertFalse(fixture.file.exists())
        assertTrue(fixture.sourceOpens.isEmpty())
        assertTrue(fixture.localOpens.isEmpty())
        assertFalse(fixture.session.readFailed(fixture.key))
    }

    @Test
    fun `stale ownership marker with a missing local file reads the source instead of failing`() {
        // R10 #15: a durable marker whose local file was removed externally must not
        // turn a pure read into EIO while the source document is still readable.
        val fixture = fixture()
        KrMirrorLocalFiles(fixture.root).markOverridden(fixture.file)
        check(fixture.file.delete())
        assertEquals(10, fixture.router().open(fixture.key, fixture.file, KrMirrorOpenRouter.Intent.READ_ONLY))
        assertEquals(listOf("source"), fixture.sourceOpens)
        assertTrue(fixture.localOpens.isEmpty())
    }

    @Test
    fun `truncating open publishes an empty override without copying the source up`() {
        // R10 #9: truncation discards the source by intent — no bounded copy-up, and
        // an oversized source must not fail an open whose result is a truncation.
        val fixture = fixture(size = KrMirrorLocalFiles.MAX_COPY_BYTES + 1)
        assertEquals(20, fixture.router().open(fixture.key, fixture.file, KrMirrorOpenRouter.Intent.TRUNCATE))
        assertTrue(fixture.sourceOpens.isEmpty())
        assertEquals(0, fixture.streamOpens)
        assertEquals(listOf(KrMirrorOpenRouter.Intent.TRUNCATE), fixture.localOpens)
        assertTrue(fixture.state.isOverridden(fixture.file))
    }

    private fun assertPendingOpenAfterUnlink(intent: KrMirrorOpenRouter.Intent, allowCreate: Boolean) {
        val fixture = fixture()
        val entered = CountDownLatch(1)
        val result = AtomicReference(Int.MIN_VALUE)
        val failure = AtomicReference<Throwable>()
        val router = fixture.router(createLocalOnOpen = allowCreate)
        val pending = Thread {
            entered.countDown()
            try {
                result.set(router.open(fixture.key, fixture.file, intent, allowCreate))
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        try {
            fixture.state.withFileLock(fixture.file) {
                pending.start()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (pending.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                assertEquals("Open must already be waiting for the mutation lock", Thread.State.BLOCKED, pending.state)
                // This is the real mutation transaction, nested in the held key lock:
                // complete unlink and its tombstone before letting UPDATE acquire it.
                assertEquals(0L, fixture.session.runMutation(fixture.key, fixture.file) {
                    check(fixture.file.delete())
                    0L
                })
                assertFalse(fixture.file.exists())
                assertEquals(null, fixture.session.sourceEntry(fixture.key))
            }
            pending.join(5000)
            assertFalse(pending.isAlive)
            assertEquals(null, failure.get())
            assertEquals(if (allowCreate) 20 else KrMirrorOpenRouter.FAILED, result.get().toInt())
            assertEquals(0, fixture.streamOpens)
            assertTrue(fixture.sourceOpens.isEmpty())
            assertEquals(allowCreate, fixture.file.exists())
            if (allowCreate) {
                assertEquals(0L, fixture.file.length())
                assertTrue(fixture.state.isOverridden(fixture.file))
            }
        } finally {
            pending.join(5000)
        }
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

        fun router(createLocalOnOpen: Boolean = false, sourceDescriptorOverride: (() -> Int)? = null) = KrMirrorOpenRouter(
            session = session,
            sourceSize = { size },
            sourceStream = {
                streamOpens++
                if (streamFails) throw IOException("Provider disconnected")
                ByteArrayInputStream("source".toByteArray())
            },
            sourceDescriptor = { source -> sourceOpens.add(source); sourceDescriptorOverride?.invoke() ?: sourceFd },
            localDescriptor = { file, intent ->
                localOpens.add(intent)
                if (createLocalOnOpen && !file.exists()) file.createNewFile()
                localFd
            },
            closeDescriptor = { closed.add(it) },
            diagnostic = { message, _ -> diagnostics.add(message) },
        )
    }
}
