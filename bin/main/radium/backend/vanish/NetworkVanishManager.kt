package radium.backend.vanish

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import radium.backend.Radium
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Network-wide vanish system that manages vanish state across all servers
 */
class NetworkVanishManager(private val radium: Radium) {
    
    companion object {
        val VANISH_CHANNEL = MinecraftChannelIdentifier.create("radium", "vanish")
    }
    
    private val vanishedPlayers = ConcurrentHashMap<UUID, VanishData>()
    private val pendingUpdates = ConcurrentHashMap<UUID, Boolean>()
    private var batcherStarted = false
    
    init {
        // Register plugin message channel
        radium.server.channelRegistrar.register(VANISH_CHANNEL)
    }
    
    /**
     * Set vanish state for a player
     */
    suspend fun setVanishState(player: Player, vanished: Boolean, level: VanishLevel? = null, vanishedBy: Player? = null, reason: String? = null): Boolean {
        val currentlyVanished = isVanished(player.uniqueId)
        
        if (vanished == currentlyVanished) {
            return false // No change needed
        }
        
        if (vanished) {
            val vanishLevel = level ?: VanishLevel.fromRankWeight(player, radium)
            val vanishData = VanishData.create(
                playerId = player.uniqueId,
                level = vanishLevel,
                vanishedBy = vanishedBy?.uniqueId,
                reason = reason
            )
            vanishedPlayers[player.uniqueId] = vanishData
            hideFromTabList(player, vanishData)
        } else {
            vanishedPlayers.remove(player.uniqueId)
            showInTabList(player)
        }
        
        // Schedule batch update
        scheduleVanishUpdate(player.uniqueId, vanished)
        
        radium.logger.info("Player ${player.username} vanish state changed to: $vanished")
        return true
    }
    
    /**
     * Set vanish state for a player (convenience method for non-async callers)
     */
    fun setVanishStateAsync(player: Player, vanished: Boolean, level: VanishLevel? = null, vanishedBy: Player? = null, reason: String? = null) {
        radium.scope.launch {
            setVanishState(player, vanished, level, vanishedBy, reason)
        }
    }
    
    /**
     * Check if a player is vanished
     */
    fun isVanished(playerId: UUID): Boolean {
        return vanishedPlayers.containsKey(playerId)
    }
    
    /**
     * Get vanish data for a player
     */
    fun getVanishData(playerId: UUID): VanishData? {
        return vanishedPlayers[playerId]
    }
    
    /**
     * Get all vanished players
     */
    fun getVanishedPlayers(): Map<UUID, VanishData> {
        return vanishedPlayers.toMap()
    }
    
    /**
     * Check if viewer can see a vanished player
     */
    suspend fun canSeeVanished(viewer: Player, vanishedPlayerId: UUID): Boolean {
        val vanishData = getVanishData(vanishedPlayerId) ?: return true
        return VanishLevel.canSeeVanished(viewer, vanishData.level, radium)
    }
    
    /**
     * Check if viewer can see a vanished player (convenience method for non-async callers)
     */
    fun canSeeVanishedAsync(viewer: Player, vanishedPlayerId: UUID, callback: (Boolean) -> Unit) {
        radium.scope.launch {
            val result = canSeeVanished(viewer, vanishedPlayerId)
            callback(result)
        }
    }
    
    /**
     * Hide player from tab list (except for staff who can see them)
     */
    private suspend fun hideFromTabList(vanishedPlayer: Player, vanishData: VanishData) {
        radium.server.allPlayers.forEach { viewer ->
            val tabList = viewer.tabList
            
            if (VanishLevel.canSeeVanished(viewer, vanishData.level, radium)) {
                // Show with vanish indicator for staff who can see them
                updateTabListEntryWithVanishIndicator(viewer, vanishedPlayer, vanishData)
            } else {
                // Remove from tab list for players who cannot see them
                tabList.removeEntry(vanishedPlayer.uniqueId)
            }
        }
    }
    
    /**
     * Update a tab list entry with vanish indicator for staff
     */
    private suspend fun updateTabListEntryWithVanishIndicator(viewer: Player, vanishedPlayer: Player, vanishData: VanishData) {
        try {
            val tabList = viewer.tabList
            val existingEntry = tabList.getEntry(vanishedPlayer.uniqueId)
            
            if (existingEntry.isPresent) {
                // Wait a moment for rank data to be available
                kotlinx.coroutines.delay(50)
                
                // Get player profile for rank-based formatting
                val profile = radium.connectionHandler.findPlayerProfile(vanishedPlayer.uniqueId.toString())
                val effectiveRank = profile?.getEffectiveRank(radium.rankManager)
                
                // Build display name with vanish indicator and rank prefix
                val vanishIndicator = radium.yamlFactory.getMessageComponent("vanish.tablist_indicator")
                val rankPrefix = effectiveRank?.tabPrefix ?: effectiveRank?.prefix ?: ""
                
                val displayName = Component.text()
                    .append(vanishIndicator)
                    .append(Component.text(rankPrefix))
                    .append(Component.text(vanishedPlayer.username))
                    .build()
                
                existingEntry.get().setDisplayName(displayName)
            } else {
                radium.logger.debug("Player ${vanishedPlayer.username} missing from ${viewer.username}'s tab list during vanish update")
            }
        } catch (e: Exception) {
            radium.logger.warn("Failed to update tab list entry for vanished player ${vanishedPlayer.username}: ${e.message}")
        }
    }
    
