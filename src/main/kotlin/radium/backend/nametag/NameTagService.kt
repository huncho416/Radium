package radium.backend.nametag

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.player.TabListEntry
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
            
            if (config.isEmpty()) {
                radium.logger.warn("Nametags configuration is empty, using defaults")
            } else {
                radium.logger.info("Loaded nametags configuration with ${config.size} keys")
            }
            
            isEnabled = config["enabled"] as? Boolean ?: true
            defaultTemplate = config["default-template"] as? String ?: "<gray><username></gray>"
            
            val visibility = config["visibility"] as? Map<String, Any> ?: emptyMap()
            respectVanish = visibility["respect-vanish"] as? Boolean ?: true
            
            val weightGating = visibility["weight-gating"] as? Map<String, Any> ?: emptyMap()
            weightGatingEnabled = weightGating["enabled"] as? Boolean ?: true
            fallbackTemplate = weightGating["fallback-template"] as? String ?: "<dark_gray>•</dark_gray> <gray><username>"
            
            val performance = config["performance"] as? Map<String, Any> ?: emptyMap()
            updateBatchMs = (performance["update-batch-ms"] as? Number)?.toLong() ?: 75L
            
            val ranks = config["ranks"] as? Map<String, Any> ?: emptyMap()
            radium.logger.info("Loaded ${ranks.size} rank configurations for nametags")
            
            radium.logger.info("NameTag configuration loaded - enabled: $isEnabled, default: '$defaultTemplate'")
            
        } catch (e: Exception) {
            radium.logger.error("Failed to load nametags configuration: ${e.message}", e)
        }
    }
    
    /**
     * Applies a nametag for the specified player by sending update to backend
     */
    suspend fun applyFor(player: Player) {
        if (!isEnabled) return
        
        try {
            val profile = radium.connectionHandler.getPlayerProfile(player.uniqueId)
            val template = if (profile == null) {
                radium.logger.warn("No profile found for player ${player.username} (${player.uniqueId}), using default template")
                // Use default template when profile is not available
                var baseTemplate = defaultTemplate.replace("<username>", player.username)
                
                // Check if player is vanished and add vanish indicator
                if (radium.staffManager.isVanished(player)) {
                    baseTemplate = addVanishIndicator(baseTemplate)
                }
                baseTemplate
            } else {
                resolveTemplate(profile, player)
            }
            
            // Apply nametag locally via tab list display name (since we don't have MSNameTags backend)
            // DISABLED: This conflicts with TabListManager - nametags are handled by backend servers
            // applyNametagViaTabList(player, template)
            
            // Send Redis message for backend servers to handle nametags
            publishNametagUpdate(player.uniqueId, template, "apply")
            
            radium.logger.info("Applied nametag for ${player.username}: $template")
            
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
        
        // Remove nametag using MSNameTags
        removeNametagDirectly(player)
        
        // Send nametag remove command to backend via Redis
        publishNametagUpdate(player.uniqueId, "", "remove")
        
        radium.logger.debug("Removed nametag for ${player.username}")
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
        
        // Apply temporary template directly
        radium.server.getPlayer(playerId).ifPresent { player ->
            applyNametagDirectly(player, template)
        }
        
        // Send temporary template to backend
        publishNametagUpdate(playerId, template, "temporary")
        
        radium.logger.debug("Set temporary nametag template for $playerId: $template")
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
     * Publishes nametag update to Redis for a specific viewer
     */
    private fun publishNametagUpdateToViewer(playerId: UUID, template: String, action: String, viewerId: UUID) {
        try {
            val message = mapOf(
                "uuid" to playerId.toString(),
                "template" to template,
                "action" to action,
                "viewer" to viewerId.toString(),
                "timestamp" to System.currentTimeMillis().toString()
            ).entries.joinToString(",") { "${it.key}=${it.value}" }
            
            radium.lettuceCache.sync().publish("radium:nametag:update:targeted", message)
            
        } catch (e: Exception) {
            radium.logger.error("Failed to publish targeted nametag update: ${e.message}")
        }
    }

    /**
     * Resolves the appropriate template for a player
     */
    private suspend fun resolveTemplate(profile: Profile, player: Player): String {
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
        
        radium.logger.debug("Resolving template for ${player.username}: rank=${primaryRank?.name ?: "null"}")
        
        // Determine base template
        var baseTemplate = when {
            // Check if rank has template override
            primaryRank?.nametagTemplate != null -> {
                radium.logger.debug("Using rank's custom nametag template for ${player.username}")
                primaryRank.nametagTemplate!!
            }
            
            // Check config for rank-specific template
            else -> {
                val ranks = config["ranks"] as? Map<String, Any> ?: emptyMap()
                val rankConfig = ranks[primaryRank?.name] as? Map<String, Any>
                val template = (rankConfig?.get("template") as? String) ?: defaultTemplate
                
                radium.logger.debug("Using config template for ${player.username} (rank: ${primaryRank?.name}): $template")
                template
            }
        }
        
        // Replace placeholders
        baseTemplate = baseTemplate
            .replace("<username>", player.username)
            .replace("<rank_name>", primaryRank?.name ?: "DEFAULT")
            .replace("<rank_color>", primaryRank?.color ?: "<gray>")
            .replace("<prefix>", primaryRank?.prefix ?: "")
            .replace("<suffix>", primaryRank?.suffix ?: "")
            .replace("<weight>", (primaryRank?.weight ?: 0).toString())
            .replace("<server>", player.currentServer.map { it.serverInfo.name }.orElse("Unknown"))
        
        radium.logger.debug("Template before vanish check for ${player.username}: $baseTemplate")
        
        // Check if player is vanished and add vanish indicator
        if (radium.staffManager.isVanished(player)) {
            baseTemplate = addVanishIndicator(baseTemplate)
            radium.logger.debug("Added vanish indicator to ${player.username}'s template")
        }
        
        return baseTemplate
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
        val configFile = File("run/plugins/Radium/nametags.yml")
        val fallbackFile = File("plugins/Radium/nametags.yml")
        
        val fileToUse = when {
            configFile.exists() -> configFile
            fallbackFile.exists() -> fallbackFile
            else -> {
                radium.logger.warn("No nametags.yml found at ${configFile.absolutePath} or ${fallbackFile.absolutePath}")
                return emptyMap()
            }
        }
        
        return try {
            radium.logger.info("Loading nametags configuration from: ${fileToUse.absolutePath}")
            val yaml = org.yaml.snakeyaml.Yaml()
            yaml.load(FileInputStream(fileToUse)) as? Map<String, Any> ?: emptyMap()
        } catch (e: Exception) {
            radium.logger.error("Failed to load nametags.yml from ${fileToUse.absolutePath}: ${e.message}", e)
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
    
    /**
     * Applies nametag by updating tab list display name (fallback when no MSNameTags backend)
     */
    private fun applyNametagViaTabList(player: Player, template: String) {
        try {
            // Parse the MiniMessage template to a Component
            val displayName = miniMessage.deserialize(template)
            
            // Update this player's tab list entry in ALL other players' tab lists
            radium.server.allPlayers.forEach { otherPlayer ->
                val tabEntry = otherPlayer.tabList.getEntry(player.uniqueId)
                if (tabEntry.isPresent) {
                    tabEntry.get().setDisplayName(displayName)
                }
            }
            
            radium.logger.debug("Applied nametag via tab list for ${player.username}")
            
        } catch (e: Exception) {
            radium.logger.error("Failed to apply nametag via tab list for ${player.username}: ${e.message}")
        }
    }
    
    /**
     * Applies nametag by sending Redis command to backend servers
     */
    private fun applyNametagDirectly(player: Player, template: String) {
        try {
            // Parse the template to a Component for logging
            val nametagComponent = miniMessage.deserialize(
                template.replace("<username>", player.username)
                        .replace("<rank_name>", getPlayerRankName(player))
                        .replace("<rank_color>", getPlayerRankColor(player))
            )
            
            radium.logger.info("Applied nametag for ${player.username}: ${PlainTextComponentSerializer.plainText().serialize(nametagComponent)}")
            
            // The actual nametag will be applied by the backend servers that receive the Redis message
            
        } catch (e: Exception) {
            radium.logger.error("Failed to apply nametag for ${player.username}: ${e.message}")
        }
    }
    
    /**
     * Removes nametag by sending Redis command to backend servers
     */
    private fun removeNametagDirectly(player: Player) {
        try {
            radium.logger.info("Removed nametag for ${player.username}")
            
            // The actual nametag removal will be handled by the backend servers
            
        } catch (e: Exception) {
            radium.logger.error("Failed to remove nametag for ${player.username}: ${e.message}")
        }
    }
    
    /**
     * Gets the player's rank name for template replacement
     */
    private fun getPlayerRankName(player: Player): String {
        return try {
            val profile = radium.connectionHandler.getPlayerProfile(player.uniqueId)
            // Use runBlocking to call suspend function
            kotlinx.coroutines.runBlocking {
                profile?.getHighestRank(radium.rankManager)?.name ?: "DEFAULT"
            }
        } catch (e: Exception) {
            "DEFAULT"
        }
    }
    
    /**
     * Gets the player's rank color for template replacement
     */
    private fun getPlayerRankColor(player: Player): String {
        return try {
            val profile = radium.connectionHandler.getPlayerProfile(player.uniqueId)
            // Use runBlocking to call suspend function
            kotlinx.coroutines.runBlocking {
                val rank = profile?.getHighestRank(radium.rankManager)
                rank?.color ?: "<gray>"
            }
        } catch (e: Exception) {
            "<gray>"
        }
    }
    
    /**
     * Adds a vanish indicator to a nametag template
     */
    private fun addVanishIndicator(template: String): String {
        // Add the vanish indicator with gray color
        return "$template <gray>(V)</gray>"
    }
    
    /**
     * Checks if a viewer should see a vanished player's nametag based on staff weights
     */
    private suspend fun shouldShowVanishedPlayer(viewer: Player, vanishedPlayer: Player): Boolean {
        // If weight gating is disabled, only check basic permissions
        if (!weightGatingEnabled) {
            return viewer.hasPermission("radium.vanish.see")
        }
        
        // Check if the viewer has the override permission to see all vanished players
        if (viewer.hasPermission("radium.vanish.see")) {
            return true
        }
        
        // Get both players' profiles and rank weights
        val viewerProfile = radium.connectionHandler.getPlayerProfile(viewer.uniqueId)
        val vanishedProfile = radium.connectionHandler.getPlayerProfile(vanishedPlayer.uniqueId)
        
        val viewerWeight = viewerProfile?.getHighestRank(radium.rankManager)?.weight ?: 0
        val vanishedWeight = vanishedProfile?.getHighestRank(radium.rankManager)?.weight ?: 0
        
        // Show if the viewer's rank weight is greater than or equal to the vanished player's weight
        return viewerWeight >= vanishedWeight
    }

    /**
     * Applies nametag with visibility rules for vanished players
     */
    suspend fun applyForWithVisibility(player: Player) {
        if (!isEnabled) return
        
        val isVanished = radium.staffManager.isVanished(player)
        
        if (isVanished && respectVanish) {
            // For vanished players, only apply nametags to players who should see them
            radium.server.allPlayers.forEach { viewer ->
                if (viewer.uniqueId != player.uniqueId && shouldShowVanishedPlayer(viewer, player)) {
                    // Apply the full nametag for viewers who can see vanished players
                    applyForViewer(player, viewer)
                } else if (viewer.uniqueId != player.uniqueId) {
                    // Apply fallback template or hide completely for viewers who can't see vanished players
                    applyFallbackForViewer(player, viewer)
                }
            }
            
            // Apply nametag for the vanished player themselves
            applyForViewer(player, player)
        } else {
            // For non-vanished players, apply normally to everyone
            applyFor(player)
        }
    }

    /**
     * Applies nametag for a specific viewer
     */
    private suspend fun applyForViewer(player: Player, viewer: Player) {
        try {
            val profile = radium.connectionHandler.getPlayerProfile(player.uniqueId)
            val template = if (profile == null) {
                var baseTemplate = defaultTemplate.replace("<username>", player.username)
                if (radium.staffManager.isVanished(player)) {
                    baseTemplate = addVanishIndicator(baseTemplate)
                }
                baseTemplate
            } else {
                resolveTemplate(profile, player)
            }
            
            // Apply locally to the specific viewer's tab list
            val displayName = miniMessage.deserialize(template)
            val tabEntry = viewer.tabList.getEntry(player.uniqueId)
            if (tabEntry.isPresent) {
                tabEntry.get().setDisplayName(displayName)
            }
            
            // Also send targeted Redis message for backends
            publishNametagUpdateToViewer(player.uniqueId, template, "apply", viewer.uniqueId)
            
        } catch (e: Exception) {
            radium.logger.error("Failed to apply nametag for ${player.username} to viewer ${viewer.username}: ${e.message}")
        }
    }

    /**
     * Applies fallback template for viewers who can't see vanished players
     */
    private fun applyFallbackForViewer(player: Player, viewer: Player) {
        try {
            val fallbackTemplate = fallbackTemplate.replace("<username>", player.username)
            
            // Apply locally to the specific viewer's tab list
            val displayName = miniMessage.deserialize(fallbackTemplate)
            val tabEntry = viewer.tabList.getEntry(player.uniqueId)
            if (tabEntry.isPresent) {
                tabEntry.get().setDisplayName(displayName)
            }
            
            // Also send targeted Redis message for backends
            publishNametagUpdateToViewer(player.uniqueId, fallbackTemplate, "fallback", viewer.uniqueId)
        } catch (e: Exception) {
            radium.logger.error("Failed to apply fallback nametag for ${player.username} to viewer ${viewer.username}: ${e.message}")
        }
    }

    /**
     * Updates nametags for all online players
     */
    suspend fun updateAllPlayersNametags() {
        radium.server.allPlayers.forEach { player ->
            applyFor(player)
        }
        radium.logger.debug("Updated nametags for all ${radium.server.allPlayers.size} online players")
    }
}
