package com.roysolberg.java.hdlserver;

import com.roysolberg.java.hdlserver.service.HdlService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mapdb.DB;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AppPortSettingTest {

    @TempDir
    Path directory;

    @Test
    @SuppressWarnings("unchecked")
    void theAppPortStaysClosedWhileOthersCanReadTheDatabase() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "No POSIX file permissions");
        Path file = directory.resolve(HdlServer.DATABASE_FILE);
        DB database = HdlServer.openDatabase(file.toString());
        try {
            Map<String, String> config = (Map<String, String>) (Map<?, ?>) database.hashMap("config").createOrOpen();
            config.put(HdlServer.APP_PORT_ON_KEY, "true");
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

            HdlServer readableByOthers = new HdlServer(database, new HdlService(database), file).start(0, 0);
            try {
                assertNull(readableByOthers.appPort());
                assertFalse(config.containsKey(AppCertificate.PRIVATE_KEY_KEY), "The private key isn't written there");
            } finally {
                readableByOthers.stop();
            }

            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            HdlServer ownerOnly = new HdlServer(database, new HdlService(database), file).start(0, 0);
            try {
                assertNotNull(ownerOnly.appPort());
                assertTrue(config.containsKey(AppCertificate.PRIVATE_KEY_KEY));
            } finally {
                ownerOnly.stop();
            }
        } finally {
            database.close();
        }
    }

    @Test
    void ownerOnlyMeansNoOneElseCanReadOrWrite() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "No POSIX file permissions");
        Path file = Files.createFile(directory.resolve("file"));

        for (String permissions : new String[]{"rw-------", "r--------", "-w-------"}) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions));
            assertTrue(HdlServer.isOwnerOnly(file), permissions);
        }
        for (String permissions : new String[]{"rw-r-----", "rw----r--", "rw--w----", "rwx------"}) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions));
            assertFalse(HdlServer.isOwnerOnly(file), permissions);
        }
        assertFalse(HdlServer.isOwnerOnly(directory.resolve("missing")));
    }
}
