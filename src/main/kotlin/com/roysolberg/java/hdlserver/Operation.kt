package com.roysolberg.java.hdlserver

/**
 * The HDL operations a custom action's commands can use, and what their parameters mean. An empty parameter name means
 * the operation doesn't use that parameter.
 */
enum class Operation(val code: Int, val title: String, val parameters: List<String>) {
    SceneControl(0x0002, "Scene control", listOf("Area", "Scene number", "")),
    SequenceControl(0x001A, "Sequence control", listOf("Area", "Sequence number", "")),
    SingleChannelControl(0x0031, "Single channel control", listOf("Channel", "Level (0-100)", "Run time (seconds)")),
    UniversalSwitchControl(0xE01C, "Universal switch control", listOf("Switch number", "On (255) or off (0)", "")),
    PanelControl(0xE3D8, "Panel control", listOf("Function", "Button number", "On (1) or off (0)")),
    CurtainSwitchControl(0xE3E0, "Curtain switch control", listOf("Curtain number", "Stop (0), open (1) or close (2)", ""));

    /** E.g. "0x0002". */
    val hex: String
        get() = "0x%04X".format(code)

    companion object {
        @JvmStatic
        fun of(code: Int): Operation? = entries.firstOrNull { it.code == code }
    }
}
