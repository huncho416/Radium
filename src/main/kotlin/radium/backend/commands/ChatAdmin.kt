package radium.backend.commands

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Subcommand
import revxrsal.commands.velocity.annotation.CommandPermission
import radium.backend.Radium

@Command("chat")
@CommandPermission("radium.chat.admin")
class ChatAdmin(private val radium: Radium) {

    private val yamlFactory = radium.yamlFactory
    private val logger = radium.logger

    @Command
    fun chatHelp(actor: Player) {
        actor.sendMessage(yamlFactory.getMessageComponent("commands.chat.header"))
        actor.sendMessage(yamlFactory.getMessageComponent("commands.chat.usage.slow"))
        actor.sendMessage(yamlFactory.getMessageComponent("commands.chat.usage.mute"))
        actor.sendMessage(yamlFactory.getMessageComponent("commands.chat.usage.unmute"))
        actor.sendMessage(yamlFactory.getMessageComponent("commands.chat.usage.clear"))
    }

    @Subcommand("slow")
    @CommandPermission("radium.chat.slow")
    fun slowChat(actor: Player, delay: String) {
        // Parse delay (1s, 5s, 10s, etc.)
        val delaySeconds = parseDelay(delay)
        if (delaySeconds == null) {
            actor.sendMessage(yamlFactory.getMessageComponent("commands.chat.slow.invalid_delay"))
            return
        }
        
        if (delaySeconds <= 0) {
            actor.sendMessage(yamlFactory.getMessageComponent("commands.chat.slow.invalid_number"))
            return
        }
        
        if (delaySeconds > 300) { // Max 5 minutes
            actor.sendMessage(yamlFactory.getMessageComponent("commands.chat.slow.too_long"))
            return
        }
        
        // Set chat slow delay
        radium.chatManager.setChatSlowDelay(delaySeconds)
        
        // Notify staff member
        actor.sendMessage(yamlFactory.getMessageComponent("commands.chat.slow.success", "delay" to delaySeconds.toString()))
        
        // Broadcast to all players (except those with bypass permission)
        val broadcastMessage = yamlFactory.getMessageComponent("commands.chat.slow.broadcast", "delay" to delaySeconds.toString())
            
        radium.server.allPlayers.forEach { player ->
            if (!player.hasPermission("radium.chat.bypass")) {
                player.sendMessage(broadcastMessage)
            }
        }
        
        logger.info("Chat slowed to ${delaySeconds}s by ${actor.username}")
    }

    @Subcommand("mute")
    @CommandPermission("radium.chat.mute")
    fun muteChat(actor: Player) {
        if (radium.chatManager.isChatMuted()) {
            actor.sendMessage(yamlFactory.getMessageComponent("commands.chat.mute.already_muted"))
            return
        }
        
        // Mute chat
        radium.chatManager.setChatMuted(true)
        
        // Notify staff member
        actor.sendMessage(yamlFactory.getMessageComponent("commands.chat.mute.success"))
        
        // Broadcast to all players (except those with bypass permission)
        val broadcastMessage = yamlFactory.getMessageComponent("commands.chat.mute.broadcast")
            
        radium.server.allPlayers.forEach { player ->
            if (!player.hasPermission("radium.chat.bypass")) {
                player.sendMessage(broadcastMessage)
            }
        }
        
        logger.info("Chat muted by ${actor.username}")
    }

    @Subcommand("unmute")
    @CommandPermission("radium.chat.mute")
    fun unmuteChat(actor: Player) {
        if (!radium.chatManager.isChatMuted()) {
            actor.sendMessage(yamlFactory.getMessageComponent("commands.chat.unmute.not_muted"))
            return
        }
        
        // Unmute chat
        radium.chatManager.setChatMuted(false)
        
        // Notify staff member
        actor.sendMessage(yamlFactory.getMessageComponent("commands.chat.unmute.success"))
        
        // Broadcast to all players
        val broadcastMessage = yamlFactory.getMessageComponent("commands.chat.unmute.broadcast")
            
        radium.server.allPlayers.forEach { player ->
            player.sendMessage(broadcastMessage)
        }
        
        logger.info("Chat unmuted by ${actor.username}")
    }

    @Subcommand("clear")
    @CommandPermission("radium.chat.clear")
    fun clearChat(actor: Player) {
        // Notify staff member
        actor.sendMessage(yamlFactory.getMessageComponent("commands.chat.clear.success"))
        
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
        val broadcastMessage = yamlFactory.getMessageComponent("commands.chat.clear.broadcast")
            
        radium.server.allPlayers.forEach { player ->
            player.sendMessage(broadcastMessage)
        }
        
        logger.info("Chat cleared by ${actor.username}")
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
