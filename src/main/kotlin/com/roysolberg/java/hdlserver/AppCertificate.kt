package com.roysolberg.java.hdlserver

import org.apache.logging.log4j.LogManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.HexFormat

/**
 * The certificate the app port shows. No certificate authority vouches for a server at home, whose address changes, so
 * Busbridge makes its own on first use and keeps it. The pairing link carries its [pin], and the app trusts nothing else.
 */
class AppCertificate private constructor(val certificate: X509Certificate, private val privateKey: PrivateKey) {

    private val hash = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)

    /** SHA-256 of the certificate, e.g. "3F:A2:…", as the Settings page shows it. */
    val fingerprint: String = HexFormat.ofDelimiter(":").withUpperCase().formatHex(hash)

    /** SHA-256 of the certificate as base64url without padding, as the pairing link carries it for the app to pin. */
    val pin: String = Pairing.base64Url(hash)

    /** The certificate and its key for the HTTPS server, protected by [password], which is only used in memory. */
    fun keyStore(password: CharArray): KeyStore = KeyStore.getInstance("PKCS12").apply {
        load(null, null)
        setKeyEntry(ALIAS, privateKey, password, arrayOf(certificate))
    }

    companion object {
        const val CERTIFICATE_KEY = "appCertificate"
        const val PRIVATE_KEY_KEY = "appPrivateKey"
        const val ALIAS = "busbridge"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private val VALIDITY = Duration.ofDays(30 * 365) // The app pins the certificate itself, so it needn't expire soon
        private val logger = LogManager.getLogger(AppCertificate::class.java)

        /**
         * The certificate kept in [config], or a new one, which is kept there. One that can't be read is replaced, and
         * paired phones will then refuse to connect until they're paired again.
         */
        @JvmStatic
        fun loadOrCreate(config: MutableMap<String, String>, commit: Runnable): AppCertificate {
            val stored = config[CERTIFICATE_KEY]?.let { certificate ->
                config[PRIVATE_KEY_KEY]?.let { privateKey ->
                    try {
                        load(certificate, privateKey)
                    } catch (e: GeneralSecurityException) {
                        logger.error("The app port's certificate can't be read, so it's replaced by a new one.", e)
                        null
                    } catch (e: IllegalArgumentException) {
                        logger.error("The app port's certificate can't be read, so it's replaced by a new one.", e)
                        null
                    }
                }
            }
            if (stored != null) {
                return stored
            }
            return create().also { created ->
                config[CERTIFICATE_KEY] = Base64.getEncoder().encodeToString(created.certificate.encoded)
                config[PRIVATE_KEY_KEY] = Base64.getEncoder().encodeToString(created.privateKey.encoded)
                commit.run()
                logger.info("Made a certificate for the app port, with the fingerprint {}.", created.fingerprint)
            }
        }

        /** A new key pair on the P-256 curve, which every Android version supports, and a certificate signed by itself. */
        @JvmStatic
        fun create(): AppCertificate {
            val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val name = X500Name("CN=Busbridge for HDL Buspro")
            val now = Instant.now()
            val builder = JcaX509v3CertificateBuilder(
                name,
                BigInteger(127, SecureRandom()).add(BigInteger.ONE), // Positive and unique
                Date.from(now.minus(Duration.ofDays(1))), // In case a phone's clock is a little behind
                Date.from(now.plus(VALIDITY)),
                name,
                keyPair.public,
            )
                .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
                .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
                .addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
            val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(keyPair.private)
            val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            return AppCertificate(certificate, keyPair.private)
        }

        private fun load(certificateBase64: String, privateKeyBase64: String): AppCertificate {
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(Base64.getDecoder().decode(certificateBase64).inputStream()) as X509Certificate
            val privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64)))
            if (!belongTogether(certificate, privateKey)) {
                throw GeneralSecurityException("The private key doesn't belong to the certificate")
            }
            return AppCertificate(certificate, privateKey)
        }

        private fun belongTogether(certificate: X509Certificate, privateKey: PrivateKey): Boolean {
            val data = "busbridge".toByteArray()
            val signature = Signature.getInstance(SIGNATURE_ALGORITHM).apply {
                initSign(privateKey)
                update(data)
            }.sign()
            return Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(certificate.publicKey)
                update(data)
                verify(signature)
            }
        }
    }
}
