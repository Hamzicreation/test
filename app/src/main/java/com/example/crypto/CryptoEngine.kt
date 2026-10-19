package com.example.crypto

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedPayload(
    val cipherTextBase64: String,
    val ivBase64: String,
    val macTag: String
)

object CryptoEngine {
    private const val AES_GCM_ALGORITHM = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128 // bits
    private const val IV_LENGTH_BYTES = 12 // 96 bits for GCM
    private val secureRandom = SecureRandom()

    /**
     * Generates a stable random device ID formatted like "BC-8F2A"
     */
    fun generateDeviceId(): String {
        val bytes = ByteArray(2)
        secureRandom.nextBytes(bytes)
        val hex = bytes.joinToString("") { "%02X".format(it) }
        return "BC-$hex"
    }

    /**
     * Generates a 256-bit random master secret key encoded as Base64.
     */
    fun generateMasterKey(): String {
        val keyBytes = ByteArray(32)
        secureRandom.nextBytes(keyBytes)
        return Base64.encodeToString(keyBytes, Base64.NO_WRAP)
    }

    /**
     * Derives a deterministic shared symmetric key between two node IDs or salt
     */
    fun deriveSharedKey(localId: String, peerId: String, localSecret: String): ByteArray {
        val sortedNodes = listOf(localId, peerId).sorted().joinToString(":")
        val seed = "$sortedNodes::$localSecret".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(seed)
    }

    /**
     * Encrypts plaintext with AES-GCM using the 256-bit key.
     */
    fun encrypt(plainText: String, keyBytes: ByteArray): EncryptedPayload {
        val iv = ByteArray(IV_LENGTH_BYTES)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(AES_GCM_ALGORITHM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        val secretKey = SecretKeySpec(keyBytes, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val cipherTextBase64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
        val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)

        // Compute HMAC / SHA-256 integrity tag for outer validation
        val tagDigest = MessageDigest.getInstance("SHA-256")
        tagDigest.update(cipherBytes)
        tagDigest.update(iv)
        val macTag = Base64.encodeToString(tagDigest.digest(), Base64.NO_WRAP).take(16)

        return EncryptedPayload(
            cipherTextBase64 = cipherTextBase64,
            ivBase64 = ivBase64,
            macTag = macTag
        )
    }

    /**
     * Decrypts ciphertext with AES-GCM using the 256-bit key and IV.
     */
    fun decrypt(cipherTextBase64: String, ivBase64: String, keyBytes: ByteArray): String? {
        return try {
            val cipherBytes = Base64.decode(cipherTextBase64, Base64.NO_WRAP)
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)

            val cipher = Cipher.getInstance(AES_GCM_ALGORITHM)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            val secretKey = SecretKeySpec(keyBytes, "AES")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val plainBytes = cipher.doFinal(cipherBytes)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Computes SHA-256 hash of a string for fast checksums / packet deduplication.
     */
    fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
