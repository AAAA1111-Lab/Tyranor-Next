package bridge

import bridge.KrSafMirror
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * KrSafMirror 索引与路径转换的纯逻辑测试（不触及 SAF/Context/Android stub）。
 * 覆盖 #129 修复引入的索引 v2 尺寸列、v1 兼容回退与 documentId 双向转换。
 * 行解析经 [KrSafMirror.parseIndexLines]（Uri 构造恒等注入，绕开 android.net.Uri JVM stub）。
 */
class KrSafMirrorIndexTest {
    /** 与 KrSafMirror 内部编码一致：URL-safe 字母表、无 padding 的 Base64（纯 JVM 实现）。 */
    private fun encode(value: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun v2(vararg lines: String): List<String> =
        listOf("krkr-saf-index-v2") + lines.toList()

    private fun v1(vararg lines: String): List<String> =
        listOf("krkr-saf-index-v1") + lines.toList()

    private fun v2Line(path: String, uri: String, size: Long): String =
        "${encode(path)}\t${encode(uri)}\t$size"

    private fun v1Line(path: String, uri: String): String =
        "${encode(path)}\t${encode(uri)}"

    @Test
    fun v2IndexRoundTripsSizesAndUris() {
        val path = "/data/user/0/x/games/game-abc/plugin/gamepad.dll"
        val uri = "content://com.android.externalstorage.documents/tree/VOL%3AROOT/document/VOL%3AROOT%2Fplugin%2Fgamepad.dll"
        val index = KrSafMirror.parseIndexLines(v2(v2Line(path, uri, 241664L))) { it }
        val entry = index[path]
        assertEquals(uri, entry?.uriString)
        assertEquals(uri, entry?.uri)
        assertEquals(241664L, entry?.size)
    }

    @Test
    fun v2IndexToleratesMalformedSizeColumn() {
        val path = "/x/data.xp3"
        val line = "${encode(path)}\t${encode("content://a/b")}\tnot-a-number"
        val index = KrSafMirror.parseIndexLines(v2(line)) { it }
        // 非法尺寸回退 -1（stat 钩子不覆盖），条目本身保留
        assertEquals(-1L, index[path]?.size)
    }

    @Test
    fun v1IndexLoadsWithoutSizes() {
        val index = KrSafMirror.parseIndexLines(v1(v1Line("/x/data.xp3", "content://a/b"))) { it }
        assertEquals("content://a/b", index["/x/data.xp3"]?.uriString)
        // v1 无尺寸列：记 -1，stat 钩子不覆盖
        assertEquals(-1L, index["/x/data.xp3"]?.size)
    }

    @Test
    fun unknownIndexVersionIsRejected() {
        val index = KrSafMirror.parseIndexLines(listOf("krkr-saf-index-v9", "junk\tjunk")) { it }
        assertTrue(index.isEmpty())
    }

    @Test
    fun emptyInputYieldsEmptyMap() {
        assertTrue(KrSafMirror.parseIndexLines(emptyList()) { it }.isEmpty())
    }

    @Test
    fun pathsAreLowercasedOnLoad() {
        val index = KrSafMirror.parseIndexLines(v2(v2Line("/X/Data.XP3", "content://a/b", 1L))) { it }
        // 索引键全小写：与引擎可能小写化的游戏路径匹配
        assertEquals(1, index.size)
        assertEquals("content://a/b", index["/x/data.xp3"]?.uriString)
    }

    @Test
    fun blankPathsAndUrisAreSkipped() {
        val blankPath = "\t${encode("content://a/b")}\t5" // 首列为空 → split<=0 跳过
        val blankUri = "${encode("/x/f")}\t\t5" // URI 为空
        val index = KrSafMirror.parseIndexLines(v2(blankPath, blankUri)) { it }
        assertTrue(index.isEmpty())
    }

    @Test
    fun documentIdConversionCoversVolumesAndAliases() {
        assertEquals("VOL:sub/file.xp3", KrSafMirror.storagePathToDocumentId("/storage/VOL/sub/file.xp3"))
        assertEquals("VOL:", KrSafMirror.storagePathToDocumentId("/storage/VOL"))
        assertEquals("primary:Games/G", KrSafMirror.storagePathToDocumentId("/storage/emulated/0/Games/G"))
        assertEquals("primary:Games/G", KrSafMirror.storagePathToDocumentId("/sdcard/Games/G"))
        assertEquals("primary:", KrSafMirror.storagePathToDocumentId("/storage/emulated/0"))
        assertEquals("primary:", KrSafMirror.storagePathToDocumentId("/sdcard"))
        assertNull(KrSafMirror.storagePathToDocumentId("/data/user/0/app/files"))
        assertNull(KrSafMirror.storagePathToDocumentId(null))
        assertNull(KrSafMirror.storagePathToDocumentId(""))
    }

    @Test
    fun documentIdConversionNormalizesSeparatorsAndTrailingSlash() {
        assertEquals("VOL:sub/file.xp3", KrSafMirror.storagePathToDocumentId("/storage/VOL/sub\\file.xp3"))
        assertEquals("VOL:sub", KrSafMirror.storagePathToDocumentId("/storage/VOL/sub/"))
    }

    @Test
    fun mirrorKeyIsStableAndDependsOnBothInputs() {
        val key1 = KrSafMirror.mirrorKey("content://tree/A", "/storage/A/Game")
        val key2 = KrSafMirror.mirrorKey("content://tree/A", "/storage/A/Game")
        assertEquals(key1, key2)
        assertEquals(16, key1.length)
        assertTrue(key1 != KrSafMirror.mirrorKey("content://tree/B", "/storage/A/Game"))
        assertTrue(key1 != KrSafMirror.mirrorKey("content://tree/A", "/storage/A/Game2"))
    }

    @Test
    fun encodedDocumentIdsSurviveRoundTrip() {
        // 索引里的 Uri 含 %XX 编码的 documentId：解析层不得解码破坏
        val encoded = "content://com.android.externalstorage.documents/tree/AAE6-D7FF%3A%E6%98%9F/document/AAE6-D7FF%3A%E6%98%9F%2Fdata.xp3"
        val index = KrSafMirror.parseIndexLines(v2(v2Line("/x/data.xp3", encoded, 100L))) { it }
        assertEquals(encoded, index["/x/data.xp3"]?.uriString)
    }

    @Test
    fun realIndexFileRoundTripsThroughDisk() {
        // 端到端：临时文件写入 v2 索引（与 writeIndex 相同格式）→ loadIndex 路径
        // 不能在 JVM 调用（Uri stub），此处验证 encode/文件读写序列化形态一致
        val dir = File(System.getProperty("java.io.tmpdir"))
        val file = File.createTempFile("krkr_idx_test", ".idx", dir)
        try {
            file.bufferedWriter().use { writer ->
                writer.appendLine("krkr-saf-index-v2")
                writer.appendLine(v2Line("/a/b.xp3", "content://t/d", 42L))
            }
            val text = file.readText()
            val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
            val index = KrSafMirror.parseIndexLines(lines) { it }
            assertEquals(42L, index["/a/b.xp3"]?.size)
        } finally {
            file.delete()
        }
    }
}
