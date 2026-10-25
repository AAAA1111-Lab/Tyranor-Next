package bridge

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.AtomicFile
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.Locale

/**
 * Builds the local shadow tree used by Kirikiroid2 for SAF-only game folders.
 *
 * Directories and empty placeholder files satisfy libc stat/access/opendir calls. Actual file
 * contents are supplied by [NativeBridge.open] from the indexed SAF Uri for read-intent opens
 * (read-only, and read-write without truncation/append); write-intent opens stay on the local
 * placeholder so SD source files are never truncated or clobbered. The source `savedata`
 * subtree is skipped: saves live in the app-private mirror directory by design. Non-empty
 * local files are retained and take precedence, matching Tyranor's original overlay behavior.
 */
object KrSafMirror {
    private const val TAG = "KrSafMirror"
    private const val INDEX_VERSION_V1 = "krkr-saf-index-v1"
    private const val INDEX_VERSION = "krkr-saf-index-v2"

    /** Depth cap for the recursive mirror walk (game trees are shallow; guards odd structures). */
    private const val MIRROR_MAX_DEPTH = 6

    /**
     * 索引条目：镜像占位文件对应的 SAF 文档与其真实尺寸。尺寸未知（v1 索引）时为 -1；
     * -2（[NativeBridge.SIZE_DIRTY]）表示 SAF 侧已被写模式打开、尺寸待刷新。
     */
    data class MirrorEntry(val uri: Uri, val size: Long)

    /**
     * 镜像准备失败（**类型化原因，不带文案**：文案由 app 层按 [stage] 映射，见 AGENTS.md 错误协议）。
     *
     * [name] 为相关目录/文件名，供调用方给出可定位的提示（如「无法创建镜像目录：data」）。
     */
    class PrepareException(
        val stage: Stage,
        val name: String? = null,
    ) : IllegalStateException(stage.name + (name?.let { ": $it" } ?: ""))

    enum class Stage {
        /** 无法解析 SD 卡/SAF 源目录（授权失效或目录已被移动）。 */
        SOURCE_UNRESOLVED,

        /** 无法创建镜像根目录。 */
        MIRROR_DIR,

        /** 无法创建 SAF 索引目录。 */
        INDEX_DIR,

        /** 无法创建镜像子目录。 */
        CHILD_DIR,

        /** 无法创建镜像占位文件。 */
        CHILD_FILE,
    }

    data class Prepared(
        val mirrorRoot: File,
        val indexFile: File,
        val fileCount: Int,
    )

