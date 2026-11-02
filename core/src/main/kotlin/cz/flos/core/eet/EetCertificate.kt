package cz.flos.core.eet

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date

/**
 * The taxpayer's EET cash-register certificate (pokladní certifikát), as the .p12 file
 * generated in DIS+ / MOJE daně ("Správa pokladních certifikátů EET").
 */
class EetCertificate(val privateKey: PrivateKey, val certificate: X509Certificate) {

    /** EIČ from the certificate subject (CN), e.g. "CZ12345678". */
    val eic: String? get() = Regex("CN=([^,]+)").find(certificate.subjectX500Principal.name)?.groupValues?.get(1)?.trim()

    val validUntil: Date get() = certificate.notAfter

    fun isValidAt(date: Date = Date()): Boolean = !date.before(certificate.notBefore) && !date.after(certificate.notAfter)

    companion object {
        /** Loads the first private-key entry from a PKCS#12 file. Throws with a readable message on failure. */
        fun fromPkcs12(bytes: ByteArray, password: String): EetCertificate {
            val ks = KeyStore.getInstance("PKCS12")
            try {
                ks.load(ByteArrayInputStream(bytes), password.toCharArray())
            } catch (e: Exception) {
                throw IllegalArgumentException("Certifikát se nepodařilo otevřít. Zkontrolujte soubor a heslo.", e)
            }
            val alias = ks.aliases().toList().firstOrNull { ks.isKeyEntry(it) }
                ?: throw IllegalArgumentException("Soubor neobsahuje soukromý klíč.")
            val key = ks.getKey(alias, password.toCharArray()) as? PrivateKey
                ?: throw IllegalArgumentException("Soukromý klíč nelze načíst.")
            val cert = ks.getCertificate(alias) as? X509Certificate
                ?: throw IllegalArgumentException("Soubor neobsahuje certifikát X.509.")
            return EetCertificate(key, cert)
        }
    }
}