    /**
     * Show player in tab list for everyone (refresh tab list properly on unvanish)
     */
    private suspend fun showInTabList(player: Player) {
        // Wait for rank data to be available
        kotlinx.coroutines.delay(100)
        
        radium.server.allPlayers.forEach { viewer ->
            try {
                val tabList = viewer.tabList
                val existingEntry = tabList.getEntry(player.uniqueId)
                
                if (existingEntry.isPresent) {
                    // Get player profile for proper rank-based formatting
                    val profile = radium.connectionHandler.findPlayerProfile(player.uniqueId.toString())
                    val highestRank = profile?.getHighestRank(radium.rankManager)
                    
                    // Build proper display name with rank formatting
                    val rankPrefix = highestRank?.tabPrefix ?: highestRank?.prefix ?: ""
                    val rankSuffix = highestRank?.tabSuffix ?: highestRank?.suffix ?: ""
                    
                    val displayName = Component.text()
                        .append(Component.text(rankPrefix))
                        .append(Component.text(player.username))
                        .append(Component.text(rankSuffix))
                        .build()
                    
                    existingEntry.get().setDisplayName(displayName)
                    radium.logger.debug("Updated tab list entry for unvanished player ${player.username} for viewer ${viewer.username}")
                } else {
                    radium.logger.debug("Player ${player.username} missing from ${viewer.username}'s tab list during unvanish")
                }
            } catch (e: Exception) {
                radium.logger.warn("Failed to update tab list for unvanished player ${player.username} for viewer ${viewer.username}: ${e.message}")
            }
        }
    }
    
    /**
     * Update tab list visibility for all players when a new player joins
     */
    suspend fun updateTabListForNewPlayer(newPlayer: Player) {
        try {
            // Wait for rank data to be available
            kotlinx.coroutines.delay(150)
            
            val newPlayerTabList = newPlayer.tabList
            
            // Add visible players to new player's tab list
            radium.server.allPlayers.forEach { existingPlayer ->
                if (existingPlayer.uniqueId == newPlayer.uniqueId) return@forEach
                
                try {
                    val vanishData = getVanishData(existingPlayer.uniqueId)
                    if (vanishData == null || VanishLevel.canSeeVanished(newPlayer, vanishData.level, radium)) {
                        val existingEntry = newPlayerTabList.getEntry(existingPlayer.uniqueId)
                        if (existingEntry.isPresent) {
                            val displayName =                            if (vanishData != null && VanishLevel.canSeeVanished(newPlayer, vanishData.level, radium)) {
                                // Show with vanish indicator for staff
                                val vanishIndicator = radium.yamlFactory.getMessageComponent("vanish.tablist_indicator")
                                val profile = radium.connectionHandler.findPlayerProfile(existingPlayer.uniqueId.toString())
                                val effectiveRank = profile?.getEffectiveRank(radium.rankManager)
                                val rankPrefix = effectiveRank?.tabPrefix ?: effectiveRank?.prefix ?: ""
                                
                                Component.text()
                                    .append(vanishIndicator)
                                    .append(Component.text(rankPrefix))
                                    .append(Component.text(existingPlayer.username))
                                    .build()
                            } else {
                                // Show normally with rank formatting
                                val profile = radium.connectionHandler.findPlayerProfile(existingPlayer.uniqueId.toString())
                                val effectiveRank = profile?.getEffectiveRank(radium.rankManager)
                                val rankPrefix = effectiveRank?.tabPrefix ?: effectiveRank?.prefix ?: ""
                                val rankSuffix = effectiveRank?.tabSuffix ?: effectiveRank?.suffix ?: ""
                                
                                Component.text()
                                    .append(Component.text(rankPrefix))
                                    .append(Component.text(existingPlayer.username))
                                    .append(Component.text(rankSuffix))
                                    .build()
                            }
                            
                            existingEntry.get().setDisplayName(displayName)
                        }
                    }
                } catch (e: Exception) {
                    radium.logger.warn("Failed to update tab entry for existing player ${existingPlayer.username} for new player ${newPlayer.username}: ${e.message}")
                }
            }
            
            // Update other players' tab lists if new player is vanished
            val newPlayerVanishData = getVanishData(newPlayer.uniqueId)
            if (newPlayerVanishData != null) {
                hideFromTabList(newPlayer, newPlayerVanishData)
            }
        } catch (e: Exception) {
            radium.logger.warn("Failed to update tab list for new player ${newPlayer.username}: ${e.message}")
        }
    }
    
