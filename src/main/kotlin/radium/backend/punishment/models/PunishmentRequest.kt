package radium.backend.punishment.models

import com.velocitypowered.api.proxy.Player
import java.time.Instant
import java.util.*

/**
 * Represents a punishment request for batch processing
 * Used in the asynchronous punishment queue system
 */
data class PunishmentRequest(
    val id: String = UUID.randomUUID().toString(),
    val targetPlayer: Player? = null,
    val targetId: String,
    val targetName: String,
    val targetIp: String? = null,
    val type: PunishmentType,
    val reason: String,
    val staffId: String,
    val staffName: String,
    val duration: String? = null,
    val silent: Boolean = false,
    val clearInventory: Boolean = false,
    val requestedAt: Instant = Instant.now(),
    val priority: Priority = Priority.NORMAL,
    val metadata: Map<String, Any> = emptyMap()
) {
    enum class Priority(val level: Int) {
        LOW(1),
        NORMAL(2),
        HIGH(3),
        URGENT(4)
    }

    /**
     * Convert this request to an actual punishment
     */
    fun toPunishment(durationMs: Long? = null, expiresAt: Instant? = null): Punishment {
        return Punishment(
            playerId = targetId,
            playerName = targetName,
            ip = targetIp,
            type = type,
            reason = reason,
            issuedBy = staffId,
            issuedByName = staffName,
            durationMs = durationMs,
            expiresAt = expiresAt,
            silent = silent,
            clearedInventory = clearInventory,
            meta = metadata
        )
    }
}
