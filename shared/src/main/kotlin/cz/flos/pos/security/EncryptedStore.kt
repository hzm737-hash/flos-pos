package cz.flos.pos.security

import cz.flos.pos.KeyValueStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps a [KeyValueStore] so that passwords and tokens are saved encrypted (AES-256-GCM) instead
 * of as plain text. Everything else is passed through unchanged.
 *
 * Update-safe by design:
 *  - values written by older versions as plain text are still read, and are encrypted once on start;
 *  - if the key cannot be created (very old or broken key store), values stay as plain text, as before;
 *  - a value that can no longer be decrypted (e.g. restored onto another device from a cloud backup)
 *    reads as empty, so the owner simply types that password again. The app never crashes on it.
 */
class EncryptedStore(
    private val inner: KeyValueStore,
    private val key: () -> SecretKey?,
    private val secretKeys: Set<String> = SECRET_KEYS,
) : KeyValueStore {

    private val cachedKey: SecretKey? by lazy { runCatching { key() }.getOrNull() }

    init {
        migratePlainSecrets()
    }

    override fun get(key: String): String? {
        val v = inner.get(key) ?: return null
        if (key !in secretKeys || !v.startsWith(PREFIX)) return v
        return decrypt(v)
    }

    override fun put(values: Map<String, String>) {
        inner.put(values.mapValues { (k, v) -> if (k in secretKeys) encrypt(v) else v })
    }

    /** Encrypts secrets that older versions saved as plain text. */
    private fun migratePlainSecrets() {
        if (cachedKey == null) return
        val plain = secretKeys.mapNotNull { k -> inner.get(k)?.takeIf { it.isNotEmpty() && !it.startsWith(PREFIX) }?.let { k to it } }.toMap()
        if (plain.isNotEmpty()) runCatching { put(plain) }
    }

    private fun encrypt(value: String): String {
        if (value.isEmpty()) return value
        val k = cachedKey ?: return value
        return runCatching {
            val c = Cipher.getInstance(TRANSFORMATION)
            c.init(Cipher.ENCRYPT_MODE, k) // the provider picks a fresh random IV
            val iv = c.iv
            val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
            val b64 = Base64.getEncoder().withoutPadding()
            PREFIX + b64.encodeToString(iv) + ":" + b64.encodeToString(ct)
        }.getOrDefault(value)
    }

    private fun decrypt(stored: String): String? {
        val k = cachedKey ?: return null
        return runCatching {
            val (ivB64, ctB64) = stored.removePrefix(PREFIX).split(':', limit = 2)
            val d = Base64.getDecoder()
            val c = Cipher.getInstance(TRANSFORMATION)
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, d.decode(ivB64)))
            String(c.doFinal(d.decode(ctB64)), Charsets.UTF_8)
        }.getOrNull()
    }

    companion object {
        const val PREFIX = "enc1:"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** Settings that hold passwords, tokens or PIN hashes. */
        val SECRET_KEYS = setOf("eetCertPassword", "imapPassword", "fioToken", "boToken", "boStaff")
    }
}
