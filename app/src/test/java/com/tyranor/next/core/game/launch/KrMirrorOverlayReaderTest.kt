package com.tyranor.next.core.game.launch

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KrMirrorOverlayReaderTest {
    @Test fun emptySourceIsSuccessfulContent() {
        val reader = reader(size = 0) { ByteArrayInputStream(byteArrayOf()) }
        val result = reader.read("patch.tjs", 4)
        assertTrue(result is KrMirrorOverlayReader.Result.Content)
        assertArrayEquals(byteArrayOf(), (result as KrMirrorOverlayReader.Result.Content).bytes)
    }

    @Test fun sizeMismatchIsUnavailableEvenForZeroMetadata() {
        // Declared (0) and actual (2) sizes disagree — the source changed after prepare;
        // content cannot be verified, so the overlay must not shadow it (R5-#5/#6).
        assertEquals(KrMirrorOverlayReader.Result.Unavailable, reader(size = 0) {
            ByteArrayInputStream(byteArrayOf(1, 2))
        }.read("patch.tjs", 4))
    }

    @Test fun confirmedAbsenceDoesNotOpenSource() {
        val reader = KrMirrorOverlayReader(mapOf("patch.tjs" to KrMirrorOverlayReader.Source(null, confirmedAbsent = true))) {
            error("Absent source must not be opened")
        }
        assertEquals(KrMirrorOverlayReader.Result.Absent, reader.read("patch.tjs", 4))
    }

    @Test fun missingIndexIsUnavailableRatherThanAbsent() {
        val reader = KrMirrorOverlayReader(emptyMap()) { error("No indexed source") }
        assertEquals(KrMirrorOverlayReader.Result.Unavailable, reader.read("patch.tjs", 4))
    }

    @Test fun deniedNullAndFailedStreamsAreUnavailable() {
        assertEquals(KrMirrorOverlayReader.Result.Unavailable, reader { throw SecurityException() }.read("patch.tjs", 4))
        assertEquals(KrMirrorOverlayReader.Result.Unavailable, reader { null }.read("patch.tjs", 4))
        assertEquals(KrMirrorOverlayReader.Result.Unavailable, reader { throw IOException() }.read("patch.tjs", 4))
    }

    @Test fun declaredAndActualLimitsBothApplyAndCloseStreams() {
        assertEquals(KrMirrorOverlayReader.Result.Unavailable, reader(size = 5) { error("Oversized source must not open") }.read("patch.tjs", 4))
        var closed = false
        val reader = reader { object : ByteArrayInputStream(ByteArray(5)) {
            override fun close() { closed = true; super.close() }
        } }
        assertEquals(KrMirrorOverlayReader.Result.Unavailable, reader.read("patch.tjs", 4))
        assertTrue(closed)
    }

    @Test(expected = CancellationException::class)
    fun cancellationPropagates() {
        reader { throw CancellationException() }.read("patch.tjs", 4)
    }

    @Test fun shortReadOfKnownSizeIsUnavailableInsteadOfShadowing() {
        // Provider EOF (or source shrank after prepare): a short/empty read must never
        // pass as content — an empty short read would synthesize the basic overlay over
        // the real patch.tjs (#129's plugin-loss path).
        assertEquals(KrMirrorOverlayReader.Result.Unavailable, reader(size = 10) {
            ByteArrayInputStream("part".toByteArray())
        }.read("patch.tjs", 16))
        assertEquals(KrMirrorOverlayReader.Result.Unavailable, reader(size = 10) {
            ByteArrayInputStream(byteArrayOf())
        }.read("patch.tjs", 16))
    }

    @Test fun exactSizeReadIsContent() {
        val result = reader(size = 4) { ByteArrayInputStream("abcd".toByteArray()) }.read("patch.tjs", 16)
        assertArrayEquals("abcd".toByteArray(), (result as KrMirrorOverlayReader.Result.Content).bytes)
    }

    private fun reader(size: Long = -1, open: (String) -> java.io.InputStream?) =
        KrMirrorOverlayReader(mapOf("patch.tjs" to KrMirrorOverlayReader.Source("source", size)), open)
}
