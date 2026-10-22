package com.roysolberg.java.hdlserver

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.logging.log4j.LogManager
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * The phones paired with Busbridge, which may use the relay. Only a hash of each phone's key is kept, so the database
 * alone isn't enough to connect as one.
 *
 * @param map where they're kept, as JSON by the phone's id
 * @param commit saves the map
 */
class Phones(private val map: MutableMap<String, String>, private val commit: Runnable) {

    class Phone(val id: String, val name: String, val pairedAt: Instant, val lastSeen: Instant?, internal val keyHash: ByteArray)

    private val objectMapper = ObjectMapper()

    /** Oldest first. */
    @Synchronized
    fun all(): List<Phone> = map.mapNotNull { (id, json) -> read(id, json) }.sortedBy { it.pairedAt }

    @get:Synchronized
    val isFull: Boolean
        get() = map.size >= MAX_PHONES

    /** @return the new phone, or null if [MAX_PHONES] are paired already */
    @Synchronized
    fun add(name: String, key: ByteArray, now: Instant): Phone? {
        if (isFull) {
            logger.warn("Didn't pair the phone calling itself [{}]: {} phones are paired already.", name, MAX_PHONES)
            return null
        }
        val phone = Phone(UUID.randomUUID().toString(), name, now, now, hash(key))
        write(phone)
        logger.info("Paired the phone calling itself [{}].", name)
        return phone
    }

    /** The phone with this key, or null. Every phone is compared, in the same time whichever matches. */
    @Synchronized
    fun find(key: ByteArray): Phone? {
        val hash = hash(key)
        return all().fold(null as Phone?) { found, phone -> if (MessageDigest.isEqual(phone.keyHash, hash)) phone else found }
    }

    @Synchronized
    fun contains(id: String): Boolean = map.containsKey(id)

    @Synchronized
    fun seen(id: String, now: Instant) {
        val phone = map[id]?.let { read(id, it) } ?: return
        write(Phone(phone.id, phone.name, phone.pairedAt, now, phone.keyHash))
    }

    /** @return the removed phone, or null if there was none with this id */
    @Synchronized
    fun remove(id: String): Phone? {
        val phone = map.remove(id)?.let { read(id, it) } ?: return null
        commit.run()
        logger.info("Removed the phone calling itself [{}].", phone.name)
        return phone
    }

    private fun write(phone: Phone) {
        map[phone.id] = objectMapper.writeValueAsString(
            mapOf(
                "name" to phone.name,
                "pairedAt" to phone.pairedAt.toEpochMilli(),
                "lastSeen" to phone.lastSeen?.toEpochMilli(),
                "keyHash" to Pairing.base64(phone.keyHash),
            )
        )
        commit.run()
    }

    private fun read(id: String, json: String): Phone? = try {
        val node = objectMapper.readTree(json)
        Phone(
            id,
            node.path("name").asText(),
            Instant.ofEpochMilli(node.path("pairedAt").asLong()),
            node.get("lastSeen")?.takeIf { it.isNumber }?.let { Instant.ofEpochMilli(it.asLong()) },
            checkNotNull(Pairing.decode(node.path("keyHash").asText(), HASH_BYTES)) { "No key hash" },
        )
    } catch (e: JacksonException) {
        logger.error("Couldn't read the paired phone {}, so it can't connect.", id, e)
        null
    } catch (e: IllegalStateException) {
        logger.error("Couldn't read the paired phone {}, so it can't connect.", id, e)
        null
    }

    companion object {
        /** Each needs the user to open pairing, but anyone on the network can, so there's a limit. */
        const val MAX_PHONES = 20
        private const val HASH_BYTES = 32
        private val logger = LogManager.getLogger(Phones::class.java)

        private fun hash(key: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(key)
    }
}
