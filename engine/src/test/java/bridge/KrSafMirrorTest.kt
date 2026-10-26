package bridge

import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pure-JVM tests for KrSafMirror preparation policies and index/path conversion (no SAF/Context/Android
 * stubs). Covers the #129 index v2 size column, v1 compatibility, document-id conversion
 * and the write→parse round trip via [KrSafMirror.writeIndexText]/[KrSafMirror.parseIndexLines]
 * (uri construction is injected as identity to bypass the android.net.Uri JVM stub).
 */
class KrSafMirrorTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun v2(vararg lines: String): List<String> =
        listOf("krkr-saf-index-v2") + lines.toList()

    private fun v1(vararg lines: String): List<String> =
        listOf("krkr-saf-index-v1") + lines.toList()

    private fun v2Line(path: String, uri: String, size: Long): String =
        buildString { KrSafMirror.writeIndexText(this, listOf(Triple(path, uri, size))) }.lineSequence().drop(1).first()

    private fun v1Line(path: String, uri: String): String =
        v2Line(path, uri, 0L).substringBeforeLast('\t')

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
        val line = v2Line(path, "content://a/b", 0L).substringBeforeLast('\t') + "\tnot-a-number"
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
        val blankPath = v2Line("", "content://a/b", 5L)
        val blankUri = v2Line("/x/f", "", 5L)
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
    fun writeIndexTextRoundTripsThroughParser() {
        // Real write→parse round trip: writeIndexText (the writeIndex serializer) feeds
        // parseIndexLines (the loadIndex parser), locking the on-disk format from both sides
        val entries = listOf(
            Triple("/a/b.xp3", "content://t/d", 42L),
            Triple("/plugin/gamepad.dll", "content://t/e", 241664L),
            Triple("/全cg存档/datasu.ksd", "content://t/f", -1L),
        )
        val text = buildString { KrSafMirror.writeIndexText(this, entries) }
        val index = KrSafMirror.parseIndexLines(text.lineSequence().filter { it.isNotBlank() }.toList()) { it }
        assertEquals(3, index.size)
        assertEquals(42L, index["/a/b.xp3"]?.size)
        assertEquals("content://t/e", index["/plugin/gamepad.dll"]?.uri)
        assertEquals(-1L, index["/全cg存档/datasu.ksd"]?.size)
    }

    @Test
    fun writeIndexTextStartsWithV2Header() {
        val text = buildString { KrSafMirror.writeIndexText(this, emptyList()) }
        assertEquals("krkr-saf-index-v2", text.lineSequence().first())
    }

    @Test
    fun corruptRowsDoNotDiscardValidSiblingResources() {
        val index = KrSafMirror.parseIndexLines(v2(
            v2Line("/x/first", "content://a/first", 10L),
            "%%%\tY29udGVudDovL2EvYg\t1",
            "L3g\t%%%\t1",
            v2Line("/x/last", "content://a/last", 20L),
        )) { it }
        assertEquals(setOf("/x/first", "/x/last"), index.keys)
    }

    @Test
    fun productionSerializerRetainsTheLiteralV2WireFormat() {
        assertEquals("krkr-saf-index-v2\nL3g\tY29udGVudDovL2EvYg\t41\n",
            buildString { KrSafMirror.writeIndexText(this, listOf(Triple("/x", "content://a/b", 41L))) })
    }

    @Test
    fun productionFileReaderAndStreamingWriterRoundTrip() {
        val file = temporary.newFile("index.idx")
        file.bufferedWriter().use { writer ->
            KrSafMirror.writeIndexText(writer, listOf(Triple("/X/data.xp3", "content://a/b", 123L)))
        }
        val index = KrSafMirror.readIndexFile(file) { it }
        assertEquals(123L, index["/x/data.xp3"]?.size)
        assertEquals("content://a/b", index["/x/data.xp3"]?.uri)
        assertTrue(KrSafMirror.readIndexFile(File(temporary.root, "missing")) { it }.isEmpty())
    }

    @Test
    fun savedataExclusionMatchesOnlyTheSourceRootSubtree() {
        assertTrue(KrSafMirror.isSourceSavedata("savedata"))
        assertTrue(KrSafMirror.isSourceSavedata("SaveData/slot/data.ksd"))
        assertFalse(KrSafMirror.isSourceSavedata("savedatabase/data.xp3"))
        assertFalse(KrSafMirror.isSourceSavedata("data/savedata/archive.xp3"))
    }

    @Test
    fun newMirrorIdentityDoesNotDependOnTheGameTitle() {
        val key = KrSafMirror.mirrorKey("content://a/game", "/storage/VOL/game")
        val games = temporary.newFolder("games")
        assertEquals(File(games, "game-$key"), KrSafMirror.findMirrorRoot(games, key))
    }

    @Test
    fun legacyMirrorWithSavesIsReusedWithoutMovingOrDeletingData() {
        val games = temporary.newFolder("games")
        val key = "0123456789abcdef"
        val empty = File(games, "aaa-$key").apply { mkdirs() }
        val legacy = File(games, "old-title-$key").apply { mkdirs() }
        val save = File(legacy, "savedata/slot.ksd").apply { requireNotNull(parentFile).mkdirs(); writeText("save") }
        assertEquals(legacy, KrSafMirror.findMirrorRoot(games, key))
        assertTrue(empty.isDirectory)
        assertEquals("save", save.readText())
        assertFalse(File(games, "game-$key").exists())
    }

    @Test
    fun staleSaveCleanupOnlyDeletesIndexedEmptyPlaceholders() {
        val root = temporary.newFolder("mirror")
        val saveRoot = File(root, "savedata").apply { mkdirs() }
        val stale = File(saveRoot, "stale.ksd").apply { createNewFile() }
        val userEmpty = File(saveRoot, "user-empty.ksd").apply { createNewFile() }
        val userSave = File(saveRoot, "slot.ksd").apply { writeText("save") }
        val current = File(saveRoot, "current.ksd").apply { createNewFile() }
        val outside = temporary.newFile("outside.ksd")
        val previous = setOf(stale.path, userSave.path, current.path, outside.path)
        assertEquals(1, KrSafMirror.removeStaleSavePlaceholders(root, previous, setOf(current.path)))
        assertFalse(stale.exists())
        assertTrue(userEmpty.exists())
        assertTrue(current.exists())
        assertTrue(outside.exists())
        assertEquals("save", userSave.readText())
    }


    @Test
    fun populatedLegacySavesBeatAnExistingEmptyStableRoot() {
        val games = temporary.newFolder("games")
        val key = "0123456789abcdef"
        val stable = File(games, "game-$key").apply { mkdirs() }
        val legacy = File(games, "legacy-$key").apply { mkdirs() }
        File(legacy, "savedata/slot.ksd").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("save")
        }
        assertEquals(legacy, KrSafMirror.findMirrorRoot(games, key))
        assertTrue(stable.isDirectory)
        File(stable, "savedata/current.ksd").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("current")
        }
        assertEquals(stable, KrSafMirror.findMirrorRoot(games, key))
        assertEquals("save", File(legacy, "savedata/slot.ksd").readText())
    }


    @Test
    fun childDocumentIdsDoNotInsertASlashAfterTheVolumeColon() {
        assertEquals("VOL:patch.tjs", KrSafMirror.childDocumentId("VOL:", "patch.tjs"))
        assertEquals("primary:ds.ini", KrSafMirror.childDocumentId("primary:", "ds.ini"))
        assertEquals("VOL:game/patch.tjs", KrSafMirror.childDocumentId("VOL:game", "patch.tjs"))
        assertEquals("VOL:game/patch.tjs", KrSafMirror.childDocumentId("VOL:game/", "patch.tjs"))
    }

    @Test
    fun scanCallbacksHaveABoundedWaitAndPropagateInterruption() {
        assertTrue(KrSafMirror.awaitScanCallbacks(CountDownLatch(0), 0L))
        assertFalse(KrSafMirror.awaitScanCallbacks(CountDownLatch(1), 0L))
        try {
            Thread.currentThread().interrupt()
            try {
                KrSafMirror.awaitScanCallbacks(CountDownLatch(1), 0L)
                fail("Interrupted scan must cancel preparation")
            } catch (_: CancellationException) {
                assertTrue(Thread.currentThread().isInterrupted)
            }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun sameGamePreparationHasASingleWriter() {
        val directory = temporary.newFolder("index-locks")
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        val first = Thread {
            try {
                KrSafMirror.withPreparationLock(directory, "same-game") {
                    firstEntered.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS))
                }
            } catch (failure: Throwable) {
                error.set(failure)
            }
        }
        val second = Thread {
            try {
                secondStarted.countDown()
                KrSafMirror.withPreparationLock(directory, "same-game") { secondEntered.countDown() }
            } catch (failure: Throwable) {
                error.set(failure)
            }
        }
        try {
            first.start()
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            second.start()
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseFirst.countDown()
            first.join(5000)
            second.join(5000)
        }
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertNull(error.get())
        assertEquals(0L, secondEntered.count)
    }

    @Test
    fun staleSaveCleanupPreservesMarkedEmptyLocalEdits() {
        val root = temporary.newFolder("marked-mirror")
        val file = File(root, "savedata/empty.ksd").apply {
            requireNotNull(parentFile).mkdirs()
            createNewFile()
        }
        assertEquals(0, KrSafMirror.removeStaleSavePlaceholders(root, setOf(file.path), emptySet()) {
            it == file
        })
        assertTrue(file.exists())
    }


    @Test
    fun internalMirrorStateIsNeverImportedFromTheGameSource() {
        assertTrue(KrSafMirror.isSourceEntryExcluded(".tyranor-mirror-state"))
        assertTrue(KrSafMirror.isSourceEntryExcluded(".TYRANOR-MIRROR-STATE/local-overrides/hash"))
        assertTrue(KrSafMirror.isSourceEntryExcluded("savedata/slot.ksd"))
        assertFalse(KrSafMirror.isSourceEntryExcluded("data/.tyranor-mirror-state/source"))
        assertFalse(KrSafMirror.isSourceEntryExcluded(".tyranor-mirror-state.txt"))
    }

    @Test
    fun volumeRootGrantsKeepTheFirstChildNameIntact() {
        assertEquals("game/plugins", KrSafMirror.relativeDocumentPath("VOL:game/plugins", "VOL:"))
        assertEquals("", KrSafMirror.relativeDocumentPath("VOL:", "vol:"))
        assertEquals("plugins", KrSafMirror.relativeDocumentPath("VOL:game/plugins", "VOL:game"))
        assertEquals("plugins", KrSafMirror.relativeDocumentPath("VOL:game/plugins", "VOL:game/"))
        assertNull(KrSafMirror.relativeDocumentPath("VOL:game-other", "VOL:game"))
        assertNull(KrSafMirror.relativeDocumentPath("OTHER:game", "VOL:"))
    }

    @Test
    fun stalePlaceholderCleanupResolvesTheActualLegacyRootCase() {
        val root = temporary.newFolder("OldTitle-0123456789abcdef")
        val stale = File(root, "savedata/stale.ksd").apply {
            requireNotNull(parentFile).mkdirs()
            createNewFile()
        }
        val local = File(root, "savedata/local.ksd").apply { createNewFile() }
        val state = KrMirrorLocalFiles(root)
        state.markOverridden(local)
        val keys = setOf(stale.path.lowercase(Locale.ROOT), local.path.lowercase(Locale.ROOT),
            root.path.lowercase(Locale.ROOT) + "/../outside")
        assertEquals(1, KrSafMirror.removeStaleSavePlaceholders(root, keys, emptySet(), state::isOverridden))
        assertFalse(stale.exists())
        assertTrue(local.exists())
    }

    @Test
    fun missingBootstrapRequiresBothAnAbsentDocumentAndACompleteRootListing() {
        assertEquals(KrSafMirror.BootstrapFileState.ABSENT,
            KrSafMirror.bootstrapProbeFailureState("patch.tjs", true, true, setOf("data.xp3")))
        assertEquals(KrSafMirror.BootstrapFileState.UNAVAILABLE,
            KrSafMirror.bootstrapProbeFailureState("patch.tjs", true, false, emptySet()))
        assertEquals(KrSafMirror.BootstrapFileState.UNAVAILABLE,
            KrSafMirror.bootstrapProbeFailureState("patch.tjs", false, true, emptySet()))
        assertEquals(KrSafMirror.BootstrapFileState.UNAVAILABLE,
            KrSafMirror.bootstrapProbeFailureState("patch.tjs", true, true, setOf("patch.tjs")))
        assertEquals(KrSafMirror.BootstrapFileState.ABSENT,
            KrSafMirror.bootstrapProbeFailureState("PATCH.TJS", true, true, setOf("data.xp3")))
    }

    @Test
    fun mirrorSourceDecisionCoversAllCompletenessStates() {
        // Fresh listing, no failures: full rebuild.
        assertEquals(KrSafMirror.MirrorSourceDecision.REBUILD_FRESH,
            KrSafMirror.decideMirrorSources(observed = 10, previousCount = 5, queryFailures = 0, localOverrideCount = 0))
        // Partial listing failure: rebuild but carry previous rows.
        assertEquals(KrSafMirror.MirrorSourceDecision.REBUILD_MERGED,
            KrSafMirror.decideMirrorSources(observed = 3, previousCount = 10, queryFailures = 2, localOverrideCount = 1))
        // Total listing failure with a usable previous index: keep it and launch anyway.
        assertEquals(KrSafMirror.MirrorSourceDecision.KEEP_EXISTING,
            KrSafMirror.decideMirrorSources(observed = 0, previousCount = 10, queryFailures = 3, localOverrideCount = 0))
        assertEquals(KrSafMirror.MirrorSourceDecision.KEEP_EXISTING,
            KrSafMirror.decideMirrorSources(observed = 0, previousCount = 0, queryFailures = 1, localOverrideCount = 2))
        // Nothing observed and nothing to fall back on: typed failure (EMPTY_MIRROR).
        assertEquals(KrSafMirror.MirrorSourceDecision.FAIL_EMPTY,
            KrSafMirror.decideMirrorSources(observed = 0, previousCount = 0, queryFailures = 1, localOverrideCount = 0))
    }

    @Test
    fun mirrorSourceDecisionTreatsLocalOverridesAsNonObservations() {
        // Local copies alone (0 observed source entries) do not satisfy the freshness
        // requirement; the decision still consults previous index availability.
        assertEquals(KrSafMirror.MirrorSourceDecision.KEEP_EXISTING,
            KrSafMirror.decideMirrorSources(observed = 0, previousCount = 0, queryFailures = 0, localOverrideCount = 3))
    }

    @Test
    fun symlinkMirrorRootRetainsIndexedSourcesAndCleansOnlyOldPlaceholders() {
        val realRoot = temporary.newFolder("real-OldTitle")
        val alias = File(temporary.root, "alias-OldTitle")
        try {
            Files.createSymbolicLink(alias.toPath(), realRoot.toPath())
        } catch (error: Exception) {
            assumeNoException("Symbolic links require platform support", error)
            return
        }
        val asset = File(alias, "plugin.dll").apply { createNewFile() }
        val stale = File(alias, "savedata/stale.ksd").apply {
            requireNotNull(parentFile).mkdirs()
            createNewFile()
        }
        val local = File(alias, "savedata/local.ksd").apply { createNewFile() }
        val helper = KrMirrorLocalFiles(alias)
        helper.markOverridden(local)
        val key = asset.absolutePath.lowercase(Locale.ROOT)
        val index = File(temporary.root, "alias.idx")
        index.writeText(buildString { KrSafMirror.writeIndexText(this, listOf(Triple(key, "content://source/plugin", 4L))) })
        val loaded = KrSafMirror.retainSourceEntries(KrSafMirror.readIndexFile(index) { it },
            helper, helper.snapshotOverrideKeys())
        assertEquals("content://source/plugin", loaded[key]?.uri)
        assertEquals(asset.canonicalFile, helper.fileForIndexKey(key)?.canonicalFile)
        assertEquals(1, KrSafMirror.removeStaleSavePlaceholders(alias,
            setOf(stale.path.lowercase(Locale.ROOT), local.path.lowercase(Locale.ROOT)), emptySet(), helper::isOverridden))
        assertFalse(stale.exists())
        assertTrue(local.exists())
        assertTrue(asset.exists())
    }

    @Test
    fun legacyEmptyOverrideStillWinsWhenFilteringAnExistingIndex() {
        val root = temporary.newFolder("legacy-marker")
        val local = File(root, "patch.tjs").apply { createNewFile() }
        val helper = KrMirrorLocalFiles(root)
        helper.markOverridden(local)
        helper.ownershipMarker(local).writeBytes(byteArrayOf(1))
        val key = local.absolutePath.lowercase(Locale.ROOT)
        val entries = mapOf(key to KrSafMirror.MirrorEntryHolder("content://source/patch", 123L))
        assertTrue(KrSafMirror.retainSourceEntries(entries, helper, helper.snapshotOverrideKeys(entries.keys)).isEmpty())
        assertEquals(0L, local.length())
    }

    @Test
    fun listedBootstrapPlaceholderKeepsRealSourceUriAndSize() {
        val root = temporary.newFolder("listed-bootstrap")
        for (name in KrSafMirror.BOOTSTRAP_FILE_NAMES) {
            val local = File(root, name).apply { createNewFile() }
            val source = "content://source/$name"
            val payload = requireNotNull(KrSafMirror.bootstrapFromListedEntry(local, source, 123L, false))
            assertEquals(KrSafMirror.BootstrapFileState.PRESENT, payload.state)
            assertEquals(source, payload.uri)
            assertEquals(123L, payload.size)
            assertFalse(payload.localOverride)
            assertEquals(0L, local.length())
        }
    }

    @Test
    fun onlyActualLocalBootstrapContentsOrAnExistingMarkedFileOverrideTheSource() {
        val root = temporary.newFolder("local-bootstrap")
        val local = File(root, "patch.tjs").apply { writeText("local patch") }
        assertTrue(requireNotNull(KrSafMirror.bootstrapFromListedEntry(local, "content://source/patch", 50L, false)).localOverride)
        local.writeBytes(byteArrayOf())
        assertTrue(requireNotNull(KrSafMirror.bootstrapFromListedEntry(local, "content://source/patch", 50L, true)).localOverride)
        assertFalse(requireNotNull(KrSafMirror.bootstrapFromListedEntry(local, "content://source/patch", 0L, false)).localOverride)
        assertNull(KrSafMirror.bootstrapFromListedEntry(local, null, -1L, false))
        assertTrue(local.delete())
        assertNull(KrSafMirror.bootstrapFromListedEntry(local, null, -1L, true))
    }

    @Test
    fun failedSourceWithAnEmptyPreviousIndexKeepsARealMarkedStartupCopy() {
        val root = temporary.newFolder("local-startup-fallback")
        val startup = File(root, "startup.tjs").apply { writeText("local startup contents") }
        KrMirrorLocalFiles(root).markOverridden(startup)
        val index = File(temporary.root, "empty-previous.idx").apply { writeText("krkr-saf-index-v2\n") }
        val previous = KrSafMirror.readIndexFile(index) { it }
        assertTrue(previous.isEmpty())

        val sources = KrSafMirror.assemblePreparationSources(root, previous.keys, emptyList(),
            observed = 0, queryFailures = 3)

        assertEquals(KrSafMirror.MirrorSourceDecision.KEEP_EXISTING, sources.decision)
        assertTrue(startup.absolutePath.lowercase(Locale.ROOT) in sources.localOverrides)
        assertEquals("local startup contents", startup.readText())
    }

    @Test
    fun absentMarkedResourcesAndSaveOnlyStateDoNotPretendTheMirrorIsUsable() {
        val root = temporary.newFolder("stale-local-fallback")
        val startup = File(root, "startup.tjs").apply { writeText("local startup") }
        val saves = File(root, "savedata/slot.ksd").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("save")
        }
        val state = KrMirrorLocalFiles(root)
        state.markOverridden(startup)
        state.markOverridden(saves)
        assertTrue(startup.delete())

        val sources = KrSafMirror.assemblePreparationSources(root, emptySet(), emptyList(),
            observed = 0, queryFailures = 2)

        assertEquals(KrSafMirror.MirrorSourceDecision.FAIL_EMPTY, sources.decision)
        assertTrue(sources.localOverrides.isEmpty())
        assertEquals("save", saves.readText())
    }

    @Test
    fun failedSourceWithHeaderOnlyIndexAlsoRecognizesLegacyStartupMarkers() {
        val root = temporary.newFolder("legacy-startup-fallback")
        val startup = File(root, "startup.tjs").apply { writeText("legacy startup contents") }
        val helper = KrMirrorLocalFiles(root)
        helper.markOverridden(startup)
        helper.ownershipMarker(startup).writeBytes(byteArrayOf(1))
        val emptyIndex = File(temporary.root, "legacy-empty.idx").apply { writeText("krkr-saf-index-v2\n") }
        val previous = KrSafMirror.readIndexFile(emptyIndex) { it }

        val sources = KrSafMirror.assemblePreparationSources(root, previous.keys, emptyList(),
            observed = 0, queryFailures = 2)

        assertEquals(KrSafMirror.MirrorSourceDecision.KEEP_EXISTING, sources.decision)
        assertTrue(startup.absolutePath.lowercase(Locale.ROOT) in sources.localOverrides)
        assertEquals("legacy startup contents", startup.readText())
    }

}
