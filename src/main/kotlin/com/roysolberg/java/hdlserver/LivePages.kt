package com.roysolberg.java.hdlserver

import io.javalin.http.sse.SseClient
import org.apache.logging.log4j.LogManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Keeps open pages up to date, like the bus graph on Status and new entries on Activity. Every few seconds, each open
 * page gets its live part again, rendered by the same template as the page itself, so there's nothing to keep in sync.
 *
 * @param render renders a live part by its template name, e.g. "live/status"
 */
internal class LivePages(private val render: (String) -> String) : AutoCloseable {

    private val clients = ConcurrentHashMap<SseClient, String>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "live-pages").apply { isDaemon = true }
    }

    init {
        scheduler.scheduleWithFixedDelay(::update, UPDATE_SECONDS, UPDATE_SECONDS, TimeUnit.SECONDS)
    }

    /** Sends [template] to [client] until the page is closed. */
    fun add(client: SseClient, template: String) {
        client.keepAlive()
        client.onClose { clients.remove(client) }
        clients[client] = template
    }

    /** Each open page costs a connection and an update every few seconds, so there's a limit. */
    val isFull: Boolean
        get() = clients.size >= MAX_CLIENTS

    /** All sending happens on the one scheduler thread, so a client is never written to by two threads at once. */
    private fun update() {
        val rendered = HashMap<String, String>()
        for ((client, template) in clients) {
            if (client.terminated()) {
                clients.remove(client)
                continue
            }
            // Thrown out of here, an exception would stop the updates for good, also for the other pages
            try {
                // An event's data ends at a blank line, and HTML doesn't need its line breaks
                client.sendEvent(EVENT, rendered.getOrPut(template) { render(template).replace(LINE_BREAKS, " ") })
            } catch (e: Exception) {
                logger.warn("Couldn't update a live page, so it's dropped.", e)
                clients.remove(client)
                client.close()
            }
        }
    }

    override fun close() {
        scheduler.shutdownNow()
        clients.keys.forEach(SseClient::close)
    }

    companion object {
        const val EVENT = "update"
        const val MAX_CLIENTS = 20
        private const val UPDATE_SECONDS = 2L
        private val LINE_BREAKS = Regex("[\r\n]+")
        private val logger = LogManager.getLogger(LivePages::class.java)
    }
}
