package radium.backend.nametag

import com.github.echolightmc.msnametags.NameTag
import com.github.echolightmc.msnametags.NameTagManager
import com.velocitypowered.api.proxy.Player
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import radium.backend.Radium
import radium.backend.player.Profile
import radium.backend.player.RankManager
import radium.backend.util.YamlFactory
import java.io.File
import java.io.FileInputStream
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Service for managing rank-based custom nametags using MSNameTags
 * This runs on the Velocity proxy and communicates with Minestom backends via Redis
 */
class NameTagService(
    internal val radium: Radium,
    private val yamlFactory: YamlFactory
) {
    
    private val miniMessage = MiniMessage.miniMessage()
    
    // Temporary template overrides: playerId -> (template, expiry)
    private val temporaryTemplates = ConcurrentHashMap<UUID, Pair<String, Instant>>()
    
    // Batch update system to prevent spam
    private val pendingUpdates = ConcurrentHashMap<UUID, String>()
    private val updateExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
    
    // Configuration cache
    private var config: Map<String, Any> = emptyMap()
    private var isEnabled = true
    private var defaultTemplate = "<gray><username></gray>"
    private var respectVanish = true
    private var weightGatingEnabled = true
    private var fallbackTemplate = "<dark_gray>•</dark_gray> <gray><username>"
    private var updateBatchMs = 75L
    
    init {
        loadConfiguration()
        startBatchProcessor()
        startCleanupTask()
    }
    
    /**
     * Loads configuration from nametags.yml
     */
    private fun loadConfiguration() {
        try {
            config = loadNametagConfig()
            
            isEnabled = config["enabled"] as? Boolean ?: true
            defaultTemplate = config["default-template"] as? String ?: "<gray><username></gray>"
            
            val visibility = config["visibility"] as? Map<String, Any> ?: emptyMap()
            respectVanish = visibility["respect-vanish"] as? Boolean ?: true
            
            val weightGating = visibility["weight-gating"] as? Map<String, Any> ?: emptyMap()
            weightGatingEnabled = weightGating["enabled"] as? Boolean ?: true
            fallbackTemplate = weightGating["fallback-template"] as? String ?: "<dark_gray>•</dark_gray> <gray><username>"
            
            val performance = config["performance"] as? Map<String, Any> ?: emptyMap()
            updateBatchMs = (performance["update-batch-ms"] as? Number)?.toLong() ?: 75L
            
        } catch (e: Exception) {
            radium.logger.error("Failed to load nametags configuration: ${e.message}")
        }
    }
    
    /**
     * Applies a nametag for the specified player by sending update to backend
     */
    suspend fun applyFor(player: Player) {
        if (!isEnabled) return
        
        try {
            val profile = radium.connectionHandler.getPlayerProfile(player.uniqueId)
            if (profile == null) {
                radium.logger.warn("No profile found for player ${player.username} (${player.uniqueId})")
                return
            }
            
            val template = resolveTemplate(profile)
            
            // Send nametag update command to backend via Redis
            publishNametagUpdate(player.uniqueId, template, "apply")
            
        } catch (e: Exception) {
            radium.logger.error("Failed to apply nametag for ${player.username}: ${e.message}")
        }
    }
    
    /**
     * Removes a nametag for the specified player
     */
    fun removeFor(player: Player) {
        temporaryTemplates.remove(player.uniqueId)
        pendingUpdates.remove(player.uniqueId)
        
        // Send nametag remove command to backend via Redis
        publishNametagUpdate(player.uniqueId, "", "remove")
    }
    
    /**
     * Updates a nametag for the specified player with a reason
     */
    fun updateFor(player: Player, reason: String = "unknown") {
        if (!isEnabled) return
        
        // Add to batch update queue
        pendingUpdates[player.uniqueId] = reason
    }
    
    /**
     * Reloads configuration and refreshes all nametags
     */
    suspend fun reload() {
        loadConfiguration()
        
        // Send reload command to all backends
        publishNametagUpdate(UUID.randomUUID(), "", "reload")
        
        // Refresh all online players
        radium.server.allPlayers.forEach { player ->
            applyFor(player)
        }
        
        radium.logger.info("NameTag configuration reloaded and refresh commands sent to backends")
    }
    
    /**
     * Sets a temporary template override for a player
     */
    suspend fun setTemporaryTemplate(playerId: UUID, template: String, ttl: Duration? = null) {
        val expiry = if (ttl != null) {
            Instant.now().plus(ttl)
        } else {
            Instant.now().plusSeconds(30) // Default 30 seconds
        }
        
        temporaryTemplates[playerId] = Pair(template, expiry)
        
        // Send temporary template to backend
        publishNametagUpdate(playerId, template, "temporary")
        
        // Trigger immediate update
        radium.server.getPlayer(playerId).ifPresent { player ->
            radium.scope.launch {
                applyFor(player)
            }
        }
    }
    
    /**
     * Publishes nametag update to Redis for backend consumption
     */
    private fun publishNametagUpdate(playerId: UUID, template: String, action: String) {
        try {
            val message = mapOf(
                "uuid" to playerId.toString(),
                "template" to template,
                "action" to action,
                "timestamp" to System.currentTimeMillis().toString()
            ).entries.joinToString(",") { "${it.key}=${it.value}" }
            
            radium.lettuceCache.sync().publish("radium:nametag:update", message)
            
        } catch (e: Exception) {
            radium.logger.error("Failed to publish nametag update: ${e.message}")
        }
    }
    
    /**
     * Resolves the appropriate template for a player
     */
    private suspend fun resolveTemplate(profile: Profile): String {
        val playerId = profile.uuid
        
        // Check for temporary override first
        temporaryTemplates[playerId]?.let { (template, expiry) ->
            if (Instant.now().isBefore(expiry)) {
                return template
            } else {
                temporaryTemplates.remove(playerId)
            }
        }
        
        // Get player's primary rank
        val primaryRank = profile.getHighestRank(radium.rankManager)
        
        // Check if rank has template override
        primaryRank?.nametagTemplate?.let { return it }
        
        // Check config for rank-specific template
        val ranks = config["ranks"] as? Map<String, Any> ?: emptyMap()
        val rankConfig = ranks[primaryRank?.name] as? Map<String, Any>
        rankConfig?.get("template")?.let { return it as String }
        
        // Fall back to default
        return defaultTemplate
    }
    
    /**
     * Gets rank configuration from config
     */
    private fun getRankConfig(rankName: String?): Map<String, Any>? {
        if (rankName == null) return null
        val ranks = config["ranks"] as? Map<String, Any> ?: return null
        return ranks[rankName] as? Map<String, Any>
    }
    
    /**
     * Starts the batch update processor
     */
    private fun startBatchProcessor() {
        updateExecutor.scheduleAtFixedRate({
            if (pendingUpdates.isNotEmpty()) {
                val updates = pendingUpdates.toMap()
                pendingUpdates.clear()
                
                updates.forEach { (playerId, reason) ->
                    try {
                        radium.server.getPlayer(playerId).ifPresent { player ->
                            radium.scope.launch {
                                applyFor(player)
                            }
                        }
                    } catch (e: Exception) {
                        radium.logger.error("Failed to process batch update for $playerId: ${e.message}")
                    }
                }
            }
        }, updateBatchMs, updateBatchMs, TimeUnit.MILLISECONDS)
    }
    
    /**
     * Starts cleanup task for expired temporary templates
     */
    private fun startCleanupTask() {
        updateExecutor.scheduleAtFixedRate({
            val now = Instant.now()
            temporaryTemplates.entries.removeIf { (_, pair) ->
                now.isAfter(pair.second)
            }
        }, 30, 30, TimeUnit.SECONDS)
    }
    
    /**
     * Loads nametag configuration from nametags.yml
     */
    private fun loadNametagConfig(): Map<String, Any> {
        val configFile = File("plugins/Radium/nametags.yml")
        return if (configFile.exists()) {
            try {
                val yaml = org.yaml.snakeyaml.Yaml()
                yaml.load(FileInputStream(configFile)) as? Map<String, Any> ?: emptyMap()
            } catch (e: Exception) {
                radium.logger.error("Failed to load nametags.yml: ${e.message}", e)
                emptyMap()
            }
        } else {
            emptyMap()
        }
    }
    
    /**
     * Shuts down the service
     */
    fun shutdown() {
        updateExecutor.shutdown()
        temporaryTemplates.clear()
        pendingUpdates.clear()
    }
}
