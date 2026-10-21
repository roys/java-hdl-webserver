package com.roysolberg.java.hdlserver

import io.javalin.http.sse.SseClient
import org.apache.logging.log4j.LogManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps open pages up to date, like the bus graph on Status and new entries on Activity. Every few seconds, each open
 * page gets its live part again, rendered by the same template as the page itself, so there's nothing to keep in sync.
 *
 * @param render renders a live part by its template name, e.g. "live/status"
 */
internal class LivePages(private val render: (String) -> String) : AutoCloseable {

    private class LivePage(val template: String) {
        /** Whether an update is on its way, so a page that's slow to take them gets one at a time. */
        val isSending = AtomicBoolean()
    }

    private val pages = ConcurrentHashMap<SseClient, LivePage>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable -> daemon(runnable, "live-pages") }

    // Each page is sent its update on its own, so one that's slow to take it only holds up itself, until the server's
    // idle timeout gives up on it. At most one thread per page, and threads end when not needed.
    private val senders = ThreadPoolExecutor(0, MAX_PAGES, 60, TimeUnit.SECONDS, SynchronousQueue()) { runnable ->
        daemon(runnable, "live-page")
    }

    init {
        scheduler.scheduleWithFixedDelay(::update, UPDATE_SECONDS, UPDATE_SECONDS, TimeUnit.SECONDS)
    }

    /** Each open page costs a connection and an update every few seconds, so there's a limit. */
    val isFull: Boolean
        get() = pages.size >= MAX_PAGES

    /**
     * Sends [template] to [client] until the page is closed.
     *
     * @return false, and the client closed, if there are already as many open pages as there can be
     */
    @Synchronized
    fun add(client: SseClient, template: String): Boolean {
        if (isFull) {
            client.close()
            return false
        }
        client.keepAlive()
        client.onClose { pages.remove(client) }
        pages[client] = LivePage(template)
        return true
    }

    private fun update() {
        val rendered = HashMap<String, String>()
        for ((client, page) in pages) {
            if (client.terminated()) {
                pages.remove(client)
                continue
            }
            if (!page.isSending.compareAndSet(false, true)) {
                continue // Still busy with the last one
            }
            // An event's data ends at a blank line, and HTML doesn't need its line breaks
            val html = rendered.getOrPut(page.template) { render(page.template).replace(LINE_BREAKS, " ") }
            try {
                senders.execute { send(client, page, html) }
            } catch (e: RejectedExecutionException) {
                page.isSending.set(false)
            }
        }
    }

    private fun send(client: SseClient, page: LivePage, html: String) {
        try {
            client.sendEvent(EVENT, html)
        } catch (e: Exception) {
            logger.warn("Couldn't update a live page, so it's dropped.", e)
            pages.remove(client)
            client.close()
        } finally {
            page.isSending.set(false)
        }
    }

    override fun close() {
        scheduler.shutdownNow()
        senders.shutdownNow()
        pages.keys.forEach(SseClient::close)
    }

    companion object {
        const val EVENT = "update"
        const val MAX_PAGES = 20
        private const val UPDATE_SECONDS = 2L
        private val LINE_BREAKS = Regex("[\r\n]+")
        private val logger = LogManager.getLogger(LivePages::class.java)

        private fun daemon(runnable: Runnable, name: String) = Thread(runnable, name).apply { isDaemon = true }
    }
}
