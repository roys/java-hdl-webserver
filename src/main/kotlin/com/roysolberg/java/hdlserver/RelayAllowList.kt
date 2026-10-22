package com.roysolberg.java.hdlserver

import com.roysolberg.java.hdlserver.util.HdlUtil

/**
 * What a phone may send to the bus through the relay: what the Android app sends to control its devices, read them and
 * find them, and nothing that reprograms them. A phone's key in the wrong hands can then work the house, but not change
 * modules' addresses, names, logic or settings.
 *
 * Built from the app's own list of what it sends, so the two change together: a packet refused here is logged with
 * its operation code, which shows what's missing when the app sends something new.
 */
internal object RelayAllowList {

    /** Controlling devices: channels, panels' keys, curtains, heating, AC, IR and music. */
    private val CONTROLS = setOf(0x0031, 0x193A, 0x1946, 0x19B0, 0x1C5C, 0xD912, 0xE01C, 0xE0EE, 0xE3D8, 0xE3E0)

    /** Asking devices how they are: what the app's screens show. */
    private val READS = setOf(
        0x0033, 0x011E, 0x0248, 0x15CE, 0x1604, 0x1645, 0x1900, 0x1938, 0x1944, 0x1948, 0x194C, 0x196A, 0x198C, 0x199E,
        0x19AA, 0x19AE, 0x19B2, 0x19B6, 0x1C00, 0x1C5E, 0x1D02, 0x3007, 0x3009, 0x3513, 0x3591, 0x3593, 0x359F, 0x35A3,
        0x35B1, 0xA008, 0xC04E, 0xC072, 0xC090, 0xC100, 0xC102, 0xD205, 0xD210, 0xD902, 0xD904, 0xD908, 0xD90A, 0xD90C,
        0xD90E, 0xD914, 0xD916, 0xD91A, 0xD920, 0xD962, 0xD964, 0xDA00, 0xDA14, 0xDA18, 0xDA1C, 0xDA28, 0xDA2C, 0xDA38,
        0xDA3C, 0xDB00, 0xE004, 0xE008, 0xE0EC, 0xE120, 0xE124, 0xE12C, 0xE14C, 0xE3DA, 0xE3E7, 0xE44A, 0xE468, 0xE548,
        0xF00E, 0xF037, 0xF112,
    )

    /** The device search, and setting a logic module's clock, which the app does after the user confirms. */
    private val OTHERS = setOf(0x000E, 0xDA02)

    private val ALLOWED = CONTROLS + READS + OTHERS

    /**
     * On an IR emitter these send and read IR codes, but on a power meter the same codes clear and calibrate it. So never
     * to a known power meter, or to every device at once.
     */
    private val IR_OR_POWER_METER = setOf(0xD912, 0xD914, 0xD916)
    private val POWER_METER_TYPES = 0x037E..0x0384
    private const val EVERY_DEVICE = 255

    /**
     * @param deviceType the type of the device with this address, if Busbridge has found it
     * @return why the packet may not be sent, or null if it may
     */
    fun refusal(packet: ByteArray, deviceType: (subnet: Int, device: Int) -> Int?): String? {
        val operationCode = operationCode(packet)
        if (operationCode !in ALLOWED) {
            return "not something the app sends"
        }
        if (operationCode in IR_OR_POWER_METER) {
            val subnet = packet[HdlUtil.BYTE_POSITION_TARGET_SUBNET_ID].toInt() and 0xFF
            val device = packet[HdlUtil.BYTE_POSITION_TARGET_DEVICE_ID].toInt() and 0xFF
            if (subnet == EVERY_DEVICE || device == EVERY_DEVICE) {
                return "an IR code to every device, which would also reach power meters"
            }
            if (deviceType(subnet, device) in POWER_METER_TYPES) {
                return "to a power meter, which it would clear or calibrate"
            }
        }
        return null
    }

    fun operationCode(packet: ByteArray): Int =
        (packet[HdlUtil.BYTE_POSITION_OPERATION_CODE_BYTE_1].toInt() and 0xFF shl 8) or (packet[HdlUtil.BYTE_POSITION_OPERATION_CODE_BYTE_1 + 1].toInt() and 0xFF)
}
