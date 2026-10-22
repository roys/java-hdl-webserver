package com.roysolberg.java.hdlserver

/**
 * An HDL Buspro UDP packet: the sender's IPv4 address (bytes 0–3), "HDLMIRACLE" and 0xAAAA (4–15), then the frame. The
 * frame starts with its length byte (16), which counts the frame from itself through its CRC: the sender's subnet,
 * device and type, the operation, the target's subnet and device, the content, and a CRC-16 of all that. Anything after
 * the CRC, like the padding in this server's own 92-byte packets, isn't part of the frame.
 */
object HdlFrame {

    enum class Validity {
        VALID,

        /** Too short, or without "HDLMIRACLE" and 0xAAAA: something else sent to the port. */
        NOT_HDL,

        /** The length byte is too small, or says the frame goes on past the end of the packet. */
        BAD_LENGTH,

        /** The CRC doesn't match the frame, so it was damaged on the way. */
        BAD_CRC,
    }

    const val LENGTH_POSITION = 16
    const val CONTENT_POSITION = 25

    /** A frame without content: the length byte, the sender (4), the operation (2), the target (2) and the CRC (2). */
    private const val MIN_LENGTH = 11
    private const val CRC_SIZE = 2
    private const val HEADER_POSITION = 4
    private val HEADER = "HDLMIRACLE".toByteArray(Charsets.US_ASCII) + byteArrayOf(0xAA.toByte(), 0xAA.toByte())

    @JvmStatic
    fun validate(bytes: ByteArray?): Validity {
        if (bytes == null || bytes.size < LENGTH_POSITION + MIN_LENGTH) return Validity.NOT_HDL
        if (HEADER.indices.any { bytes[HEADER_POSITION + it] != HEADER[it] }) return Validity.NOT_HDL
        val length = length(bytes)
        if (length < MIN_LENGTH || LENGTH_POSITION + length > bytes.size) return Validity.BAD_LENGTH
        return if (crcOf(bytes) == crc(bytes, LENGTH_POSITION, length - CRC_SIZE)) Validity.VALID else Validity.BAD_CRC
    }

    /** The frame's length byte. */
    @JvmStatic
    fun length(bytes: ByteArray): Int = bytes[LENGTH_POSITION].toInt() and 0xFF

    /** How many content bytes the length byte says the frame has. */
    @JvmStatic
    fun contentLength(bytes: ByteArray): Int = (length(bytes) - MIN_LENGTH).coerceAtLeast(0)

    /** The CRC the frame carries, in its last two bytes. */
    @JvmStatic
    fun crcOf(bytes: ByteArray): Int {
        val position = LENGTH_POSITION + length(bytes) - CRC_SIZE
        return (bytes[position].toInt() and 0xFF shl 8) or (bytes[position + 1].toInt() and 0xFF)
    }

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
}
