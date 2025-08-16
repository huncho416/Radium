package radium.backend.nametag

import java.time.Duration
import java.util.*

/**
 * Public API for the nametag system
 */
object NametagAPI {
    
    private lateinit var nameTagService: NameTagService
    
    /**
     * Initializes the API with the service instance
     */
    internal fun initialize(service: NameTagService) {
        nameTagService = service
    }
    
    /**
     * Sets a temporary template override for a player
     * 
     * @param playerId The UUID of the player
     * @param miniMessage The MiniMessage template to apply
     * @param ttl Time to live for the override, defaults to 30 seconds
     */
    suspend fun setTemporaryTemplate(playerId: UUID, miniMessage: String, ttl: Duration? = null) {
        if (::nameTagService.isInitialized) {
            nameTagService.setTemporaryTemplate(playerId, miniMessage, ttl)
        }
    }
    
    /**
     * Refreshes the nametag for a specific player
     * 
     * @param playerId The UUID of the player to refresh
     */
    fun refresh(playerId: UUID) {
        if (::nameTagService.isInitialized) {
            // Find the player and update their nametag
            nameTagService.radium.server.getPlayer(playerId).ifPresent { player ->
                nameTagService.updateFor(player, "api_refresh")
            }
        }
    }
    
    /**
     * Refreshes all online players' nametags
     */
    fun refreshAll() {
        if (::nameTagService.isInitialized) {
            nameTagService.radium.server.allPlayers.forEach { player ->
                nameTagService.updateFor(player, "api_refresh_all")
            }
        }
    }
    
    /**
     * Reloads the nametag configuration and refreshes all tags
     */
    suspend fun reload() {
        if (::nameTagService.isInitialized) {
            nameTagService.reload()
        }
    }
    
    /**
     * Checks if the API is initialized and ready to use
     */
    fun isInitialized(): Boolean = ::nameTagService.isInitialized
}
