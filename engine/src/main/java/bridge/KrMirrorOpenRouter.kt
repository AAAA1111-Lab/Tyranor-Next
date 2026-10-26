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

    fun open(key: String, file: File, intent: Intent): Int {
        val source = session.sourceEntry(key) ?: return NOT_ROUTED
        val state = session.localFiles ?: return FAILED
        return try {
            if (intent == Intent.READ_ONLY) {
                // Copy-up publishes with atomic rename. Do not hold a write mutex
                // across provider I/O: an old launch must not block a new launch read.
                if (session.isLocal(key, file) || file.length() > 0L) {
                    localDescriptor(file, intent).takeIf { it >= 0 } ?: FAILED
                } else {
                    readSource(key, source)
                }
            } else {
                state.withFileLock(file) {
                    if (session.isLocal(key, file) || file.length() > 0L) {
                        return@withFileLock openLocal(key, file, intent, state)
                    }
                    if (intent == Intent.UPDATE) {
                        try {
                            state.copyUp(file, sourceSize(source), { before, after ->
                                diagnostic("Mirror source size changed: indexed=$before actual=$after", null)
                            }) { sourceStream(source) }
                        } catch (error: InterruptedIOException) {
                            throw error
                        } catch (error: IOException) {
                            diagnostic("Mirror copy-up unavailable; update open is read-only", error)
                            return@withFileLock readSource(key, source)
                        }
                    }
                    openLocal(key, file, intent, state)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: InterruptedIOException) {
            throw error
        } catch (error: Exception) {
            if (intent == Intent.UPDATE && error is IOException &&
                !runCatching { session.isLocal(key, file) }.getOrDefault(true) && file.length() == 0L
            ) {
                diagnostic("Mirror copy-up unavailable; update open is read-only", error)
                try {
                    return readSource(key, source)
                } catch (readError: Exception) {
                    session.recordRead(key, false)
                    diagnostic("Mirror read-only fallback failed", readError)
                    return FAILED
                }
            }
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