    /**
     * 构建镜像目录树并写索引。流程：解析 SAF 源目录 → 递归建 0 字节占位（跳过源
     * `savedata` 子树：存档按设计只走应用私有目录，源存档经存档管理导入）
     * → 三重补全缺失条目（MediaStore / File 直列 / .sig 反推探测）→ 原子写索引 v2。
     * 失败以 [PrepareException] 按阶段抛出，由 app 层映射文案。
     */
    @JvmStatic
    fun prepare(
        context: Context,
        sourceReference: String,
        logicalPath: String,
        displayName: String,
    ): Prepared {
        val startedAt = System.currentTimeMillis()
        val resolved = resolveDocumentDirectory(context.applicationContext, sourceReference, logicalPath)
            ?: throw PrepareException(Stage.SOURCE_UNRESOLVED, logicalPath)
        val mirrorRoot = mirrorRootFor(context, sourceReference, logicalPath, displayName)
        if (!mirrorRoot.isDirectory && !mirrorRoot.mkdirs()) {
            throw PrepareException(Stage.MIRROR_DIR, mirrorRoot.name)
        }

        val entries = ArrayList<Triple<String, String, Long>>()
        // Case-collapsed key set shared by mirroring and the merge passes: mirroring
        // dedupes root-tree entries, the merges skip anything already known
        val known = HashSet<String>()
        mirrorDirectory(context.applicationContext, resolved, mirrorRoot, entries, known, atRoot = true)
        val sourceRoot = logicalPath.trimEnd('/')
        var merged = mergeMissingFromMediaStore(context.applicationContext, resolved.treeUri, sourceRoot, mirrorRoot, entries, known)
        merged += mergeMissingFromDirectListing(resolved.treeUri, sourceRoot, mirrorRoot, entries, known)
        merged += probeSigSiblingDlls(context.applicationContext, resolved.treeUri, entries, known)

        val indexDir = File(context.noBackupFilesDir, "krkr_saf_index")
        if (!indexDir.isDirectory && !indexDir.mkdirs()) {
            throw PrepareException(Stage.INDEX_DIR, indexDir.name)
        }
        val indexFile = File(indexDir, "${mirrorKey(sourceReference, logicalPath)}.idx")
        // 索引写入失败会抛 IOException（AtomicFile.failWrite 后原样抛出）：归到 INDEX_DIR，
        // 否则它会落到调用方的 catch-all 分支、报成「未知路径」，用户看不出是写索引失败
        try {
            writeIndex(indexFile, entries)
        } catch (error: Throwable) {
            throw PrepareException(Stage.INDEX_DIR, indexFile.name).also { it.initCause(error) }
        }
        val sized = entries.count { it.third >= 0 }
        val totalBytes = entries.sumOf { if (it.third >= 0) it.third else 0L }
        Log.i(
            TAG,
            "mirror prepared root=${mirrorRoot.name} files=${entries.size} sized=$sized " +
                "mediaMerged=$merged bytes=$totalBytes cost=${System.currentTimeMillis() - startedAt}ms",
        )
        return Prepared(mirrorRoot, indexFile, entries.size)
    }

    /** [prepare] 使用的镜像 key；存档管理侧据此定位引擎真实读写的镜像目录，算法必须与 prepare 一致。 */
    @JvmStatic
    fun mirrorKey(sourceReference: String, logicalPath: String): String =
        sha256("$sourceReference\n$logicalPath").take(16)

    /** 预测 [prepare] 将使用的镜像根目录（不触发构建、不读取 SAF），供存档定位与删除清理复用。 */
    @JvmStatic
    fun mirrorRootFor(context: Context, sourceReference: String, logicalPath: String, displayName: String): File {
        val safeName = sanitize(displayName.ifBlank { "" }).ifBlank { "game" }
        val dataRoot = context.filesDir.parentFile ?: context.filesDir
        return File(File(dataRoot, "games"), "${safeName.lowercase(Locale.ROOT)}-${mirrorKey(sourceReference, logicalPath)}")
    }

    /** 解析索引文件（兼容 v1/v2；v1 无尺寸列记 -1），键为镜像路径全小写。文件缺失/损坏返回空表。 */
    @JvmStatic
    fun loadIndex(indexPath: String?): Map<String, MirrorEntry> {
        if (indexPath.isNullOrBlank()) return emptyMap()
        val file = File(indexPath)
        if (!file.isFile) return emptyMap()
        return file.bufferedReader().useLines { lines ->
            parseIndexLines(lines.asIterable()) { uri -> Uri.parse(uri) }
                .mapValues { (_, holder) -> MirrorEntry(holder.uri, holder.size) }
        }
    }

    /**
     * 索引文本行解析（与 [loadIndex] 共用；独立出来便于 JVM 单测——
     * Uri 构造经由 [buildUri] 注入，测试中用恒等函数绕开 android.net.Uri stub）。
     */
    internal fun <T> parseIndexLines(
        lines: Iterable<String>,
        buildUri: (String) -> T,
    ): Map<String, MirrorEntryHolder<T>> {
        val result = LinkedHashMap<String, MirrorEntryHolder<T>>()
        val iterator = lines.iterator()
        if (!iterator.hasNext()) return result
        when (iterator.next()) {
            // v2 行带第三列十进制尺寸；v1 无尺寸列，size 记 -1（stat 钩子不覆盖）
            INDEX_VERSION -> {}
            INDEX_VERSION_V1 -> {}
            else -> return result
        }
        while (iterator.hasNext()) {
            val line = iterator.next()
            val split = line.indexOf('\t')
            if (split <= 0 || split >= line.lastIndex) continue
            val path = decode(line.substring(0, split)).lowercase(Locale.ROOT)
            val rest = line.substring(split + 1)
            val sizeSplit = rest.indexOf('\t')
            val uri: String
            val size: Long
            if (sizeSplit < 0) {
                uri = rest
                size = -1L
            } else {
                uri = rest.substring(0, sizeSplit)
                size = rest.substring(sizeSplit + 1).toLongOrNull() ?: -1L
            }
            val decodedUri = decode(uri)
            if (path.isNotBlank() && decodedUri.isNotBlank()) {
                result[path] = MirrorEntryHolder(size, buildUri(decodedUri))
            }
        }
        return result
    }

