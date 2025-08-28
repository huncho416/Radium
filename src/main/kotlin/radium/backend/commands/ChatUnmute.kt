package radium.backend.commands

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import revxrsal.commands.annotation.Command
import revxrsal.commands.velocity.annotation.CommandPermission
import radium.backend.Radium

@Command("chatunmute")
@CommandPermission("radium.chat.mute")
class ChatUnmute(private val radium: Radium) {

    @Command("chatunmute")
    fun unmuteChat(actor: Player) {
        if (!radium.chatManager.isChatMuted()) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("chat.unmute.not_muted"))
            return
        }
        
        // Unmute chat
        radium.chatManager.setChatMuted(false)
        
        // Notify staff member
        actor.sendMessage(radium.yamlFactory.getMessageComponent("chat.unmute.success"))
        
        // Broadcast to all players
        val broadcastMessage = radium.yamlFactory.getMessageComponent("chat.unmute.broadcast")
            
        radium.server.allPlayers.forEach { player ->
            player.sendMessage(broadcastMessage)
        }
        
        radium.logger.info("Chat unmuted by ${actor.username}")
    }
}
