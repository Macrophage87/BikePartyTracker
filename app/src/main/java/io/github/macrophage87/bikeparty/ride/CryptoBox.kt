package io.github.macrophage87.bikeparty.ride

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption for ride messages.
 *
 * All payloads are AES-256-GCM encrypted with a key derived (PBKDF2) from the
 * ride code + password set by the ride leader. The MQTT topic is likewise
 * derived by hashing both secrets, so riders without the password can neither
 * find nor read the group's traffic — the broker only ever sees ciphertext.
 */
class CryptoBox(rideCode: String, password: String) {

    private val key: SecretKeySpec

    init {
        val salt = MessageDigest.getInstance("SHA-256")
            .digest("bikeparty-salt|${normalize(rideCode)}".toByteArray(Charsets.UTF_8))
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        key = SecretKeySpec(keyBytes, "AES")
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return iv + cipher.doFinal(plain)
    }

    /** Returns null if the payload is malformed or was encrypted with a different key. */
    fun decrypt(data: ByteArray): ByteArray? {
        if (data.size <= IV_LENGTH) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, key,
                GCMParameterSpec(TAG_BITS, data.copyOfRange(0, IV_LENGTH))
            )
            cipher.doFinal(data, IV_LENGTH, data.size - IV_LENGTH)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val PBKDF2_ITERATIONS = 60_000
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128

        private fun normalize(rideCode: String) = rideCode.trim().uppercase()

        /**
         * Topic namespace for a ride. Derived from both secrets so the ride
         * cannot be discovered on a shared broker without the password.
         */
        fun topicRoot(rideCode: String, password: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("bikeparty-topic|${normalize(rideCode)}|$password".toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }
            return "bpt/${hex.substring(0, 20)}"
        }
    }
}
