package com.roysolberg.java.hdlserver;

import org.junit.jupiter.api.Test;

import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AppCertificateTest {

    @Test
    void aNewCertificateIsKeptAndLoadedAgain() {
        Map<String, String> config = new HashMap<>();
        AtomicInteger commits = new AtomicInteger();

        AppCertificate created = AppCertificate.loadOrCreate(config, commits::incrementAndGet);
        AppCertificate loaded = AppCertificate.loadOrCreate(config, commits::incrementAndGet);

        assertEquals(created.getFingerprint(), loaded.getFingerprint());
        assertEquals(1, commits.get(), "Only the new one is written");
        assertTrue(created.getFingerprint().matches("([0-9A-F]{2}:){31}[0-9A-F]{2}"), created.getFingerprint());
    }

    @Test
    void itsAServerCertificateSignedByItself() throws Exception {
        X509Certificate certificate = AppCertificate.create().getCertificate();

        certificate.verify(certificate.getPublicKey());
        certificate.checkValidity();
        assertEquals("EC", certificate.getPublicKey().getAlgorithm());
        assertEquals(-1, certificate.getBasicConstraints(), "Not a certificate authority");
        assertEquals(List.of("1.3.6.1.5.5.7.3.1"), certificate.getExtendedKeyUsage(), "For servers");
    }

    @Test
    void aCertificateThatCantBeReadIsReplaced() {
        Map<String, String> config = new HashMap<>();
        String original = AppCertificate.loadOrCreate(config, () -> {
        }).getFingerprint();
        config.put(AppCertificate.CERTIFICATE_KEY, "not base64!");

        String replaced = AppCertificate.loadOrCreate(config, () -> {
        }).getFingerprint();

        assertNotEquals(original, replaced);
        assertEquals(replaced, AppCertificate.loadOrCreate(config, () -> {
        }).getFingerprint(), "The new one is kept");
    }

    @Test
    void aKeyThatDoesntBelongToTheCertificateIsntUsed() {
        Map<String, String> config = new HashMap<>();
        String original = AppCertificate.loadOrCreate(config, () -> {
        }).getFingerprint();
        Map<String, String> other = new HashMap<>();
        AppCertificate.loadOrCreate(other, () -> {
        });
        config.put(AppCertificate.PRIVATE_KEY_KEY, other.get(AppCertificate.PRIVATE_KEY_KEY));

        assertNotEquals(original, AppCertificate.loadOrCreate(config, () -> {
        }).getFingerprint());
    }
}