    /** Parse intermediate: the built uri plus its size; generic over android/net or test identity. */
    internal data class MirrorEntryHolder<T>(val size: Long, val uri: T)

    private data class ResolvedDocument(val treeUri: Uri, val documentId: String)

    // 目录级 scanFile 已移除：实测 MediaScannerConnection 对目录路径不递归建行
    //（本机 ROM 25-67ms 即回调但 children 无变化），真正生效的是
    // probeSigSiblingDlls 的逐文件扫描（按文件路径建行即时可见）。

    /**
     * 用 MediaStore 文件表补全 SAF 子文档列表缺失的条目，返回补全数量。
     * 仅做增量合并：SAF 已列出的以 SAF 为准；缺失的按 `_data` 路径推导文档 ID，
     * 直接经 [treeUri] 构造文档 URI——按 ID 的 openFileDescriptor/stat
     * 不依赖 children 查询，树授权即可访问。查询失败（权限/ROM 差异）静默跳过。
     */
    private fun mergeMissingFromMediaStore(
        context: Context,
        treeUri: Uri,
        sourceRoot: String,
        mirrorRoot: File,
        entries: MutableList<Triple<String, String, Long>>,
        known: MutableSet<String>,
    ): Int {
        var merged = 0
        try {
            val collection = MediaStore.Files.getContentUri("external")
            context.contentResolver.query(
                collection,
                arrayOf(
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.MIME_TYPE,
                    // MediaStore 无公开 FORMAT 常量，目录行按 MTP format 列识别（association=0x3001）
                    "format",
                ),
                "${MediaStore.MediaColumns.DATA} LIKE ?",
                arrayOf("$sourceRoot/%"),
                null,
            )?.use { cursor ->
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val formatColumn = cursor.getColumnIndex("format")
                while (cursor.moveToNext()) {
                    val data = cursor.getString(dataColumn) ?: continue
                    if (!data.startsWith("$sourceRoot/")) continue
                    // 目录行必须排除：否则会生成同名普通文件占位，后续子文件占位的
                    // parent.mkdirs() 全部失败，整棵子树被跳过。目录的 mime_type 通常为
                    // NULL，需按 MTP format 列（FORMAT_ASSOCIATION=0x3001）识别
                    val mime = cursor.getString(mimeColumn)
                    if (mime != null && mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    if (formatColumn >= 0 && !cursor.isNull(formatColumn) &&
                        cursor.getLong(formatColumn) == 0x3001L
                    ) {
                        continue
                    }
                    val size = if (cursor.isNull(sizeColumn)) -1L else cursor.getLong(sizeColumn)
                    if (addMergedEntry(treeUri, sourceRoot, data, size, mirrorRoot, entries, known)) merged++
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "merge media store entries failed root=$sourceRoot", t)
        }
        if (merged > 0) Log.i(TAG, "media store merged $merged missing files for $sourceRoot")
        return merged
    }

    /**
     * 树授权目录在 FUSE 层按路径对持有者放行：直接 File 遍历游戏目录补全 SAF 列表
     * 缺失的文件（尺寸取自 File）。应用无权访问（Scoped Storage 拒绝）时静默跳过。
     */
    private fun mergeMissingFromDirectListing(
        treeUri: Uri,
        sourceRoot: String,
        mirrorRoot: File,
        entries: MutableList<Triple<String, String, Long>>,
        known: MutableSet<String>,
    ): Int {
        val rootDir = File(sourceRoot)
        val canList = runCatching { rootDir.isDirectory && rootDir.canRead() }.getOrDefault(false)
        if (!canList) return 0
        var merged = 0
        // 深度封顶防异常结构递归；krkr 游戏目录树通常 ≤4 层
        fun walk(dir: File, depth: Int) {
            if (depth > 6) return
            val children = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (child in children) {
                if (child.isDirectory) {
                    walk(child, depth + 1)
                } else if (child.isFile) {
                    if (addMergedEntry(treeUri, sourceRoot, child.absolutePath, child.length(), mirrorRoot, entries, known)) {
                        merged++
                    }
                }
            }
        }
        walk(rootDir, 0)
        if (merged > 0) Log.i(TAG, "direct listing merged $merged missing files for $sourceRoot")
        return merged
    }

    /** 大小写不敏感地去掉 `.sig` 后缀（文档 ID 保留原始大小写，removeSuffix 会漏掉 `X.DLL.SIG`）。 */
    internal fun stripSigSuffix(name: String): String {
        return if (name.length >= 4 && name.regionMatches(name.length - 4, ".sig", 0, 4, ignoreCase = true)) {
            name.dropLast(4)
        } else {
            name
        }
    }

    /**
     * 末道兜底：已索引的 `X.dll.sig` 证明源目录存在插件 `X.dll`。对缺失的同名 DLL：
     * 先以应用身份逐文件触发媒体扫描（scanFile 对目录不递归，必须按文件路径扫描；
     * 扫描建行后按 ID 的访问与列表随之可见），再 queryDocument 校验并补全。
     * 真实路径取自 sig 条目 URI 中的文档 ID（保留原始大小写）。
     */
    private fun probeSigSiblingDlls(
        context: Context,
        treeUri: Uri,
        entries: MutableList<Triple<String, String, Long>>,
        known: MutableSet<String>,
    ): Int {
        val candidates = ArrayList<Pair<String, String>>() // (mirrorKey, sourcePath)
        val sigEntries = entries.filter { it.first.endsWith(".dll.sig") }.toList()
        for ((mirrorKeyPath, sigUriString, _) in sigEntries) {
            val dllMirrorKey = stripSigSuffix(mirrorKeyPath)
            if (dllMirrorKey == mirrorKeyPath || known.contains(dllMirrorKey)) continue
            val sigDocId = runCatching {
                DocumentsContract.getDocumentId(Uri.parse(sigUriString))
            }.getOrNull() ?: continue
            val dllSourcePath = documentIdToStoragePath(stripSigSuffix(sigDocId)) ?: continue
            candidates += dllMirrorKey to dllSourcePath
        }
        if (candidates.isEmpty()) return 0
        // 批量扫描：一次调用带回逐路径回调，汇总闩等待，单文件扫描在 ROM 上近乎即时
        try {
            val latch = java.util.concurrent.CountDownLatch(candidates.size)
            MediaScannerConnection.scanFile(context, candidates.map { it.second }.toTypedArray(), null) { _, _ ->
                latch.countDown()
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (t: Throwable) {
            Log.w(TAG, "sig sibling scan failed", t)
        }
        var merged = 0
        for ((dllMirrorKey, dllSourcePath) in candidates) {
            val dllDocumentId = storagePathToDocumentId(dllSourcePath) ?: continue
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, dllDocumentId)
            val size = runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(DocumentsContract.Document.COLUMN_SIZE),
                    null, null, null,
                )?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
                } ?: -1L
            }.getOrDefault(-1L)
            if (size <= 0) continue
            val local = File(dllMirrorKey)
            val parent = local.parentFile
            if (parent == null || (!parent.isDirectory && !runCatching { parent.mkdirs() }.getOrDefault(false))) continue
            if (!local.exists() && !runCatching { local.createNewFile() }.getOrDefault(false)) continue
            entries += Triple(dllMirrorKey, uri.toString(), size)
            known.add(dllMirrorKey)
            merged++
        }
        if (merged > 0) Log.i(TAG, "sig probing merged $merged plugin dlls")
        return merged
    }

