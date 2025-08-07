package radium.backend.player

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import com.velocitypowered.api.event.player.PlayerChatEvent
import com.velocitypowered.api.proxy.Player
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import radium.backend.Radium

class TabListManager(private val radium: Radium) {

    /**
     * Updates the tab list display name for a player
     */
    suspend fun updatePlayerTabList(player: Player) {
        try {
            // Get player profile to access rank information
            val profile = radium.connectionHandler.findPlayerProfile(player.uniqueId.toString())
            if (profile != null) {
                // Get highest rank for prefix and color
                val highestRank = profile.getHighestRank(radium.rankManager)
                val prefix = highestRank?.prefix ?: ""
                val color = highestRank?.color ?: "&7"
                
                // Debug logging
                radium.logger.info("Updating tab list for ${player.username}: highest rank = ${highestRank?.name ?: "none"}, prefix = '$prefix', color = '$color'")
                
                // Format: (prefix)(name) with rank color
                val tabDisplayName = radium.yamlFactory.getMessageComponent("tab.player_format",
                    "prefix" to prefix,
                    "player" to player.username,
                    "color" to color
                )
                
                // Update this player's entry in ALL other players' tab lists
                radium.server.allPlayers.forEach { otherPlayer ->
                    val tabEntry = otherPlayer.tabList.getEntry(player.uniqueId)
                    if (tabEntry.isPresent) {
                        tabEntry.get().setDisplayName(tabDisplayName)
                    }
                }
            } else {
                radium.logger.info("No profile found for ${player.username}, using default format")
                // Fallback if profile not found - just show username in gray
                val defaultDisplayName = radium.yamlFactory.getMessageComponent("tab.default_format",
                    "player" to player.username
                )
                
                // Update this player's entry in ALL other players' tab lists
                radium.server.allPlayers.forEach { otherPlayer ->
                    val tabEntry = otherPlayer.tabList.getEntry(player.uniqueId)
                    if (tabEntry.isPresent) {
                        tabEntry.get().setDisplayName(defaultDisplayName)
                    }
                }
            }
        } catch (e: Exception) {
            radium.logger.warn("Failed to update tab list for ${player.username}: ${e.message}")
        }
    }

    /**
     * Updates tab list for all online players
     */
    suspend fun updateAllPlayersTabList() {
        radium.server.allPlayers.forEach { player ->
            updatePlayerTabList(player)
        }
    }

    @Subscribe
    fun onPlayerConnect(event: ServerPostConnectEvent) {
        GlobalScope.launch {
            // Update the connecting player's tab list
            updatePlayerTabList(event.player)
            
            // Update all other players' tab lists in case rank changes affect display
            updateAllPlayersTabList()
        }
    }
}
