package ua.grey.qstarlight.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QStarProtocolTest {
    @Test fun whiteFrame() {
        assertArrayEquals(
            byteArrayOf(0x56, 0x64, 0x00, 0x64, 0x00, 0x00, 0xAA.toByte()),
            QStarProtocol.cctFrame(100, 100)
        )
    }

    @Test fun yellowFrame() {
        assertArrayEquals(
            byteArrayOf(0x56, 0x00, 0x64, 0x64, 0x00, 0x00, 0xAA.toByte()),
            QStarProtocol.cctFrame(0, 100)
        )
    }

    @Test fun stateParsing() {
        val f = byteArrayOf(0x66, 0x10, 0x23, 0x00, 0x64, 0x64, 0x00, 0x00, 0x00, 0x00, 0x03, 0x99.toByte())
        val s = QStarProtocol.parseState(f)!!
        assertTrue(s.power)
        assertEquals(100, s.white)
        assertEquals(0, s.yellow)
        assertEquals(100, s.brightness)
    }

    @Test fun handshakeShape() {
        val h = QStarProtocol.newHandshake()
        assertEquals(16, h.plain.size)
        assertEquals(18, h.request.size)
        assertEquals(0xFB, h.request.first().toInt() and 0xFF)
        assertEquals(0xFA, h.request.last().toInt() and 0xFF)
        assertTrue(h.plain.all { (it.toInt() and 0xFF) in 1..90 })
        val response = byteArrayOf(0xF9.toByte()) + QStarProtocol.aesEncrypt(h.plain) + byteArrayOf(0xF8.toByte())
        assertTrue(QStarProtocol.validateHandshakeResponse(response, h.plain))
    }

    @Test fun passwordStatus() {
        val on = byteArrayOf(0x2F,0,0,0,0,0,0,0,1,0,0,0xF2.toByte())
        val off = byteArrayOf(0x2F,0,0,0,0,0,0,0,0,0,0,0xF2.toByte())
        assertTrue(QStarProtocol.parsePasswordRequired(on) == true)
        assertFalse(QStarProtocol.parsePasswordRequired(off) == true)
    }
}
