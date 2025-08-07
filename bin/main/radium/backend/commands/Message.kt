package radium.backend.commands

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Optional
import revxrsal.commands.velocity.annotation.CommandPermission
import radium.backend.Radium
import radium.backend.annotations.OnlinePlayers
import java.util.UUID

@Command("message", "msg", "tell", "whisper")
@CommandPermission("command.message")
class Message(private val radium: Radium) {

    private val yamlFactory = radium.yamlFactory
    
    // Store last message senders for reply functionality
    companion object {
        private val lastMessageSenders = mutableMapOf<UUID, UUID>() // receiver -> sender
    }

    @Command("message <target> <message>", "msg <target> <message>", "tell <target> <message>", "whisper <target> <message>")
    fun sendMessage(actor: Player, @OnlinePlayers target: String, message: String) {
        // Find the target player
        val targetPlayer = radium.server.allPlayers.find { 
            it.username.equals(target, ignoreCase = true) 
        }
        
        if (targetPlayer == null) {
            actor.sendMessage(yamlFactory.getMessageComponent("commands.message.player_not_found", "target" to target))
            return
        }
        
        if (targetPlayer.uniqueId == actor.uniqueId) {
            actor.sendMessage(yamlFactory.getMessageComponent("commands.message.cannot_message_self"))
            return
        }

        // Store this conversation for reply functionality
        lastMessageSenders[targetPlayer.uniqueId] = actor.uniqueId
        
        // Format and send messages
        val senderMessage = yamlFactory.getMessageComponent("commands.message.sender_format",
            "target" to targetPlayer.username,
            "message" to message
        ).clickEvent(ClickEvent.suggestCommand("/r "))
            .hoverEvent(HoverEvent.showText(Component.text("Click to reply")))
            
        val receiverMessage = yamlFactory.getMessageComponent("commands.message.receiver_format",
            "sender" to actor.username,
            "message" to message
        ).clickEvent(ClickEvent.suggestCommand("/r "))
            .hoverEvent(HoverEvent.showText(Component.text("Click to reply")))
        
        actor.sendMessage(senderMessage)
        targetPlayer.sendMessage(receiverMessage)
    }

    @Command("reply <message>", "r <message>")
    fun replyToMessage(actor: Player, message: String) {
        val lastSenderUuid = lastMessageSenders[actor.uniqueId]
        
        if (lastSenderUuid == null) {
            actor.sendMessage(yamlFactory.getMessageComponent("commands.message.no_one_to_reply"))
            return
        }
        
        val targetPlayer = radium.server.getPlayer(lastSenderUuid).orElse(null)
        
        if (targetPlayer == null) {
            actor.sendMessage(yamlFactory.getMessageComponent("commands.message.reply_target_offline"))
            return
        }
        
        // Update the conversation
        lastMessageSenders[targetPlayer.uniqueId] = actor.uniqueId
        
        // Send the messages
        val senderMessage = yamlFactory.getMessageComponent("commands.message.sender_format",
            "target" to targetPlayer.username,
            "message" to message
        ).clickEvent(ClickEvent.suggestCommand("/r "))
            .hoverEvent(HoverEvent.showText(Component.text("Click to reply")))
            
        val receiverMessage = yamlFactory.getMessageComponent("commands.message.receiver_format",
            "sender" to actor.username,
            "message" to message
        ).clickEvent(ClickEvent.suggestCommand("/r "))
            .hoverEvent(HoverEvent.showText(Component.text("Click to reply")))
        
        actor.sendMessage(senderMessage)
        targetPlayer.sendMessage(receiverMessage)
    }

    @Command("message", "msg", "tell", "whisper")
    fun messageUsage(actor: Player) {
        actor.sendMessage(yamlFactory.getMessageComponent("commands.message.header"))
        actor.sendMessage(yamlFactory.getMessageComponent("commands.message.usage.main"))
        actor.sendMessage(yamlFactory.getMessageComponent("commands.message.usage.reply"))
    }
}
