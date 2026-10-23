package com.dataproxy.rotation

import android.content.Context
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

data class AdbKey(val privateKey: PrivateKey, val certificate: Certificate)

/**
 * Persistent ADB client identity. The key is generated once, then reused for
 * every pairing and connection so Wireless debugging keeps recognizing
 * DataProxy across restarts.
 */
class AdbKeyStore(private val context: Context) {

    @Synchronized
    fun ensure(): AdbKey {
        load()?.let { return it }
        val key = generate()
        save(key)
        return key
    }

    private fun load(): AdbKey? {
        return runCatching {
            val keyBytes = keyFile.readBytes()
            val certBytes = certFile.readBytes()
            val privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(keyBytes))
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certBytes))
            if (!matches(privateKey, certificate)) return null
            AdbKey(privateKey, certificate)
        }.onFailure {
            if (keyFile.exists() || certFile.exists()) {
                Log.w(TAG, "Could not load ADB key: ${it.message}")
            }
        }.getOrNull()
    }

    private fun matches(privateKey: PrivateKey, certificate: Certificate): Boolean {
        val rsaPrivate = privateKey as? java.security.interfaces.RSAPrivateKey ?: return false
        val rsaPublic = certificate.publicKey as? java.security.interfaces.RSAPublicKey
            ?: return false
        return rsaPrivate.modulus == rsaPublic.modulus
    }

    private fun generate(): AdbKey {
        val generator = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048, SecureRandom())
        }
        val pair = generator.generateKeyPair()
        val now = System.currentTimeMillis()
        val notBefore = Date(now - TimeUnit.DAYS.toMillis(1))
        val notAfter = Date(now + TimeUnit.DAYS.toMillis(3650))
        val subject = X500Name("CN=DataProxy")
        val serial = BigInteger(64, SecureRandom()).abs()
        val builder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            notBefore,
            notAfter,
            subject,
            pair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(pair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        return AdbKey(pair.private, certificate)
    }

    private fun save(key: AdbKey) {
        keyFile.writeBytes(key.privateKey.encoded)
        certFile.writeBytes(key.certificate.encoded)
    }

    private val keyFile: File get() = File(context.filesDir, KEY_FILE)
    private val certFile: File get() = File(context.filesDir, CERT_FILE)

    companion object {
        private const val TAG = "AdbKeyStore"
        private const val KEY_FILE = "rotation_adb_key.der"
        private const val CERT_FILE = "rotation_adb_cert.der"
    }
}
