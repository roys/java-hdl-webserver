package com.roysolberg.java.hdlserver

import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the status page shows.
 */
class Status(
    val version: String,
    val javaVersion: String,
    val startedAt: Instant,
    val now: Instant,
    val busMessages: Long,
    val lastBusMessage: Instant?,
    val busMessagesPerMinute: List<Int>,
    val components: Int,
    val actions: Int,
    val lastApiCall: ActivityLog.Entry?,
    val apiCallsToday: Int,
    val memoryUsedMb: Long,
    val memoryMaxMb: Long,
    val cpuTemperature: Double?,
) {

    val uptime: String
        get() = describe(Duration.between(startedAt, now))

    /** Whether the bus has said anything in the last minute; it normally does every few seconds. */
    val busAlive: Boolean
        get() = lastBusMessage != null && Duration.between(lastBusMessage, now) < Duration.ofMinutes(1)

    val lastBusMessageAgo: String
        get() = lastBusMessage?.let { "${describe(Duration.between(it, now))} ago" } ?: "never"

    /**
     * The last hour's bus traffic as SVG points in a [SPARKLINE_WIDTH] x [SPARKLINE_HEIGHT] box: the line itself.
     */
    val sparklineLine: String
        get() = sparklinePoints().joinToString(" ")

    /** The same line closed along the bottom, for filling the area under it. */
    val sparklineArea: String
        get() = (listOf("0,$SPARKLINE_HEIGHT") + sparklinePoints() + "$SPARKLINE_WIDTH,$SPARKLINE_HEIGHT").joinToString(" ")

    private fun sparklinePoints(): List<String> {
        val max = (busMessagesPerMinute.maxOrNull() ?: 0).coerceAtLeast(1)
        val step = SPARKLINE_WIDTH.toDouble() / (busMessagesPerMinute.size - 1).coerceAtLeast(1)
        return busMessagesPerMinute.mapIndexed { i, count ->
            // Locale.ROOT: with a Norwegian locale, 12.5 would become "12,5" and break the SVG
            String.format(Locale.ROOT, "%.1f,%.1f", i * step, SPARKLINE_HEIGHT - count.toDouble() / max * (SPARKLINE_HEIGHT - 4))
        }
    }

    val busMessagesLastHour: Int
        get() = busMessagesPerMinute.sum()

    companion object {
        const val SPARKLINE_WIDTH = 600
        const val SPARKLINE_HEIGHT = 80
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

        @JvmStatic
        fun formatTime(instant: Instant): String = TIME_FORMAT.format(instant.atZone(ZoneId.systemDefault()))

        /** "3 days, 4 hours", "12 minutes", "8 seconds" */
        fun describe(duration: Duration): String {
            val seconds = duration.seconds.coerceAtLeast(0)
            val parts = listOf(
                seconds / 86_400 to "day", seconds % 86_400 / 3_600 to "hour",
                seconds % 3_600 / 60 to "minute", seconds % 60 to "second",
            ).filter { it.first > 0 }.take(2)
            return parts.joinToString(", ") { (count, unit) -> "$count $unit${if (count == 1L) "" else "s"}" }.ifEmpty { "0 seconds" }
        }

        /** The Raspberry Pi's (and most Linux machines') CPU temperature, if it can be read. */
        fun cpuTemperature(): Double? = try {
            Files.readAllLines(Paths.get("/sys/class/thermal/thermal_zone0/temp")).firstOrNull()?.trim()?.toDoubleOrNull()?.div(1000)
        } catch (e: Exception) {
            null
        }

        fun countToday(entries: List<ActivityLog.Entry>, zone: ZoneId = ZoneId.systemDefault()): Int {
            val today = LocalDate.now(zone)
            return entries.count { it.time.atZone(zone).toLocalDate() == today }
        }
    }
}
