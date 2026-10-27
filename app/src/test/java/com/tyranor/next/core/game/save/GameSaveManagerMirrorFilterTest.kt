package com.tyranor.next.core.game.save

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameSaveManagerMirrorFilterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun mirrorForcedListAndExportExcludeNestedEmptyPlaceholders() {
        val root = saveTree()
        assertListAndExport(root, mirrorForced = true, expected = setOf("slot/data.sav"))
    }

    @Test
    fun ordinaryListAndExportKeepLegitimateEmptyFiles() {
        val root = saveTree()
        assertListAndExport(root, mirrorForced = false, expected = setOf("empty.marker", "slot/empty.sav", "slot/data.sav"))
    }

    @Test
    fun excludedDirectoriesRemainExcludedInBothPaths() {
        val root = saveTree()
        root.resolve("ignored").mkdirs()
        root.resolve("ignored/real.sav").writeText("hidden")
        val exclude: (String) -> Boolean = { it == "ignored" }
        assertListAndExport(root, mirrorForced = false, expected = setOf("empty.marker", "slot/empty.sav", "slot/data.sav"), exclude = exclude)
    }

    @Test
    fun mirrorForcedImportCountMatchesListedAndExportableFiles() {
        val source = saveTree()
        val staging = temporaryFolder.newFolder()
        val copied = GameSaveManager.stageImportContents(source, staging, { false }, mirrorForced = true)
        assertEquals(1, copied)
        assertListAndExport(staging, mirrorForced = true, expected = setOf("slot/data.sav"))
        assertFalse(staging.resolve("empty.marker").exists())
        assertFalse(staging.resolve("slot/empty.sav").exists())
    }

    @Test
    fun ordinaryImportRetainsAndCountsEmptyFiles() {
        val source = saveTree()
        val staging = temporaryFolder.newFolder()
        val copied = GameSaveManager.stageImportContents(source, staging, { false }, mirrorForced = false)
        assertEquals(3, copied)
        assertListAndExport(staging, mirrorForced = false, expected = setOf("empty.marker", "slot/empty.sav", "slot/data.sav"))
    }

    @Test
    fun ordinaryEmptyOnlyImportRemainsValid() {
        val source = temporaryFolder.newFolder()
        source.resolve("empty.marker").createNewFile()
        val staging = temporaryFolder.newFolder()
        assertEquals(1, GameSaveManager.stageImportContents(source, staging, { false }, mirrorForced = false))
        assertTrue(staging.resolve("empty.marker").isFile)
        assertEquals(0L, staging.resolve("empty.marker").length())
    }

    @Test
    fun emptyOnlyMirrorImportIsRejectedBeforeLiveSaveExchange() {
        val source = temporaryFolder.newFolder()
        source.resolve("empty.sav").createNewFile()
        val live = liveSaves()
        val renames = mutableListOf<Pair<File, File>>()
        expectImportError(SaveErrorCode.NO_FILES_IN_ZIP) {
            GameSaveManager.importSaveDirectory(live, { false }, mirrorForced = true, rename = { from, to ->
                renames += from to to
                from.renameTo(to)
            }) { source }
        }
        assertEquals("existing save", live.resolve("slot.sav").readText())
        assertTrue(renames.isEmpty())
        assertTransactionClean(live)
    }

    @Test
    fun mirrorImportStagesFiltersAndExchangesTheLiveDirectory() {
        val source = saveTree()
        val live = liveSaves()
        var stagedBeforeExchange = false
        val copied = GameSaveManager.importSaveDirectory(live, { false }, mirrorForced = true, rename = { from, to ->
            if (from == live) {
                val staging = transactionPath(live, "staging")
                assertEquals("existing save", live.resolve("slot.sav").readText())
                assertEquals("save contents", staging.resolve("slot/data.sav").readText())
                assertFalse(staging.resolve("empty.marker").exists())
                stagedBeforeExchange = true
            }
            from.renameTo(to)
        }) { source }
        assertTrue(stagedBeforeExchange)
        assertEquals(1, copied)
        assertFalse(live.resolve("slot.sav").exists())
        assertListAndExport(live, mirrorForced = true, expected = setOf("slot/data.sav"))
        assertTransactionClean(live)
    }

    @Test
    fun failedStagingExchangeRollsBackTheLiveSaves() {
        val source = saveTree()
        val live = liveSaves()
        val staging = transactionPath(live, "staging")
        expectImportError(SaveErrorCode.SAVE_DIR_UNAVAILABLE) {
            GameSaveManager.importSaveDirectory(live, { false }, mirrorForced = true, rename = { from, to ->
                if (from == staging && to == live) false else from.renameTo(to)
            }) { source }
        }
        assertEquals("existing save", live.resolve("slot.sav").readText())
        assertFalse(live.resolve("slot/data.sav").exists())
        assertTransactionClean(live)
    }

    @Test
    fun failedRollbackRetainsBackupAndNextImportRecoversItBeforeReadingSource() {
        val source = saveTree()
        val live = liveSaves()
        val staging = transactionPath(live, "staging")
        val backup = transactionPath(live, "backup")
        expectImportError(SaveErrorCode.SAVE_DIR_UNAVAILABLE) {
            GameSaveManager.importSaveDirectory(live, { false }, mirrorForced = true, rename = { from, to ->
                if ((from == staging || from == backup) && to == live) false else from.renameTo(to)
            }) { source }
        }
        assertFalse(live.exists())
        assertEquals("existing save", backup.resolve("slot.sav").readText())
        try {
            GameSaveManager.importSaveDirectory(live, { false }, mirrorForced = true) {
                assertEquals("existing save", live.resolve("slot.sav").readText())
                assertFalse(backup.exists())
                throw IOException("Source unavailable")
            }
            fail("Source failure must be propagated")
        } catch (error: IOException) {
            assertEquals("Source unavailable", error.message)
        }
        assertEquals("existing save", live.resolve("slot.sav").readText())
        assertTransactionClean(live)
    }

    @Test
    fun resourceRestoreFailureRetainsTheCompleteBackupAfterExchange() {
        val source = saveTree()
        val live = liveSaves()
        live.resolve("system.pfs").writeText("engine resource")
        try {
            GameSaveManager.importSaveDirectory(live, { it.endsWith(".pfs") }, mirrorForced = false,
                restoreExcluded = { _, _ -> throw IOException("Resource restore failed") }) { source }
            fail("Resource restoration failure must be propagated")
        } catch (error: IOException) {
            assertEquals("Resource restore failed", error.message)
        }
        val backup = transactionPath(live, "backup")
        assertEquals("existing save", backup.resolve("slot.sav").readText())
        assertEquals("engine resource", backup.resolve("system.pfs").readText())
        assertEquals("save contents", live.resolve("slot/data.sav").readText())
        assertFalse(transactionPath(live, "staging").exists())
    }

    @Test
    fun ordinaryEmptyOnlyImportExchangesAndCountsTheLiveDirectory() {
        val source = temporaryFolder.newFolder()
        source.resolve("empty.marker").createNewFile()
        val live = liveSaves()
        assertEquals(1, GameSaveManager.importSaveDirectory(live, { false }, mirrorForced = false) { source })
        assertTrue(live.resolve("empty.marker").isFile)
        assertEquals(0L, live.resolve("empty.marker").length())
        assertFalse(live.resolve("slot.sav").exists())
        assertTransactionClean(live)
    }

    @Test
    fun recreatedEmptyDestinationRecoversBackupBeforeAFilteredEmptyImport() {
        val live = liveSaves()
        val backup = transactionPath(live, "backup")
        assertTrue(live.renameTo(backup))
        assertTrue(live.mkdir())
        val emptyImport = temporaryFolder.newFolder()
        emptyImport.resolve("empty.ksd").createNewFile()
        expectImportError(SaveErrorCode.NO_FILES_IN_ZIP) {
            GameSaveManager.importSaveDirectory(live, { false }, mirrorForced = true) {
                assertEquals("existing save", live.resolve("slot.sav").readText())
                assertFalse(backup.exists())
                emptyImport
            }
        }
        assertEquals("existing save", live.resolve("slot.sav").readText())
        assertTransactionClean(live)
    }

    @Test
    fun recreatedEmptyDestinationRecoversBackupBeforeInvalidZipParsing() {
        val live = liveSaves()
        val backup = transactionPath(live, "backup")
        assertTrue(live.renameTo(backup))
        assertTrue(live.mkdir())
        val extracted = temporaryFolder.newFolder()
        expectImportError(SaveErrorCode.NO_FILES_IN_ZIP) {
            GameSaveManager.importSaveDirectory(live, { false }, mirrorForced = true) {
                assertEquals("existing save", live.resolve("slot.sav").readText())
                val count = GameSaveManager.extractZip(ByteArrayInputStream("not a zip".toByteArray()), extracted)
                if (count == 0) throw GameSaveException(SaveErrorCode.NO_FILES_IN_ZIP)
                extracted
            }
        }
        assertEquals("existing save", live.resolve("slot.sav").readText())
        assertTransactionClean(live)
    }

    @Test
    fun recreatedEmptyDestinationKeepsBackupWhenRecoveryRenameFails() {
        val live = liveSaves()
        val backup = transactionPath(live, "backup")
        assertTrue(live.renameTo(backup))
        assertTrue(live.mkdir())
        expectImportError(SaveErrorCode.SAVE_DIR_UNAVAILABLE) {
            GameSaveManager.importSaveDirectory(live, { false }, mirrorForced = true,
                rename = { from, to -> if (from == backup && to == live) false else from.renameTo(to) }) {
                error("Do not inspect the new import before backup recovery succeeds")
            }
        }
        assertEquals("existing save", backup.resolve("slot.sav").readText())
        assertFalse(transactionPath(live, "staging").exists())
    }

    @Test
    fun recreatedEmptyDestinationCanCompleteANewImportAfterRecoveringBackup() {
        val live = liveSaves()
        val backup = transactionPath(live, "backup")
        assertTrue(live.renameTo(backup))
        assertTrue(live.mkdir())
        val source = saveTree()
        assertEquals(1, GameSaveManager.importSaveDirectory(live, { false }, mirrorForced = true) {
            assertEquals("existing save", live.resolve("slot.sav").readText())
            source
        })
        assertEquals("save contents", live.resolve("slot/data.sav").readText())
        assertTransactionClean(live)
    }

    private fun liveSaves(): File = temporaryFolder.newFolder().apply {
        resolve("slot.sav").writeText("existing save")
    }

    private fun transactionPath(live: File, kind: String): File =
        File(live.parentFile, live.name + ".import_" + kind)

    private fun assertTransactionClean(live: File) {
        assertFalse(transactionPath(live, "staging").exists())
        assertFalse(transactionPath(live, "backup").exists())
    }

    private fun expectImportError(code: SaveErrorCode, action: () -> Unit) {
        try {
            action()
            fail("Expected import failure")
        } catch (error: GameSaveException) {
            assertEquals(code, error.code)
        }
    }

    private fun saveTree(): File = temporaryFolder.newFolder().apply {
        resolve("empty.marker").createNewFile()
        resolve("slot").mkdirs()
        resolve("slot/empty.sav").createNewFile()
        resolve("slot/data.sav").writeText("save contents")
    }

    private fun assertListAndExport(
        root: File,
        mirrorForced: Boolean,
        expected: Set<String>,
        exclude: (String) -> Boolean = { false },
    ) {
        val listed = mutableListOf<File>()
        GameSaveManager.collectFiles(root, listed, exclude, mirrorForced)
        assertEquals(expected, listed.map { it.relativeTo(root).invariantSeparatorsPath }.toSet())
        val output = ByteArrayOutputStream()
        val count = ZipOutputStream(output).use { zip ->
            GameSaveManager.writeZipContents(root, root, zip, mutableSetOf(), exclude, mirrorForced = mirrorForced)
        }
        assertEquals(expected.size, count)
        val exported = mutableMapOf<String, ByteArray>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                exported[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        assertEquals(expected, exported.keys)
        assertEquals("save contents", exported.getValue("slot/data.sav").toString(Charsets.UTF_8))
        if (!mirrorForced) assertTrue(exported.getValue("slot/empty.sav").isEmpty())
    }
}
