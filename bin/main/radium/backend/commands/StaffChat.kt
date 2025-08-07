package radium.backend.commands

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Subcommand
import revxrsal.commands.velocity.annotation.CommandPermission
import radium.backend.Radium

@Command("staffchat", "sc")
@CommandPermission("radium.staff")
class StaffChat(private val radium: Radium) {

    private val staffManager = radium.staffManager

    private val yamlFactory = radium.yamlFactory

    @Command("staffchat", "sc")
    @CommandPermission("radium.staffchat.use")
    fun toggleStaffChat(actor: Player) {
        // Toggle talking mode for staff chat
        val isTalking = staffManager.isTalking(actor)
        staffManager.setTalking(actor, !isTalking)

        if (staffManager.isTalking(actor)) {
            actor.sendMessage(yamlFactory.getMessageComponent("staff.chat_enabled"))
        } else {
            actor.sendMessage(yamlFactory.getMessageComponent("staff.chat_disabled"))
        }
    }

    @Subcommand("hide")
    @CommandPermission("radium.staffchat.hide")
    fun hideStaffChat(actor: Player) {
        // Toggle listening status
        val isCurrentlyListening = staffManager.isListening(actor)
        staffManager.setListening(actor, !isCurrentlyListening)

        // If turning on listening, show appropriate message
        if (!isCurrentlyListening) {
            actor.sendMessage(yamlFactory.getMessageComponent("staff.listening_enabled"))
        } else {
            // If turning off listening, also turn off talking
            staffManager.setTalking(actor, false)
            actor.sendMessage(yamlFactory.getMessageComponent("staff.listening_disabled"))
        }
    }

    @Command("staffchat <message>", "sc <message>")
    @CommandPermission("radium.staffchat.send")
    fun sendStaffMessage(actor: Player, message: String) {
        // Ensure the player is listening when they send a direct message
        if (!staffManager.isListening(actor)) {
            staffManager.setListening(actor, true)
            actor.sendMessage(yamlFactory.getMessageComponent("staff.listening_enabled"))
        }

        // Launch coroutine to handle async profile lookup
        GlobalScope.launch {
            // Get player profile to access rank information
            val profile = radium.connectionHandler.findPlayerProfile(actor.uniqueId.toString())
            if (profile != null) {
                // Get highest rank for prefix and name color
                val highestRank = profile.getHighestRank(radium.rankManager)
                val prefix = highestRank?.prefix ?: ""
                val chatColor = highestRank?.color ?: "&7"
                
                // Use the chat format from the lang.yml with proper formatting
                val chatFormat = yamlFactory.getMessageComponent("staff.chat_format",
                    "prefix" to prefix,
                    "player" to actor.username,
                    "chatColor" to chatColor,
                    "message" to message
                )

                staffManager.sendStaffMessage(chatFormat)
            } else {
                // Fallback if profile not found
                val chatFormat = yamlFactory.getMessageComponent("staff.chat_format",
                    "prefix" to "",
                    "player" to actor.username,
                    "chatColor" to "&7",
                    "message" to message
                )
                staffManager.sendStaffMessage(chatFormat)
            }
        }
    }
}
