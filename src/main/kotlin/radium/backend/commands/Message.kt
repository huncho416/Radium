package radium.backend.commands

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Optional
import revxrsal.commands.velocity.annotation.CommandPermission
import radium.backend.Radium
import kotlinx.coroutines.launch
import com.google.gson.Gson
import java.util.UUID

@Command("message", "msg", "tell", "whisper")
class Message(private val radium: Radium) {

    private val yamlFactory = radium.yamlFactory
    private val gson = Gson()
    
    // Store last message senders for reply functionality
    companion object {
        private val lastMessageSenders = mutableMapOf<UUID, UUID>() // receiver -> sender
        private val pendingMessages = mutableMapOf<String, PendingMessage>() // requestId -> pending message data
        
        data class PendingMessage(
            val senderUuid: String,
            val senderName: String,
            val targetName: String,
            val message: String,
            val isReply: Boolean = false
        )
        
        data class CrossServerMessage(
            val type: String, // "send", "reply", or "delivered"
            val requestId: String,
            val senderUuid: String,
            val senderName: String,
            val targetName: String,
            val targetUuid: String? = null,
            val message: String,
            val isReply: Boolean = false
        )
    }

    @Command("message <target> <message>", "msg <target> <message>", "tell <target> <message>", "whisper <target> <message>")
    fun sendMessage(actor: Player, target: String, message: String) {
        // First try to find the target player locally
        val localTarget = radium.server.allPlayers.find { 
            it.username.equals(target, ignoreCase = true) 
        }
        
        if (localTarget != null) {
            // Handle local messaging (existing logic)
            handleLocalMessage(actor, localTarget, message)
            return
        }
        
        // Player not found locally, try cross-server messaging via Redis
        val requestId = UUID.randomUUID().toString()
        pendingMessages[requestId] = PendingMessage(
            senderUuid = actor.uniqueId.toString(),
            senderName = actor.username,
            targetName = target,
            message = message,
            isReply = false
        )
        
        // Send Redis message to find and message the player on other servers
        radium.scope.launch {
            val crossServerMessage = CrossServerMessage(
                type = "message",
                requestId = requestId,
                senderName = actor.username,
                senderUuid = actor.uniqueId.toString(),
                targetName = target,
                message = message
            )
            
            // Since we removed Redis integration, try to find the player directly
            val targetPlayer = radium.server.getPlayer(target).orElse(null)
            if (targetPlayer != null) {
                // Player is online on this proxy, send message directly
                sendDirectMessage(actor.username, targetPlayer, message)
                pendingMessages.remove(requestId)
            } else {
                // Player not found on this proxy
                val notFoundMessage = radium.yamlFactory.getMessageComponent("commands.message.player_not_found", "player" to target)
                actor.sendMessage(notFoundMessage)
                pendingMessages.remove(requestId)
                return@launch
            }

            // Give it a moment to process, then check if message was delivered
            kotlinx.coroutines.delay(1000)
            if (pendingMessages.containsKey(requestId)) {
                // Message wasn't delivered, player not found anywhere
                pendingMessages.remove(requestId)
                actor.sendMessage(yamlFactory.getMessageComponent("commands.message.player_not_found", "target" to target))
            }
        }
    }
    
    private fun handleLocalMessage(sender: Player, target: Player, message: String) {
        if (target.uniqueId == sender.uniqueId) {
            sender.sendMessage(yamlFactory.getMessageComponent("commands.message.cannot_message_self"))
            return
        }

        // Store this conversation for reply functionality
        lastMessageSenders[target.uniqueId] = sender.uniqueId
        
        // Format and send messages
        val senderMessage = yamlFactory.getMessageComponent("commands.message.sender_format",
            "sender" to sender.username,
            "target" to target.username,
            "message" to message
        )

        val receiverMessage = yamlFactory.getMessageComponent("commands.message.receiver_format",
            "sender" to sender.username,
            "target" to target.username,
            "message" to message
        )

        sender.sendMessage(senderMessage)
        target.sendMessage(receiverMessage)
    }

    @Command("reply <message>", "r <message>")
    fun replyToMessage(actor: Player, message: String) {
        val lastSenderUuid = lastMessageSenders[actor.uniqueId]
        
        if (lastSenderUuid == null) {
            actor.sendMessage(yamlFactory.getMessageComponent("commands.message.no_one_to_reply"))
            return
        }
        
        // First try to find the target player locally
        val localTarget = radium.server.getPlayer(lastSenderUuid).orElse(null)
        
        if (localTarget != null) {
            // Handle local reply
            handleLocalMessage(actor, localTarget, message)
            // Update conversation
            lastMessageSenders[localTarget.uniqueId] = actor.uniqueId
            return
        }
        
        // Target not found locally, try cross-server reply via Redis
        val requestId = UUID.randomUUID().toString()
        pendingMessages[requestId] = PendingMessage(
            senderUuid = actor.uniqueId.toString(),
            senderName = actor.username,
            targetName = "", // We'll get the name when we find them
            message = message,
            isReply = true
        )
        
        radium.scope.launch {
            val crossServerMessage = CrossServerMessage(
                type = "reply",
                requestId = requestId,
                senderUuid = actor.uniqueId.toString(),
                senderName = actor.username,
                targetName = "", // Will be resolved by target UUID
                targetUuid = lastSenderUuid.toString(),
                message = message,
                isReply = true
            )
            
            // Since we removed Redis integration, player is not found
            pendingMessages.remove(requestId)
            actor.sendMessage(yamlFactory.getMessageComponent("commands.message.reply_target_offline"))
        }
    }

