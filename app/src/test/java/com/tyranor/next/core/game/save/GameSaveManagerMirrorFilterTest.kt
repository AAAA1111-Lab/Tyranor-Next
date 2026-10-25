package com.tyranor.next.core.game.save

import java.io.ByteArrayOutputStream
import java.io.File
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
        val live = temporaryFolder.newFolder()
        live.resolve("slot.sav").writeText("existing save")
        val staging = File(live.parentFile, live.name + ".import_staging").apply { mkdirs() }
        try {
            GameSaveManager.stageImportContents(source, staging, { false }, mirrorForced = true)
            fail("Filtered-empty imports must not reach the directory exchange")
        } catch (error: GameSaveException) {
            assertEquals(SaveErrorCode.NO_FILES_IN_ZIP, error.code)
        }
        assertEquals("existing save", live.resolve("slot.sav").readText())
        assertTrue(staging.listFiles().orEmpty().isEmpty())
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
