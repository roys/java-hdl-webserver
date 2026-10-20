package com.roysolberg.java.hdlserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/**
 * Databases written by released versions, see misc/legacy-db/README.md.
 */
final class LegacyDatabases {

    static final String AUTH_TOKEN = "LEGACYTOKEN0123456789ABCDEF";
    static final int CUSTOM_ACTION_ID = 1;
    static final int DIMMER_ACTION_ID = 2;
    static final int NUM_OF_COMPONENTS = 24;

    private LegacyDatabases() {
    }

    /**
     * Unpacks a legacy database into the directory, since MapDB writes to the file it opens.
     */
    static String copyToDirectory(String version, Path directory) {
        Path file = directory.resolve("hdlserver-" + version + ".db");
        try (InputStream inputStream = new GZIPInputStream(LegacyDatabases.class.getResourceAsStream("/legacy/hdlserver-" + version + ".db.gz"))) {
            Files.copy(inputStream, file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file.toString();
    }

}
