package radium.backend.punishment.models

import org.bson.Document
import java.time.Instant
import java.util.*

/**
 * Represents a punishment record in the system
 * Follows the existing pattern used by Profile and other data models
 */
data class Punishment(
    val id: String = UUID.randomUUID().toString(),
    val playerId: String,
    val playerName: String,
    val ip: String? = null,
    val type: PunishmentType,
    val reason: String,
    val issuedBy: String, // Staff UUID
    val issuedByName: String, // Staff display name
    val issuedAt: Instant = Instant.now(),
    val durationMs: Long? = null,
    val expiresAt: Instant? = null,
    val active: Boolean = true,
    val silent: Boolean = false,
    val clearedInventory: Boolean = false,
    val meta: Map<String, Any> = emptyMap()
) {
    /**
     * Checks if this punishment has expired based on current time
     */
    fun isExpired(): Boolean {
        return expiresAt?.let { it.isBefore(Instant.now()) } ?: false
    }

    /**
     * Checks if this punishment is currently active and not expired
     */
    fun isCurrentlyActive(): Boolean {
        return active && !isExpired()
    }

    /**
     * Converts this punishment to a MongoDB Document
     * Following the pattern used in Profile.kt
     */
    fun toDocument(): Document {
        val doc = Document()
            .append("_id", id)
            .append("playerId", playerId)
            .append("playerName", playerName)
            .append("type", type.name)
            .append("reason", reason)
            .append("issuedBy", issuedBy)
            .append("issuedByName", issuedByName)
            .append("issuedAt", Date.from(issuedAt))
            .append("active", active)
            .append("silent", silent)
            .append("clearedInventory", clearedInventory)
            .append("meta", Document(meta))

        ip?.let { doc.append("ip", it) }
        durationMs?.let { doc.append("durationMs", it) }
        expiresAt?.let { doc.append("expiresAt", Date.from(it)) }

        return doc
    }

    companion object {
        /**
         * Creates a Punishment from a MongoDB Document
         * Following the pattern used in Profile.kt
         */
        fun fromDocument(doc: Document): Punishment {
            return Punishment(
                id = doc.getString("_id"),
                playerId = doc.getString("playerId"),
                playerName = doc.getString("playerName"),
                ip = doc.getString("ip"),
                type = PunishmentType.valueOf(doc.getString("type")),
                reason = doc.getString("reason"),
                issuedBy = doc.getString("issuedBy"),
                issuedByName = doc.getString("issuedByName"),
                issuedAt = doc.getDate("issuedAt").toInstant(),
                durationMs = doc.getLong("durationMs"),
                expiresAt = doc.getDate("expiresAt")?.toInstant(),
                active = doc.getBoolean("active", true),
                silent = doc.getBoolean("silent", false),
                clearedInventory = doc.getBoolean("clearedInventory", false),
                meta = doc.get("meta", Document::class.java)?.toMap() ?: emptyMap()
            )
        }
    }
}
