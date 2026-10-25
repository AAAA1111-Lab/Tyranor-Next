package bridge

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.OsConstants
import android.util.Log
import com.core.engine.LaunchContract
import org.tvp.kirikiri2.KR2Activity
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

object NativeBridge {
    /** 镜像条目尺寸标记：SAF 侧已被写模式打开，索引尺寸过期，stat 时按需刷新。 */
    private const val SIZE_DIRTY = -2L

    @Volatile
    private var SAF_DOCUMENTS: Map<String, KrSafMirror.MirrorEntry> = emptyMap()
    @Volatile
    private var krkrGameReadyListener: Runnable? = null
    @Volatile
    private var patchOverlayTarget: String? = null
    @Volatile
    private var patchOverlayPath: String? = null
    @Volatile
    private var steamConfigOverlayTarget: String? = null
    @Volatile
    private var steamConfigOverlayPath: String? = null

    @JvmStatic external fun initialize(so: String?): Boolean
    @JvmStatic external fun isLaunchSceneReady(so: String?): Boolean
    @JvmStatic external fun launch(so: String?, path: String?, useMaps: Boolean): Boolean
    @JvmStatic external fun interceptor(prefix: String?): Unit
    @JvmStatic external fun setMirrorPrefix(prefix: String?): Unit
    @JvmStatic external fun relocate(): Int
    @JvmStatic external fun write(path: String?, data: ByteArray?): Boolean

    @JvmStatic
    private fun onKrkrGameReady() {
        val listener = krkrGameReadyListener
        Log.i("NativeBridge", "KRKR ready callback listener=${listener != null}")
        listener?.run()
    }

    @JvmStatic
    fun setKrkrGameReadyListener(listener: Runnable?) {
        krkrGameReadyListener = listener
    }

    /** 装载 SAF 镜像索引（path→文档 Uri+真实尺寸），供 [open] 路由与 [metadataSize] 查询。 */
    @JvmStatic
    fun configureSafMirror(indexPath: String?) {
        SAF_DOCUMENTS = try {
            KrSafMirror.loadIndex(indexPath)
        } catch (t: Throwable) {
            Log.e("NativeBridge", "load SAF mirror index failed path=$indexPath", t)
            emptyMap()
        }
        val sized = SAF_DOCUMENTS.values.count { it.size >= 0 }
        Log.i("NativeBridge", "SAF mirror index entries=${SAF_DOCUMENTS.size} sized=$sized")
    }

    @JvmStatic
    fun configurePatchOverlay(targetPath: String?, overlayPath: String?) {
        val target = KrPathUtils.canonicalizeKrStoragePath(KrPathUtils.normalizeFilePath(targetPath))
        val overlay = KrPathUtils.normalizeFilePath(overlayPath)
        val overlayFile = overlay?.let { File(it) }
        if (target.isNullOrBlank() || overlay.isNullOrBlank() || overlayFile?.isFile != true) {
            patchOverlayTarget = null
            patchOverlayPath = null
            Log.i("NativeBridge", "KRKR patch overlay disabled target=$targetPath overlay=$overlayPath")
            return
        }
        patchOverlayTarget = target
        patchOverlayPath = overlay
        Log.i("NativeBridge", "KRKR patch overlay configured target=$target overlay=$overlay")
    }

    @JvmStatic
    fun configureSteamConfigOverlay(targetPath: String?, overlayPath: String?) {
        val target = KrPathUtils.canonicalizeKrStoragePath(KrPathUtils.normalizeFilePath(targetPath))
        val overlay = KrPathUtils.normalizeFilePath(overlayPath)
        val overlayFile = overlay?.let { File(it) }
        if (target.isNullOrBlank() || overlay.isNullOrBlank() || overlayFile?.isFile != true) {
            steamConfigOverlayTarget = null
            steamConfigOverlayPath = null
            Log.i("NativeBridge", "KRKR Steam config overlay disabled target=$targetPath overlay=$overlayPath")
            return
        }
        steamConfigOverlayTarget = target
        steamConfigOverlayPath = overlay
        Log.i("NativeBridge", "KRKR Steam config overlay configured target=$target overlay=$overlay")
    }

