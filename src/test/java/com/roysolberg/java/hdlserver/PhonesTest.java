package com.roysolberg.java.hdlserver;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PhonesTest {

    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
    private final Map<String, String> map = new HashMap<>();
    private int commits;
    private final Phones phones = new Phones(map, () -> commits++);

    @Test
    void aPhoneIsFoundByItsKeyOnly() {
        byte[] key = key(1);
        Phones.Phone added = phones.add("Pixel 9", key, NOW);
        phones.add("Other", key(2), NOW.plusSeconds(1));

        assertEquals(added.getId(), phones.find(key).getId());
        assertNull(phones.find(key(3)));
        assertEquals("Pixel 9", phones.all().get(0).getName(), "Oldest first");
        assertTrue(commits > 0, "Saved");
    }

    @Test
    void onlyAHashOfTheKeyIsSaved() {
        byte[] key = key(1);
        phones.add("Pixel 9", key, NOW);

        String saved = map.values().iterator().next();
        assertFalse(saved.contains(Pairing.base64(key)), saved);
    }

    @Test
    void lastSeenIsKeptAndRemovedPhonesAreGone() {
        Phones.Phone phone = phones.add("Pixel 9", key(1), NOW);

        phones.seen(phone.getId(), NOW.plusSeconds(60));
        assertEquals(NOW.plusSeconds(60), phones.all().get(0).getLastSeen());
        assertEquals(NOW, phones.all().get(0).getPairedAt());

        assertEquals("Pixel 9", phones.remove(phone.getId()).getName());
        assertNull(phones.find(key(1)));
        assertFalse(phones.contains(phone.getId()));
        assertNull(phones.remove(phone.getId()));
    }

    @Test
    void thereIsALimit() {
        for (int i = 0; i < Phones.MAX_PHONES; i++) {
            assertNotNull(phones.add("Phone " + i, key(i), NOW));
        }

        assertTrue(phones.isFull());
        assertNull(phones.add("One more", key(100), NOW));
    }

    @Test
    void aPhoneThatCantBeReadIsSkipped() {
        phones.add("Pixel 9", key(1), NOW);
        map.put("broken", "{not json");
        map.put("no-hash", "{\"name\":\"x\",\"pairedAt\":0}");

        assertEquals(1, phones.all().size());
        assertNotNull(phones.find(key(1)));
    }

    private static byte[] key(int value) {
        byte[] key = new byte[32];
        key[0] = (byte) value;
        return key;
    }
}
