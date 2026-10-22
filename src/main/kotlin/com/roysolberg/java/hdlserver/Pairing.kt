package com.roysolberg.java.hdlserver

import org.apache.logging.log4j.LogManager
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.time.InstantSource
import java.util.Base64

/**
 * Pairing the Android app with Busbridge by a link, which Settings shows as a QR code. The link carries the app port's
 * address, its certificate's fingerprint and a one-time token, so the app trusts only that certificate from its first
 * connection, and only a phone given the link can pair. No protocol of its own: TLS, the pinned certificate and a
 * random token.
 *
 * It doesn't protect against anyone who can use the pages, which have no login, or read them on the way, since they're
 * plain HTTP: they can get a link too.
 *
 * One lock keeps every change in order.
 */
class Pairing @JvmOverloads constructor(
    private val clock: InstantSource = InstantSource.system(),
    private val random: SecureRandom = SecureRandom(),
) {

    /** The open window's link token, which Settings shows. */
    class Window(val token: String, val closesAt: Instant)

    private class Allowed(val name: String, val key: ByteArray, val endsAt: Instant)

    private var token: ByteArray? = null
    private var closesAt: Instant? = null
    /** Phones that have paired but not yet connected to the relay, oldest first. */
    private val allowed = ArrayList<Allowed>()

    /** Opens the window with a new token. One that was open before stops working. */
    @Synchronized
    fun open(): Window {
        val newToken = randomBytes(TOKEN_BYTES)
        val newClosesAt = clock.instant() + WINDOW_DURATION
        token = newToken
        closesAt = newClosesAt
        logger.info("The pairing window is open.")
        return Window(base64Url(newToken), newClosesAt)
    }

    @Synchronized
    fun cancel() {
        expire()
        if (token != null) {
            token = null
            closesAt = null
            logger.info("The pairing window was closed on Settings.")
        }
    }

    /** The open window, or null. */
    @Synchronized
    fun window(): Window? {
        expire()
        val token = token ?: return null
        return Window(base64Url(token), checkNotNull(closesAt))
    }

    /** The names of phones that have paired but not yet connected. */
    @Synchronized
    fun connecting(): List<String> {
        expire()
        return allowed.map { it.name }
    }

    /**
     * A phone pairs with the link's token. That uses the token up and closes the window. A wrong token doesn't: at 128
     * bits it can't be guessed.
     *
     * @return the phone's new key, which it uses for the relay, or null if no window is open or the token isn't its
     * @throws IllegalArgumentException if the name isn't valid
     */
    @Synchronized
    fun pair(token: ByteArray, name: String): ByteArray? {
        val label = validName(name) ?: throw IllegalArgumentException("Not a valid name")
        expire()
        val open = this.token
        if (open == null || !MessageDigest.isEqual(open, token)) {
            logger.info("A phone calling itself [{}] tried to pair with a token that isn't the open one.", label)
            return null
        }
        this.token = null
        closesAt = null
        val key = randomBytes(KEY_BYTES)
        allowed.add(Allowed(label, key, clock.instant() + ALLOWED_TIMEOUT))
        // Normally one; anyone who can use the pages can pair, so not without end
        while (allowed.size > MAX_ALLOWED_KEPT) {
            allowed.removeAt(0)
        }
        logger.info("The phone calling itself [{}] paired with the link.", label)
        return key.copyOf()
    }

    /** Whether a phone that has paired but not yet connected has this key. */
    @Synchronized
    fun isAllowed(key: ByteArray): Boolean {
        expire()
        return allowed.any { MessageDigest.isEqual(it.key, key) }
    }

    /**
     * The phone that paired with this key connects for the first time, which completes its pairing: [add] saves the
     * phone, and the key is no longer kept here. If [add] can't, nothing changes.
     *
     * @return what [add] returned, or null if no phone waiting to connect has this key
     */
    @Synchronized
    fun <T : Any> complete(key: ByteArray, add: (name: String) -> T?): T? {
        expire()
        val waiting = allowed.firstOrNull { MessageDigest.isEqual(it.key, key) } ?: return null
        return add(waiting.name)?.also { allowed.remove(waiting) }
    }

    /** Forgets a phone that has paired but not yet connected, when the app forgets it first. */
    @Synchronized
    fun withdraw(key: ByteArray): Boolean = allowed.removeIf { MessageDigest.isEqual(it.key, key) }

    /** Forgets what has run out. */
    private fun expire() {
        val now = clock.instant()
        closesAt?.let {
            if (now >= it) {
                token = null
                closesAt = null
                logger.info("The pairing window closed after {} minutes.", WINDOW_DURATION.toMinutes())
            }
        }
        allowed.removeIf { now >= it.endsAt }
    }

    private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)

    companion object {
        const val TOKEN_BYTES = 16
        const val KEY_BYTES = 32
        const val MAX_NAME_LENGTH = 40
        /** The Android app, which opens the pairing link. */
        const val APP_PACKAGE = "com.roysolberg.android.smarthome"

        @JvmField
        val WINDOW_DURATION: Duration = Duration.ofMinutes(5)

        @JvmField
        val ALLOWED_TIMEOUT: Duration = Duration.ofMinutes(10)
        private const val MAX_ALLOWED_KEPT = 3
        private val logger = LogManager.getLogger(Pairing::class.java)

        /**
         * The pairing link: `busbridge://pair?v=1&h=<host>&p=<port>&fp=<fingerprint>&t=<token>`, with the fingerprint
         * and token as base64url without padding.
         *
         * @param host the app port's address on the home network
         * @param pin SHA-256 of the app port certificate's DER, see [AppCertificate.pin]
         */
        @JvmStatic
        fun link(host: String, port: Int, pin: String, token: String): String = "busbridge://pair?${query(host, port, pin, token)}"

        /** The same link for Chrome on Android, which opens only the app, or Google Play if it's missing. */
        @JvmStatic
        fun intentLink(host: String, port: Int, pin: String, token: String): String =
            "intent://pair?${query(host, port, pin, token)}#Intent;scheme=busbridge;package=$APP_PACKAGE;end"

        private fun query(host: String, port: Int, pin: String, token: String): String {
            val bracketed = if (host.contains(':')) "[$host]" else host
            return "v=1&h=$bracketed&p=$port&fp=$pin&t=$token"
        }

        /**
         * The name trimmed, or null if it's empty, too long, or has control or invisible formatting characters, which
         * could make one name look like another.
         */
        @JvmStatic
        fun validName(name: String): String? {
            val trimmed = Normalizer.normalize(name, Normalizer.Form.NFC).trim()
            val length = trimmed.codePointCount(0, trimmed.length)
            if (length !in 1..MAX_NAME_LENGTH) return null
            return trimmed.takeIf { it.codePoints().allMatch { codePoint -> Character.getType(codePoint).toByte() !in HIDDEN_TYPES } }
        }

        /**
         * Exactly [size] bytes from standard Base64 with padding, or null for anything else, also for Base64 that
         * wouldn't encode back to the same text.
         */
        @JvmStatic
        fun decode(base64: String?, size: Int): ByteArray? = decode(base64, size, Base64.getDecoder(), ::base64)

        /** As [decode], from base64url without padding, as in the pairing link. */
        @JvmStatic
        fun decodeUrl(base64Url: String?, size: Int): ByteArray? = decode(base64Url, size, Base64.getUrlDecoder(), ::base64Url)

        @JvmStatic
        fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        @JvmStatic
        fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        private fun decode(text: String?, size: Int, decoder: Base64.Decoder, encode: (ByteArray) -> String): ByteArray? {
            if (text == null) return null
            val bytes = try {
                decoder.decode(text)
            } catch (e: IllegalArgumentException) {
                return null
            }
            return bytes.takeIf { it.size == size && encode(it) == text }
        }

        private val HIDDEN_TYPES = setOf(
            Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.SURROGATE, Character.UNASSIGNED,
            Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
        )
    }
}
