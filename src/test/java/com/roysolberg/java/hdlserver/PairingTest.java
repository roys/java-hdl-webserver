package com.roysolberg.java.hdlserver;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PairingTest {

    private static final byte[] CERTIFICATE = "not a real certificate".getBytes(StandardCharsets.US_ASCII);
    private Instant now = Instant.parse("2026-10-22T12:00:00Z");
    private final Pairing pairing = new Pairing(CERTIFICATE, () -> now);

    /** A phone's side of pairing. */
    private record Phone(byte[] nonce, Pairing.Asked asked) {
        String requestId() {
            return ((Pairing.Asked.Created) asked).getRequestId();
        }

        byte[] serverNonce() {
            return ((Pairing.Asked.Created) asked).getNonce();
        }

        String code() {
            return Pairing.code(nonce, serverNonce(), CERTIFICATE);
        }
    }

    @Test
    void theCodeIsTheSameAsInThePlan() {
        byte[] phoneNonce = filled(0x01);
        byte[] serverNonce = filled(0x02);

        assertEquals("9A52PAqdQ3N+b/Vp/sNjnYGFD2CgLD5C4TOB90f5pGg=", Pairing.base64(Pairing.commitment(phoneNonce)));
        assertEquals("332809", Pairing.code(phoneNonce, serverNonce, CERTIFICATE), "The hash starts with b10bae89, read unsigned");
        byte[] other = new byte[8];
        Arrays.fill(other, (byte) 0x1F);
        assertEquals("048178", Pairing.code(phoneNonce, serverNonce, other), "Leading zeros are kept");
    }

    @Test
    void onlyExactBase64OfTheRightLengthIsTaken() {
        String valid = Pairing.base64(filled(0x01));

        assertArrayEquals(filled(0x01), Pairing.decode(valid, 32));
        assertNull(Pairing.decode(valid.replace("=", ""), 32), "No padding");
        assertNull(Pairing.decode(" " + valid, 32));
        String withPlusAndSlash = Pairing.base64(filled(0xFB));
        assertArrayEquals(filled(0xFB), Pairing.decode(withPlusAndSlash, 32));
        assertNull(Pairing.decode(withPlusAndSlash.replace('+', '-').replace('/', '_'), 32), "Not the URL alphabet");
        assertNull(Pairing.decode("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQF=", 32), "Not the canonical form");
        assertNull(Pairing.decode(Pairing.base64(new byte[31]), 32));
        assertNull(Pairing.decode(Pairing.base64(new byte[33]), 32));
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

    @Test
    void thePhoneTypedIsAllowedAndGetsItsKey() {
        pairing.open();
        Phone phone = ask("Pixel 9");
        assertEquals(Pairing.Status.WAITING, pairing.reveal(phone.requestId(), phone.nonce()));
        assertEquals(List.of("Pixel 9"), pairing.window().getPhones());
        assertEquals(Pairing.Status.WAITING, pairing.poll(phone.requestId(), phone.nonce()).getStatus());

        Pairing.Typed typed = pairing.type(phone.code().substring(0, 3) + " " + phone.code().substring(3));

        assertEquals("Pixel 9", assertInstanceOf(Pairing.Typed.Allowed.class, typed).getName());
        Pairing.Polled polled = pairing.poll(phone.requestId(), phone.nonce());
        assertEquals(Pairing.Status.ALLOWED, polled.getStatus());
        assertEquals(32, polled.getKey().length);
        assertArrayEquals(polled.getKey(), pairing.poll(phone.requestId(), phone.nonce()).getKey(), "The same key again");
        assertNull(pairing.window(), "Closed after one phone is allowed");
    }

    @Test
    void nothingCanBeAskedWithoutAnOpenWindow() {
        assertSame(Pairing.Asked.Closed.INSTANCE, ask("Pixel 9").asked());
        assertSame(Pairing.Typed.Closed.INSTANCE, pairing.type("123456"));

        pairing.open();
        now = now.plus(Pairing.WINDOW_DURATION);

        assertNull(pairing.window());
        assertSame(Pairing.Asked.Closed.INSTANCE, ask("Pixel 9").asked());
    }

    @Test
    void aWindowTakesThreeRequestsWhateverHappensToThem() {
        pairing.open();
        Phone dropped = ask("One");
        pairing.reveal(dropped.requestId(), filled(0x09)); // Not what it committed to
        Phone expired = ask("Two");
        now = now.plus(Pairing.REVEAL_TIMEOUT);
        ask("Three");

        assertSame(Pairing.Asked.Full.INSTANCE, ask("Four").asked());
        assertEquals(0, pairing.window().getRequestsLeft());
        assertEquals(Pairing.Status.EXPIRED, pairing.reveal(expired.requestId(), expired.nonce()), "Too late");
    }

    @Test
    void aCommitmentIsTakenOnlyOnce() {
        pairing.open();
        byte[] nonce = filled(0x05);
        ask("Pixel 9", nonce);
        pairing.cancel();
        pairing.open();

        assertSame(Pairing.Asked.Full.INSTANCE, ask("Pixel 9", nonce).asked());
    }

    @Test
    void theRevealMustMatchTheCommitmentAndStaysTheSame() {
        pairing.open();
        Phone phone = ask("Pixel 9");

        assertEquals(Pairing.Status.WAITING, pairing.reveal(phone.requestId(), phone.nonce()));
        assertEquals(Pairing.Status.WAITING, pairing.reveal(phone.requestId(), phone.nonce()), "Sent again");
        assertEquals(Pairing.Status.EXPIRED, pairing.reveal(phone.requestId(), filled(0x09)), "Another value drops it");
        assertEquals(Pairing.Status.EXPIRED, pairing.poll(phone.requestId(), phone.nonce()).getStatus());
        assertEquals(Pairing.Status.EXPIRED, pairing.reveal("unknown", phone.nonce()));
    }

    @Test
    void theRequestIdAloneGetsNothing() {
        pairing.open();
        Phone phone = ask("Pixel 9");
        pairing.reveal(phone.requestId(), phone.nonce());
        pairing.type(phone.code());

        Pairing.Polled polled = pairing.poll(phone.requestId(), filled(0x09));

        assertEquals(Pairing.Status.EXPIRED, polled.getStatus());
        assertNull(polled.getKey());
    }

    @Test
    void aPhoneIsListedAndAllowedOnlyAfterItsReveal() {
        pairing.open();
        Phone phone = ask("Pixel 9");

        assertEquals(List.of(), pairing.window().getPhones());
        assertSame(Pairing.Typed.WrongCode.INSTANCE, pairing.type(phone.code()));
    }

    @Test
    void aWrongCodeClosesTheWindowAndDeniesEveryone() {
        pairing.open();
        Phone phone = ask("Pixel 9");
        pairing.reveal(phone.requestId(), phone.nonce());
        String wrong = phone.code().equals("000000") ? "000001" : "000000";

        assertNull(pairing.type("12345"), "Not 6 digits, so no try");
        assertNull(pairing.type("12345a"));
        assertNotNull(pairing.window());
        assertSame(Pairing.Typed.WrongCode.INSTANCE, pairing.type(wrong));

        assertNull(pairing.window());
        assertEquals(Pairing.Status.DENIED, pairing.poll(phone.requestId(), phone.nonce()).getStatus());
        assertSame(Pairing.Typed.Closed.INSTANCE, pairing.type(phone.code()), "Not even the right code now");
    }

    @Test
    void allowingOneDeniesTheOthers() {
        pairing.open();
        Phone mine = ask("Pixel 9");
        Phone other = ask("Pixel 9");
        pairing.reveal(mine.requestId(), mine.nonce());
        pairing.reveal(other.requestId(), other.nonce());

        assertInstanceOf(Pairing.Typed.Allowed.class, pairing.type(mine.code()));

        assertEquals(Pairing.Status.ALLOWED, pairing.poll(mine.requestId(), mine.nonce()).getStatus());
        assertEquals(Pairing.Status.DENIED, pairing.poll(other.requestId(), other.nonce()).getStatus());
    }

    @Test
    void cancelDeniesEveryoneWaiting() {
        pairing.open();
        Phone phone = ask("Pixel 9");
        pairing.reveal(phone.requestId(), phone.nonce());

        pairing.cancel();

        assertNull(pairing.window());
        assertEquals(Pairing.Status.DENIED, pairing.poll(phone.requestId(), phone.nonce()).getStatus());
        assertEquals(Pairing.Status.DENIED, pairing.reveal(phone.requestId(), phone.nonce()));
    }

    @Test
    void everythingExpires() {
        pairing.open();
        Phone notTyped = ask("Pixel 9");
        pairing.reveal(notTyped.requestId(), notTyped.nonce());
        now = now.plus(Pairing.CODE_TIMEOUT);
        assertEquals(Pairing.Status.EXPIRED, pairing.poll(notTyped.requestId(), notTyped.nonce()).getStatus());

        Phone allowed = ask("Pixel 9");
        pairing.reveal(allowed.requestId(), allowed.nonce());
        pairing.type(allowed.code());
        now = now.plus(Pairing.ALLOWED_TIMEOUT).minus(Duration.ofSeconds(1));
        assertEquals(Pairing.Status.ALLOWED, pairing.poll(allowed.requestId(), allowed.nonce()).getStatus(), "Outlives the window");
        now = now.plus(Duration.ofSeconds(1));
        Pairing.Polled polled = pairing.poll(allowed.requestId(), allowed.nonce());
        assertEquals(Pairing.Status.EXPIRED, polled.getStatus());
        assertNull(polled.getKey());
    }

    @Test
    void theWindowRunningOutForgetsThoseWaiting() {
        pairing.open();
        now = now.plus(Duration.ofMinutes(4));
        pairing.open(); // Already open, so it isn't made longer
        Phone phone = ask("Pixel 9");
        pairing.reveal(phone.requestId(), phone.nonce());

        now = now.plus(Duration.ofMinutes(1));

        assertNull(pairing.window());
        assertEquals(Pairing.Status.EXPIRED, pairing.poll(phone.requestId(), phone.nonce()).getStatus());
    }

    @Test
    void openingAgainAndAgainKeepsLittle() {
        List<Phone> allowed = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            pairing.open();
            Phone phone = ask("Phone " + i);
            ask("Other " + i);
            pairing.reveal(phone.requestId(), phone.nonce());
            pairing.type(phone.code());
            allowed.add(phone);
        }
        pairing.open();
        Phone denied = ask("Denied");
        pairing.cancel();
        pairing.open();

        long kept = allowed.stream().filter(phone -> pairing.poll(phone.requestId(), phone.nonce()).getStatus() == Pairing.Status.ALLOWED).count();
        assertEquals(3, kept, "Only the latest allowed");
        assertEquals(Pairing.Status.ALLOWED, pairing.poll(allowed.get(99).requestId(), allowed.get(99).nonce()).getStatus());
        assertEquals(Pairing.Status.EXPIRED, pairing.poll(denied.requestId(), denied.nonce()).getStatus(), "Forgotten");
    }

    @Test
    void phonesAskingAtOnceGetNoMoreThanThree() throws Exception {
        pairing.open();
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<Phone>> asked = new java.util.ArrayList<>();
            for (int i = 0; i < 20; i++) {
                asked.add(executor.submit(() -> ask("Phone")));
            }
            int created = 0;
            for (java.util.concurrent.Future<Phone> phone : asked) {
                if (phone.get().asked() instanceof Pairing.Asked.Created) {
                    created++;
                }
            }
            assertEquals(Pairing.MAX_REQUESTS, created);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void eachRequestGetsItsOwnIdAndNonce() {
        pairing.open();
        Phone first = ask("One");
        Phone second = ask("Two");

        assertNotEquals(first.requestId(), second.requestId());
        assertFalse(Arrays.equals(first.serverNonce(), second.serverNonce()));
        assertEquals(32, first.serverNonce().length);
        assertEquals(16, Pairing.decode(first.requestId(), 16).length, "128 bits");
    }

    private Phone ask(String name) {
        byte[] nonce = new byte[32];
        new java.security.SecureRandom().nextBytes(nonce);
        return ask(name, nonce);
    }

    private Phone ask(String name, byte[] nonce) {
        return new Phone(nonce, pairing.ask(name, Pairing.commitment(nonce)));
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
