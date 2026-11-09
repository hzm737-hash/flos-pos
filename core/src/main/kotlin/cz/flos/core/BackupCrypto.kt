package cz.flos.core

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password protection of a till backup. The file holds bank and EET secrets and customer data,
 * and the owner keeps it on a drive or in e-mail, so the whole .zip is encrypted:
 * PBKDF2-HMAC-SHA256 (210 000 rounds, 16-byte salt) gives an AES-256 key, AES-GCM (12-byte IV)
 * encrypts and authenticates the zip. Layout: "FLOSBAK1" + salt + IV + ciphertext with tag.
 */
object BackupCrypto {
    private val MAGIC = "FLOSBAK1".toByteArray(Charsets.US_ASCII)
    private const val SALT = 16
    private const val IV = 12
    private const val ROUNDS = 210_000
    const val MIN_PASSWORD = 8

    sealed interface Opened {
        data class Ok(val zip: ByteArray) : Opened
        data object WrongPassword : Opened
        data object Damaged : Opened
    }

    fun isEncrypted(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    fun encrypt(zip: ByteArray, password: String, random: SecureRandom = SecureRandom()): ByteArray {
        require(password.length >= MIN_PASSWORD) { "password too short" }
        val salt = ByteArray(SALT).also(random::nextBytes)
        val iv = ByteArray(IV).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC)
        val body = cipher.doFinal(zip)
        return ByteBuffer.allocate(MAGIC.size + SALT + IV + body.size).put(MAGIC).put(salt).put(iv).put(body).array()
    }

    fun decrypt(bytes: ByteArray, password: String): Opened {
        if (!isEncrypted(bytes) || bytes.size < MAGIC.size + SALT + IV + 16) return Opened.Damaged
        val salt = bytes.copyOfRange(MAGIC.size, MAGIC.size + SALT)
        val iv = bytes.copyOfRange(MAGIC.size + SALT, MAGIC.size + SALT + IV)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
            cipher.updateAAD(MAGIC)
            Opened.Ok(cipher.doFinal(bytes, MAGIC.size + SALT + IV, bytes.size - MAGIC.size - SALT - IV))
        } catch (e: AEADBadTagException) {
            // A wrong password and a changed file look the same to GCM; a wrong password is far more likely.
            Opened.WrongPassword
        } catch (e: Exception) {
            Opened.Damaged
        }
    }

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ROUNDS, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
