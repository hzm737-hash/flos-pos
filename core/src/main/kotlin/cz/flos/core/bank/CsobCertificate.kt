package cz.flos.core.bank

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * The client certificate for ČSOB Business Connector, made without any tool on a computer:
 *  1. Flos creates an RSA key on the till and a certificate request ([newRequest], a .csr file);
 *  2. the owner uploads it in CEB → Nastavení → Business Connector → Požádat o certifikát and downloads the issued certificate;
 *  3. Flos joins the issued certificate with its key ([keyStore]).
 * The private key never leaves the device. A .p12/.pfx made another way (openssl, Windows) can be loaded with [fromPkcs12].
 */
object CsobCertificate {
    /** Password of the in-memory key store; the key itself is protected by the till's encrypted settings. */
    val STORE_PASSWORD = "flos".toCharArray()

    data class Request(val privateKeyPkcs8: String, val csrPem: String)

    /** A new 2048-bit RSA key and a PKCS#10 request "C=CZ, CN=[name]" signed with SHA-256. */
    fun newRequest(name: String): Request {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        return Request(Base64.getEncoder().encodeToString(kp.private.encoded), pem("CERTIFICATE REQUEST", csr(kp, name)))
    }

    fun csr(kp: KeyPair, name: String): ByteArray {
        val cn = name.trim().ifBlank { "Flos" }.take(64)
        val subject = seq(
            set(seq(oid("2.5.4.6"), tlv(0x13, "CZ".toByteArray()))),
            set(seq(oid("2.5.4.3"), tlv(0x0C, cn.toByteArray(Charsets.UTF_8)))),
        )
        val info = seq(tlv(0x02, byteArrayOf(0)), subject, kp.public.encoded, tlv(0xA0, ByteArray(0)))
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(kp.private); update(info) }.sign()
        val algorithm = seq(oid("1.2.840.113549.1.1.11"), tlv(0x05, ByteArray(0)))
        return seq(info, algorithm, tlv(0x03, byteArrayOf(0) + sig))
    }

    fun privateKey(pkcs8Base64: String): PrivateKey =
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pkcs8Base64.trim())))

    /**
     * All certificates in a downloaded file: .crt/.cer in PEM or DER, a .p7b chain (PEM or DER), bare Base64
     * without the BEGIN/END lines, UTF-16 text (Windows), or a .zip with any of these inside (CEB may download a zip).
     */
    fun certificates(bytes: ByteArray): List<X509Certificate> = certificates(bytes, 0)

    private fun certificates(bytes: ByteArray, depth: Int): List<X509Certificate> {
        if (depth < 2 && bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            return unzip(bytes).flatMap { runCatching { certificates(it, depth + 1) }.getOrDefault(emptyList()) }
        }
        val cf = CertificateFactory.getInstance("X.509")
        val text = text(bytes)
        val pem = Regex("-----BEGIN ([A-Z0-9 ]+)-----([\\s\\S]+?)-----END \\1-----").findAll(text).toList()
        if (pem.isNotEmpty()) {
            return pem.flatMap { m -> runCatching { derCertificates(cf, base64(m.groupValues[2])) }.getOrDefault(emptyList()) }
        }
        runCatching { derCertificates(cf, bytes) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        // Bare Base64 of a DER certificate or chain.
        val b64 = text.filterNot(Char::isWhitespace)
        if (b64.length > 100 && b64.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_' }) {
            return derCertificates(cf, base64(b64))
        }
        throw IllegalArgumentException("not a certificate")
    }

    private fun derCertificates(cf: CertificateFactory, der: ByteArray): List<X509Certificate> =
        cf.generateCertificates(ByteArrayInputStream(der)).filterIsInstance<X509Certificate>()

    private fun base64(s: String): ByteArray {
        val clean = s.filterNot(Char::isWhitespace).replace('-', '+').replace('_', '/')
        return Base64.getDecoder().decode(clean.trimEnd('=').let { it + "=".repeat((4 - it.length % 4) % 4) })
    }

    /** Text of the file: UTF-16 with a byte order mark (saved by Windows Notepad) or plain ASCII/UTF-8. */
    private fun text(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> String(bytes, Charsets.ISO_8859_1)
    }

    private fun unzip(bytes: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        java.util.zip.ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) out += z.readBytes()
            }
        }
        return out
    }

    /** The certificate in [bytes] that belongs to [key]; throws when the file is for another key. */
    fun matching(key: PrivateKey, bytes: ByteArray): X509Certificate {
        if (isRequest(bytes)) throw IllegalArgumentException("Toto je žádost o certifikát (flos-csob.csr), ne certifikát. Nahrajte soubor, který jste stáhli z CEB (Business Connector → Stáhnout).")
        val all = runCatching { certificates(bytes) }.getOrElse { throw IllegalArgumentException("Soubor není certifikát (.crt / .cer / .zip z CEB)") }
        if (all.isEmpty()) throw IllegalArgumentException("Soubor není certifikát (.crt / .cer / .zip z CEB)")
        val modulus = (key as? RSAPrivateCrtKey)?.modulus
        return all.firstOrNull { (it.publicKey as? RSAPublicKey)?.modulus == modulus }
            ?: throw IllegalArgumentException("Tento certifikát nepatří k žádosti z této pokladny. Nahrajte certifikát vydaný k poslední žádosti.")
    }

    /** The request file made by Flos (.csr), which the owner may pick by mistake instead of the issued certificate. */
    fun isRequest(bytes: ByteArray): Boolean = text(bytes).contains("CERTIFICATE REQUEST-----")

    fun pem(type: String, der: ByteArray): String =
        "-----BEGIN $type-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $type-----\n"

    fun certPem(cert: X509Certificate) = pem("CERTIFICATE", cert.encoded)

    /** Key store for the HTTPS connection: the till's key with the issued certificate. */
    fun keyStore(pkcs8Base64: String, certPem: String): KeyStore {
        val key = privateKey(pkcs8Base64)
        val cert = matching(key, certPem.toByteArray(Charsets.ISO_8859_1))
        return KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("csob-bc", key, STORE_PASSWORD, arrayOf(cert))
        }
    }

    /** Key and certificate from a .p12/.pfx file: (PKCS#8 key in Base64, certificate PEM). */
    fun fromPkcs12(bytes: ByteArray, password: String): Pair<String, String> {
        val ks = KeyStore.getInstance("PKCS12")
        runCatching { ks.load(ByteArrayInputStream(bytes), password.toCharArray()) }
            .getOrElse { throw IllegalArgumentException("Soubor .p12 se nepodařilo otevřít – zkontrolujte heslo") }
        val alias = ks.aliases().toList().firstOrNull { ks.isKeyEntry(it) } ?: throw IllegalArgumentException("V souboru .p12 chybí soukromý klíč")
        val key = ks.getKey(alias, password.toCharArray()) as? PrivateKey ?: throw IllegalArgumentException("V souboru .p12 chybí soukromý klíč")
        val cert = ks.getCertificate(alias) as X509Certificate
        return Base64.getEncoder().encodeToString(key.encoded) to certPem(cert)
    }

    // ---- minimal DER ----

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
            else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xFF); out.write(n and 0xFF) }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    private fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })

    private fun oid(dotted: String): ByteArray {
        val p = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((p[0] * 40 + p[1]).toInt())
        for (v in p.drop(2)) {
            val chunks = mutableListOf<Int>()
            var x = v
            do { chunks.add((x and 0x7F).toInt()); x = x shr 7 } while (x > 0)
            chunks.reversed().forEachIndexed { i, c -> out.write(if (i < chunks.size - 1) c or 0x80 else c) }
        }
        return tlv(0x06, out.toByteArray())
    }
}
