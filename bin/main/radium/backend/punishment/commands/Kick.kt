package radium.backend.punishment.commands

import com.velocitypowered.api.proxy.Player
import kotlinx.coroutines.launch
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Flag
import revxrsal.commands.velocity.annotation.CommandPermission
import radium.backend.Radium
import radium.backend.annotations.OnlinePlayers
import radium.backend.punishment.models.PunishmentType

@Command("kick")
class Kick(private val radium: Radium) {

    @Command("kick <target> <reason>")
    @CommandPermission("radium.punish.kick")
    fun kick(
        actor: Player,
        @OnlinePlayers target: String,
        reason: String,
        @Flag("s") silent: Boolean = false
    ) {
        // Check for both old and new permission formats for lobby compatibility
        if (!actor.hasPermission("radium.punish.kick") && !actor.hasPermission("radium.command.kick")) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("punishments.no_permission"))
            return
        }
        
        if (target.isEmpty() || reason.isEmpty()) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.kick.usage"))
            return
        }

        if (silent && !actor.hasPermission("radium.punish.silent")) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("punishments.no_silent_permission"))
            return
        }

        radium.scope.launch {
            try {
                // For kicks, player must be online
                val targetPlayer = radium.server.getPlayer(target).orElse(null)
                if (targetPlayer == null) {
                    actor.sendMessage(
                        radium.yamlFactory.getMessageComponent(
                            "punishments.player_not_online",
                            "player" to target
                        )
                    )
                    return@launch
                }

                val targetId = targetPlayer.uniqueId.toString()
                val targetName = targetPlayer.username
                val targetIp = targetPlayer.remoteAddress.address.hostAddress

                // Prevent self-punishment
                if (targetId == actor.uniqueId.toString() && !actor.hasPermission("radium.punish.self")) {
                    actor.sendMessage(radium.yamlFactory.getMessageComponent("punishments.cannot_punish_self"))
                    return@launch
                }

                val success = radium.punishmentManager.issuePunishment(
                    target = targetPlayer,
                    targetId = targetId,
                    targetName = targetName,
                    targetIp = targetIp,
                    type = PunishmentType.KICK,
                    reason = reason,
                    staff = actor,
                    duration = null, // Kicks don't have duration
                    silent = silent,
                    clearInventory = false
                )

                if (success) {
                    actor.sendMessage(
                        radium.yamlFactory.getMessageComponent(
                            "punishments.kick_issued",
                            "player" to targetName,
                            "reason" to reason
                        )
                    )
                }
            } catch (e: Exception) {
                radium.logger.error("Error executing kick command: ${e.message}", e)
                actor.sendMessage(radium.yamlFactory.getMessageComponent("punishments.error_occurred"))
            }
        }
    }
}
