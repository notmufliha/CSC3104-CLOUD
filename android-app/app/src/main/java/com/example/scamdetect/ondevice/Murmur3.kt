package com.example.scamdetect.ondevice

/**
 * Workstream G.10: MurmurHash3 (x86, 32-bit), the hash scikit-learn's HashingVectorizer uses
 * (sklearn.utils.murmurhash3_32 on the token's UTF-8 bytes, seed 0). A hashing model's slot for a token is
 * [slot]; it must match Python exactly, or the phone would look up the wrong weights.
 */
object Murmur3 {
    private const val C1 = 0xcc9e2d51.toInt()
    private const val C2 = 0x1b873593

    fun hash32(data: ByteArray, seed: Int = 0): Int {
        var h = seed
        val blocks = data.size / 4
        for (i in 0 until blocks) {
            val o = i * 4
            var k = (data[o].toInt() and 0xff) or ((data[o + 1].toInt() and 0xff) shl 8) or
                ((data[o + 2].toInt() and 0xff) shl 16) or (data[o + 3].toInt() shl 24)
            k *= C1
            k = Integer.rotateLeft(k, 15)
            k *= C2
            h = h xor k
            h = Integer.rotateLeft(h, 13)
            h = h * 5 + 0xe6546b64.toInt()
        }
        val tail = blocks * 4
        val rest = data.size and 3
        var k = 0
        if (rest >= 3) k = k xor ((data[tail + 2].toInt() and 0xff) shl 16)
        if (rest >= 2) k = k xor ((data[tail + 1].toInt() and 0xff) shl 8)
        if (rest >= 1) {
            k = k xor (data[tail].toInt() and 0xff)
            k *= C1
            k = Integer.rotateLeft(k, 15)
            k *= C2
            h = h xor k
        }
        h = h xor data.size
        h = h xor (h ushr 16)
        h *= 0x85ebca6b.toInt()
        h = h xor (h ushr 13)
        h *= 0xc2b2ae35.toInt()
        h = h xor (h ushr 16)
        return h
    }

    /** scikit-learn's slot for a token: abs(hash) % nFeatures (with its special case for Int.MIN_VALUE). */
    fun slot(token: String, nFeatures: Int): Int {
        val h = hash32(token.toByteArray(Charsets.UTF_8))
        return if (h == Int.MIN_VALUE) ((2147483647L - (nFeatures - 1)) % nFeatures).toInt()
        else Math.abs(h) % nFeatures
    }
}
