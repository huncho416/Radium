package radium.backend.commands

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Subcommand
import revxrsal.commands.annotation.Optional
import revxrsal.commands.velocity.annotation.CommandPermission
import radium.backend.Radium
import radium.backend.annotations.OnlinePlayers
import radium.backend.vanish.VanishLevel

@Command("vanish")
@CommandPermission("radium.vanish.use")
class Vanish(private val radium: Radium) {

    private val vanishManager = radium.networkVanishManager
    private val yamlFactory = radium.yamlFactory

    @Command("vanish", "v")
    fun toggleVanish(actor: Player) {
        val isCurrentlyVanished = vanishManager.isVanished(actor.uniqueId)
        val newState = !isCurrentlyVanished
        
        // Use async method since it's now suspend
        vanishManager.setVanishStateAsync(actor, newState)
        
        // Send immediate feedback
        if (newState) {
            actor.sendMessage(yamlFactory.getMessageComponent("vanish.now_vanished"))
        } else {
            actor.sendMessage(yamlFactory.getMessageComponent("vanish.now_visible"))
        }
    }

    @Subcommand("player")
    @CommandPermission("radium.vanish.others")
    fun vanishPlayer(actor: Player, @OnlinePlayers targetName: String, @Optional state: String?) {
        val target = radium.server.getPlayer(targetName).orElse(null)
        if (target == null) {
            actor.sendMessage(yamlFactory.getMessageComponent("general.player_not_found", "target" to targetName))
            return
        }
        
        val newState = when (state?.lowercase()) {
            "on", "true", "enable" -> true
            "off", "false", "disable" -> false
            null -> !vanishManager.isVanished(target.uniqueId) // Toggle if no state specified
            else -> {
                actor.sendMessage(Component.text("Invalid state. Use: on, off, or leave empty to toggle.", NamedTextColor.RED))
                return
            }
        }
        
        // Use async method since it's now suspend
        vanishManager.setVanishStateAsync(target, newState, vanishedBy = actor)
        
        // Send immediate feedback
        val stateText = if (newState) "vanished" else "visible"
        actor.sendMessage(Component.text("${target.username} is now $stateText.", NamedTextColor.GREEN))
        
        if (target.uniqueId != actor.uniqueId) {
            if (newState) {
                target.sendMessage(yamlFactory.getMessageComponent("vanish.vanished_by_staff", "staff" to actor.username))
            } else {
                target.sendMessage(yamlFactory.getMessageComponent("vanish.unvanished_by_staff", "staff" to actor.username))
            }
        }
    }

    @Subcommand("list")
    @CommandPermission("radium.vanish.list")
    fun listVanished(actor: Player) {
        val vanishedPlayers = vanishManager.getVanishedPlayers()
        
        if (vanishedPlayers.isEmpty()) {
            actor.sendMessage(Component.text("No players are currently vanished.", NamedTextColor.YELLOW))
            return
        }
        
        actor.sendMessage(Component.text("Vanished Players:", NamedTextColor.YELLOW))
        
        vanishedPlayers.forEach { (playerId, vanishData) ->
            val player = radium.server.getPlayer(playerId).orElse(null)
            val playerName = player?.username ?: "Unknown"
            
            if (VanishLevel.canSeeVanished(actor, vanishData.level)) {
                val duration = vanishData.getFormattedDuration()
                val levelText = vanishData.level.displayName
                
                actor.sendMessage(Component.text()
                    .append(Component.text("- ", NamedTextColor.GRAY))
                    .append(Component.text(playerName, NamedTextColor.WHITE))
                    .append(Component.text(" (", NamedTextColor.GRAY))
                    .append(Component.text(levelText, NamedTextColor.YELLOW))
                    .append(Component.text(", ", NamedTextColor.GRAY))
                    .append(Component.text(duration, NamedTextColor.GREEN))
                    .append(Component.text(")", NamedTextColor.GRAY))
                    .build())
            }
        }
    }

    @Subcommand("auto")
    @CommandPermission("radium.vanish.auto")
    suspend fun autoVanish(actor: Player) {
        // Get the player's profile
        val profile = radium.connectionHandler.getPlayerProfile(actor.uniqueId, actor.username)

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