    /**
     * 引擎侧文件打开入口（native 钩子上调）。优先级：只读 overlay → scoped 存档重定向 →
     * SAF 镜像文档（占位仍为 0 字节时，任意模式；异常捕获后回落）→ 本地直开/SAF 兜底。
     * 返回可用的 fd，失败返回 -1。
     */
    @Synchronized
    @JvmStatic
    fun open(path: String?, mode: Int): Int {
        val normalized = KrPathUtils.canonicalizeKrStoragePath(KrPathUtils.normalizeFilePath(path))
        readOnlyOverlayRedirect(normalized)?.takeIf { isReadOnlyOpen(mode) }?.let { overlay ->
            return openDirectFile(path, overlay, mode, diagnosticPrefix = "read-only overlay")
        }
        val redirected = KrPathUtils.redirectScopedSavePath(normalized)
        // The native hook uses the stable storage-volume prefix because KRKR may lowercase
        // the game path. Keep regular asset I/O native; only scoped saves need Java redirection.
        if (redirected == null && !isSafFallbackEnabled()) return -1
        val target = if (redirected != null) redirected else normalized ?: return -1
        val javaMode: String = try {
            toJavaMode(mode)
        } catch (t: Throwable) {
            Log.e("NativeBridge", "bad open mode=$mode path=$path", t)
            return -1
        }
        val mirror = SAF_DOCUMENTS[target.lowercase(Locale.ROOT)]
        // 占位文件仍是 0 字节时（未被引擎本地覆写），所有打开模式都路由到 SAF 文档：
        // 引擎常用 O_RDWR 打开插件 DLL 与既有存档（krkr 的 CreateFile 习惯），
        // 只读路由会让这些打开落到本地 0 字节占位上、读到全零——插件匹配失败、
        // 用户放入游戏目录的存档被判为损坏。SAF 打开失败再回落本地占位（保持旧覆盖语义）。
        if (mirror != null && File(target).length() == 0L) {
            // SAF 打开可能抛 FileNotFoundException/SecurityException：捕获后回落本地，
            // 否则异常传出 open()、JNI 侧只会清异常返回 -1，本地回落与日志都被跳过
            val mirrorFd = runCatching { openDocumentUri(mirror.uri, mode) }
                .onFailure { Log.w("NativeBridge", "open mirror SAF failed path=$path", it) }
                .getOrDefault(-1)
            if (mirrorFd >= 0) {
                // 非只读打开会改写 SAF 侧内容：索引尺寸即刻过期，标记脏，供 metadataSize 按需刷新
                if (!isReadOnlyOpen(mode)) markMirrorEntryDirty(target.lowercase(Locale.ROOT))
                return mirrorFd
            }
        }
        return try {
            openDirectFile(path, target, mode, diagnosticPrefix = if (redirected != null) "redirect" else null)
        } catch (directError: Throwable) {
            if (isSafFallbackEnabled()) {
                val safFd = openViaSaf(target, mode, directError)
                if (safFd >= 0) return safFd
            }
            if (redirected != null) recordOpenDiagnostic(
                "failed path=$path target=$target flags=$mode mode=$javaMode " +
                    "error=${directError.javaClass.simpleName}:${directError.message}",
            )
            Log.e("NativeBridge", "open failed mode=$mode path=$path", directError)
            -1
        }
    }

    /** 仅做路径规范化/重定向（不开文件）：scoped 存档重定向优先，其次规范化差异。 */
    @JvmStatic
    fun redirect(path: String?): String? {
        val raw = KrPathUtils.normalizeFilePath(path)
        val normalized = KrPathUtils.canonicalizeKrStoragePath(raw)
        KrPathUtils.redirectScopedSavePath(normalized)?.let { return it }
        if (normalized != null && normalized != path) return normalized
        return null
    }

    /** [redirect] 的带模式版本：只读时叠加只读 overlay 重定向，供 fopen/open 回退路径选择。 */
    @JvmStatic
    fun redirectOpen(path: String?, mode: Int): String? {
        val raw = KrPathUtils.normalizeFilePath(path)
        val normalized = KrPathUtils.canonicalizeKrStoragePath(raw)
        if (isReadOnlyOpen(mode)) {
            readOnlyOverlayRedirect(normalized)?.let { return it }
        }
        KrPathUtils.redirectScopedSavePath(normalized)?.let { return it }
        if (normalized != null && normalized != path) return normalized
        return null
    }

