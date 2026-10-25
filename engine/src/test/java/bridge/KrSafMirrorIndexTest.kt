package bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the KrSafMirror index and path conversion (no SAF/Context/Android
 * stubs). Covers the #129 index v2 size column, v1 compatibility, document-id conversion
 * and the write→parse round trip via [KrSafMirror.buildIndexText]/[KrSafMirror.parseIndexLines]
 * (uri construction is injected as identity to bypass the android.net.Uri JVM stub).
 */
class KrSafMirrorIndexTest {
    /** Matches the internal encoding: URL-safe alphabet, no padding (pure JVM Base64). */
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
        assertEquals(uri, entry?.uri)
        assertEquals(241664L, entry?.size)
    }

    @Test
    fun v2IndexToleratesMalformedSizeColumn() {
        val path = "/x/data.xp3"
        val line = "${encode(path)}\t${encode("content://a/b")}\tnot-a-number"
        val index = KrSafMirror.parseIndexLines(v2(line)) { it }
        // Malformed size falls back to -1 (stat hook does not override); entry survives
        assertEquals(-1L, index[path]?.size)
    }

    @Test
    fun v1IndexLoadsWithoutSizes() {
        val index = KrSafMirror.parseIndexLines(v1(v1Line("/x/data.xp3", "content://a/b"))) { it }
        assertEquals("content://a/b", index["/x/data.xp3"]?.uri)
        // v1 has no size column: -1, the stat hook does not override
        assertEquals(-1L, index["/x/data.xp3"]?.size)
    }

    @Test
    fun unknownIndexVersionIsRejected() {
        val index = KrSafMirror.parseIndexLines(listOf("krkr-saf-index-v9", "junk\tjunk")) { it }
        assertTrue(index.isEmpty())
    }

    @Test
    fun emptyInputYieldsEmptyMap() {
        assertTrue(KrSafMirror.parseIndexLines(emptyList<String>()) { it }.isEmpty())
    }

    @Test
    fun pathsAreLowercasedOnLoad() {
        val index = KrSafMirror.parseIndexLines(v2(v2Line("/X/Data.XP3", "content://a/b", 1L))) { it }
        // Index keys are lowercased to match engine-lowercased game paths
        assertEquals(1, index.size)
        assertEquals("content://a/b", index["/x/data.xp3"]?.uri)
    }

    @Test
    fun blankPathsAndUrisAreSkipped() {
        val blankPath = "\t${encode("content://a/b")}\t5" // empty first column → split<=0 → skipped
        val blankUri = "${encode("/x/f")}\t\t5" // empty uri
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
    fun stripSigSuffixIsCaseInsensitiveAndConservative() {
        // Document ids keep the provider case: an uppercase .SIG must be stripped too,
        // otherwise the signature document is indexed as the DLL itself
        assertEquals("/x/Foo.DLL", KrSafMirror.stripSigSuffix("/x/Foo.DLL.SIG"))
        assertEquals("/x/Foo.dll", KrSafMirror.stripSigSuffix("/x/Foo.dll.sig"))
        assertEquals("/x/Foo.Dll", KrSafMirror.stripSigSuffix("/x/Foo.Dll.Sig"))
        assertEquals("/x/Foo.dll", KrSafMirror.stripSigSuffix("/x/Foo.dll"))
        assertEquals("", KrSafMirror.stripSigSuffix(".sig"))
        assertEquals("sig", KrSafMirror.stripSigSuffix("sig"))
        assertEquals("", KrSafMirror.stripSigSuffix(""))
    }

    @Test
    fun encodedDocumentIdsSurviveRoundTrip() {
        // Index uris carry %XX-encoded document ids: parsing must not decode them
        val encoded = "content://com.android.externalstorage.documents/tree/AAE6-D7FF%3A%E6%98%9F/document/AAE6-D7FF%3A%E6%98%9F%2Fdata.xp3"
        val index = KrSafMirror.parseIndexLines(v2(v2Line("/x/data.xp3", encoded, 100L))) { it }
        assertEquals(encoded, index["/x/data.xp3"]?.uri)
    }

    @Test
    fun buildIndexTextRoundTripsThroughParser() {
        // Real write→parse round trip: buildIndexText (the writeIndex serializer) feeds
        // parseIndexLines (the loadIndex parser), locking the on-disk format from both sides
        val entries = listOf(
            Triple("/a/b.xp3", "content://t/d", 42L),
            Triple("/plugin/gamepad.dll", "content://t/e", 241664L),
            Triple("/全cg存档/datasu.ksd", "content://t/f", -1L),
        )
        val text = KrSafMirror.buildIndexText(entries)
        val index = KrSafMirror.parseIndexLines(text.lineSequence().filter { it.isNotBlank() }.toList()) { it }
        assertEquals(3, index.size)
        assertEquals(42L, index["/a/b.xp3"]?.size)
        assertEquals("content://t/e", index["/plugin/gamepad.dll"]?.uri)
        assertEquals(-1L, index["/全cg存档/datasu.ksd"]?.size)
    }

    @Test
    fun buildIndexTextStartsWithV2Header() {
        val text = KrSafMirror.buildIndexText(emptyList())
        assertEquals("krkr-saf-index-v2", text.lineSequence().first())
    }
}
