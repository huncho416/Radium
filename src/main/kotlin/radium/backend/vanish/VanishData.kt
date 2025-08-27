package radium.backend.vanish

import java.util.UUID

/**
 * Represents vanish state data for a player
 */
data class VanishData(
    val playerId: UUID,
    val vanishTime: Long,
    val level: VanishLevel,
    val vanishedBy: UUID? = null, // Who vanished this player (for admin vanish)
    val reason: String? = null    // Reason for vanish (for admin vanish)
) {
    companion object {
        fun create(playerId: UUID, level: VanishLevel, vanishedBy: UUID? = null, reason: String? = null): VanishData {
            return VanishData(
                playerId = playerId,
                vanishTime = System.currentTimeMillis(),
                level = level,
                vanishedBy = vanishedBy,
                reason = reason
            )
        }
    }
    
    /**
     * Get duration vanished in milliseconds
     */
    fun getVanishDuration(): Long {
        return System.currentTimeMillis() - vanishTime
    }
    
    /**
     * Get formatted vanish duration
     */
    fun getFormattedDuration(): String {
        val duration = getVanishDuration()
        val seconds = duration / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        
        return when {
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }
}