    /** 元数据读取（stat/access）前的重定向：只读 overlay 与 scoped 存档重定向。 */
    @JvmStatic
    fun redirectReadMetadata(path: String?): String? {
        val raw = KrPathUtils.normalizeFilePath(path)
        val normalized = KrPathUtils.canonicalizeKrStoragePath(raw)
        readOnlyOverlayRedirect(normalized)?.let { return it }
        KrPathUtils.redirectScopedSavePath(normalized)?.let { return it }
        if (normalized != null && normalized != path) return normalized
        return null
    }

    /** scoped 存档目录重定向：命中 SCOPED_SAVE_DIR 配置的路径改写到独立存档根。 */
    @JvmStatic
    fun redirectScopedSave(path: String?): String? {
        val normalized = KrPathUtils.canonicalizeKrStoragePath(KrPathUtils.normalizeFilePath(path))
        return KrPathUtils.redirectScopedSavePath(normalized)
    }

    /**
     * 镜像占位文件的 stat 尺寸覆盖：本地占位为 0 字节、SAF 侧记录了真实尺寸时返回该尺寸，
     * 否则返回 -1（不覆盖——引擎已本地覆写的文件 stat 本来就正确）。
     * 脏条目（SAF 侧被引擎写入后尺寸过期）先按 ID 查询文档当前尺寸并回填缓存。
     * 供 native stat/lstat 钩子把 st_size 纠正为真实值：引擎按 stat 尺寸分配缓冲/匹配插件时，
     * 0 字节会让插件 DLL 与既有存档被当成空文件（issue #129 的 SD 卡 Krkr 启动失败/存档不可读）。
     */
    @JvmStatic
    fun metadataSize(path: String?): Long {
        val normalized = KrPathUtils.canonicalizeKrStoragePath(KrPathUtils.normalizeFilePath(path))
            ?: return -1L
        val key = normalized.lowercase(Locale.ROOT)
        val entry = SAF_DOCUMENTS[key] ?: return -1L
        if (entry.size == SIZE_DIRTY) return refreshDirtyMirrorSize(key, entry)
        if (entry.size < 0L) return -1L
        return if (File(normalized).length() == 0L) entry.size else -1L
    }

    /** 把条目标记为脏（尺寸过期）；索引键不存在时忽略。与 [open]/[refreshDirtyMirrorSize] 共用对象锁串行化。 */
    @Synchronized
    private fun markMirrorEntryDirty(key: String) {
        val entry = SAF_DOCUMENTS[key] ?: return
        if (entry.size == SIZE_DIRTY) return
        SAF_DOCUMENTS = SAF_DOCUMENTS.toMutableMap().apply { put(key, KrSafMirror.MirrorEntry(entry.uri, SIZE_DIRTY)) }
    }

    /** 查询脏条目文档的当前尺寸并回填缓存；查询失败返回 -1（保持脏标记，下次再试）。 */
    @Synchronized
    private fun refreshDirtyMirrorSize(key: String, entry: KrSafMirror.MirrorEntry): Long {
        val size = runCatching {
            KrPathUtils.currentActivity()?.contentResolver
                ?.query(entry.uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L }
        }.getOrNull() ?: -1L
        if (size >= 0) {
            SAF_DOCUMENTS = SAF_DOCUMENTS.toMutableMap().apply {
                put(key, KrSafMirror.MirrorEntry(entry.uri, size))
            }
            return size
        }
        return -1L
    }

    private fun recordOpenDiagnostic(value: String) {
        try {
            KrPathUtils.currentActivity()?.getSharedPreferences("krkr_bridge_diagnostics", 0)
                ?.edit()?.putString("last_open", value)?.commit()
        } catch (_: Throwable) {
        }
    }

    private fun readOnlyOverlayRedirect(path: String?): String? {
        if (path == null || path.isBlank()) return null
        val steamConfigTarget = steamConfigOverlayTarget
        val steamConfigOverlay = steamConfigOverlayPath
        if (
            !steamConfigTarget.isNullOrBlank() &&
            !steamConfigOverlay.isNullOrBlank() &&
            matchesOverlayTarget(path, steamConfigTarget)
        ) {
            return steamConfigOverlay
        }
        val patchTarget = patchOverlayTarget
        val patchOverlay = patchOverlayPath
        if (
            !patchTarget.isNullOrBlank() &&
            !patchOverlay.isNullOrBlank() &&
            matchesOverlayTarget(path, patchTarget)
        ) {
            return patchOverlay
        }
        return null
    }

