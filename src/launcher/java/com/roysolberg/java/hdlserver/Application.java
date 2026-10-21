package com.roysolberg.java.hdlserver;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Starts the server, after checking that this Java can run it.
 * <p>
 * Built for Java 8, unlike the rest of the server, so the start command earlier versions used
 * ({@code java -cp hdlserver-LATEST.jar com.roysolberg.java.hdlserver.Application}) explains what to do on too old a
 * Java instead of failing with an UnsupportedClassVersionError.
 */
public final class Application {

    static final int REQUIRED_JAVA_VERSION = 17;
    private static final String SERVER_CLASS = "com.roysolberg.java.hdlserver.HdlServer";
    private static final Path LOG_FILE = Paths.get("logs", "hdl-server.log");

    private Application() {
    }

    /**
     * With --version, only checks that this Java can run the server and prints the versions, as update.sh does before
     * updating.
     */
    public static void main(String[] args) throws Throwable {
        String javaVersion = System.getProperty("java.version");
        if (majorVersion(System.getProperty("java.specification.version")) < REQUIRED_JAVA_VERSION) {
            String message = tooOldJavaMessage(version(), javaVersion);
            System.err.println(message);
            appendToLog(message);
            System.exit(1);
        }
        Class<?> server = Class.forName(SERVER_CLASS);
        if (args.length > 0 && "--version".equals(args[0])) {
            System.out.println("HDL Buspro server " + version() + " on Java " + javaVersion);
            return;
        }
        MethodHandles.publicLookup().findStatic(server, "main", MethodType.methodType(void.class, String[].class)).invoke(args);
    }

    /**
     * @return 8 for "1.8", 17 for "17" and so on, or 0 if it can't be read.
     */
    static int majorVersion(String specificationVersion) {
        if (specificationVersion == null) {
            return 0;
        }
        String major = specificationVersion.startsWith("1.") ? specificationVersion.substring(2) : specificationVersion;
        int dot = major.indexOf('.');
        try {
            return Integer.parseInt(dot >= 0 ? major.substring(0, dot) : major);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String tooOldJavaMessage(String version, String javaVersion) {
        return "HDL Buspro server " + version + " needs Java " + REQUIRED_JAVA_VERSION + " or later, but this is Java " + javaVersion + ".\n"
                + " - Install Java " + REQUIRED_JAVA_VERSION + " or later, e.g. on Raspberry Pi OS: sudo apt install openjdk-" + REQUIRED_JAVA_VERSION + "-jre-headless\n"
                + " - Or go back to version 1.1.1, which runs on Java 8: in the folder you cloned the server to, run\n"
                + "   git checkout v1.1.1 -- releases/hdlserver-LATEST.jar\n"
                + "   or download it from https://github.com/roys/java-hdl-webserver/releases/tag/v1.1.1\n"
                + "See https://github.com/roys/java-hdl-webserver#upgrading-to-version-2";
    }

    private static String version() {
        String version = Application.class.getPackage().getImplementationVersion();
        return version != null ? version : "(development build)";
    }

    /**
     * The README tells people to look in this log, so the message is there too, not only where nohup or systemd put it.
     */
    private static void appendToLog(String message) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
        try {
            Files.createDirectories(LOG_FILE.getParent());
            Files.write(LOG_FILE, ("[ERROR] " + timestamp + " " + message + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Couldn't also write this to " + LOG_FILE.toAbsolutePath() + ": " + e);
        }
    }

}
