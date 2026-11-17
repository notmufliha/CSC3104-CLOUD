import Foundation

/// Port of android-app ondevice/Murmur3.kt (G.10): MurmurHash3 (x86, 32-bit), the hash scikit-learn's
/// HashingVectorizer uses (sklearn.utils.murmurhash3_32 on the token's UTF-8 bytes, seed 0).
nonisolated enum Murmur3 {
    private static let c1: UInt32 = 0xcc9e2d51
    private static let c2: UInt32 = 0x1b873593

    private static func rotl(_ x: UInt32, _ r: UInt32) -> UInt32 { (x << r) | (x >> (32 - r)) }

    /// The signed 32-bit hash, as Python/Java print it.
    static func hash32(_ data: [UInt8], seed: UInt32 = 0) -> Int32 {
        var h = seed
        let blocks = data.count / 4
        for i in 0..<blocks {
            let o = i * 4
            var k = UInt32(data[o]) | (UInt32(data[o + 1]) << 8) | (UInt32(data[o + 2]) << 16) | (UInt32(data[o + 3]) << 24)
            k = k &* c1
            k = rotl(k, 15)
            k = k &* c2
            h ^= k
            h = rotl(h, 13)
            h = h &* 5 &+ 0xe6546b64
        }
        let tail = blocks * 4
        let rest = data.count & 3
        var k: UInt32 = 0
        if rest >= 3 { k ^= UInt32(data[tail + 2]) << 16 }
        if rest >= 2 { k ^= UInt32(data[tail + 1]) << 8 }
        if rest >= 1 {
            k ^= UInt32(data[tail])
            k = k &* c1
            k = rotl(k, 15)
            k = k &* c2
            h ^= k
        }
        h ^= UInt32(truncatingIfNeeded: data.count)
        h ^= h >> 16
        h = h &* 0x85ebca6b
        h ^= h >> 13
        h = h &* 0xc2b2ae35
        h ^= h >> 16
        return Int32(bitPattern: h)
    }

    /// scikit-learn's slot for a token: abs(hash) % nFeatures (with its special case for Int32.min).
    static func slot(_ token: String, _ nFeatures: Int) -> Int {
        let h = hash32(Array(token.utf8))
        if h == Int32.min { return Int((Int64(2147483647) - Int64(nFeatures - 1)) % Int64(nFeatures)) }
        return Int(abs(h)) % nFeatures
    }
}
