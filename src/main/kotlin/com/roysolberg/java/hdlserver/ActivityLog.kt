package com.roysolberg.java.hdlserver

import java.time.Clock
import java.time.Instant

/**
 * The latest actions run or refused, newest first. Kept in memory only, so it starts empty when the server starts.
 */
class ActivityLog @JvmOverloads constructor(private val clock: Clock = Clock.systemDefaultZone()) {

    class Entry(val time: Instant, val source: String, val what: String, val result: String, val succeeded: Boolean)

    private val entries = ArrayDeque<Entry>()

    /**
     * The source and what was asked for can come from the Internet (e.g. an alias in the URL), so they're shortened.
     */
    @Synchronized
    fun record(source: String, what: String, result: String, succeeded: Boolean) {
        entries.addFirst(Entry(clock.instant(), source.take(MAX_TEXT_LENGTH), what.take(MAX_TEXT_LENGTH), result, succeeded))
        while (entries.size > MAX_ENTRIES) {
            entries.removeLast()
        }
    }

    @Synchronized
    fun entries(): List<Entry> = entries.toList()

    companion object {
        const val MAX_ENTRIES = 100
        const val MAX_TEXT_LENGTH = 200
    }
}
