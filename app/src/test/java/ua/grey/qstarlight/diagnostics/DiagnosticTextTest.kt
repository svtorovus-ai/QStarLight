package ua.grey.qstarlight.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticTextTest {
    @Test fun credentialsAreRemovedFromNestedNetworkMessages() {
        val raw = """host=192.168.1.5 {"pin":"654321","config":{"devices":[{"name":"QStar","password":"9876"}],"brightness":73}}"""
        val safe = DiagnosticText.sanitize(raw)
        assertFalse(safe.contains("654321"))
        assertFalse(safe.contains("9876"))
        assertTrue(safe.contains("\"brightness\":73"))
        assertTrue(safe.contains("192.168.1.5"))
    }

    @Test fun escapedQuotesCannotExposeTheRemainderOfAPassword() {
        val safe = DiagnosticText.sanitize("""{"PASSWORD":"before\"after","remotePin":"secret","pwd_AB":"hidden"}""")
        assertFalse(safe.contains("before"))
        assertFalse(safe.contains("after"))
        assertFalse(safe.contains("secret"))
        assertFalse(safe.contains("hidden"))
    }

    @Test fun oneEventRemainsOneBoundedLogLine() {
        val safe = DiagnosticText.sanitize("start\nforged\rentry" + "x".repeat(5000))
        assertEquals(4000, safe.length)
        assertFalse(safe.contains('\n'))
        assertFalse(safe.contains('\r'))
    }
}
