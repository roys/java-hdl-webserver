package com.roysolberg.java.hdlserver

import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Counts the messages received from the HDL bus: in total, when the last came, and per minute for the last hour.
 */
class BusStats @JvmOverloads constructor(private val clock: Clock = Clock.systemDefaultZone()) {

    private val total = AtomicLong()
    private val perMinute = IntArray(MINUTES)
    private var currentMinute = -1L

    @Volatile
    var lastMessage: Instant? = null
        private set

    val totalMessages: Long
        get() = total.get()

    @Synchronized
    fun record() {
        val now = clock.instant()
        total.incrementAndGet()
        lastMessage = now
        val minute = advanceTo(now)
        perMinute[(minute % MINUTES).toInt()]++
    }

    /**
     * @return messages per minute for the last hour, oldest first, with the current minute last.
     */
    @Synchronized
    fun messagesPerMinute(): List<Int> {
        val minute = advanceTo(clock.instant())
        return (minute - MINUTES + 1..minute).map { perMinute[Math.floorMod(it, MINUTES)] }
    }

    /** Clears the minutes that have passed without messages, and returns the current minute. */
    private fun advanceTo(now: Instant): Long {
        val minute = now.epochSecond / 60
        if (currentMinute >= 0) {
            for (passed in (currentMinute + 1..minute).take(MINUTES)) {
                perMinute[Math.floorMod(passed, MINUTES)] = 0
            }
        }
        currentMinute = maxOf(currentMinute, minute)
        return currentMinute
    }

    private companion object {
        const val MINUTES = 60
    }
}
