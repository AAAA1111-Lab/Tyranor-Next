package bridge

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Immutable index snapshot with per-launch failures and local ownership. */
internal class KrMirrorReadSession<T>(
    documents: Map<String, T>,
    val localFiles: KrMirrorLocalFiles? = null,
    initialOverrides: Set<String> = emptySet(),
) {
    private val documents = documents.toMap()
    private val failedReads = ConcurrentHashMap.newKeySet<String>()
    private val localOverrides = ConcurrentHashMap.newKeySet<String>().apply { addAll(initialOverrides) }
    private val removedKeys = ConcurrentHashMap.newKeySet<String>()
    private val ownershipMarkers = ConcurrentHashMap<String, File>()

    fun sourceEntry(key: String): T? = if (key in removedKeys) null else documents[key]

    fun entry(key: String): T? = if (key in localOverrides || key in removedKeys) null else documents[key]

    /** Cache path derivation, not existence: another process may publish a zero-byte override. */
    fun isLocal(key: String, file: File): Boolean {
        if (key in localOverrides) return true
        val state = localFiles ?: return false
        val marker = ownershipMarkers.computeIfAbsent(key) { state.ownershipMarker(file) }
        return marker.isFile
    }

    fun recordRead(key: String, succeeded: Boolean) {
        if (succeeded) failedReads.remove(key) else failedReads.add(key)
    }

    fun readFailed(key: String): Boolean = key in failedReads

    /** Synchronize ownership after a successful local unlink/remove/rename. */
    fun synchronizeMutation(fromKey: String?, from: File?, toKey: String? = null, to: File? = null) {
        val state = localFiles ?: return
        // rename(path, path) succeeds but does not replace a source placeholder.
        if (from != null && to != null && (from == to || fromKey == toKey)) return
        // A successful ordinary-file rename is authoritative even for a new,
        // unindexed, zero-byte temporary file (including one outside the root).
        if (to != null && to.isFile) {
            state.markOverridden(to)
            promote(requireNotNull(toKey))
        }
        // A concurrent recreation is a new local file and keeps its marker.
        if (from != null && fromKey != null && !from.exists()) {
            state.removeOverride(from)
            remove(fromKey)
        }
    }

    /** Successful unlink/rename must not resurrect the old source during this launch. */
    fun remove(key: String) {
        removedKeys.add(key)
        localOverrides.remove(key)
        ownershipMarkers.remove(key)
        failedReads.remove(key)
    }

    fun promote(key: String) {
        removedKeys.remove(key)
        localOverrides.add(key)
        failedReads.remove(key)
    }
}
