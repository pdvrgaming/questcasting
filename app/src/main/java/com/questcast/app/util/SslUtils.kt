package com.questcast.app.util

import com.questcast.app.util.AppLogger as Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

object SslUtils {
    private const val TAG = "QuestCast-SSL"
    private const val KEY_PASSWORD = "questcast_secure_key"

    @Volatile
    private var cachedSslContext: SSLContext? = null

    /**
     * Returns an SSLContext backed by an in-memory self-signed X.509 certificate.
     * The certificate is generated at runtime on the device, enabling 100% offline HTTPS and WSS
     * without external dependencies or root CA requirements.
     */
    @Synchronized
    fun getOrCreateSslContext(additionalIp: String? = null): SSLContext {
        cachedSslContext?.let { return it }

        try {
            Log.i(TAG, "QuestCast: Generating self-signed RSA keypair and X.509 certificate for HTTPS/WSS...")
            // 1. Generate RSA 2048-bit KeyPair
            val keyGen = KeyPairGenerator.getInstance("RSA")
            keyGen.initialize(2048, SecureRandom())
            val keyPair = keyGen.generateKeyPair()

            // 2. Generate self-signed X.509 certificate using BouncyCastle
            val cert = generateCertificate(keyPair, additionalIp)

            // 3. Store in in-memory PKCS12 KeyStore
            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(null, null)
            keyStore.setKeyEntry("questcast_ssl", keyPair.private, KEY_PASSWORD.toCharArray(), arrayOf(cert))

            // 4. Create and initialize KeyManagerFactory
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, KEY_PASSWORD.toCharArray())

            // 5. Initialize TLS SSLContext
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(kmf.keyManagers, null, SecureRandom())

            cachedSslContext = sslContext
            Log.i(TAG, "QuestCast: SSLContext generated successfully for HTTPS/WSS")
            return sslContext
        } catch (e: Exception) {
            Log.e(TAG, "QuestCast: Failed to generate SSLContext", e)
            throw e
        }
    }

    private fun generateCertificate(keyPair: KeyPair, additionalIp: String?): java.security.cert.X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24 * 60 * 60 * 1000L) // Yesterday to account for clock skew
        val notAfter = Date(now + 10L * 365 * 24 * 60 * 60 * 1000L) // 10 years validity

        val subject = X500Name("CN=QuestCast, O=QuestCast Intercom, OU=Meta Quest Low-Latency Receiver")
        val serial = BigInteger(64, SecureRandom())

        val certBuilder = JcaX509v3CertificateBuilder(
            subject, // Issuer (self-signed)
            serial,
            notBefore,
            notAfter,
            subject, // Subject
            keyPair.public
        )

        // Add Subject Alternative Names (SAN) for localhost and local IPs
        val generalNamesList = mutableListOf<GeneralName>()
        generalNamesList.add(GeneralName(GeneralName.dNSName, "localhost"))
        generalNamesList.add(GeneralName(GeneralName.dNSName, "questcast.local"))
        generalNamesList.add(GeneralName(GeneralName.dNSName, "quest.local"))
        generalNamesList.add(GeneralName(GeneralName.iPAddress, "127.0.0.1"))
        generalNamesList.add(GeneralName(GeneralName.iPAddress, "0.0.0.0"))

        if (!additionalIp.isNullOrBlank() && additionalIp != "127.0.0.1" && additionalIp != "0.0.0.0") {
            try {
                generalNamesList.add(GeneralName(GeneralName.iPAddress, additionalIp))
            } catch (e: Exception) {
                Log.w(TAG, "Could not add additional IP $additionalIp to SAN: ${e.message}")
            }
        }

        val san = GeneralNames(generalNamesList.toTypedArray())
        certBuilder.addExtension(Extension.subjectAlternativeName, false, san)

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certHolder = certBuilder.build(signer)
        return JcaX509CertificateConverter().getCertificate(certHolder)
    }
}
