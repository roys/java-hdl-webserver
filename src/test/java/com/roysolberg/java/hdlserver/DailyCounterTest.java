package com.roysolberg.java.hdlserver;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class DailyCounterTest {

    private Instant now = Instant.parse("2026-10-21T23:59:00Z");
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };

    @Test
    void startsAgainAtMidnight() {
        DailyCounter counter = new DailyCounter(clock);
        counter.increment();
        counter.increment();
        assertEquals(2, counter.today());

        now = now.plusSeconds(120);

        assertEquals(0, counter.today());
        counter.increment();
        assertEquals(1, counter.today());
    }

}
