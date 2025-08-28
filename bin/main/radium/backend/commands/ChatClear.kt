package radium.backend.commands

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import revxrsal.commands.annotation.Command
import revxrsal.commands.velocity.annotation.CommandPermission
import radium.backend.Radium

@Command("chatclear", "chat clear")
@CommandPermission("radium.chat.clear")
class ChatClear(private val radium: Radium) {

    @Command("chatclear", "chat clear")
    fun clearChat(actor: Player) {
        // Notify staff member
        actor.sendMessage(radium.yamlFactory.getMessageComponent("chat.clear.success"))
        
        // Clear chat for all players (except those with bypass permission)
        val clearLines = (1..100).map { Component.text("") }
        
        radium.server.allPlayers.forEach { player ->
            if (!player.hasPermission("radium.chat.bypass")) {
                clearLines.forEach { line ->
                    player.sendMessage(line)
                }
            }
        }
        
        // Send notification to all players (including staff)
        val broadcastMessage = radium.yamlFactory.getMessageComponent("chat.clear.broadcast")
            
        radium.server.allPlayers.forEach { player ->
            player.sendMessage(broadcastMessage)
        }
        
        radium.logger.info("Chat cleared by ${actor.username}")
    }
}
