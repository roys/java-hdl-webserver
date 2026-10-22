package com.roysolberg.java.hdlserver;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class PairingTest {

    private Instant now = Instant.parse("2026-10-22T12:00:00Z");
    private final Pairing pairing = new Pairing(() -> now);

    @Test
    void thePhoneWithTheLinksTokenPairsOnce() {
        byte[] token = token(pairing.open());

        byte[] key = pairing.pair(token, "  Pixel 9 ");

        assertEquals(32, key.length);
        assertNull(pairing.window(), "Closed once used");
        assertNull(pairing.pair(token, "Pixel 9"), "Only once");
        assertEquals(List.of("Pixel 9"), pairing.connecting());
        assertTrue(pairing.isAllowed(key));
    }

    @Test
    void aWrongTokenGetsNothingAndLeavesTheWindowOpen() {
        Pairing.Window window = pairing.open();

        assertNull(pairing.pair(new byte[16], "Pixel 9"));

        assertEquals(window.getToken(), pairing.window().getToken());
        assertNotNull(pairing.pair(token(window), "Pixel 9"));
    }

    @Test
    void openingAgainReplacesTheToken() {
        byte[] old = token(pairing.open());
        byte[] newer = token(pairing.open());

        assertNull(pairing.pair(old, "Pixel 9"));
        assertNotNull(pairing.pair(newer, "Pixel 9"));
    }

    @Test
    void nothingPairsWithoutAnOpenWindow() {
        byte[] token = token(pairing.open());
        pairing.cancel();
        assertNull(pairing.window());
        assertNull(pairing.pair(token, "Pixel 9"), "Cancelled");

        token = token(pairing.open());
        now = now.plus(Pairing.WINDOW_DURATION);
        assertNull(pairing.window());
        assertNull(pairing.pair(token, "Pixel 9"), "Expired");
    }

    @Test
    void anInvalidNameIsRefused() {
        byte[] token = token(pairing.open());

        assertThrows(IllegalArgumentException.class, () -> pairing.pair(token, "Pixel‮9"));
        assertNotNull(pairing.window(), "Still open");
    }

    @Test
    void theFirstConnectionTakesTheKeyOnlyWhenThePhoneIsSaved() {
        byte[] key = pairing.pair(token(pairing.open()), "Pixel 9");

        assertNull(pairing.complete(new byte[32], name -> name), "Another key");
        assertNull(pairing.complete(key, name -> null), "Couldn't be saved");
        assertTrue(pairing.isAllowed(key));

        assertEquals("Pixel 9", pairing.complete(key, name -> name));
        assertFalse(pairing.isAllowed(key));
        assertNull(pairing.complete(key, name -> name), "Only once");
    }

    @Test
    void aPairedPhoneHasTenMinutesToConnect() {
        byte[] key = pairing.pair(token(pairing.open()), "Pixel 9");

        now = now.plus(Pairing.ALLOWED_TIMEOUT).minus(Duration.ofSeconds(1));
        assertTrue(pairing.isAllowed(key));
        now = now.plus(Duration.ofSeconds(1));
        assertFalse(pairing.isAllowed(key));
        assertNull(pairing.complete(key, name -> name));
    }

    @Test
    void aPhoneThatForgetsBeforeConnectingIsWithdrawn() {
        byte[] key = pairing.pair(token(pairing.open()), "Pixel 9");

        assertTrue(pairing.withdraw(key));

        assertFalse(pairing.isAllowed(key));
        assertFalse(pairing.withdraw(key));
    }

    @Test
    void anExpiredPairingCantBeWithdrawn() {
        byte[] key = pairing.pair(token(pairing.open()), "Pixel 9");
        now = now.plus(Pairing.ALLOWED_TIMEOUT);

        assertFalse(pairing.withdraw(key), "Already gone, so forget answers 401");
    }

    @Test
    void pairingAgainAndAgainKeepsLittleAndTakesNoKeyBack() {
        List<byte[]> keys = new ArrayList<>();
        for (int i = 0; i < Pairing.MAX_ALLOWED_KEPT; i++) {
            keys.add(pairing.pair(token(pairing.open()), "Phone " + i));
        }

        byte[] token = token(pairing.open());
        assertNull(pairing.pair(token, "One more"), "Too many waiting to connect");
        assertTrue(keys.stream().allMatch(pairing::isAllowed), "Every key given keeps its 10 minutes");
        assertNotNull(pairing.window(), "The link still works once there's room");

        pairing.withdraw(keys.get(0));
        assertNotNull(pairing.pair(token, "One more"));
    }

    @Test
    void phonesPairingAtOnceGetOneKey() throws Exception {
        byte[] token = token(pairing.open());
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<byte[]>> paired = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                paired.add(executor.submit(() -> pairing.pair(token, "Phone")));
            }
            int keys = 0;
            for (Future<byte[]> key : paired) {
                if (key.get() != null) {
                    keys++;
                }
            }
            assertEquals(1, keys);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void eachWindowHasItsOwn128BitToken() {
        String first = pairing.open().getToken();
        String second = pairing.open().getToken();

        assertNotEquals(first, second);
        assertEquals(22, first.length());
        assertEquals(16, Pairing.decodeUrl(first, 16).length);
        assertEquals(now.plus(Pairing.WINDOW_DURATION), pairing.window().getClosesAt());
    }

    @Test
    void theLinkIsWhatTheAppReads() {
        String pin = Pairing.base64Url(new byte[32]);
        String token = Pairing.base64Url(new byte[16]);

        assertEquals("busbridge://pair?v=1&h=192.0.2.10&p=4568&fp=" + pin + "&t=" + token, Pairing.link("192.0.2.10", 4568, pin, token));
        assertEquals("intent://pair?v=1&h=192.0.2.10&p=4568&fp=" + pin + "&t=" + token
                        + "#Intent;scheme=busbridge;package=com.roysolberg.android.smarthome;end",
                Pairing.intentLink("192.0.2.10", 4568, pin, token));
        assertTrue(Pairing.link("2001:db8::1", 4568, pin, token).contains("&h=[2001:db8::1]&"), "IPv6 in brackets");
        assertEquals(43, pin.length());
    }

    @Test
    void onlyExactBase64OfTheRightLengthIsTaken() {
        byte[] bytes = filled(0xFB);
        String standard = Pairing.base64(bytes);
        String url = Pairing.base64Url(bytes);

        assertArrayEquals(bytes, Pairing.decode(standard, 32));
        assertArrayEquals(bytes, Pairing.decodeUrl(url, 32));
        assertNull(Pairing.decode(url, 32), "Not the URL alphabet");
        assertNull(Pairing.decodeUrl(standard, 32), "Not the standard alphabet or padding");
        assertNull(Pairing.decode(standard.replace("=", ""), 32), "No padding");
        assertNull(Pairing.decodeUrl(url + "=", 32), "Padding");
        assertNull(Pairing.decode(" " + standard, 32));
        assertNull(Pairing.decode("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQF=", 32), "Not the canonical form");
        assertNull(Pairing.decode(Pairing.base64(new byte[31]), 32));
        assertNull(Pairing.decodeUrl(Pairing.base64Url(new byte[17]), 16));
        assertNull(Pairing.decode(null, 32));
    }

    @Test
    void namesAreLabelsThatCantHideAnything() {
        assertEquals("Pixel 9", Pairing.validName("  Pixel 9 "));
        assertEquals("Rå telefon", Pairing.validName("Rå telefon"));
        assertNull(Pairing.validName(" "));
        assertNull(Pairing.validName("x".repeat(41)));
        assertEquals("x".repeat(40), Pairing.validName("x".repeat(40)));
        for (String hidden : List.of("Pixel\n9", "Pixel‮9", "Pixel​9", "Pixel\u00009", "Pixel 9")) {
            assertNull(Pairing.validName(hidden), hidden);
        }
    }

    private static byte[] token(Pairing.Window window) {
        return Pairing.decodeUrl(window.getToken(), Pairing.TOKEN_BYTES);
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
