package com.roysolberg.java.hdlserver;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class UpdateCheckTest {

    @ParameterizedTest
    @CsvSource({
            "2.1.0, v2.2.0, 2.2.0",
            "2.1.0, v2.1.1, 2.1.1",
            "2.1.0, v3.0.0, 3.0.0",
            "2.1.9, v2.10.0, 2.10.0",
            "2.1.0, v2.1.0, ",
            "2.1.0, v2.0.5, ",
            "2.1.0, 2.2.0, 2.2.0",
            "2.1.0-SNAPSHOT, v2.2.0, ",
            ", v2.2.0, ",
            "2.1.0, v2.2.0-rc.1, ",
            "2.1.0, '<script>', ",
    })
    void onlyANewerReleaseIsShown(String current, String latestTag, String expected) {
        assertEquals(expected, UpdateCheck.newerVersion(current, latestTag));
    }
}
