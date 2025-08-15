package radium.backend.punishment.events

import radium.backend.punishment.models.Punishment
import radium.backend.punishment.models.PunishmentType

/**
 * Events emitted by the punishment system
 * Following the pattern of other event classes in the project
 */
sealed class PunishmentEvent {
    abstract val punishment: Punishment
    abstract val timestamp: Long
}

/**
 * Fired when a new punishment is issued
 */
data class PunishmentIssued(
    override val punishment: Punishment,
    val staffId: String,
    val staffName: String,
    override val timestamp: Long = System.currentTimeMillis()
) : PunishmentEvent()

/**
 * Fired when a punishment is revoked (unban, unmute, etc.)
 */
data class PunishmentRevoked(
    override val punishment: Punishment,
    val revokedBy: String,
    val revokedByName: String,
    val reason: String,
    override val timestamp: Long = System.currentTimeMillis()
) : PunishmentEvent()

/**
 * Fired when a punishment expires automatically
 */
data class PunishmentExpired(
    override val punishment: Punishment,
    override val timestamp: Long = System.currentTimeMillis()
) : PunishmentEvent()

/**
 * Fired when warnings are escalated to a ban
 */
data class WarningsEscalated(
    val playerId: String,
    val playerName: String,
    val warningCount: Int,
    val resultingBan: Punishment,
    override val timestamp: Long = System.currentTimeMillis()
) : PunishmentEvent() {
    override val punishment: Punishment = resultingBan
}
