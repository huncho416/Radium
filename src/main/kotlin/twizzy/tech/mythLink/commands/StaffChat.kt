package twizzy.tech.mythLink.commands

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Subcommand
import revxrsal.commands.velocity.annotation.CommandPermission
import twizzy.tech.mythLink.MythLink

@Command("staffchat", "sc")
@CommandPermission("command.staffchat")
class StaffChat(private val mythLink: MythLink) {

    private val staffManager = mythLink.staffManager

    private val yamlFactory = mythLink.yamlFactory

    @Command("staffchat", "sc")
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
    fun sendStaffMessage(actor: Player, message: String) {
        // Ensure the player is listening when they send a direct message
        if (!staffManager.isListening(actor)) {
            staffManager.setListening(actor, true)
            actor.sendMessage(yamlFactory.getMessageComponent("staff.listening_enabled"))
        }

        // Use the chat format from the lang.yml
        val chatFormat = yamlFactory.getMessageComponent("staff.chat_format",
            "player" to actor.username,
            "message" to message
        )

        staffManager.sendStaffMessage(chatFormat)
    }
}