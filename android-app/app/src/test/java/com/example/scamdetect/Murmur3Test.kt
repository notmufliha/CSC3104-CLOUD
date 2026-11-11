package com.example.scamdetect

import com.example.scamdetect.ondevice.Murmur3
import org.junit.Assert.assertEquals
import org.junit.Test

/** Workstream G.10: the phone's hash must equal scikit-learn's (sklearn.utils.murmurhash3_32(token.encode(), 0)). */
class Murmur3Test {
    // Values printed by scikit-learn 1.4.2: murmurhash3_32(s.encode("utf-8"), seed=0) and abs(h) % 2**18.
    private val cases = listOf(
        Triple("now", 1153501715, 68115),
        Triple("act now", 576793883, 77083),
        Triple("arrested", 1873934942, 129630),
        Triple("été", 865297935, 222735),        // "été": multi-byte UTF-8
        Triple(" ab", -760043134, 87678),                  // negative hash: slot uses abs()
        Triple(" pol", 122369946, 210842),
        Triple("police officer", -1029493358, 53870),
    )

    @Test fun hashMatchesScikitLearn() = cases.forEach { (s, h, _) ->
        assertEquals("hash of \"$s\"", h, Murmur3.hash32(s.toByteArray(Charsets.UTF_8)))
    }

    @Test fun slotMatchesScikitLearn() = cases.forEach { (s, _, slot) ->
        assertEquals("slot of \"$s\"", slot, Murmur3.slot(s, 1 shl 18))
    }
}
