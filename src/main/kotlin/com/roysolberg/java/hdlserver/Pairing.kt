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
 * Pairing the Android app with Busbridge. The user opens a short window on Settings, the app asks to pair on the app
 * port, and the user types the code the app shows into Settings. Both sides work the code out from the app port's
 * certificate and a random value from each, see [code].
 *
 * It protects against someone listening, someone stepping into the phone's connection only, guessing the code, and
 * web pages elsewhere. Not against someone on the network who can use the pages or change them, which are plain HTTP
 * and show the API key anyway.
 *
 * One lock keeps every change in order, so two calls at the same time can't both take the window's last request.
 *
 * @param certificate the DER bytes of the app port's certificate, which the phone saw
 */
class Pairing @JvmOverloads constructor(
    private val certificate: ByteArray,
    private val clock: InstantSource = InstantSource.system(),
    private val random: SecureRandom = SecureRandom(),
) {

    enum class Status(val json: String) {
        WAITING("waiting"),
        ALLOWED("allowed"),
        DENIED("denied"),
        EXPIRED("expired"),
    }

    /** The answer to a phone asking to pair. */
    sealed interface Asked {
        class Created(val requestId: String, val nonce: ByteArray) : Asked

        /** No window is open. */
        data object Closed : Asked

        /** The window's requests are used up, or the commitment was used before. */
        data object Full : Asked
    }

    /** What typing a code on Settings did. */
    sealed interface Typed {
        class Allowed(val name: String) : Typed

        /** It matched no phone, or more than one; the window is closed either way. */
        data object WrongCode : Typed

        /** No window is open. */
        data object Closed : Typed
    }

    /** What Settings shows of an open window. */
    class Window(val closesAt: Instant, val phones: List<String>, val requestsLeft: Int)

    /** The answer to a poll. [key] is set only when [status] is [Status.ALLOWED]. */
    class Polled(val status: Status, val key: ByteArray? = null)

    private class Request(val id: String, val name: String, val commitment: ByteArray, val nonce: ByteArray, val createdAt: Instant) {
        var status = Status.WAITING
        var revealed: ByteArray? = null
        var code: String? = null
        var key: ByteArray? = null
        /** When it expires, or is forgotten once denied. */
        var endsAt: Instant = createdAt + REVEAL_TIMEOUT
    }

    private var windowClosesAt: Instant? = null
    /** Only ever counts up, also when a request is dropped or expires, so no one gets more. */
    private var requestsInWindow = 0
    private val requests = LinkedHashMap<String, Request>()
    /** Never accepted twice, so someone can't keep trying with another phone's commitment. */
    private val usedCommitments = LinkedHashSet<String>()

    /**
     * Opens the window, or keeps the one that's open as it is. What earlier windows denied is forgotten, and of the
     * phones allowed but not yet connected only the latest few are kept, so opening again and again can't fill memory.
     */
    @Synchronized
    fun open() {
        expire()
        if (windowClosesAt != null) return
        requests.values.removeIf { it.status == Status.DENIED }
        val allowed = requests.values.filter { it.status == Status.ALLOWED }
        allowed.take((allowed.size - MAX_ALLOWED_KEPT).coerceAtLeast(0)).forEach { requests.remove(it.id) }
        windowClosesAt = clock.instant() + WINDOW_DURATION
        requestsInWindow = 0
        logger.info("The pairing window is open.")
    }

    /** Closes the window and denies every phone in it that isn't allowed. */
    @Synchronized
    fun cancel() {
        expire()
        if (windowClosesAt != null) {
            closeWindow()
            logger.info("The pairing window was closed on Settings.")
        }
    }

    /** The open window, or null. */
    @Synchronized
    fun window(): Window? {
        expire()
        val closesAt = windowClosesAt ?: return null
        val phones = requests.values.filter { it.status == Status.WAITING && it.code != null }.map { it.name }
        return Window(closesAt, phones, MAX_REQUESTS - requestsInWindow)
    }

    /**
     * A phone asks to pair. Its [commitment] is the hash of a random value it reveals next, see [commitment].
     *
     * @throws IllegalArgumentException if the name or commitment isn't valid
     */
    @Synchronized
    fun ask(name: String, commitment: ByteArray): Asked {
        val label = validName(name) ?: throw IllegalArgumentException("Not a valid name")
        require(commitment.size == NONCE_BYTES) { "The commitment must be $NONCE_BYTES bytes" }
        expire()
        if (windowClosesAt == null) return Asked.Closed
        val commitmentKey = base64(commitment)
        if (requestsInWindow >= MAX_REQUESTS || commitmentKey in usedCommitments) return Asked.Full
        requestsInWindow++
        usedCommitments.add(commitmentKey)
        if (usedCommitments.size > MAX_USED_COMMITMENTS) {
            usedCommitments.remove(usedCommitments.first())
        }
        val request = Request(base64(randomBytes(REQUEST_ID_BYTES)), label, commitment, randomBytes(NONCE_BYTES), clock.instant())
        requests[request.id] = request
        logger.info("A phone calling itself [{}] asks to pair.", label)
        return Asked.Created(request.id, request.nonce)
    }

    /**
     * The phone reveals the random value its commitment was made from. Busbridge works the code out once it has it.
     */
    @Synchronized
    fun reveal(requestId: String, nonce: ByteArray): Status {
        expire()
        val request = requests[requestId] ?: return Status.EXPIRED
        if (request.status != Status.WAITING) return request.status
        val revealed = request.revealed
        if (revealed != null) {
            if (MessageDigest.isEqual(revealed, nonce)) return Status.WAITING // Sent again
            drop(request, "a different value revealed again")
            return Status.EXPIRED
        }
        if (!MessageDigest.isEqual(commitment(nonce), request.commitment)) {
            drop(request, "a value that isn't the one it committed to")
            return Status.EXPIRED
        }
        request.revealed = nonce.copyOf()
        request.code = code(nonce, request.nonce, certificate)
        request.endsAt = clock.instant() + CODE_TIMEOUT
        return Status.WAITING
    }

    /**
     * The user types the code the app shows, which allows the phone with that code. Spaces don't matter. One wrong
     * code closes the window: each is compared with every phone waiting, so allowing more would give someone in between
     * more chances.
     *
     * @return null if what was typed isn't 6 digits, which doesn't count as a try
     */
    @Synchronized
    fun type(typed: String): Typed? {
        val code = typed.filterNot(Char::isWhitespace).takeIf { it.length == CODE_DIGITS && it.all { c -> c in '0'..'9' } } ?: return null
        expire()
        if (windowClosesAt == null) return Typed.Closed
        // Two phones with the same code can't be told apart, so neither is allowed
        val request = requests.values.filter { it.status == Status.WAITING && it.code == code }.singleOrNull()
        if (request == null) {
            closeWindow()
            logger.warn("A wrong pairing code was typed on Settings, so the pairing window is closed.")
            return Typed.WrongCode
        }
        request.status = Status.ALLOWED
        request.key = randomBytes(KEY_BYTES)
        request.endsAt = clock.instant() + ALLOWED_TIMEOUT
        closeWindow()
        logger.info("Allowed the phone calling itself [{}] to pair.", request.name)
        return Typed.Allowed(request.name)
    }

    /** The phone asks how it's going, with the value it revealed, so the requestId alone gets nothing. */
    @Synchronized
    fun poll(requestId: String, nonce: ByteArray): Polled {
        expire()
        val request = requests[requestId]
        if (request == null || !MessageDigest.isEqual(commitment(nonce), request.commitment)) {
            return Polled(Status.EXPIRED)
        }
        return Polled(request.status, request.key?.copyOf())
    }

    private fun closeWindow() {
        windowClosesAt = null
        val now = clock.instant()
        for (request in requests.values) {
            if (request.status == Status.WAITING) {
                request.status = Status.DENIED
                request.revealed = null
                request.endsAt = now + DENIED_KEPT_FOR
            }
        }
    }

    private fun drop(request: Request, why: String) {
        requests.remove(request.id)
        logger.warn("Dropped the pairing request from the phone calling itself [{}]: {}.", request.name, why)
    }

    /** Forgets what has run out, and closes a window that has. Phones waiting in it then get expired, not denied. */
    private fun expire() {
        val now = clock.instant()
        windowClosesAt?.let { closesAt ->
            if (now >= closesAt) {
                windowClosesAt = null
                requests.values.removeIf { it.status == Status.WAITING }
                logger.info("The pairing window closed after {} minutes.", WINDOW_DURATION.toMinutes())
            }
        }
        requests.values.removeIf { now >= it.endsAt }
    }

    private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)

    companion object {
        const val NONCE_BYTES = 32
        const val CODE_DIGITS = 6
        const val MAX_NAME_LENGTH = 40
        const val MAX_REQUESTS = 3
        @JvmField
        val WINDOW_DURATION: Duration = Duration.ofMinutes(5)
        @JvmField
        val REVEAL_TIMEOUT: Duration = Duration.ofSeconds(15)
        @JvmField
        val CODE_TIMEOUT: Duration = Duration.ofMinutes(2)
        @JvmField
        val ALLOWED_TIMEOUT: Duration = Duration.ofMinutes(10)
        /** How long a denied phone's polls are told so, before they get expired. */
        private val DENIED_KEPT_FOR = Duration.ofMinutes(2)
        private const val KEY_BYTES = 32
        const val REQUEST_ID_BYTES = 16
        /** Phones allowed but not yet connected, which is normally one. */
        private const val MAX_ALLOWED_KEPT = 3
        private const val MAX_USED_COMMITMENTS = 64
        private val COMMIT_LABEL = "busbridge-pair-v1/app-commit".toByteArray(Charsets.US_ASCII)
        private val CODE_LABEL = "busbridge-pair-v1/code".toByteArray(Charsets.US_ASCII)
        private val logger = LogManager.getLogger(Pairing::class.java)

        /** What the phone sends before it reveals [nonce]: SHA-256("busbridge-pair-v1/app-commit" ‖ nonce). */
        @JvmStatic
        fun commitment(nonce: ByteArray): ByteArray = sha256(COMMIT_LABEL, nonce)

        /**
         * The code both sides show: SHA-256("busbridge-pair-v1/code" ‖ phone's nonce ‖ Busbridge's nonce ‖
         * certificate), its first 4 bytes as an unsigned big-endian number, mod 1 000 000, as 6 digits.
         */
        @JvmStatic
        fun code(phoneNonce: ByteArray, serverNonce: ByteArray, certificate: ByteArray): String {
            val hash = sha256(CODE_LABEL, phoneNonce, serverNonce, certificate)
            val number = hash.take(4).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xFF) }
            return (number % 1_000_000).toString().padStart(CODE_DIGITS, '0')
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
        fun decode(base64: String?, size: Int): ByteArray? {
            if (base64 == null) return null
            val bytes = try {
                Base64.getDecoder().decode(base64)
            } catch (e: IllegalArgumentException) {
                return null
            }
            return bytes.takeIf { it.size == size && base64(it) == base64 }
        }

        @JvmStatic
        fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        private val HIDDEN_TYPES = setOf(
            Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.SURROGATE, Character.UNASSIGNED,
            Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
        )

        private fun sha256(vararg parts: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            parts.forEach(digest::update)
            return digest.digest()
        }
    }
}
