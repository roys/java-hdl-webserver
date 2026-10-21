package com.roysolberg.java.hdlserver

import java.time.Clock
import java.time.LocalDate

/**
 * Counts something per day, starting again from zero at midnight.
 */
class DailyCounter @JvmOverloads constructor(private val clock: Clock = Clock.systemDefaultZone()) {

    private var day: LocalDate = LocalDate.now(clock)
    private var count = 0

    @Synchronized
    fun increment() {
        rollOver()
        count++
    }

    @Synchronized
    fun today(): Int {
        rollOver()
        return count
    }

    private fun rollOver() {
        val today = LocalDate.now(clock)
        if (today != day) {
            day = today
            count = 0
        }
    }
}
