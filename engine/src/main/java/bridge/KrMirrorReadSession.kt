package bridge

import java.util.concurrent.ConcurrentHashMap

/** Immutable index snapshot with per-launch read failures and promoted local files. */
internal class KrMirrorReadSession<T>(
    documents: Map<String, T>,
    val localFiles: KrMirrorLocalFiles? = null,
) {
    private val documents = documents.toMap()
    private val failedReads = ConcurrentHashMap.newKeySet<String>()
    private val localOverrides = ConcurrentHashMap.newKeySet<String>()

    fun entry(key: String): T? = if (key in localOverrides) null else documents[key]

    fun recordRead(key: String, succeeded: Boolean) {
        if (succeeded) failedReads.remove(key) else failedReads.add(key)
    }

    fun readFailed(key: String): Boolean = key in failedReads

    fun promote(key: String) {
        localOverrides.add(key)
        failedReads.remove(key)
    }
}
