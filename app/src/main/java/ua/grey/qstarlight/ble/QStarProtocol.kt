package ua.grey.qstarlight.ble

import java.util.UUID
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object QStarProtocol {
    val SERVICE_WRITE: UUID = UUID.fromString("0000ffd5-0000-1000-8000-00805f9b34fb")
    val CHAR_WRITE: UUID = UUID.fromString("0000ffd9-0000-1000-8000-00805f9b34fb")
    val SERVICE_NOTIFY: UUID = UUID.fromString("0000ffd0-0000-1000-8000-00805f9b34fb")
    val CHAR_NOTIFY: UUID = UUID.fromString("0000ffd4-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val AES_KEY = bytes(
        0xD0, 0xF9, 0xF4, 0x8C, 0x59, 0xA2, 0x69, 0x1D,
        0x20, 0x53, 0xCB, 0xDA, 0x80, 0x84, 0x43, 0x93
    )

    data class Handshake(val plain: ByteArray, val request: ByteArray)

    private val secureRandom = SecureRandom()

    // Drive Light generates 16 bytes in the inclusive range 1..90 once per connection.
    // Retries reuse the same 16-byte challenge for that connection.
    fun newHandshake(): Handshake {
        val plain = ByteArray(16) { (secureRandom.nextInt(90) + 1).toByte() }
        val request = byteArrayOf(0xFB.toByte()) + plain + byteArrayOf(0xFA.toByte())
        return Handshake(plain, request)
    }
    val QUERY_STATE = bytes(0xEF, 0x01, 0x77)
    val QUERY_CONFIG = bytes(0xC5, 0xF0, 0x5C)
    val POWER_ON = bytes(0xDD, 0x23, 0x33)
    val POWER_OFF = bytes(0xDD, 0x24, 0x33)

    data class LampState(
        val power: Boolean,
        val yellow: Int,
        val white: Int,
        val brightness: Int,
        val mode: Int
    )

    fun cctFrame(white: Int, brightness: Int, mode: Int = 0): ByteArray {
        val w = white.coerceIn(0, 100)
        val y = 100 - w
        val b = brightness.coerceIn(5, 100)
        return bytes(0x56, w, y, b, mode.coerceIn(0, 255), 0x00, 0xAA)
    }

    fun passwordFrame(password: String): ByteArray {
        require(password.length == 4 && password.all(Char::isDigit)) { "Password must be exactly four digits" }
        return bytes(
            0xCF,
            password[0].digitToInt(),
            password[1].digitToInt(),
            password[2].digitToInt(),
            password[3].digitToInt(),
            0xFC
        )
    }

    fun passwordModeFrame(enabled: Boolean): ByteArray = bytes(
        0xF2, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, if (enabled) 0x01 else 0x00, 0x00, 0x00, 0x2F
    )

    fun parseState(frame: ByteArray): LampState? {
        if (frame.size != 12 || u(frame[0]) != 0x66 || u(frame[11]) != 0x99) return null
        val powerByte = u(frame[2])
        if (powerByte != 0x23 && powerByte != 0x24) return null
        return LampState(
            power = powerByte == 0x23,
            yellow = u(frame[3]),
            white = u(frame[4]),
            brightness = u(frame[5]),
            mode = u(frame[6])
        )
    }

    fun parsePasswordRequired(frame: ByteArray): Boolean? {
        if (frame.size != 12 || u(frame[0]) != 0x2F || u(frame[11]) != 0xF2) return null
        return u(frame[8]) == 0x01
    }

    fun isPasswordAck(frame: ByteArray): Boolean =
        frame.size >= 2 && u(frame[0]) == 0xA0 && u(frame[1]) == 0xF0

    fun validateHandshakeResponse(frame: ByteArray, plain: ByteArray): Boolean {
        if (frame.size != 18 || u(frame[0]) != 0xF9 || u(frame[17]) != 0xF8) return false
        if (plain.size != 16) return false
        val expected = aesEncrypt(plain)
        for (i in expected.indices) {
            if (frame[i + 1] != expected[i]) return false
        }
        return true
    }

    fun aesEncrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(AES_KEY, "AES"))
        return cipher.doFinal(data)
    }

    fun hex(data: ByteArray): String = data.joinToString(" ") { "%02X".format(u(it)) }

    private fun bytes(vararg ints: Int): ByteArray = ByteArray(ints.size) { ints[it].toByte() }
    private fun u(value: Byte): Int = value.toInt() and 0xFF
}
