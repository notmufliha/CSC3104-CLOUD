package com.example.scamdetect.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.scamdetect.logic.Pin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.pinData by preferencesDataStore(name = "scamguard_pin")

/**
 * The optional PIN: only its salted PBKDF2 hash, in its own DataStore file (never the PIN itself, never in the
 * SharedPreferences with the other settings). "Forgot PIN" clears just this file; the call history is untouched.
 */
class PinStore(context: Context) {
    private val data = context.applicationContext.pinData
    private val keyHash = stringPreferencesKey("pin_hash")
    private val keySalt = stringPreferencesKey("pin_salt")

    val isSet: Flow<Boolean> = data.data.map { it[keyHash] != null && it[keySalt] != null }

    suspend fun set(pin: String) {
        require(Pin.isValid(pin))
        val salt = Pin.newSalt()
        val hash = Pin.hash(pin, salt)
        data.edit { it[keySalt] = Pin.hex(salt); it[keyHash] = Pin.hex(hash) }
    }

    suspend fun verify(pin: String): Boolean {
        val p = data.data.first()
        val salt = p[keySalt] ?: return false
        val hash = p[keyHash] ?: return false
        return Pin.matches(pin, Pin.unhex(salt), Pin.unhex(hash))
    }

    suspend fun clear() { data.edit { it.remove(keyHash); it.remove(keySalt) } }
}
