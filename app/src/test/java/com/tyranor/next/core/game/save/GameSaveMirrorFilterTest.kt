package com.tyranor.next.core.game.save

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameSaveMirrorFilterTest {
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
