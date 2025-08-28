package radium.backend.commands

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import revxrsal.commands.annotation.Command
import revxrsal.commands.velocity.annotation.CommandPermission
import radium.backend.Radium

@Command("chatmute", "chat mute")
@CommandPermission("radium.chat.mute")
class ChatMute(private val radium: Radium) {

    @Command("chatmute", "chat mute")
    fun muteChat(actor: Player) {
        if (radium.chatManager.isChatMuted()) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("chat.mute.already_muted"))
            return
        }
        
        // Mute chat
        radium.chatManager.setChatMuted(true)
        
        // Notify staff member
        actor.sendMessage(radium.yamlFactory.getMessageComponent("chat.mute.success"))
        
        // Broadcast to all players (except those with bypass permission)
        val broadcastMessage = radium.yamlFactory.getMessageComponent("chat.mute.broadcast")
            
        radium.server.allPlayers.forEach { player ->
            if (!player.hasPermission("radium.chat.bypass")) {
                player.sendMessage(broadcastMessage)
            }
        }
        
        radium.logger.info("Chat muted by ${actor.username}")
    }
}
