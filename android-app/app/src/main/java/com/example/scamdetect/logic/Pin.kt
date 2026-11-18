package com.example.scamdetect.logic

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The optional 4-digit PIN for Advanced settings and "For family". Only a salted PBKDF2-HMAC-SHA256 hash is kept
 * (data/PinStore.kt, DataStore), never the PIN. The same hash as iOS Logic/Pin.swift (stored in the Keychain), so
 * both apps test the same vectors.
 */
object Pin {
    const val LENGTH = 4
    const val ITERATIONS = 20_000

    fun isValid(pin: String) = pin.length == LENGTH && pin.all { it in '0'..'9' }

    fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    /** Constant-time compare of the typed PIN's hash with the saved one. */
    fun matches(pin: String, salt: ByteArray, saved: ByteArray): Boolean =
        isValid(pin) && MessageDigest.isEqual(hash(pin, salt), saved)

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** What a PIN screen does with what was typed (pure, for tests). */
    enum class Check { OK, WRONG, TOO_SHORT }
}