    private fun matchesOverlayTarget(path: String, target: String): Boolean {
        if (path.equals(target, ignoreCase = true)) return true
        if (path.contains('/')) return false
        return path.equals(File(target).name, ignoreCase = true)
    }

    private fun isReadOnlyOpen(mode: Int): Boolean =
        (mode and OsConstants.O_ACCMODE) == OsConstants.O_RDONLY

    private fun openDirectFile(path: String?, target: String, mode: Int, diagnosticPrefix: String?): Int {
        val javaMode = toJavaMode(mode)
        val raf = RandomAccessFile(File(target), javaMode)
        if ((mode and OsConstants.O_TRUNC) == OsConstants.O_TRUNC) raf.setLength(0)
        if ((mode and OsConstants.O_APPEND) == OsConstants.O_APPEND) raf.seek(raf.length())
        val fd = getFd(raf)
        raf.close()
        if (diagnosticPrefix != null) recordOpenDiagnostic(
            "$diagnosticPrefix ok path=$path target=$target flags=$mode mode=$javaMode fd=$fd",
        )
        Log.i("NativeBridge", "open $fd $javaMode $path -> $target")
        return fd
    }

    private fun isSafFallbackEnabled(): Boolean {
        return try {
            val activity = KrPathUtils.currentActivity()
            val intent = activity?.intent
            intent != null && intent.getBooleanExtra(LaunchContract.SAF_FILE_FALLBACK, false)
        } catch (_: Throwable) {
            false
        }
    }

    private fun openViaSaf(path: String, mode: Int, directError: Throwable): Int {
        return try {
            val uri = storagePathToPersistedDocumentUri(path, mode) ?: return -1
            openDocumentUri(uri, mode)
        } catch (safError: Throwable) {
            Log.w("NativeBridge", "open SAF fallback failed path=$path direct=$directError", safError)
            -1
        }
    }