    @Command("message", "msg", "tell", "whisper")
    fun messageUsage(actor: Player) {
        actor.sendMessage(yamlFactory.getMessageComponent("commands.message.header"))
        actor.sendMessage(yamlFactory.getMessageComponent("commands.message.usage.main"))
        actor.sendMessage(yamlFactory.getMessageComponent("commands.message.usage.reply"))
    }
    
    // Handle incoming cross-server messages
    fun handleCrossServerMessage(jsonMessage: String) {
        try {
            val message = gson.fromJson(jsonMessage, CrossServerMessage::class.java)
            
            when (message.type) {
                "send" -> {
                    // Try to find the target player on this server
                    val targetPlayer = radium.server.allPlayers.find { 
                        it.username.equals(message.targetName, ignoreCase = true) 
                    }
                    
                    if (targetPlayer != null) {
                        // Found the target, deliver the message
                        val receiverMessage = yamlFactory.getMessageComponent("commands.message.receiver_format",
                            "sender" to message.senderName,
                            "message" to message.message
                        ).clickEvent(ClickEvent.suggestCommand("/r "))
                            .hoverEvent(HoverEvent.showText(Component.text("Click to reply")))
                        
                        targetPlayer.sendMessage(receiverMessage)
                        
                        // Store for reply functionality
                        lastMessageSenders[targetPlayer.uniqueId] = UUID.fromString(message.senderUuid)
                        
                        // Send confirmation back to sender
                        sendMessageDeliveryConfirmation(message)
                    }
                }
                
                "reply" -> {
                    // Try to find the target player by UUID
                    val targetPlayer = message.targetUuid?.let { uuid ->
                        radium.server.getPlayer(UUID.fromString(uuid)).orElse(null)
                    }
                    
                    if (targetPlayer != null) {
                        // Found the target, deliver the reply
                        val receiverMessage = yamlFactory.getMessageComponent("commands.message.receiver_format",
                            "sender" to message.senderName,
                            "message" to message.message
                        ).clickEvent(ClickEvent.suggestCommand("/r "))
                            .hoverEvent(HoverEvent.showText(Component.text("Click to reply")))
                        
                        targetPlayer.sendMessage(receiverMessage)
                        
                        // Update conversation
                        lastMessageSenders[targetPlayer.uniqueId] = UUID.fromString(message.senderUuid)
                        
                        // Send confirmation back to sender
                        sendMessageDeliveryConfirmation(message)
                    }
                }
                
                "delivered" -> {
                    // Remove pending message and send confirmation to sender
                    val pending = pendingMessages.remove(message.requestId)
                    if (pending != null) {
                        val senderPlayer = radium.server.getPlayer(UUID.fromString(pending.senderUuid)).orElse(null)
                        if (senderPlayer != null) {
                            val senderMessage = yamlFactory.getMessageComponent("commands.message.sender_format",
                                "target" to message.targetName,
                                "message" to pending.message
                            ).clickEvent(ClickEvent.suggestCommand("/r "))
                                .hoverEvent(HoverEvent.showText(Component.text("Click to reply")))
                            
                            senderPlayer.sendMessage(senderMessage)
                            
                            // Store for reply functionality
                            message.targetUuid?.let { targetUuid ->
                                lastMessageSenders[UUID.fromString(targetUuid)] = senderPlayer.uniqueId
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            radium.logger.warn("Failed to handle cross-server message: ${e.message}")
        }
    }
    
    private fun sendMessageDeliveryConfirmation(originalMessage: CrossServerMessage) {
        // Since we removed Redis integration, this method is no longer needed
        // but kept for compatibility with the handleCrossServerMessage function
    }

    // Add the missing sendDirectMessage function
    fun sendDirectMessage(senderName: String, targetPlayer: Player, message: String) {
        val receiverMessage = yamlFactory.getMessageComponent("commands.message.receiver_format",
            "sender" to senderName,
            "message" to message
        ).clickEvent(ClickEvent.suggestCommand("/r "))
            .hoverEvent(HoverEvent.showText(Component.text("Click to reply")))

        targetPlayer.sendMessage(receiverMessage)
    }
}
