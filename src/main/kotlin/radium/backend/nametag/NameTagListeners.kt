package radium.backend.nametag

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import com.velocitypowered.api.proxy.Player
import io.lettuce.core.pubsub.RedisPubSubListener
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.launch
import radium.backend.Radium
import radium.backend.util.LettuceCache
import java.util.*

/**
 * Event listeners for the nametag system on Velocity proxy
 */
class NameTagListeners(
    private val radium: Radium,
    private val nameTagService: NameTagService,
    private val lettuceCache: LettuceCache
) {
    
    private var pubSubConnection: StatefulRedisPubSubConnection<String, String>? = null
    
    /**
     * Registers all nametag-related event listeners
     */
    fun registerListeners() {
        // Register Velocity event listeners
        radium.server.eventManager.register(radium, this)
        
        // Subscribe to Redis channels for profile/rank updates
        subscribeToRedisChannels()
    }
    
    /**
     * Handles player login to proxy (happens before server connection)
     */
    @Subscribe
    fun onPostLogin(event: PostLoginEvent) {
        val player = event.player
        
        // Apply nametag with visibility rules immediately when player logs into proxy
        radium.scope.launch {
            nameTagService.applyForWithVisibility(player)
        }
        
        radium.logger.info("Applied nametag for ${player.username} on proxy login")
    }
    
    /**
     * Handles player connection to backend servers
     */
    @Subscribe
    fun onServerConnect(event: ServerPostConnectEvent) {
        val player = event.player
        
        // Re-apply nametag with visibility rules when player connects to a backend server
        radium.scope.launch {
            nameTagService.applyForWithVisibility(player)
        }
        
        // Update all other players' nametags visibility for this new player
        radium.server.allPlayers
            .filter { it.uniqueId != player.uniqueId }
            .forEach { otherPlayer ->
                radium.scope.launch {
                    nameTagService.applyForWithVisibility(otherPlayer)
                }
            }
            
        radium.logger.info("Applied nametag for ${player.username} on server connect")
    }
    
    /**
     * Handles player disconnection
     */
    @Subscribe
    fun onPlayerDisconnect(event: DisconnectEvent) {
        val player = event.player
        nameTagService.removeFor(player)
        
        // Update visibility for remaining players (in case vanish rules change)
        radium.server.allPlayers
            .filter { it.uniqueId != player.uniqueId }
            .forEach { otherPlayer ->
                nameTagService.updateFor(otherPlayer, "player_disconnected")
            }
    }
    
    /**
     * Handles player initial login to proxy
     */
    @Subscribe
    fun onPlayerLogin(event: PostLoginEvent) {
        val player = event.player
        
        radium.logger.debug("Player ${player.username} logged into proxy")
        
        // Apply nametag immediately when player logs in
        radium.scope.launch {
            // Small delay to ensure profile is loaded
            kotlinx.coroutines.delay(500)
            nameTagService.applyFor(player)
        }
    }

    /**
     * Subscribes to Redis channels for real-time updates
     */
    private fun subscribeToRedisChannels() {
        try {
            // Set up Redis subscription for nametag updates
            setupRedisSubscription()
            
        } catch (e: Exception) {
            radium.logger.error("Failed to initialize NameTagListeners: ${e.message}", e)
        }
    }
    
    private fun setupRedisSubscription() {
        try {
            val redisClient = lettuceCache.getRedisClient()
            pubSubConnection = redisClient.connectPubSub()
            val pubSub = pubSubConnection!!.sync()
            
            // Subscribe to profile update channels
            pubSub.subscribe(
                "radium:player:profile:response",
                "radium:player:vanish",
                "radium:rank:updated",
                "radium:grant:added",
                "radium:grant:removed"
            )
            
            pubSubConnection!!.addListener(object : RedisPubSubListener<String, String> {
                override fun message(channel: String, message: String) {
                    radium.scope.launch {
                        handleRedisMessage(channel, message)
                    }
                }
                
                override fun message(pattern: String, channel: String, message: String) {
                    radium.scope.launch {
                        handleRedisMessage(channel, message)
                    }
                }
                
                override fun subscribed(channel: String, count: Long) {
                    radium.logger.debug("Subscribed to Redis channel: $channel")
                }
                override fun unsubscribed(channel: String, count: Long) {}
                override fun psubscribed(pattern: String, count: Long) {}
                override fun punsubscribed(pattern: String, count: Long) {}
            })
            
            radium.logger.info("NameTag listeners subscribed to Redis channels")
            
        } catch (e: Exception) {
            radium.logger.error("Failed to subscribe to Redis channels for nametags: ${e.message}")
        }
    }
    
    /**
     * Handles incoming Redis messages
     */
    private suspend fun handleRedisMessage(channel: String, message: String) {
        try {
            when (channel) {
                "radium:player:profile:response" -> handleProfileUpdate(message)
                "radium:player:vanish" -> handleVanishUpdate(message)
                "radium:rank:updated" -> handleRankUpdate(message)
                "radium:grant:added", "radium:grant:removed" -> handleGrantUpdate(message)
            }
        } catch (e: Exception) {
            radium.logger.error("Failed to handle Redis message from $channel: ${e.message}")
        }
    }
    
    /**
     * Handles profile update messages
     */
    private fun handleProfileUpdate(message: String) {
        try {
            // Parse the profile update message (assuming JSON format)
            val data = parseJsonMessage(message)
            val playerUuid = data["uuid"]?.let { UUID.fromString(it.toString()) } ?: return
            
            // Find the player and update their nametag
            radium.server.getPlayer(playerUuid).ifPresent { player ->
                nameTagService.updateFor(player, "profile_updated")
            }
            
        } catch (e: Exception) {
            radium.logger.debug("Failed to parse profile update message: ${e.message}")
        }
    }
    
    /**
     * Handles vanish status updates
     */
    private fun handleVanishUpdate(message: String) {
        try {
            val data = parseJsonMessage(message)
            val playerUuid = data["uuid"]?.let { UUID.fromString(it.toString()) } ?: return
            val isVanished = data["vanished"] as? Boolean ?: false
            
            // Update the vanished player's nametag with visibility rules
            radium.server.getPlayer(playerUuid).ifPresent { player ->
                radium.scope.launch {
                    nameTagService.applyForWithVisibility(player)
                }
            }
            
            // Update all other players' nametags with visibility rules (their view of the vanished player may have changed)
            radium.server.allPlayers
                .filter { it.uniqueId != playerUuid }
                .forEach { otherPlayer ->
                    radium.scope.launch {
                        nameTagService.applyForWithVisibility(otherPlayer)
                    }
                }
            
            radium.logger.info("Updated nametag visibility for vanish change: ${data["uuid"]} (vanished: $isVanished)")
            
        } catch (e: Exception) {
            radium.logger.debug("Failed to parse vanish update message: ${e.message}")
        }
    }
    
    /**
     * Handles rank updates
     */
    private suspend fun handleRankUpdate(message: String) {
        try {
            val data = parseJsonMessage(message)
            val rankName = data["rank"]?.toString() ?: return
            
            // Update all players who have this rank
            radium.server.allPlayers.forEach { player ->
                val profile = radium.connectionHandler.getPlayerProfile(player.uniqueId)
                val primaryRank = profile?.getHighestRank(radium.rankManager)
                
                if (primaryRank?.name == rankName) {
                    nameTagService.updateFor(player, "rank_updated")
                }
            }
            
        } catch (e: Exception) {
            radium.logger.debug("Failed to parse rank update message: ${e.message}")
        }
    }
    
    /**
     * Handles grant/revoke updates
     */
    private fun handleGrantUpdate(message: String) {
        try {
            val data = parseJsonMessage(message)
            val playerUuid = data["uuid"]?.let { UUID.fromString(it.toString()) } ?: return
            
            // Update the affected player's nametag
            radium.server.getPlayer(playerUuid).ifPresent { player ->
                nameTagService.updateFor(player, "grant_changed")
            }
            
        } catch (e: Exception) {
            radium.logger.debug("Failed to parse grant update message: ${e.message}")
        }
    }
    
    /**
     * Simple JSON-like message parser (basic implementation)
     */
    private fun parseJsonMessage(message: String): Map<String, Any> {
        // This is a simplified parser - in production you'd use a proper JSON library
        // For now, assuming messages are in format: key1=value1,key2=value2
        return try {
            message.split(",").associate { pair ->
                val (key, value) = pair.split("=", limit = 2)
                key.trim() to value.trim()
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }
    
    /**
     * Shuts down the Redis subscription connection
     */
    fun shutdown() {
        try {
            pubSubConnection?.close()
            radium.logger.info("NameTag Redis subscription closed")
        } catch (e: Exception) {
            radium.logger.error("Error closing NameTag Redis subscription: ${e.message}")
        }
    }
}
