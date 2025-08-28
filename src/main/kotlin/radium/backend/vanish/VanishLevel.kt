package radium.backend.vanish

import radium.backend.Radium
import kotlinx.coroutines.runBlocking

/**
 * Different vanish levels for rank weight-based visibility
 */
enum class VanishLevel(val minWeight: Int, val displayName: String) {
    HELPER(10, "Helper"),        // Hidden from players (weight < 10)
    MODERATOR(50, "Moderator"),  // Hidden from helpers and players (weight < 50)
    ADMIN(100, "Admin"),         // Hidden from all non-admins (weight < 100)
    OWNER(1000, "Owner");        // Hidden from everyone (weight < 1000)
    
    companion object {
        /**
         * Determine vanish level based on player's rank weight
         */
        suspend fun fromRankWeight(player: com.velocitypowered.api.proxy.Player, radium: Radium): VanishLevel {
            return try {
                val profile = radium.connectionHandler.findPlayerProfile(player.uniqueId.toString())
                val highestRank = profile?.getHighestRank(radium.rankManager)
                val weight = highestRank?.weight ?: 0
                
                when {
                    weight >= OWNER.minWeight -> OWNER
                    weight >= ADMIN.minWeight -> ADMIN
                    weight >= MODERATOR.minWeight -> MODERATOR
                    else -> HELPER
                }
            } catch (e: Exception) {
                radium.logger.warn("Failed to get rank weight for ${player.username}, defaulting to HELPER: ${e.message}")
                HELPER
            }
        }
        
        /**
         * Fallback method using permissions (for backward compatibility)
         */
        fun fromPermissionLevel(player: com.velocitypowered.api.proxy.Player): VanishLevel {
            return when {
                player.hasPermission("radium.vanish.owner") -> OWNER
                player.hasPermission("radium.vanish.admin") -> ADMIN
                player.hasPermission("radium.vanish.moderator") -> MODERATOR
                player.hasPermission("radium.vanish.helper") -> HELPER
                else -> HELPER // Default to lowest level if they have vanish permission
            }
        }
        
        /**
         * Check if viewer can see a vanished player based on rank weight
         */
        suspend fun canSeeVanished(viewer: com.velocitypowered.api.proxy.Player, vanishedLevel: VanishLevel, radium: Radium): Boolean {
            return try {
                // Special permission overrides rank weight
                if (viewer.hasPermission("radium.vanish.see")) {
                    return true
                }
                
                val viewerProfile = radium.connectionHandler.findPlayerProfile(viewer.uniqueId.toString())
                val viewerHighestRank = viewerProfile?.getHighestRank(radium.rankManager)
                val viewerWeight = viewerHighestRank?.weight ?: 0
                
                // Viewer can see vanished player if their rank weight meets the vanish level requirement
                viewerWeight >= vanishedLevel.minWeight
            } catch (e: Exception) {
                radium.logger.warn("Failed to check vanish visibility for ${viewer.username}: ${e.message}")
                false
            }
        }
        
        /**
         * Fallback synchronous method using permissions (for compatibility)
         */
        fun canSeeVanished(viewer: com.velocitypowered.api.proxy.Player, vanishedLevel: VanishLevel): Boolean {
            val viewerLevel = fromPermissionLevel(viewer)
            return viewerLevel.minWeight >= vanishedLevel.minWeight
        }
    }
}
