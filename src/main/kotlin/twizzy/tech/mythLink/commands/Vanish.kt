package twizzy.tech.mythLink.commands

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import revxrsal.commands.annotation.Command
import revxrsal.commands.velocity.annotation.CommandPermission
import twizzy.tech.mythLink.MythLink

@Command("vanish")
@CommandPermission("command.vanish")
class Vanish(private val mythLink: MythLink) {

    private val staffManager = mythLink.staffManager
    private val yamlFactory = mythLink.yamlFactory

    @Command("vanish", "v")
    fun toggleVanish(actor: Player) {
        val isVanished = staffManager.vanishToggle(actor)

        // Send feedback to the player based on their new vanish state
        if (isVanished) {
            actor.sendMessage(yamlFactory.getMessageComponent("vanish.now_vanished"))
        } else {
            actor.sendMessage(yamlFactory.getMessageComponent("vanish.now_visible"))
        }
    }

    @Command("vanish auto")
    suspend fun autoVanish(actor: Player) {
        // Get the player's profile
        val profile = mythLink.connectionHandler.getPlayerProfile(actor.uniqueId)

        if (profile == null) {
            actor.sendMessage(yamlFactory.getMessageComponent("general.player_not_found", "target" to actor.username))
            return
        }

        // Get current setting or default to false if not set
        val currentSetting = profile.getSetting("autoVanish")?.toBoolean() ?: false

        // Toggle the setting
        val newSetting = !currentSetting

        // Update the setting in the profile
        profile.setSetting("autoVanish", newSetting.toString())

        // Display message based on the new setting value
        if (newSetting) {
            actor.sendMessage(yamlFactory.getMessageComponent("vanish.auto_enabled"))
        } else {
            actor.sendMessage(yamlFactory.getMessageComponent("vanish.auto_disabled"))
        }
    }
}