    /**
     * Refresh tab list for all players (useful for manual refresh)
     */
    suspend fun refreshAllTabLists() {
        try {
            radium.server.allPlayers.forEach { player ->
                updateTabListForNewPlayer(player)
            }
            radium.logger.info("Refreshed tab lists for all players")
        } catch (e: Exception) {
            radium.logger.warn("Failed to refresh all tab lists: ${e.message}")
        }
    }
    
    /**
     * Schedule a vanish update for batch processing
     */
    private fun scheduleVanishUpdate(playerId: UUID, vanished: Boolean) {
        // Start batcher if not already started
        startUpdateBatcher()
        pendingUpdates[playerId] = vanished
    }
    
    /**
     * Start the batch update processor (lazy initialization)
     */
    private fun startUpdateBatcher() {
        if (!batcherStarted) {
            batcherStarted = true
            radium.scope.launch {
                while (true) {
                    delay(50) // Process batch every 50ms
                    processBatchUpdates()
                }
            }
        }
    }
    
    /**
     * Process batch vanish updates
     */
    private fun processBatchUpdates() {
        if (pendingUpdates.isEmpty()) return
        
        val batch = HashMap(pendingUpdates)
        pendingUpdates.clear()
        
        // Send batch update to all servers
        sendBatchVanishUpdate(batch)
    }
    
    /**
     * Send batch vanish updates to all backend servers
     */
    private fun sendBatchVanishUpdate(updates: Map<UUID, Boolean>) {
        val batchUpdates = updates.map { (playerId, vanished) ->
            mapOf(
                "action" to "set_vanish",
                "player" to playerId.toString(),
                "vanished" to vanished,
                "level" to "HELPER" // Default level for batch updates
            )
        }
        
        val jsonMessage = mapOf(
            "action" to "batch_update",
            "updates" to batchUpdates
        )
        
        val jsonString = com.google.gson.Gson().toJson(jsonMessage)
        val messageBytes = jsonString.toByteArray(Charsets.UTF_8)
        
        // Send to all servers
        radium.server.allServers.forEach { server ->
            try {
                server.sendPluginMessage(VANISH_CHANNEL, messageBytes)
            } catch (e: Exception) {
                radium.logger.warn("Failed to send vanish update to server ${server.serverInfo.name}: ${e.message}")
            }
        }
    }
    
    /**
     * Handle player server connections
     */
    @Subscribe
    fun onServerSwitch(event: ServerConnectedEvent) {
        val player = event.player
        val playerId = player.uniqueId
        
        // If player is vanished, notify the destination server
        val vanishData = getVanishData(playerId)
        if (vanishData != null) {
            // Create JSON message for consistency with batch updates
            val jsonMessage = mapOf(
                "type" to "VANISH_STATE",
                "player_id" to playerId.toString(),
                "player_name" to player.username,
                "vanished" to true,
                "level" to vanishData.level.minWeight
            )
            
            val jsonString = com.google.gson.Gson().toJson(jsonMessage)
            val messageBytes = jsonString.toByteArray(Charsets.UTF_8)
            
            try {
                event.server.sendPluginMessage(VANISH_CHANNEL, messageBytes)
                radium.logger.debug("Notified server ${event.server.serverInfo.name} about vanished player ${player.username}")
            } catch (e: Exception) {
                radium.logger.warn("Failed to notify server about vanish state: ${e.message}")
            }
        }
    }
    
    /**
     * Handle initial server connections
     */
    @Subscribe
    fun onInitialServerConnect(event: PlayerChooseInitialServerEvent) {
        // Update tab list for the new player after a short delay
        radium.scope.launch {
            delay(200) // Wait for player to fully connect and rank data to be available
            val player = event.player
            updateTabListForNewPlayer(player)
        }
    }
    
    /**
     * Get vanish statistics for admin commands
     */
    fun getVanishStats(): Map<String, Any> {
        val stats = mutableMapOf<String, Any>()
        stats["total_vanished"] = vanishedPlayers.size
        stats["by_level"] = VanishLevel.values().associate { level ->
            level.displayName to vanishedPlayers.values.count { it.level == level }
        }
        return stats
    }
    
    /**
     * Initialize the vanish manager (call after plugin is fully loaded)
     */
    fun initialize() {
        startUpdateBatcher()
        radium.logger.info("NetworkVanishManager initialized successfully")
    }
}
