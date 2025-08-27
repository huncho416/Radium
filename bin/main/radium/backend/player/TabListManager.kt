package radium.backend.player

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import com.velocitypowered.api.event.player.PlayerChatEvent
import com.velocitypowered.api.proxy.Player
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
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
            val isVanished = radium.staffManager.isVanished(player)
            
            if (profile != null) {
                // Get highest rank for prefix and color
                val highestRank = profile.getHighestRank(radium.rankManager)
                
                // Use tab-specific prefix if available, otherwise fall back to regular prefix
                val tabPrefix = highestRank?.tabPrefix ?: highestRank?.prefix ?: ""
                val tabSuffix = highestRank?.tabSuffix ?: ""
                val color = highestRank?.color ?: "&7"
                
                // Base tab display format with tab-specific formatting
                val baseTabDisplayName = radium.yamlFactory.getMessageComponent("tab.player_format",
                    "prefix" to tabPrefix,
                    "player" to player.username,
                    "color" to color,
                    "suffix" to tabSuffix
                )
                
                // Update this player's entry in ALL other players' tab lists
                radium.server.allPlayers.forEach { otherPlayer ->
                    if (otherPlayer.uniqueId != player.uniqueId) {
                        val canSeePlayer = if (isVanished) {
                            radium.staffManager.canSeeVanishedPlayerSync(otherPlayer, player)
                        } else {
                            true // Always visible when not vanished
                        }
                        
                        val tabEntry = otherPlayer.tabList.getEntry(player.uniqueId)
                        
                        if (canSeePlayer) {
                            // Player should be visible - update display name
                            if (tabEntry.isPresent) {
                                val finalDisplayName = if (isVanished) {
                                    baseTabDisplayName.append(Component.text(" (V)"))
                                } else {
                                    baseTabDisplayName
                                }
                                tabEntry.get().setDisplayName(finalDisplayName)
                            }
                        } else {
                            // Player should not be visible - remove from tab list
                            if (tabEntry.isPresent) {
                                otherPlayer.tabList.removeEntry(player.uniqueId)
                                radium.logger.debug("Removed vanished ${player.username} from ${otherPlayer.username}'s tab")
                            }
                        }
                    }
                }
            } else {
                radium.logger.info("No profile found for ${player.username}, using default format")
                // Fallback if profile not found - just show username in gray
                val baseDefaultDisplayName = radium.yamlFactory.getMessageComponent("tab.default_format",
                    "player" to player.username
                )
                
                // Update this player's entry in ALL other players' tab lists
                radium.server.allPlayers.forEach { otherPlayer ->
                    if (otherPlayer.uniqueId != player.uniqueId) {
                        val canSeePlayer = if (isVanished) {
                            radium.staffManager.canSeeVanishedPlayerSync(otherPlayer, player)
                        } else {
                            true // Always visible when not vanished
                        }
                        
                        val tabEntry = otherPlayer.tabList.getEntry(player.uniqueId)
                        
                        if (canSeePlayer) {
                            // Player should be visible - update display name
                            if (tabEntry.isPresent) {
                                val finalDisplayName = if (isVanished) {
                                    baseDefaultDisplayName.append(Component.text(" (V)"))
                                } else {
                                    baseDefaultDisplayName
                                }
                                tabEntry.get().setDisplayName(finalDisplayName)
                            }
                        } else {
                            // Player should not be visible - remove from tab list
                            if (tabEntry.isPresent) {
                                otherPlayer.tabList.removeEntry(player.uniqueId)
                                radium.logger.debug("Removed vanished ${player.username} from ${otherPlayer.username}'s tab")
                            }
                        }
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

    /**
     * Ensures a player is visible in all appropriate tab lists
     * This handles re-adding players who were removed due to vanish
     */
    suspend fun ensurePlayerVisibleInTabLists(player: Player) {
        try {
            val isVanished = radium.staffManager.isVanished(player)
            var needsFullRefresh = false
            
            radium.logger.debug("Ensuring ${player.username} is visible in tab lists (vanished: $isVanished)")
            
            // For each online player, determine if they should see this player
            radium.server.allPlayers.forEach { otherPlayer ->
                if (otherPlayer.uniqueId != player.uniqueId) {
                    val shouldBeVisible = if (isVanished) {
                        // Check if the viewer can see vanished players using rank-based logic
                        radium.staffManager.canSeeVanishedPlayerSync(otherPlayer, player)
                    } else {
                        true // Always visible when not vanished
                    }
                    
                    val tabEntry = otherPlayer.tabList.getEntry(player.uniqueId)
                    
                    if (shouldBeVisible && !tabEntry.isPresent) {
                        // Player should be visible but is missing from tab list
                        // This happens after unvanishing - Velocity doesn't automatically re-add them
                        radium.logger.debug("${player.username} missing from ${otherPlayer.username}'s tab - needs refresh")
                        needsFullRefresh = true
                    }
                    
                    if (!shouldBeVisible && tabEntry.isPresent) {
                        // Player should not be visible but is in tab list
                        otherPlayer.tabList.removeEntry(player.uniqueId)
                        radium.logger.debug("Removed ${player.username} from ${otherPlayer.username}'s tab (vanish)")
                    }
                }
            }
            
            if (needsFullRefresh) {
                // The most reliable way to ensure missing players reappear is to trigger 
                // Velocity's internal tab list synchronization by forcing a full refresh
                radium.logger.debug("Triggering full tab refresh to restore ${player.username}")
                
                // Force a complete tab list rebuild for all players
                GlobalScope.launch {
                    delay(100) // Small delay to ensure vanish state is properly updated
                    
                    // Update all players' tab displays to trigger refresh
                    radium.server.allPlayers.forEach { allPlayer ->
                        updatePlayerTabList(allPlayer)
                    }
                    
                    // Also specifically update this player's tab entry everywhere
                    updatePlayerTabList(player)
                    
                    radium.logger.debug("Full tab refresh completed for ${player.username}")
                }
            } else {
                // Just update the display names for this player
                updatePlayerTabList(player)
                radium.logger.debug("Updated tab display for ${player.username}")
            }
            
        } catch (e: Exception) {
            radium.logger.warn("Failed to ensure ${player.username} is visible in tab lists: ${e.message}")
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
