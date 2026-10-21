package com.roysolberg.java.hdlserver;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BusStatsTest {

    private Instant now = Instant.parse("2026-10-21T20:00:30Z");
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };

    @Test
    void countsMessagesPerMinuteForTheLastHour() {
        BusStats busStats = new BusStats(clock);
        busStats.record();
        busStats.record();
        now = now.plus(Duration.ofMinutes(2));
        busStats.record();

        List<Integer> perMinute = busStats.messagesPerMinute();
        assertEquals(60, perMinute.size());
        assertEquals(1, perMinute.get(59), "This minute");
        assertEquals(0, perMinute.get(58));
        assertEquals(2, perMinute.get(57), "Two minutes ago");
        assertEquals(3, busStats.getTotalMessages());
        assertEquals(now, busStats.getLastMessage());
    }

    @Test
    void forgetsMinutesOlderThanAnHour() {
        BusStats busStats = new BusStats(clock);
        busStats.record();
        now = now.plus(Duration.ofMinutes(60));

        assertEquals(0, busStats.messagesPerMinute().stream().mapToInt(Integer::intValue).sum());
        assertEquals(1, busStats.getTotalMessages());
    }

    @Test
    void nothingReceivedYet() {
        BusStats busStats = new BusStats(clock);

        assertNull(busStats.getLastMessage());
        assertEquals(60, busStats.messagesPerMinute().size());
    }

}
