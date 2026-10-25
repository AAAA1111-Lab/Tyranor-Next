package bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KrMirrorReadSessionTest {
    @Test
    fun `session owns immutable snapshot independent of caller mutations`() {
        val documents = mutableMapOf("asset" to "source")
        val session = KrMirrorReadSession(documents)
        documents["asset"] = "replacement"
        documents.clear()
        assertEquals("source", session.entry("asset"))
    }

    @Test
    fun `promotion removes source route and stale read failure only for same key`() {
        val session = KrMirrorReadSession(mapOf("asset" to 1, "other" to 2))
        session.recordRead("asset", false)
        session.recordRead("other", false)
        session.promote("asset")
        assertNull(session.entry("asset"))
        assertFalse(session.readFailed("asset"))
        assertEquals(2, session.entry("other"))
        assertTrue(session.readFailed("other"))
    }

    @Test
    fun `successful retry clears failure without dropping source route`() {
        val session = KrMirrorReadSession(mapOf("asset" to "uri"))
        session.recordRead("asset", false)
        assertTrue(session.readFailed("asset"))
        session.recordRead("asset", true)
        assertFalse(session.readFailed("asset"))
        assertEquals("uri", session.entry("asset"))
    }
}