    private fun openDocumentUri(uri: Uri, mode: Int): Int {
        val activity = KrPathUtils.currentActivity() ?: return -1
        val pfdMode = toPfdMode(mode)
        val pfd = activity.contentResolver.openFileDescriptor(uri, pfdMode) ?: return -1
        // PFD 无读写追加模式：RDWR|APPEND 以 "rw" 打开后在此定位到末尾实现追加语义
        // （Os.lseek 需要 FileDescriptor，须在 detachFd 前执行）
        if (pfdMode == "rw" && (mode and OsConstants.O_APPEND) != 0) {
            runCatching { android.system.Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_END) }
        }
        val fd = pfd.detachFd()
        Log.i("NativeBridge", "open SAF $fd $pfdMode -> $uri")
        return fd
    }

    @JvmStatic
    fun writeViaSafIfPossible(path: String?, data: ByteArray?): Boolean {
        return try {
            val p = KrPathUtils.canonicalizeKrStoragePath(path) ?: return false
            val uri = storagePathToPersistedDocumentUri(p, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_TRUNC) ?: return false
            val activity = KrPathUtils.currentActivity() ?: return false
            val out = activity.contentResolver.openOutputStream(uri, "wt") ?: return false
            out.use {
                if (data != null) it.write(data)
                it.flush()
            }
            Log.i("NativeBridge", "write SAF $p -> $uri bytes=${data?.size ?: 0}")
            true
        } catch (t: Throwable) {
            Log.w("NativeBridge", "write SAF failed path=$path", t)
            false
        }
    }

    @JvmStatic
    fun createDirectoryViaSafIfPossible(path: String?): Boolean {
        return try {
            val p = KrPathUtils.canonicalizeKrStoragePath(path) ?: return false
            val uri = storagePathToPersistedDocumentUri(p + "/.tyranor_dir_probe", OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_TRUNC) ?: return false
            val activity = KrPathUtils.currentActivity()
            if (activity != null) {
                try { DocumentsContract.deleteDocument(activity.contentResolver, uri) } catch (_: Throwable) {}
            }
            Log.i("NativeBridge", "mkdir SAF $p")
            true
        } catch (t: Throwable) {
            Log.w("NativeBridge", "mkdir SAF failed path=$path", t)
            false
        }
    }

    @JvmStatic
    fun deleteViaSafIfPossible(path: String?): Boolean {
        return try {
            val p = KrPathUtils.canonicalizeKrStoragePath(path) ?: return false
            val uri = storagePathToPersistedDocumentUri(p, OsConstants.O_RDONLY) ?: return false
            val activity = KrPathUtils.currentActivity() ?: return false
            val ok = DocumentsContract.deleteDocument(activity.contentResolver, uri)
            Log.i("NativeBridge", "delete SAF $p -> $uri ok=$ok")
            ok
        } catch (t: Throwable) {
            Log.w("NativeBridge", "delete SAF failed path=$path", t)
            false
        }
    }

    @JvmStatic
    fun existsViaSafIfPossible(path: String?): Boolean {
        return try {
            val p = KrPathUtils.canonicalizeKrStoragePath(path) ?: return false
            val uri = storagePathToPersistedDocumentUri(p, OsConstants.O_RDONLY) ?: return false
            val activity = KrPathUtils.currentActivity() ?: return false
            val input = activity.contentResolver.openInputStream(uri)
            input.use { it != null }
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun renameViaSafIfPossible(from: String?, to: String?): Boolean {
        return try {
            val f = KrPathUtils.canonicalizeKrStoragePath(from) ?: return false
            val t = KrPathUtils.canonicalizeKrStoragePath(to) ?: return false
            val src = storagePathToPersistedDocumentUri(f, OsConstants.O_RDONLY) ?: return false
            val activity = KrPathUtils.currentActivity() ?: return false
            val resolver = activity.contentResolver
            val input = resolver.openInputStream(src) ?: return false
            input.use {
                val dst = storagePathToPersistedDocumentUri(t, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_TRUNC) ?: return false
                val out = resolver.openOutputStream(dst, "wt") ?: return false
                out.use { o ->
                    val buf = ByteArray(64 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) o.write(buf, 0, n)
                    o.flush()
                }
                try { DocumentsContract.deleteDocument(resolver, src) } catch (_: Throwable) {}
                Log.i("NativeBridge", "rename SAF $f -> $t src=$src")
                true
            }
        } catch (t: Throwable) {
            Log.w("NativeBridge", "rename SAF failed $from -> $to", t)
            false
        }
    }

    @SuppressLint("SdCardPath")
    private fun storagePathToPersistedDocumentUri(path: String?, mode: Int): Uri? {
        if (path == null) return null
        val p = KrPathUtils.normalizeFilePath(path) ?: return null
        if (!p.startsWith("/storage/") && !p.startsWith("/sdcard")) return null
        val volume: String
        val rel: String
        if (p.startsWith("/storage/emulated/0/")) {
            volume = "primary"
            rel = p.substring("/storage/emulated/0/".length)
        } else if ("/storage/emulated/0" == p) {
            volume = "primary"
            rel = ""
        } else if (p.startsWith("/sdcard/")) {
            volume = "primary"
            rel = p.substring("/sdcard/".length)
        } else if ("/sdcard" == p) {
            volume = "primary"
            rel = ""
        } else {
            val rest = p.substring("/storage/".length)
            val slash = rest.indexOf('/')
            if (slash <= 0) return null
            volume = rest.substring(0, slash)
            rel = rest.substring(slash + 1)
        }
        if (volume.isEmpty()) return null
        val activity = KrPathUtils.currentActivity() ?: return null
        val resolver = activity.contentResolver
        val docId = "$volume:$rel"
        Log.i("NativeBridge", "SAF resolve path=$path volume=$volume rel=$rel")
        for (perm in resolver.persistedUriPermissions) {
            val tree = perm.uri ?: continue
            val treeId = try { DocumentsContract.getTreeDocumentId(tree) } catch (_: Throwable) { null } ?: continue
            val decodedTreeId = Uri.decode(treeId)
            if (!decodedTreeId.startsWith("$volume:")) continue
            val treeRel = decodedTreeId.substring("$volume:".length)
            if (treeRel.isNotEmpty()) {
                if (rel != treeRel && !rel.startsWith("$treeRel/")) continue
            }
            val existing = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            if (!needsCreate(mode)) return existing
            val created = ensureDocumentExists(resolver, tree, decodedTreeId, volume, rel)
            return created ?: existing
        }
        return null
    }

    private fun needsCreate(mode: Int): Boolean {
        val accessMode = mode and OsConstants.O_ACCMODE
        return accessMode == OsConstants.O_WRONLY || accessMode == OsConstants.O_RDWR || (mode and OsConstants.O_CREAT) == OsConstants.O_CREAT
    }

    private fun ensureDocumentExists(resolver: ContentResolver, tree: Uri, decodedTreeId: String, volume: String, rel: String): Uri? {
        return try {
            val activity = KrPathUtils.currentActivity() ?: return null
            val dir = DocumentFile.fromTreeUri(activity, tree) ?: return null
            val treePrefix = "$volume:"
            val treeRel = if (decodedTreeId.startsWith(treePrefix)) decodedTreeId.substring(treePrefix.length) else ""
            var localRel = rel
            if (treeRel.isNotEmpty()) {
                if (localRel == treeRel) return DocumentsContract.buildDocumentUriUsingTree(tree, "$volume:$rel")
                if (localRel.startsWith("$treeRel/")) localRel = localRel.substring(treeRel.length + 1)
            }
            val parts = localRel.split("/".toRegex()).toTypedArray()
            var current: DocumentFile? = dir
            for (i in parts.indices) {
                val part = parts[i]
                if (part.isEmpty() || part == ".") continue
                val last = i == parts.size - 1
                var child = findChildDocument(current, part)
                if (last) {
                    if (child == null) child = current!!.createFile(guessMime(part), part)
                    return child?.uri
                }
                if (child == null) child = current!!.createDirectory(part)
                if (child == null || !child.isDirectory) return null
                current = child
            }
            null
        } catch (t: Throwable) {
            Log.w("NativeBridge", "ensure SAF document failed rel=$rel", t)
            null
        }
    }

    private fun guessMime(name: String?): String {
        val lower = name?.lowercase(Locale.ROOT) ?: ""
        if (lower.endsWith(".txt") || lower.endsWith(".tjs") || lower.endsWith(".ks") || lower.endsWith(".xml") || lower.endsWith(".json")) return "text/plain"
        return "application/octet-stream"
    }

    private fun findChildDocument(dir: DocumentFile?, name: String?): DocumentFile? {
        if (dir == null || name == null) return null
        return try {
            dir.findFile(name) ?: dir.listFiles().firstOrNull { it.getName()?.equals(name, ignoreCase = true) == true }
        } catch (_: Throwable) {
            null
        }
    }

    private fun toJavaMode(mode: Int): String {
        val accessMode = mode and OsConstants.O_ACCMODE
        if (accessMode == OsConstants.O_RDONLY) return "r"
        if (accessMode == OsConstants.O_WRONLY || accessMode == OsConstants.O_RDWR) return "rw"
        throw IllegalArgumentException("Bad mode: $mode")
    }

    private fun toPfdMode(mode: Int): String {
        val accessMode = mode and OsConstants.O_ACCMODE
        if (accessMode == OsConstants.O_RDONLY) return "r"
        if ((mode and OsConstants.O_APPEND) == OsConstants.O_APPEND) {
            // PFD 不支持 "rwa"（parseMode 只认 r/w/wt/wa/rw/rwt）：RDWR|APPEND 用 "rw"
            // 打开后由 openDocumentUri 按 O_APPEND lseek 到末尾定位追加语义
            return if (accessMode == OsConstants.O_RDWR) "rw" else "wa"
        }
        if ((mode and OsConstants.O_TRUNC) == OsConstants.O_TRUNC) {
            // RDWR|TRUNC 必须保持可读，映射为读写截断；仅 WRONLY 截断用 "wt"
            return if (accessMode == OsConstants.O_RDWR) "rwt" else "wt"
        }
        if (accessMode == OsConstants.O_WRONLY) return "w"
        if (accessMode == OsConstants.O_RDWR) return "rw"
        throw IllegalArgumentException("Bad mode: $mode")
    }

    private fun getFd(raf: RandomAccessFile): Int {
        val duplicate = ParcelFileDescriptor.dup(raf.fd)
        return duplicate.detachFd()
    }
}
