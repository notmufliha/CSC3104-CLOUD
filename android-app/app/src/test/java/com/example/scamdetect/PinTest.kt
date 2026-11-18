package com.example.scamdetect

import com.example.scamdetect.logic.Pin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 4: the optional PIN (set / verify / reset). The same vectors as iOS PinTests. */
class PinTest {
    private val salt = Pin.unhex("000102030405060708090a0b0c0d0e0f")

    @Test fun onlyFourDigits() {
        assertTrue(Pin.isValid("0000"))
        assertTrue(Pin.isValid("2468"))
        listOf("", "123", "12345", "12a4", "١٢٣٤", " 1234").forEach { assertFalse(it, Pin.isValid(it)) }
    }

    /** The same hash on both platforms (PBKDF2-HMAC-SHA256, 20 000 rounds, 32 bytes). */
    @Test fun knownHash() {
        assertEquals(PIN_2468_HASH, Pin.hex(Pin.hash("2468", salt)))
        assertEquals(32, Pin.hash("0000", salt).size)
    }

    @Test fun setVerifyReset() {
        val s = Pin.newSalt()
        assertEquals(16, s.size)
        assertFalse(s.contentEquals(Pin.newSalt()))
        val saved = Pin.hash("2468", s)
        assertTrue(Pin.matches("2468", s, saved))
        assertFalse(Pin.matches("2469", s, saved))
        assertFalse(Pin.matches("", s, saved))
        assertNotEquals(Pin.hex(saved), Pin.hex(Pin.hash("2468", Pin.newSalt())))   // a new salt, a new hash
        assertArrayEquals(salt, Pin.unhex(Pin.hex(salt)))
    }

    companion object {
        const val PIN_2468_HASH = "874306222d2ae5883abfc71a9ee1618098b7a33f9504cea3e2213739c89d5275"
    }
}
