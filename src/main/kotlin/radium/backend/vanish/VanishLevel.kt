package radium.backend.vanish

/**
 * Different vanish levels for permission-based visibility
 */
enum class VanishLevel(val level: Int, val displayName: String) {
    HELPER(1, "Helper"),         // Hidden from players
    MODERATOR(2, "Moderator"),   // Hidden from helpers and players  
    ADMIN(3, "Admin"),           // Hidden from all non-admins
    OWNER(4, "Owner");           // Hidden from everyone
    
    companion object {
        fun fromPermissionLevel(player: com.velocitypowered.api.proxy.Player): VanishLevel {
            return when {
                player.hasPermission("radium.vanish.owner") -> OWNER
                player.hasPermission("radium.vanish.admin") -> ADMIN
                player.hasPermission("radium.vanish.moderator") -> MODERATOR
                player.hasPermission("radium.vanish.helper") -> HELPER
                else -> HELPER // Default to lowest level if they have vanish permission
            }
        }
        
        fun canSeeVanished(viewer: com.velocitypowered.api.proxy.Player, vanishedLevel: VanishLevel): Boolean {
            val viewerLevel = fromPermissionLevel(viewer)
            return viewerLevel.level >= vanishedLevel.level
        }
    }
}
