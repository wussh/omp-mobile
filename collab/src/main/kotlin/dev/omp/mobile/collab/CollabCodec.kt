package dev.omp.mobile.collab

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM room cipher.
 *
 * Sealed layout matches upstream: `[12B random IV][ciphertext + 128-bit tag]`.
 * No AAD, no KDF, no compression. The room key is imported once per activation.
 */
class RoomCipher private constructor(private val key: SecretKeySpec, private val random: SecureRandom) {
    fun seal(plaintext: String): ByteArray {
        val iv = ByteArray(IV_LENGTH)
        random.nextBytes(iv)
        return seal(plaintext, iv)
    }

    /** Fixed-IV overload exists for conformance tests only. */
    fun seal(plaintext: String, iv: ByteArray): ByteArray {
        require(iv.size == IV_LENGTH) { "IV must be $IV_LENGTH bytes" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return ByteArray(IV_LENGTH + ciphertext.size).also {
            System.arraycopy(iv, 0, it, 0, IV_LENGTH)
            System.arraycopy(ciphertext, 0, it, IV_LENGTH, ciphertext.size)
        }
    }

    @Throws(GeneralSecurityException::class)
    fun open(sealed: ByteArray): String {
        if (sealed.size <= IV_LENGTH) throw GeneralSecurityException("Sealed frame too short")
        val iv = sealed.copyOfRange(0, IV_LENGTH)
        val ciphertext = sealed.copyOfRange(IV_LENGTH, sealed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    companion object {
        const val IV_LENGTH = 12
        private const val TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** Throws when the key is not exactly 32 bytes. */
        fun fromKey(raw: ByteArray, random: SecureRandom = SecureRandom()): RoomCipher {
            require(raw.size == ROOM_KEY_BYTES) { "Room key must be $ROOM_KEY_BYTES bytes, got ${raw.size}" }
            return RoomCipher(SecretKeySpec(raw, "AES"), random)
        }
    }
}

fun packEnvelope(peerId: Int, sealed: ByteArray): ByteArray {
    val out = ByteArray(ENVELOPE_HEADER_LENGTH + sealed.size)
    ByteBuffer.wrap(out).putInt(peerId)
    System.arraycopy(sealed, 0, out, ENVELOPE_HEADER_LENGTH, sealed.size)
    return out
}

data class Envelope(val peerId: Int, val payload: ByteArray)

/** Returns null for envelopes shorter than the 4-byte peer prefix. */
fun unpackEnvelope(data: ByteArray): Envelope? {
    if (data.size < ENVELOPE_HEADER_LENGTH) return null
    val peerId = ByteBuffer.wrap(data, 0, ENVELOPE_HEADER_LENGTH).int
    return Envelope(peerId, data.copyOfRange(ENVELOPE_HEADER_LENGTH, data.size))
}
