package com.roysolberg.java.hdlserver

/**
 * An HDL Buspro UDP packet: the sender's IPv4 address (bytes 0–3), "HDLMIRACLE" and 0xAAAA (4–15), then the frame. The
 * frame starts with its length byte (16), which counts the frame from itself through its CRC: the sender's subnet,
 * device and type, the operation, the target's subnet and device, the content, and a CRC-16 of all that. Anything after
 * the CRC, like the padding in this server's own 92-byte packets, isn't part of the frame.
 *
 * Content too long for the length byte, like an SMS module's messages, goes in a big frame instead: 255 in the length
 * byte, the content's length in two bytes after the target (25–26, most significant first), then the content, and no
 * CRC.
 */
object HdlFrame {

    enum class Validity {
        VALID,

        /** Too short, or without "HDLMIRACLE" and 0xAAAA: something else sent to the port. */
        NOT_HDL,

        /** The length byte is too small, or the frame's length says it goes on past the end of the packet. */
        BAD_LENGTH,

        /** The CRC doesn't match the frame, so it was damaged on the way. */
        BAD_CRC,
    }

    const val LENGTH_POSITION = 16

    /** A frame without content: the length byte, the sender (4), the operation (2), the target (2) and the CRC (2). */
    private const val MIN_LENGTH = 11
    private const val CRC_SIZE = 2
    private const val HEADER_POSITION = 4
    private const val CONTENT_POSITION = 25
    private const val BIG = 0xFF
    private const val BIG_CONTENT_LENGTH_POSITION = 25
    private const val BIG_CONTENT_POSITION = 27
    private val HEADER = "HDLMIRACLE".toByteArray(Charsets.US_ASCII) + byteArrayOf(0xAA.toByte(), 0xAA.toByte())

    @JvmStatic
    fun validate(bytes: ByteArray?): Validity {
        if (bytes == null || bytes.size < LENGTH_POSITION + MIN_LENGTH) return Validity.NOT_HDL
        if (HEADER.indices.any { bytes[HEADER_POSITION + it] != HEADER[it] }) return Validity.NOT_HDL
        if (isBig(bytes)) {
            // Nothing to check it against, but all its content must be there
            return if (BIG_CONTENT_POSITION + contentLength(bytes) <= bytes.size) Validity.VALID else Validity.BAD_LENGTH
        }
        val length = length(bytes)
        if (length < MIN_LENGTH || LENGTH_POSITION + length > bytes.size) return Validity.BAD_LENGTH
        return if (crcOf(bytes) == crc(bytes, LENGTH_POSITION, length - CRC_SIZE)) Validity.VALID else Validity.BAD_CRC
    }

    /** How many bytes of a [Validity.VALID] packet are its header and frame, without anything after them. */
    @JvmStatic
    fun packetLength(bytes: ByteArray): Int =
        if (isBig(bytes)) BIG_CONTENT_POSITION + contentLength(bytes) else LENGTH_POSITION + length(bytes)

    /** The frame's length byte. */
    @JvmStatic
    fun length(bytes: ByteArray): Int = bytes[LENGTH_POSITION].toInt() and 0xFF

    /** Whether it's a big frame, with its content's length after the target instead of in the length byte. */
    @JvmStatic
    fun isBig(bytes: ByteArray): Boolean = length(bytes) == BIG

    /** Where the frame's content starts. */
    @JvmStatic
    fun contentPosition(bytes: ByteArray): Int = if (isBig(bytes)) BIG_CONTENT_POSITION else CONTENT_POSITION

    /** How many content bytes the frame says it has. */
    @JvmStatic
    fun contentLength(bytes: ByteArray): Int = if (isBig(bytes)) {
        bytes.uShortAt(BIG_CONTENT_LENGTH_POSITION)
    } else {
        (length(bytes) - MIN_LENGTH).coerceAtLeast(0)
    }

    /** The CRC the frame carries in its last two bytes, or null for a big frame, which has none. */
    @JvmStatic
    fun crcOf(bytes: ByteArray): Int? = if (isBig(bytes)) null else bytes.uShortAt(LENGTH_POSITION + length(bytes) - CRC_SIZE)

    /** Writes the CRC of the frame its length byte describes into the frame's last two bytes. */
    @JvmStatic
    fun writeCrc(bytes: ByteArray) {
        val position = LENGTH_POSITION + length(bytes) - CRC_SIZE
        val crc = crc(bytes, LENGTH_POSITION, position - LENGTH_POSITION)
        bytes[position] = (crc ushr 8).toByte()
        bytes[position + 1] = crc.toByte()
    }

    /** CRC-16/XMODEM: polynomial 0x1021, starting at 0, most significant bit first, as HDL Buspro uses. */
    @JvmStatic
    fun crc(bytes: ByteArray, offset: Int, length: Int): Int {
        var crc = 0
        for (index in offset until offset + length) {
            val byte = bytes[index].toInt()
            for (bit in 7 downTo 0) {
                val isTopBitDifferent = (crc shr 15 and 1) != (byte shr bit and 1)
                crc = crc shl 1 and 0xFFFF
                if (isTopBitDifferent) crc = crc xor 0x1021
            }
        }
        return crc
    }

    private fun ByteArray.uShortAt(position: Int): Int = (this[position].toInt() and 0xFF shl 8) or (this[position + 1].toInt() and 0xFF)
}
