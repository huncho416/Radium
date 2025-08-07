package radium.backend.commands

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import revxrsal.commands.annotation.Command
import revxrsal.commands.velocity.annotation.CommandPermission
import radium.backend.Radium

@Command("vanish")
@CommandPermission("radium.staff")
class Vanish(private val radium: Radium) {

    private val staffManager = radium.staffManager
    private val yamlFactory = radium.yamlFactory

    @Command("vanish", "v")
    @CommandPermission("radium.vanish.use")
    suspend fun toggleVanish(actor: Player) {
        val isVanished = staffManager.vanishToggle(actor)

        // Send feedback to the player based on their new vanish state
        if (isVanished) {
            actor.sendMessage(yamlFactory.getMessageComponent("vanish.now_vanished"))
        } else {
            actor.sendMessage(yamlFactory.getMessageComponent("vanish.now_visible"))
        }
    }

    @Command("vanish auto")
    @CommandPermission("radium.vanish.auto")
    suspend fun autoVanish(actor: Player) {
        // Get the player's profile
        val profile = radium.connectionHandler.getPlayerProfile(actor.uniqueId)

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

    @Command("vanish list", "v list")
    @CommandPermission("radium.vanish.list")
    suspend fun listVanished(actor: Player) {
        val visibleVanished = staffManager.getVisibleVanishedPlayers(actor)
        
        if (visibleVanished.isEmpty()) {
            actor.sendMessage(yamlFactory.getMessageComponent("vanish.list.none"))
        } else {
            actor.sendMessage(yamlFactory.getMessageComponent("vanish.list.header", "count" to visibleVanished.size.toString()))
            
            visibleVanished.forEach { vanishedPlayer ->
                // Get the vanished player's profile to show their rank
                val profile = radium.connectionHandler.findPlayerProfile(vanishedPlayer.uniqueId.toString())
                val rankName = if (profile != null) {
                    val highestRank = profile.getHighestRank(radium.rankManager)
                    highestRank?.name ?: "Default"
                } else {
                    "Unknown"
                }
                
                actor.sendMessage(yamlFactory.getMessageComponent("vanish.list.entry", 
                    "player" to vanishedPlayer.username,
                    "rank" to rankName
                ))
            }
        }
    }

    @Command("vanish help", "v help")
    @CommandPermission("radium.vanish.use")
    fun vanishUsage(actor: Player) {
        actor.sendMessage(yamlFactory.getMessageComponent("commands.vanish.header"))
        actor.sendMessage(yamlFactory.getMessageComponent("commands.vanish.usage.main"))
        actor.sendMessage(yamlFactory.getMessageComponent("commands.vanish.usage.auto"))
        actor.sendMessage(yamlFactory.getMessageComponent("commands.vanish.usage.list"))
    }
}
