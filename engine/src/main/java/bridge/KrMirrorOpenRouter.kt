package bridge

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException

/** Injectable mirror-only open path; unindexed/scoped paths retain the existing bridge route. */
internal class KrMirrorOpenRouter<T>(
    private val session: KrMirrorReadSession<T>,
    private val sourceSize: (T) -> Long,
    private val sourceStream: (T) -> InputStream,
    private val sourceDescriptor: (T) -> Int,
    private val localDescriptor: (File, Intent) -> Int,
    private val closeDescriptor: (Int) -> Unit,
    private val diagnostic: (String, Exception?) -> Unit,
) {
    enum class Intent { READ_ONLY, UPDATE, WRITE }

    fun open(key: String, file: File, intent: Intent, allowCreate: Boolean = false): Int {
        if (!session.shouldRoute(key)) return NOT_ROUTED
        val state = session.localFiles ?: return FAILED
        return try {
            if (intent == Intent.READ_ONLY) {
                // Copy-up publishes with atomic rename. Do not hold a write mutex
                // across provider I/O: an old launch must not block a new launch read.
                val source = session.sourceEntry(key)
                if (session.isLocal(key, file) || file.length() > 0L || (source == null && file.isFile)) {
                    localDescriptor(file, intent).takeIf { it >= 0 } ?: FAILED
                } else if (source != null) {
                    readSource(key, source)
                } else {
                    FAILED
                }
            } else {
                // Every mutating open materializes the source first (copy-up), so a
                // crash or ENOSPC mid-write cannot leave a 0-byte local file with an
                // ownership marker permanently shadowing the real document. Append
                // opens copy up as well; their content is then preserved locally.
                state.withFileLock(file) {
                    // A successful unlink/rename may have completed while this open
                    // waited. Never publish source bytes captured before the lock.
                    val source = session.sourceEntry(key)
                    if (file.isFile && (session.isLocal(key, file) || file.length() > 0L || source == null)) {
                        return@withFileLock openLocal(key, file, intent, state)
                    }
                    if (source == null) {
                        // O_CREAT may intentionally make a new empty local file. A
                        // plain r+/O_RDWR or write-only open must fail after unlink.
                        return@withFileLock if (allowCreate && !file.exists()) {
                            openLocal(key, file, intent, state)
                        } else {
                            FAILED
                        }
                    }
                    try {
                        state.copyUp(file, sourceSize(source), { before, after ->
                            diagnostic("Mirror source size changed: indexed=$before actual=$after", null)
                        }) { sourceStream(source) }
                    } catch (error: InterruptedIOException) {
                        throw error
                    } catch (error: IOException) {
                        diagnostic("Mirror copy-up unavailable; mutating open fails", error)
                        return@withFileLock FAILED
                    }
                    openLocal(key, file, intent, state)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: InterruptedIOException) {
            throw error
        } catch (error: Exception) {
            session.recordRead(key, false)
            diagnostic("Mirror open failed", error)
            FAILED
        }
    }

    private fun readSource(key: String, source: T): Int {
        val fd = sourceDescriptor(source)
        session.recordRead(key, fd >= 0)
        return if (fd >= 0) fd else FAILED
    }

    private fun openLocal(key: String, file: File, intent: Intent, state: KrMirrorLocalFiles): Int {
        val fd = localDescriptor(file, intent)
        if (fd < 0) return FAILED
        try {
            state.markOverridden(file)
            session.promote(key)
            return fd
        } catch (error: Exception) {
            runCatching { closeDescriptor(fd) }
            throw error
        }
    }

    companion object {
        const val NOT_ROUTED = -1
        const val FAILED = -2
    }
}