    /** 文档 ID（`卷:相对路径`）还原为 `/storage/卷/相对路径`；主存储 `primary:` 对应 emulated/0。 */
    private fun documentIdToStoragePath(documentId: String): String? {
        val colon = documentId.indexOf(':')
        if (colon <= 0 || colon == documentId.lastIndex) return null
        val volume = documentId.substring(0, colon)
        val rest = documentId.substring(colon + 1)
        if (volume.isBlank() || rest.isBlank()) return null
        val volumeDir = if (volume.equals("primary", ignoreCase = true)) "emulated/0" else volume
        return "/storage/$volumeDir/$rest"
    }

    /**
     * 把一条源文件路径合并进镜像（已在索引中则跳过）。[sourcePath] 必须位于
     * `[sourceRoot]/` 之下。返回是否新增。
     */
    private fun addMergedEntry(
        treeUri: Uri,
        sourceRoot: String,
        sourcePath: String,
        size: Long,
        mirrorRoot: File,
        entries: MutableList<Triple<String, String, Long>>,
        known: MutableSet<String>,
    ): Boolean {
        try {
            if (!sourcePath.startsWith("$sourceRoot/")) return false
            val relative = sourcePath.substring(sourceRoot.length + 1)
            if (relative.isBlank()) return false
            // 补全路径的中央拦截：源 savedata 子树与 mirrorDirectory 的跳过规则一致，
            // 否则 MediaStore/File 直列会把源存档合并成占位、绕过镜像跳过逻辑
            if (relative.equals("savedata", ignoreCase = true) ||
                relative.startsWith("savedata/", ignoreCase = true)
            ) {
                return false
            }
            val local = File(mirrorRoot, relative.lowercase(Locale.ROOT))
            // canonical 前缀校验（与 mirrorDirectory 的 safeMirrorChild 同类防护）：
            // sourcePath 携带 ../ 时 File(mirrorRoot, relative) 可能逃出镜像目录
            val mirrorRootCanonical = runCatching { mirrorRoot.canonicalPath }
                .getOrDefault(mirrorRoot.absolutePath)
            val localCanonical = runCatching { local.canonicalPath }.getOrNull() ?: return false
            if (!localCanonical.startsWith("$mirrorRootCanonical/")) return false
            val key = local.absolutePath.lowercase(Locale.ROOT)
            if (known.contains(key)) return false
            // 目标已是目录（SAF 已列出该目录）时跳过：createNewFile 会失败，
            // exists()==true 也会把目录当文件写进索引
            if (local.isDirectory) return false
            val parent = local.parentFile ?: return false
            if (!parent.isDirectory && !parent.mkdirs()) return false
            if (!local.exists() && !local.createNewFile()) return false
            val documentId = storagePathToDocumentId(sourcePath) ?: return false
            entries += Triple(key, DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).toString(), size)
            known.add(key)
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "merge entry failed path=$sourcePath", t)
            return false
        }
    }

    /**
     * Recursively mirror the SAF directory tree. One DocumentsContract children query per
     * directory (displayName/mime/size in the same projection, avoiding per-file length()
     * Binder round-trips). Placeholders stay 0 bytes: real content is supplied by
     * NativeBridge from the indexed SAF Uri, and the real size is recorded for the stat
     * hook override. [atRoot] skips the source `savedata` subtree: saves live in the
     * app-private mirror directory by design. A failed children query logs a warning and
     * returns the partial result (the three merge passes fill the gaps) instead of failing
     * the whole launch; [seenKeys] dedupes case-collapsed paths so two documents that only
     * differ in case cannot produce conflicting index entries.
     */
    private fun mirrorDirectory(
        context: Context,
        source: ResolvedDocument,
        destination: File,
        entries: MutableList<Triple<String, String, Long>>,
        seenKeys: MutableSet<String>,
        depth: Int = 0,
        atRoot: Boolean = false,
    ) {
        if (depth > MIRROR_MAX_DEPTH) return
        val destRoot = runCatching { destination.canonicalPath }.getOrNull() ?: destination.absolutePath
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            source.treeUri,
            source.documentId,
        )
        val cursor = runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null,
                null,
                null,
            )
        }.onFailure { error ->
            // Baseline DocumentFile.listFiles() tolerated provider exceptions and kept the
            // partial listing; preserve that behavior instead of failing the launch.
            Log.w(TAG, "mirror children query failed dir=${destination.name}", error)
        }.getOrNull() ?: return
        cursor.use {
            while (it.moveToNext()) {
                val documentId = it.getString(0) ?: continue
                val name = it.getString(1)?.takeIf { n -> n.isNotBlank() } ?: continue
                val isDirectory = it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                val size = if (it.isNull(3)) -1L else it.getLong(3)
                // Skip unsafe provider-supplied names (same protection as zip-slip guards)
                val local = safeMirrorChild(destination, destRoot, name.lowercase(Locale.ROOT)) ?: continue
                if (atRoot && isDirectory && local.name.equals("savedata", ignoreCase = true)) continue
                if (isDirectory) {
                    if (!local.isDirectory && !local.mkdirs()) {
                        throw PrepareException(Stage.CHILD_DIR, local.name)
                    }
                    mirrorDirectory(
                        context,
                        ResolvedDocument(source.treeUri, documentId),
                        local,
                        entries,
                        seenKeys,
                        depth + 1,
                    )
                } else {
                    val key = local.absolutePath.lowercase(Locale.ROOT)
                    // Same-key documents that only differ in case would write conflicting
                    // index entries (size/content from different documents); keep the first
                    if (!seenKeys.add(key)) {
                        Log.w(TAG, "duplicate mirror entry skipped: $key")
                        continue
                    }
                    if (!local.exists() && !local.createNewFile()) {
                        throw PrepareException(Stage.CHILD_FILE, local.name)
                    }
                    entries += Triple(
                        key,
                        DocumentsContract.buildDocumentUriUsingTree(source.treeUri, documentId).toString(),
                        size,
                    )
                }
            }
        }
    }

    /** 把 provider 给的条目名安全落到 [destination] 之下；不安全返回 null（调用方跳过）。 */
    private fun safeMirrorChild(destination: File, destRoot: String, name: String): File? {
        if (name.isBlank() || name == "." || name == "..") return null
        if (name.contains('/') || name.contains('\\')) return null
        val file = File(destination, name)
        val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return null
        return if (canonical.startsWith(destRoot + File.separator)) file else null
    }

    /**
     * Resolve a scanned child document through the nearest persisted tree grant. A saved game Uri
     * is not guaranteed to be a tree Uri itself; providers commonly return a child document Uri
     * whose access is inherited from an ancestor selected through ACTION_OPEN_DOCUMENT_TREE.
     */
    private fun resolveDocumentDirectory(
        context: Context,
        sourceReference: String,
        logicalPath: String,
    ): ResolvedDocument? {
        val uri = Uri.parse(sourceReference)
        val contentUri = uri.takeIf { it.scheme.equals("content", ignoreCase = true) }
        val documentId = contentUri?.let {
            runCatching { DocumentsContract.getDocumentId(it) }.getOrNull()
                ?: runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull()
        } ?: storagePathToDocumentId(logicalPath)
            ?: return null
        val candidates = LinkedHashMap<String, Uri>()
        contentUri?.let { sourceUri ->
            runCatching { DocumentsContract.getTreeDocumentId(sourceUri) }.getOrNull()?.let { treeId ->
                candidates[treeId] = sourceUri
            }
        }
        for (permission in context.contentResolver.persistedUriPermissions) {
            if (!permission.isReadPermission) continue
            if (contentUri != null && permission.uri.authority != contentUri.authority) continue
            if (contentUri == null && permission.uri.authority != "com.android.externalstorage.documents") continue
            val treeId = runCatching {
                DocumentsContract.getTreeDocumentId(permission.uri)
            }.getOrNull() ?: continue
            candidates[treeId] = permission.uri
        }
        val ordered = candidates.entries
            .filter { (treeId, _) -> isSameOrDescendant(documentId, treeId) }
            .sortedByDescending { it.key.length }
        for ((treeId, treeUri) in ordered) {
            val root = DocumentFile.fromTreeUri(context, treeUri)?.takeIf { it.isDirectory } ?: continue
            var current = root
            var failed = false
            val relative = if (documentId.length == treeId.length) "" else {
                documentId.substring(treeId.length + 1)
            }
            for (segment in relative.split('/').filter { it.isNotBlank() }) {
                val next = current.findFile(segment)
                    ?: current.listFiles().firstOrNull { it.name.equals(segment, ignoreCase = true) }
                if (next == null || !next.isDirectory) {
                    failed = true
                    break
                }
                current = next
            }
            if (!failed) {
                val resolvedId = runCatching { DocumentsContract.getDocumentId(current.uri) }.getOrNull()
                if (resolvedId != null) {
                    Log.i(TAG, "resolved document=$documentId from tree=$treeId name=${current.name}")
                    return ResolvedDocument(treeUri, resolvedId)
                }
            }
        }
        Log.e(TAG, "cannot resolve document=$documentId grants=${candidates.keys}")
        return null
    }

    /** Convert the real path used by native KRKR into ExternalStorageProvider's document id. */
    @JvmStatic
    fun storagePathToDocumentId(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val normalized = path.replace('\\', '/').trimEnd('/')
        return when {
            normalized == "/storage/emulated/0" || normalized == "/sdcard" -> "primary:"
            normalized.startsWith("/storage/emulated/0/") ->
                "primary:${normalized.substring("/storage/emulated/0/".length)}"
            normalized.startsWith("/sdcard/") ->
                "primary:${normalized.substring("/sdcard/".length)}"
            normalized.startsWith("/storage/") -> {
                val rest = normalized.substring("/storage/".length)
                val slash = rest.indexOf('/')
                if (slash < 0) "$rest:" else {
                    val volume = rest.substring(0, slash)
                    val relative = rest.substring(slash + 1)
                    if (volume.isBlank()) null else "$volume:$relative"
                }
            }
            else -> null
        }
    }

    private fun isSameOrDescendant(documentId: String, treeId: String): Boolean =
        documentId.equals(treeId, ignoreCase = true) ||
            (documentId.length > treeId.length &&
                documentId.regionMatches(0, treeId, 0, treeId.length, ignoreCase = true) &&
                documentId[treeId.length] == '/')

    /** Serializes entries into the index text (header + one tab-separated line per entry). */
    internal fun buildIndexText(entries: List<Triple<String, String, Long>>): String {
        return buildString {
            appendLine(INDEX_VERSION)
            for ((path, uri, size) in entries) {
                append(encode(path)).append('\t')
                    .append(encode(uri)).append('\t')
                    .append(size.toString()).append('\n')
            }
        }
    }

    private fun writeIndex(file: File, entries: List<Triple<String, String, Long>>) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            val writer = OutputStreamWriter(stream, Charsets.UTF_8).buffered()
            writer.append(buildIndexText(entries))
            writer.flush()
            atomic.finishWrite(stream)
        } catch (t: Throwable) {
            atomic.failWrite(stream)
            throw t
        }
    }

    // java.util.Base64（API 26+，与 minSdk 一致）：URL-safe 字母表、无 padding,
    // 与旧 android.util.Base64(NO_WRAP or URL_SAFE) 输出完全一致，且 JVM 单测可直接运行
    private fun encode(value: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decode(value: String): String =
        String(java.util.Base64.getUrlDecoder().decode(value), Charsets.UTF_8)

    private fun sanitize(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
