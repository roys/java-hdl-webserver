package com.roysolberg.java.hdlserver

/**
 * The HDL operations a custom action's commands can use, and the parameters each takes. The page's form and the server's
 * checks both come from here, so a command can only reach the bus with the values its operation expects.
 *
 * @param parameters parameters 1 to 3; null where the operation has none
 */
enum class Operation(val code: Int, val title: String, val parameters: List<Parameter?>) {
    SceneControl(0x0002, "Scene control", listOf(Parameter("Area", 1, 255), Parameter("Scene number", 0, 255), null)),
    SequenceControl(0x001A, "Sequence control", listOf(Parameter("Area", 1, 255), Parameter("Sequence number", 0, 255), null)),
    SingleChannelControl(
        0x0031, "Single channel control",
        listOf(Parameter("Channel", 1, 255), Parameter("Level (0-100)", 0, 100), Parameter("Run time (seconds)", 0, 65535, isRequired = false)),
    ),
    UniversalSwitchControl(
        0xE01C, "Universal switch control",
        listOf(Parameter("Switch number", 1, 255), Parameter("On (255) or off (0)", 0, 255, choices = listOf(0, 255)), null),
    ),
    PanelControl(
        0xE3D8, "Panel control",
        listOf(Parameter("Function", 0, 255), Parameter("Button number", 0, 255), Parameter("Value (1 = on, 0 = off)", 0, 255, isRequired = false)),
    ),
    CurtainSwitchControl(
        0xE3E0, "Curtain switch control",
        listOf(Parameter("Curtain number", 1, 255), Parameter("Stop (0), open (1) or close (2)", 0, 2), null),
    );

    /**
     * What a parameter means for this operation, and the values it takes.
     *
     * @param choices the only values that make sense, if fewer than the whole range
     */
    class Parameter(val name: String, val min: Int, val max: Int, val isRequired: Boolean = true, val choices: List<Int>? = null)

    /** E.g. "0x0002". */
    val hex: String
        get() = "0x%04X".format(code)

    /** The parameters for the page's script, e.g. `[{"name":"Area","min":1,"max":255,"required":true},null,null]`. */
    val parametersJson: String
        get() = parameters.joinToString(",", "[", "]") { parameter ->
            parameter?.let { """{"name":"${it.name}","min":${it.min},"max":${it.max},"required":${it.isRequired}}""" } ?: "null"
        }

    /**
     * @param values parameters 1 to 3 as typed, blank when left out
     * @return the parameters as numbers, null where left out
     * @throws IllegalArgumentException saying which parameter can't be used, and why
     */
    fun parse(values: List<String?>): List<Int?> = parameters.mapIndexed { i, parameter ->
        val value = values.getOrNull(i)?.trim().orEmpty()
        if (parameter == null || value.isEmpty()) {
            require(parameter?.isRequired != true) { "${parameter?.name} is needed for ${title.lowercase()}." }
            null
        } else {
            val number = value.toIntOrNull()
            require(number != null && number in parameter.min..parameter.max && (parameter.choices == null || number in parameter.choices)) {
                "${parameter.name} must be ${parameter.choices?.joinToString(" or ") ?: "${parameter.min}-${parameter.max}"} for ${title.lowercase()}."
            }
            number
        }
    }

    companion object {
        @JvmStatic
        fun of(code: Int): Operation? = entries.firstOrNull { it.code == code }
    }
}
