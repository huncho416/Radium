package radium.backend.util

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import io.lettuce.core.RedisClient
import io.lettuce.core.pubsub.RedisPubSubListener
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import radium.backend.Radium
import java.util.*

/**
 * Handles Redis communication for MythicHub integration
 * Provides API endpoints for the RadiumClient in MythicHub
 */
class ProxyCommunicationManager(
    private val plugin: Radium,
    private val server: ProxyServer,
    private val logger: ComponentLogger,
    private val scope: CoroutineScope,
    private val redisClient: RedisClient
) {
    private val gson = Gson()
    private var pubSubConnection: StatefulRedisPubSubConnection<String, String>? = null

    fun initialize() {
        scope.launch {
            try {
                setupRedisCommunication()
                logger.info("Proxy communication manager initialized")
            } catch (e: Exception) {
                logger.error("Failed to initialize proxy communication: ${e.message}", e)
            }
        }
    }

    private fun setupRedisCommunication() {
        pubSubConnection = redisClient.connectPubSub()
        val pubSub = pubSubConnection!!.sync()

        // Subscribe to channels that MythicHub uses
        pubSub.subscribe(
            "radium:proxy:request",
            "radium:server:transfer",
            "radium:player:permission:check",
            "radium:player:profile:request",
            "radium:command:execute",
            "radium:player:message"
        )

        pubSubConnection!!.addListener(object : RedisPubSubListener<String, String> {
            override fun message(channel: String, message: String) {
                scope.launch {
                    handleRedisMessage(channel, message)
                }
            }

            override fun message(pattern: String, channel: String, message: String) {}
            override fun subscribed(channel: String, count: Long) {
                logger.info("Subscribed to Redis channel: $channel")
            }
            override fun unsubscribed(channel: String, count: Long) {}
            override fun psubscribed(pattern: String, count: Long) {}
            override fun punsubscribed(pattern: String, count: Long) {}
        })
    }

    private suspend fun handleRedisMessage(channel: String, message: String) {
        try {
            when (channel) {
                "radium:proxy:request" -> handleProxyRequest(message)
                "radium:server:transfer" -> handleServerTransfer(message)
                "radium:player:permission:check" -> handlePermissionCheck(message)
                "radium:player:profile:request" -> handleProfileRequest(message)
                "radium:command:execute" -> handleCommandExecution(message)
                "radium:player:message" -> handlePlayerMessage(message)
            }
        } catch (e: Exception) {
            logger.error("Error handling Redis message on channel $channel: ${e.message}", e)
        }
    }

    private suspend fun handleProxyRequest(message: String) {
        val request = gson.fromJson(message, JsonObject::class.java)
        val type = request.get("type")?.asString ?: return

        when (type) {
            "server_list" -> {
                val response = JsonObject().apply {
                    addProperty("type", "server_list_response")
                    addProperty("requestId", request.get("requestId")?.asString)
                    add("servers", gson.toJsonTree(getServerList()))
                }
                publishResponse("radium:proxy:response", response.toString())
            }
            "player_count" -> {
                val response = JsonObject().apply {
                    addProperty("type", "player_count_response")
                    addProperty("requestId", request.get("requestId")?.asString)
                    addProperty("total", server.playerCount)
                }
                publishResponse("radium:proxy:response", response.toString())
            }
        }
    }

    private suspend fun handleServerTransfer(message: String) {
        val transfer = gson.fromJson(message, JsonObject::class.java)
        val playerName = transfer.get("player")?.asString ?: return
        val targetServer = transfer.get("server")?.asString ?: return

        val player = server.getPlayer(playerName).orElse(null) ?: return
        val serverInfo = server.getServer(targetServer).orElse(null)

        if (serverInfo != null) {
            player.createConnectionRequest(serverInfo).fireAndForget()
            logger.info("Transferred player $playerName to server $targetServer")
        } else {
            logger.warn("Attempted to transfer player $playerName to unknown server $targetServer")
        }
    }

    private suspend fun handlePermissionCheck(message: String) {
        val request = gson.fromJson(message, JsonObject::class.java)
        val playerName = request.get("player")?.asString ?: return
        val permission = request.get("permission")?.asString ?: return
        val requestId = request.get("requestId")?.asString ?: return

        val player = server.getPlayer(playerName).orElse(null)
        val hasPermission = player?.hasPermission(permission) ?: false

        val response = JsonObject().apply {
            addProperty("type", "permission_check_response")
            addProperty("requestId", requestId)
            addProperty("player", playerName)
            addProperty("permission", permission)
            addProperty("hasPermission", hasPermission)
        }

        publishResponse("radium:player:permission:response", response.toString())
    }

    private suspend fun handleProfileRequest(message: String) {
        val request = gson.fromJson(message, JsonObject::class.java)
        val playerName = request.get("player")?.asString ?: return
        val requestId = request.get("requestId")?.asString ?: return

        val player = server.getPlayer(playerName).orElse(null)
        if (player != null) {
            val profile = plugin.connectionHandler.getPlayerProfile(player.uniqueId)
            val response = JsonObject().apply {
                addProperty("type", "profile_response")
                addProperty("requestId", requestId)
                addProperty("player", playerName)
                addProperty("uuid", player.uniqueId.toString())
                if (profile != null) {
                    val highestRank = profile.getHighestRank(plugin.rankManager)
                    if (highestRank != null) {
                        // Debug logging to check for encoding issues
                        plugin.logger.debug("Sending rank data for $playerName: rank=${highestRank.name}, prefix='${highestRank.prefix}', color='${highestRank.color}'")
                        
                        // Ensure safe transmission of color codes through Redis
                        // Convert & codes to a safe format that won't cause encoding issues
                        val safePrefix = highestRank.prefix.replace("&", "%%AMP%%")
                        val safeColor = highestRank.color.replace("&", "%%AMP%%")
                        val safeTabPrefix = highestRank.tabPrefix?.replace("&", "%%AMP%%")
                        val safeTabSuffix = highestRank.tabSuffix?.replace("&", "%%AMP%%")
                        
                        addProperty("rank", highestRank.name)
                        addProperty("rankWeight", highestRank.weight)
                        addProperty("prefix", safePrefix)
                        addProperty("color", safeColor)
                        addProperty("tabPrefix", safeTabPrefix ?: safePrefix)
                        addProperty("tabSuffix", safeTabSuffix ?: "")
                        add("permissions", gson.toJsonTree(highestRank.permissions))
                    } else {
                        plugin.logger.debug("No rank found for player $playerName, using defaults")
                    }
                } else {
                    plugin.logger.debug("No profile found for player $playerName")
                }
            }
            publishResponse("radium:player:profile:response", response.toString())
        }
    }

    private suspend fun handleCommandExecution(message: String) {
        val commandRequest = gson.fromJson(message, JsonObject::class.java)
        val playerName = commandRequest.get("player")?.asString ?: return
        val command = commandRequest.get("command")?.asString ?: return
        val requestId = commandRequest.get("requestId")?.asString ?: return

        val player = server.getPlayer(playerName).orElse(null)
        if (player != null) {
            try {
                // Execute the command as if the player typed it on the proxy
                server.commandManager.executeAsync(player, command).join()
                
                val response = JsonObject().apply {
                    addProperty("type", "command_response")
                    addProperty("requestId", requestId)
                    addProperty("player", playerName)
                    addProperty("command", command)
                    addProperty("success", "true")
                    addProperty("executed", "true")
                }
                
                publishResponse("radium:command:response", response.toString())
                logger.info("Executed command '$command' for player $playerName via Redis")
            } catch (e: Exception) {
                val response = JsonObject().apply {
                    addProperty("type", "command_response")
                    addProperty("requestId", requestId)
                    addProperty("player", playerName)
                    addProperty("command", command)
                    addProperty("success", "false")
                    addProperty("executed", "false")
                    addProperty("error", e.message)
                }
                
                publishResponse("radium:command:response", response.toString())
                logger.error("Failed to execute command '$command' for player $playerName: ${e.message}", e)
            }
        } else {
            val response = JsonObject().apply {
                addProperty("type", "command_response")
                addProperty("requestId", requestId)
                addProperty("player", playerName)
                addProperty("command", command)
                addProperty("success", "false")
                addProperty("executed", "false")
                addProperty("error", "Player not found")
            }
            
            publishResponse("radium:command:response", response.toString())
        }
    }

    private fun getServerList(): List<Map<String, Any>> {
        return server.allServers.map { serverInfo ->
            mapOf(
                "name" to serverInfo.serverInfo.name,
                "address" to serverInfo.serverInfo.address.toString(),
                "players" to serverInfo.playersConnected.size
            )
        }
    }

    private suspend fun publishResponse(channel: String, message: String) {
        try {
            val connection = redisClient.connect()
            connection.sync().publish(channel, message)
            connection.close()
        } catch (e: Exception) {
            logger.error("Failed to publish Redis response: ${e.message}", e)
        }
    }

    fun shutdown() {
        pubSubConnection?.close()
    }

    /**
     * Send a global message to all servers via Redis
     */
    fun sendGlobalMessage(message: String) {
        scope.launch {
            val notification = JsonObject().apply {
                addProperty("type", "global_message")
                addProperty("message", message)
                addProperty("timestamp", System.currentTimeMillis())
            }
            publishResponse("radium:global:message", notification.toString())
        }
    }

    /**
     * Publish a message to a specific Redis channel
     */
    fun publishMessage(channel: String, message: String) {
        scope.launch {
            publishResponse(channel, message)
        }
    }

    /**
     * Notify MythicHub about player vanish status changes
     */
    fun notifyVanishChange(player: Player, vanished: Boolean) {
        scope.launch {
            val notification = JsonObject().apply {
                addProperty("type", "vanish_update")
                addProperty("player", player.username)
                addProperty("uuid", player.uniqueId.toString())
                addProperty("vanished", vanished)
                addProperty("timestamp", System.currentTimeMillis())
            }
            publishResponse("radium:player:vanish", notification.toString())
        }
    }
    
    private suspend fun handlePlayerMessage(message: String) {
        // Delegate to the Message command handler
        plugin.messageCommand?.handleCrossServerMessage(message)
    }
}
