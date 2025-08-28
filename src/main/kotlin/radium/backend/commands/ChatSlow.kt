package radium.backend.commands

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import revxrsal.commands.annotation.Command
import revxrsal.commands.velocity.annotation.CommandPermission
import radium.backend.Radium

@Command("chatslow", "chat slow")
@CommandPermission("radium.chat.slow")
class ChatSlow(private val radium: Radium) {

    @Command("chatslow", "chat slow")
    fun slowChat(actor: Player, delay: String) {
        // Parse delay (1s, 5s, 10s, etc.)
        val delaySeconds = parseDelay(delay)
        if (delaySeconds == null) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("chat.slow.invalid_duration"))
            return
        }
        
        if (delaySeconds <= 0) {
            // Disable slow mode
            radium.chatManager.setChatSlowDelay(0)
            
            // Notify staff member
            actor.sendMessage(radium.yamlFactory.getMessageComponent("chat.slow.disabled"))
            
            // Broadcast to all players
            val broadcastMessage = radium.yamlFactory.getMessageComponent("chat.slow.broadcast_disabled")
            radium.server.allPlayers.forEach { player ->
                player.sendMessage(broadcastMessage)
            }
            return
        }
        
        if (delaySeconds > 300) { // Max 5 minutes
            actor.sendMessage(radium.yamlFactory.getMessageComponent("chat.slow.invalid_duration"))
            return
        }
        
        // Set chat slow delay
        radium.chatManager.setChatSlowDelay(delaySeconds)
        
        // Notify staff member
        actor.sendMessage(radium.yamlFactory.getMessageComponent("chat.slow.success", "delay" to delaySeconds.toString()))
        
        // Broadcast to all players (except those with bypass permission)
        val broadcastMessage = radium.yamlFactory.getMessageComponent("chat.slow.broadcast_enabled", "delay" to delaySeconds.toString())
            
        radium.server.allPlayers.forEach { player ->
            if (!player.hasPermission("radium.chat.bypass")) {
                player.sendMessage(broadcastMessage)
            }
        }
        
        radium.logger.info("Chat slowed to ${delaySeconds}s by ${actor.username}")
    }

    /**
     * Parse delay string (e.g., "5s", "10s", "1m") to seconds
     */
    private fun parseDelay(delayStr: String): Int? {
        val lowercaseDelay = delayStr.lowercase().trim()
        
        return try {
            when {
                lowercaseDelay.endsWith("s") -> {
                    val number = lowercaseDelay.dropLast(1).toInt()
                    number
                }
                lowercaseDelay.endsWith("m") -> {
                    val number = lowercaseDelay.dropLast(1).toInt()
                    number * 60
                }
                lowercaseDelay.endsWith("h") -> {
                    val number = lowercaseDelay.dropLast(1).toInt()
                    number * 3600
                }
                else -> {
                    // Try parsing as plain number (assume seconds)
                    lowercaseDelay.toInt()
                }
            }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
