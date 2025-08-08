package radium.backend.player

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.PlayerChatEvent
import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import radium.backend.Radium
import radium.backend.util.YamlFactory

class ChatManager(private val radium: Radium) {

    private val yamlFactory = radium.yamlFactory

    @Subscribe(priority = 50) // Lower priority than staff chat to run after it
    fun mainChatHandler(event: PlayerChatEvent) {
        val player = event.player
        
        // Check if this is on the hub server (Minestom) where signed chat isn't an issue
        val currentServer = player.currentServer.orElse(null)
        if (currentServer?.serverInfo?.name?.equals("hub", ignoreCase = true) != true) {
            // Only format chat on the hub server to avoid signed chat issues on backend servers
            return
        }
        
        // Cancel the default chat message and send our formatted one
        event.result = PlayerChatEvent.ChatResult.message("")
        
        // Launch coroutine to handle async profile lookup
        GlobalScope.launch {
            try {
                // Get player profile to access rank information
                val profile = radium.connectionHandler.findPlayerProfile(player.uniqueId.toString())
                if (profile != null) {
                    // Get highest rank for prefix and chat color
                    val highestRank = profile.getHighestRank(radium.rankManager)
                    val prefix = highestRank?.prefix ?: ""
                    val chatColor = highestRank?.color ?: "&7"
                    
                    // Use the chat format from lang.yml
                    val chatFormat = yamlFactory.getMessageComponent("chat.main_format",
                        "prefix" to prefix,
                        "player" to player.username,
                        "chatColor" to chatColor,
                        "message" to event.message
                    )
                    
                    // Send formatted message to all players on the current server
                    currentServer.server.playersConnected.forEach { serverPlayer ->
                        serverPlayer.sendMessage(chatFormat)
                    }
                } else {
                    // Fallback to default format if profile not found
                    val chatFormat = Component.text("<${player.username}> ${event.message}")
                    
                    currentServer.server.playersConnected.forEach { serverPlayer ->
                        serverPlayer.sendMessage(chatFormat)
                    }
                }
            } catch (e: Exception) {
                radium.logger.warn("Failed to format chat message for ${player.username}: ${e.message}")
                // Let the original message through if formatting fails
                currentServer?.server?.playersConnected?.forEach { serverPlayer ->
                    serverPlayer.sendMessage(Component.text("<${player.username}> ${event.message}"))
                }
            }
        }
    }
}
