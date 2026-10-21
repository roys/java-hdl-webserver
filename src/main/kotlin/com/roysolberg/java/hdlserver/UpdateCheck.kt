package com.roysolberg.java.hdlserver

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.logging.log4j.LogManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * When turned on in Settings, asks GitHub once a day which version is the latest, so the Status page can say when
 * there's a newer one. Only the version number is used; nothing else from the answer is shown or followed.
 *
 * @param isEnabled read before every check, so turning it off in Settings stops the next one
 */
internal class UpdateCheck(private val currentVersion: String?, private val isEnabled: () -> Boolean) : AutoCloseable {

    /** A newer version than the one running, e.g. "2.2.0". */
    @Volatile
    var newerVersion: String? = null
        private set

    private val httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "update-check").apply { isDaemon = true }
    }

    init {
        scheduler.scheduleWithFixedDelay(::check, 1, CHECK_EVERY.toMinutes(), TimeUnit.MINUTES)
    }

    /** Checks right away, e.g. when turned on in Settings, instead of waiting for the next daily check. */
    fun checkSoon() {
        scheduler.execute(::check)
    }

    private fun check() {
        if (!isEnabled()) {
            newerVersion = null
            return
        }
        try {
            val request = HttpRequest.newBuilder(URI.create(LATEST_RELEASE_URL))
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Busbridge/${currentVersion ?: "development"}")
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                logger.info("Couldn't check for updates: GitHub answered {}.", response.statusCode())
                return
            }
            val latestTag = ObjectMapper().readTree(response.body()).path("tag_name").asText()
            newerVersion = newerVersion(currentVersion, latestTag)
            logger.info("Checked for updates: the latest is {}, this is {}.", latestTag, currentVersion)
        } catch (e: Exception) {
            // The check is only a nicety; it'll try again tomorrow
            logger.info("Couldn't check for updates: {}", e.toString())
        }
    }

    override fun close() {
        scheduler.shutdownNow()
    }

    companion object {
        const val RELEASES_URL = "https://github.com/roys/java-hdl-webserver/releases"
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/roys/java-hdl-webserver/releases/latest"
        private val CHECK_EVERY = Duration.ofDays(1)
        private val TIMEOUT = Duration.ofSeconds(10)
        private val VERSION = Regex("""v?(\d+)\.(\d+)\.(\d+)""")
        private val logger = LogManager.getLogger(UpdateCheck::class.java)

        /**
         * @param latestTag the latest release's tag, e.g. "v2.2.0"
         * @return the latest version, e.g. "2.2.0", if it's newer than [current]; null for development builds like
         * "2.1.0-SNAPSHOT", and for anything that doesn't look like a version
         */
        @JvmStatic
        fun newerVersion(current: String?, latestTag: String): String? {
            val latest = VERSION.matchEntire(latestTag.trim())?.groupValues?.drop(1)?.map(String::toInt) ?: return null
            val running = current?.let(VERSION::matchEntire)?.groupValues?.drop(1)?.map(String::toInt) ?: return null
            val isNewer = latest.zip(running).firstOrNull { (l, r) -> l != r }?.let { (l, r) -> l > r } ?: false
            return if (isNewer) latest.joinToString(".") else null
        }
    }
}
