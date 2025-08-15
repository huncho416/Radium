package radium.backend.punishment

import com.mongodb.reactivestreams.client.MongoDatabase
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import org.bson.Document
import radium.backend.punishment.models.Punishment
import radium.backend.punishment.models.PunishmentType
import java.time.Instant
import java.util.*

/**
 * Repository for punishment data operations
 * Follows the pattern established by the existing MongoDB utilities
 */
class PunishmentRepository(
    private val database: MongoDatabase,
    private val logger: ComponentLogger
) {
    private val collection = database.getCollection("punishments")

    companion object {
        private const val COLLECTION_NAME = "punishments"
    }

    /**
     * Initialize the punishment collection with required indexes
     * Following the pattern from MongoStream.kt
     */
    suspend fun initializeIndexes() {
        try {
            // Index for finding active punishments by player
            collection.createIndex(Document("playerId", 1).append("active", 1)).awaitFirstOrNull()

            // Index for IP-based lookups
            collection.createIndex(Document("ip", 1).append("active", 1)).awaitFirstOrNull()

            // Index for type-based queries with expiration
            collection.createIndex(
                Document("type", 1)
                    .append("active", 1)
                    .append("expiresAt", 1)
            ).awaitFirstOrNull()

            // Index for history queries (most recent first)
            collection.createIndex(Document("issuedAt", -1)).awaitFirstOrNull()

            logger.info(Component.text("Successfully created punishment collection indexes", NamedTextColor.GREEN))
        } catch (e: Exception) {
            logger.warn(Component.text("Could not create punishment indexes: ${e.message}", NamedTextColor.YELLOW))
        }
    }

    /**
     * Save a punishment to the database
     */
    suspend fun savePunishment(punishment: Punishment): Boolean {
        return try {
            collection.insertOne(punishment.toDocument()).awaitFirst()
            logger.debug(Component.text("Saved punishment ${punishment.id} for player ${punishment.playerName}"))
            true
        } catch (e: Exception) {
            logger.error(Component.text("Failed to save punishment: ${e.message}", NamedTextColor.RED))
            false
        }
    }

    /**
     * Find a punishment by its ID
     */
    suspend fun findPunishmentById(id: String): Punishment? {
        return try {
            val doc = collection.find(Document("_id", id)).awaitFirstOrNull()
            doc?.let { Punishment.fromDocument(it) }
        } catch (e: Exception) {
            logger.error(Component.text("Failed to find punishment by ID: ${e.message}", NamedTextColor.RED))
            null
        }
    }

    /**
     * Find active punishments for a player by UUID
     */
    suspend fun findActivePunishments(playerId: String): List<Punishment> {
        return try {
            val filter = Document("playerId", playerId).append("active", true)
            collection.find(filter)
                .asFlow()
                .toList()
                .map { Punishment.fromDocument(it) }
                .filter { !it.isExpired() } // Double-check expiration
        } catch (e: Exception) {
            logger.error(Component.text("Failed to find active punishments: ${e.message}", NamedTextColor.RED))
            emptyList()
        }
    }

    /**
     * Find active punishments by IP address
     */
    suspend fun findActivePunishmentsByIp(ip: String): List<Punishment> {
        return try {
            val filter = Document("ip", ip).append("active", true)
            collection.find(filter)
                .asFlow()
                .toList()
                .map { Punishment.fromDocument(it) }
                .filter { !it.isExpired() }
        } catch (e: Exception) {
            logger.error(Component.text("Failed to find punishments by IP: ${e.message}", NamedTextColor.RED))
            emptyList()
        }
    }

    /**
     * Find active punishment of specific type for a player
     */
    suspend fun findActivePunishment(playerId: String, type: PunishmentType): Punishment? {
        return try {
            val filter = Document("playerId", playerId)
                .append("type", type.name)
                .append("active", true)

            val doc = collection.find(filter).awaitFirstOrNull()
            doc?.let {
                val punishment = Punishment.fromDocument(it)
                if (punishment.isExpired()) null else punishment
            }
        } catch (e: Exception) {
            logger.error(Component.text("Failed to find active punishment: ${e.message}", NamedTextColor.RED))
            null
        }
    }

    /**
     * Get punishment history for a player with pagination
     */
    suspend fun getPunishmentHistory(
        playerId: String,
        page: Int = 0,
        pageSize: Int = 10
    ): List<Punishment> {
        return try {
            val filter = Document("playerId", playerId)
            collection.find(filter)
                .sort(Document("issuedAt", -1))
                .skip(page * pageSize)
                .limit(pageSize)
                .asFlow()
                .toList()
                .map { Punishment.fromDocument(it) }
        } catch (e: Exception) {
            logger.error(Component.text("Failed to get punishment history: ${e.message}", NamedTextColor.RED))
            emptyList()
        }
    }

    /**
     * Count total punishments for a player
     */
    suspend fun countPunishments(playerId: String): Long {
        return try {
            val filter = Document("playerId", playerId)
            collection.countDocuments(filter).awaitFirst()
        } catch (e: Exception) {
            logger.error(Component.text("Failed to count punishments: ${e.message}", NamedTextColor.RED))
            0L
        }
    }

    /**
     * Count active warnings for a player (for escalation logic)
     */
    suspend fun countActiveWarnings(playerId: String): Long {
        return try {
            val filter = Document("playerId", playerId)
                .append("type", PunishmentType.WARN.name)
                .append("active", true)

            collection.countDocuments(filter).awaitFirst()
        } catch (e: Exception) {
            logger.error(Component.text("Failed to count warnings: ${e.message}", NamedTextColor.RED))
            0L
        }
    }

    /**
     * Deactivate a punishment (for unbans, unmutes, etc.)
     */
    suspend fun deactivatePunishment(punishmentId: String): Boolean {
        return try {
            val filter = Document("_id", punishmentId)
            val update = Document("\$set", Document("active", false))

            val result = collection.updateOne(filter, update).awaitFirst()
            result.modifiedCount > 0
        } catch (e: Exception) {
            logger.error(Component.text("Failed to deactivate punishment: ${e.message}", NamedTextColor.RED))
            false
        }
    }

    /**
     * Deactivate all active warnings for a player (used after escalation)
     */
    suspend fun deactivateActiveWarnings(playerId: String): Boolean {
        return try {
            val filter = Document("playerId", playerId)
                .append("type", PunishmentType.WARN.name)
                .append("active", true)
            val update = Document("\$set", Document("active", false))

            collection.updateMany(filter, update).awaitFirst()
            true
        } catch (e: Exception) {
            logger.error(Component.text("Failed to deactivate warnings: ${e.message}", NamedTextColor.RED))
            false
        }
    }

    /**
     * Find expired punishments that need to be deactivated
     */
    suspend fun findExpiredPunishments(): List<Punishment> {
        return try {
            val now = Date.from(Instant.now())
            val filter = Document("active", true)
                .append("expiresAt", Document("\$lte", now))

            collection.find(filter)
                .asFlow()
                .toList()
                .map { Punishment.fromDocument(it) }
        } catch (e: Exception) {
            logger.error(Component.text("Failed to find expired punishments: ${e.message}", NamedTextColor.RED))
            emptyList()
        }
    }

    /**
     * Batch deactivate multiple punishments
     */
    suspend fun deactivatePunishments(punishmentIds: List<String>): Boolean {
        return try {
            val filter = Document("_id", Document("\$in", punishmentIds))
            val update = Document("\$set", Document("active", false))

            collection.updateMany(filter, update).awaitFirst()
            true
        } catch (e: Exception) {
            logger.error(Component.text("Failed to batch deactivate punishments: ${e.message}", NamedTextColor.RED))
            false
        }
    }
}
