package com.vivi.core.data

import com.vivi.core.data.security.PinPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 隐私空间 PIN 的规则：强度、派生、比对、错误惩罚阶梯。 */
class PinPolicyTest {

    private val saltHex = PinPolicy.toHex(ByteArray(PinPolicy.SALT_BYTES) { it.toByte() })

    @Test
    fun pinMustBeDigitsAndAtLeastFourChars() {
        assertTrue(PinPolicy.accepts("1234"))
        assertTrue(PinPolicy.accepts("1234567890"))
        assertFalse(PinPolicy.accepts("123"))
        assertFalse(PinPolicy.accepts(""))
        assertFalse(PinPolicy.accepts("12a4"))
        assertFalse(PinPolicy.accepts("12 45"))
    }

    @Test
    fun deriveIsDeterministicForSameSalt() {
        val salt = PinPolicy.hexToBytes(saltHex)
        assertEquals(PinPolicy.derive("1234", salt).toList(), PinPolicy.derive("1234", salt).toList())
    }

    @Test
    fun differentSaltGivesDifferentHash() {
        val a = PinPolicy.hashHex("1234", saltHex)
        val other = PinPolicy.hashHex("1234", PinPolicy.toHex(PinPolicy.randomSalt()))
        assertNotEquals(a, other)
    }

    @Test
    fun differentPinGivesDifferentHash() {
        assertNotEquals(PinPolicy.hashHex("1234", saltHex), PinPolicy.hashHex("1235", saltHex))
    }

    @Test
    fun matchesOnlyAcceptsTheRightPin() {
        val stored = PinPolicy.hashHex("4321", saltHex)
        assertTrue(PinPolicy.matches("4321", saltHex, stored))
        assertFalse(PinPolicy.matches("4322", saltHex, stored))
        assertFalse(PinPolicy.matches("", saltHex, stored))
        assertFalse(PinPolicy.matches("43214321", saltHex, stored))
    }

    @Test
    fun duressPinIsDistinguishableFromMainPin() {
        val main = PinPolicy.hashHex("1234", saltHex)
        val duress = PinPolicy.hashHex("9999", saltHex)
        assertNotEquals(main, duress)
        assertTrue(PinPolicy.matches("9999", saltHex, duress))
        assertFalse(PinPolicy.matches("9999", saltHex, main))
    }

    @Test
    fun penaltyLadderMatchesTheDesign() {
        assertEquals(0, PinPolicy.penaltyMinutes(1))
        assertEquals(0, PinPolicy.penaltyMinutes(2))
        assertEquals(1, PinPolicy.penaltyMinutes(3))
        assertEquals(1, PinPolicy.penaltyMinutes(5))
        assertEquals(15, PinPolicy.penaltyMinutes(6))
        assertEquals(15, PinPolicy.penaltyMinutes(9))
        assertEquals(60, PinPolicy.penaltyMinutes(10))
        assertEquals(60, PinPolicy.penaltyMinutes(99))
    }

    @Test
    fun hexRoundTripsAndRejectsOddLength() {
        val bytes = ByteArray(16) { (it * 7).toByte() }
        assertEquals(bytes.toList(), PinPolicy.hexToBytes(PinPolicy.toHex(bytes)).toList())
        assertEquals("00ff10", PinPolicy.toHex(byteArrayOf(0, -1, 0x10)))
        val rejected = runCatching { PinPolicy.hexToBytes("abc") }.isFailure
        assertTrue(rejected)
    }

    @Test
    fun constantTimeCompareHandlesLengthMismatch() {
        assertTrue(PinPolicy.equalsConstantTime("abcd", "abcd"))
        assertFalse(PinPolicy.equalsConstantTime("abcd", "abce"))
        assertFalse(PinPolicy.equalsConstantTime("abcd", "abc"))
    }
}
