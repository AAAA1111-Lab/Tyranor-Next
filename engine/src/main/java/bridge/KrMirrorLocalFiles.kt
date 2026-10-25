package bridge

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** App-private copy-up state. Markers survive an intentionally empty local override. */
class KrMirrorLocalFiles(mirrorRoot: File) {
    private val root = mirrorRoot.canonicalFile
    private val stateDirectory = File(root, ".tyranor-mirror-state")

    /** Index keys fold the entire absolute path; restore the selected root's real case. */
    fun fileForIndexKey(indexKey: String): File? {
        val prefix = root.path.replace(File.separatorChar, '/').lowercase(Locale.ROOT) + "/"
        val normalized = indexKey.replace(File.separatorChar, '/').lowercase(Locale.ROOT)
        if (!normalized.startsWith(prefix)) return null
        val relative = normalized.substring(prefix.length)
        if (relative.isEmpty()) return null
        val file = File(root, relative)
        return try {
            localKey(file)
            file
        } catch (_: IOException) {
            null
        }
    }

    fun isOverridden(file: File): Boolean = marker(file).isFile

    fun markOverridden(file: File) {
        withFileLock(file) {
            val destination = marker(file)
            if (destination.isFile) return@withFileLock
            val directory = destination.parentFile ?: throw IOException("Missing override directory")
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw IOException("Cannot create override directory")
            if (!directory.canonicalPath.startsWith(root.path + File.separator)) throw IOException("Mirror state escaped root")
            val temporary = File.createTempFile(".override-", ".tmp", directory)
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(1)
                    output.fd.sync()
                }
                replaceAtomically(temporary, destination)
            } finally {
                temporary.delete()
            }
        }
    }

    /** Serialize this key both across engine threads and separate native processes. */
    fun <T> withFileLock(file: File, action: () -> T): T {
        val key = root.path + "/" + localKey(file)
        return synchronized(FILE_LOCKS.computeIfAbsent(key) { Any() }) {
            val held = requireNotNull(HELD_LOCKS.get())
            if (key in held) return@synchronized action()
            val lockDirectory = File(stateDirectory, "locks")
            if (!lockDirectory.isDirectory && !lockDirectory.mkdirs() && !lockDirectory.isDirectory) throw IOException("Cannot create mirror lock directory")
            if (!lockDirectory.canonicalPath.startsWith(root.path + File.separator)) throw IOException("Mirror state escaped root")
            RandomAccessFile(File(lockDirectory, digest(localKey(file)) + ".lock"), "rw").use { lockFile ->
                lockFile.channel.lock().use {
                    held.add(key)
                    try { action() } finally { held.remove(key) }
                }
            }
        }
    }

    /** Stream source bytes into a sibling temporary file; publish only complete content. */
    fun copyUp(file: File, expectedSize: Long, openSource: () -> InputStream): File = withFileLock(file) {
        if (isOverridden(file)) return@withFileLock file
        if (file.isDirectory) throw IOException("Mirror target is a directory")
        if (file.length() > 0L) {
            markOverridden(file)
            return@withFileLock file
        }
        val parent = file.parentFile ?: throw IOException("Missing mirror parent")
        if (!parent.isDirectory) throw IOException("Missing mirror directory")
        val temporary = File.createTempFile(".mirror-copy-", ".tmp", parent)
        val existed = file.exists()
        var published = false
        try {
            var copied = 0L
            openSource().use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        checkInterrupted()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        copied += count
                        if (expectedSize >= 0L && copied > expectedSize) throw IOException("Mirror source size changed")
                    }
                    if (expectedSize >= 0L && copied != expectedSize) throw IOException("Incomplete mirror source")
                    output.fd.sync()
                }
            }
            checkInterrupted()
            replaceAtomically(temporary, file)
            published = true
            markOverridden(file)
            file
        } catch (error: Exception) {
            // Readers of this key use the same lock. No writable fd is handed out
            // until the durable marker exists, so restoring the original empty
            // placeholder cannot discard an engine write from this operation.
            if (published && !isOverridden(file)) {
                try {
                    if (existed) FileOutputStream(file).use { it.fd.sync() } else file.delete()
                } catch (rollbackError: Exception) {
                    error.addSuppressed(rollbackError)
                }
            }
            throw error
        } finally {
            temporary.delete()
        }
    }

    private fun marker(file: File): File = File(File(stateDirectory, "local-overrides"), digest(localKey(file)))

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun localKey(file: File): String {
        val path = file.canonicalPath
        val prefix = root.path + File.separator
        if (!path.startsWith(prefix)) throw IOException("Mirror local file escaped root")
        return path.substring(prefix.length).replace(File.separatorChar, '/').lowercase(Locale.ROOT)
    }

    private fun replaceAtomically(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Mirror copy interrupted")
    }

    private companion object {
        const val COPY_BUFFER_SIZE = 64 * 1024
        val FILE_LOCKS = ConcurrentHashMap<String, Any>()
        val HELD_LOCKS = ThreadLocal.withInitial { HashSet<String>() }
    }
}
