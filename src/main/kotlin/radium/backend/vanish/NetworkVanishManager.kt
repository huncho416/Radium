package radium.backend.vanish

import com.google.common.io.ByteArrayDataOutput
import com.google.common.io.ByteStreams
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
    fun setVanishState(player: Player, vanished: Boolean, level: VanishLevel? = null, vanishedBy: Player? = null, reason: String? = null): Boolean {
        val currentlyVanished = isVanished(player.uniqueId)
        
        if (vanished == currentlyVanished) {
            return false // No change needed
        }
        
        if (vanished) {
            val vanishLevel = level ?: VanishLevel.fromPermissionLevel(player)
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
    fun canSeeVanished(viewer: Player, vanishedPlayerId: UUID): Boolean {
        val vanishData = getVanishData(vanishedPlayerId) ?: return true
        return VanishLevel.canSeeVanished(viewer, vanishData.level)
    }
    
    /**
     * Hide player from tab list (except for staff who can see them)
     */
    private fun hideFromTabList(vanishedPlayer: Player, vanishData: VanishData) {
        radium.server.allPlayers.forEach { viewer ->
            val tabList = viewer.tabList
            
            if (VanishLevel.canSeeVanished(viewer, vanishData.level)) {
                // Show with vanish indicator for staff
                val displayName = Component.text()
                    .append(Component.text("§7[V] ", NamedTextColor.GRAY))
                    .append(Component.text(vanishedPlayer.username))
                    .build()
                
                // Update existing entry or create new one
                val existingEntry = tabList.getEntry(vanishedPlayer.uniqueId)
                if (existingEntry.isPresent) {
                    existingEntry.get().setDisplayName(displayName)
                } else {
                    // For Velocity, we need to ensure the player appears in the tab list first
                    // The tab list entry should already exist from Velocity's default behavior
                    // We just update the display name if missing
                    radium.logger.warn("Player ${vanishedPlayer.username} missing from ${viewer.username}'s tab list")
                }
            } else {
                // Remove from tab list for non-staff
                tabList.removeEntry(vanishedPlayer.uniqueId)
            }
        }
    }
    
    /**
     * Show player in tab list for everyone
     */
    private fun showInTabList(player: Player) {
        radium.server.allPlayers.forEach { viewer ->
            val tabList = viewer.tabList
            
            // Check if entry exists and update it, or create new one
            val existingEntry = tabList.getEntry(player.uniqueId)
            if (existingEntry.isPresent) {
                existingEntry.get().setDisplayName(Component.text(player.username))
            } else {
                // For Velocity, we rely on the TabListManager to properly handle tab entries
                // We just update the display name for existing entries
                radium.logger.warn("Player ${player.username} missing from ${viewer.username}'s tab list")
            }
        }
    }
    
    /**
     * Update tab list visibility for all players when a new player joins
     */
    fun updateTabListForNewPlayer(newPlayer: Player) {
        val newPlayerTabList = newPlayer.tabList
        
        // Add visible players to new player's tab list
        radium.server.allPlayers.forEach { existingPlayer ->
            if (existingPlayer.uniqueId == newPlayer.uniqueId) return@forEach
            
            val vanishData = getVanishData(existingPlayer.uniqueId)
            if (vanishData == null || VanishLevel.canSeeVanished(newPlayer, vanishData.level)) {
                val displayName = if (vanishData != null && VanishLevel.canSeeVanished(newPlayer, vanishData.level)) {
                    Component.text()
                        .append(Component.text("§7[V] ", NamedTextColor.GRAY))
                        .append(Component.text(existingPlayer.username))
                        .build()
                } else {
                    Component.text(existingPlayer.username)
                }
                
                // For Velocity, tab list entries are automatically managed
                // We just need to update display names for existing entries
                val existingEntry = newPlayerTabList.getEntry(existingPlayer.uniqueId)
                if (existingEntry.isPresent) {
                    existingEntry.get().setDisplayName(displayName)
                }
            }
        }
        
        // Update other players' tab lists if new player is vanished
        val newPlayerVanishData = getVanishData(newPlayer.uniqueId)
        if (newPlayerVanishData != null) {
            hideFromTabList(newPlayer, newPlayerVanishData)
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
        val out: ByteArrayDataOutput = ByteStreams.newDataOutput()
        out.writeUTF("VANISH_BATCH_UPDATE")
        out.writeInt(updates.size)
        
        updates.forEach { (playerId, vanished) ->
            out.writeUTF(playerId.toString())
            out.writeBoolean(vanished)
        }
        
        // Send to all servers
        radium.server.allServers.forEach { server ->
            try {
                server.sendPluginMessage(VANISH_CHANNEL, out.toByteArray())
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
            val out: ByteArrayDataOutput = ByteStreams.newDataOutput()
            out.writeUTF("VANISH_STATE")
            out.writeUTF(playerId.toString())
            out.writeBoolean(true)
            out.writeInt(vanishData.level.level)
            
            try {
                event.server.sendPluginMessage(VANISH_CHANNEL, out.toByteArray())
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
            delay(100) // Wait for player to fully connect
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
