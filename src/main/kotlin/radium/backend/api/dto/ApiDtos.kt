package radium.backend.api.dto

import java.util.*

// General response DTOs
data class ErrorResponse(
    val error: String,
    val message: String? = null
)

data class HealthResponse(
    val status: String,
    val timestamp: Long
)

data class SuccessResponse(
    val success: Boolean = true,
    val message: String? = null
)

// Player-related DTOs
data class PlayerResponse(
    val username: String,
    val uuid: String,
    val server: String?,
    val isOnline: Boolean,
    val lastSeen: Long? = null
)

data class PlayerListResponse(
    val players: List<PlayerResponse>,
    val total: Int
)

// Server-related DTOs
data class ServerResponse(
    val name: String,
    val address: String,
    val playerCount: Int,
    val isOnline: Boolean
)

data class ServerListResponse(
    val servers: List<ServerResponse>,
    val totalPlayers: Int
)

// Profile-related DTOs
data class ProfileResponse(
    val username: String,
    val uuid: String,
    val rank: RankResponse?,
    val permissions: List<String>,
    val prefix: String?,
    val color: String?,
    val isVanished: Boolean = false,
    val lastSeen: Long? = null
)

// Rank-related DTOs
data class RankResponse(
    val name: String,
    val weight: Int,
    val prefix: String,
    val color: String,
    val permissions: List<String>
)

data class RankListResponse(
    val ranks: List<RankResponse>,
    val total: Int
)

// Permission-related DTOs
data class PermissionCheckResponse(
    val player: String,
    val permission: String,
    val hasPermission: Boolean
)

// Command-related DTOs
data class CommandRequest(
    val player: String,
    val command: String
)

data class CommandResponse(
    val player: String,
    val command: String,
    val success: Boolean,
    val executed: Boolean,
    val error: String? = null
)

// Transfer-related DTOs
data class TransferRequest(
    val player: String,
    val server: String
)

data class TransferResponse(
    val player: String,
    val server: String,
    val success: Boolean,
    val error: String? = null
)

// Message-related DTOs
data class MessageRequest(
    val from: String,
    val to: String,
    val message: String
)

data class GlobalMessageRequest(
    val message: String,
    val sender: String? = null
)

// Vanish-related DTOs
data class VanishRequest(
    val player: String,
    val vanished: Boolean
)

data class VanishResponse(
    val player: String,
    val vanished: Boolean,
    val success: Boolean
)

// Punishment-related DTOs
data class PunishmentRequest(
    val target: String,
    val type: String, // BAN, MUTE, WARN, KICK, etc.
    val reason: String,
    val staffId: String, // UUID or username of staff issuing punishment
    val duration: String? = null, // e.g., "1d", "2h", "30m"
    val silent: Boolean = false,
    val clearInventory: Boolean = false
)

data class PunishmentRevokeRequest(
    val target: String,
    val type: String, // BAN, MUTE, etc.
    val reason: String,
    val staffId: String, // UUID or username of staff revoking punishment
    val silent: Boolean = false
)

data class PunishmentResponse(
    val success: Boolean,
    val target: String,
    val type: String,
    val reason: String,
    val staff: String,
    val message: String? = null
)

data class PunishmentInfo(
    val id: String,
    val type: String,
    val reason: String,
    val issuedBy: String,
    val issuedAt: Long,
    val expiresAt: Long? = null,
    val active: Boolean,
    val revokedBy: String? = null,
    val revokedAt: Long? = null,
    val revokeReason: String? = null
)

data class PunishmentHistoryResponse(
    val target: String,
    val targetId: String,
    val totalPunishments: Int,
    val activePunishments: Int,
    val punishments: List<PunishmentInfo>
)
