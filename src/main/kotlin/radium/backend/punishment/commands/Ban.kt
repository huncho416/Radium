package radium.backend.punishment.commands

import com.velocitypowered.api.proxy.Player
import kotlinx.coroutines.launch
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Flag
import revxrsal.commands.velocity.annotation.CommandPermission
import radium.backend.Radium
import radium.backend.annotations.OnlinePlayers
import radium.backend.punishment.models.PunishmentType
import radium.backend.punishment.models.PunishmentRequest

@Command("ban", "unban", "ipban")
class Ban(private val radium: Radium) {

    @Command("ban <target> <reason>")
    @CommandPermission("radium.punish.ban")
    fun ban(
        actor: Player,
        @OnlinePlayers target: String,
        reason: String,
        @Flag("s") silent: Boolean = false,
        @Flag("c") clearInventory: Boolean = false
    ) {
        // Check for both old and new permission formats for lobby compatibility
        if (!actor.hasPermission("radium.punish.ban") && !actor.hasPermission("radium.command.ban")) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("punishments.no_permission"))
            return
        }
        
        if (target.isEmpty() || reason.isEmpty()) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.ban.usage"))
            return
        }

        // Validate flags
        if (silent && !actor.hasPermission("radium.punish.silent")) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("punishments.no_silent_permission"))
            return
        }

        if (clearInventory && !actor.hasPermission("radium.punish.clearinventory")) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("punishments.no_clear_permission"))
            return
        }

        radium.scope.launch {
            try {
                // Try to find player online first
                val targetPlayer = radium.server.getPlayer(target).orElse(null)

                // If not online, try to get from database
                val (targetId, targetName, targetIp) = if (targetPlayer != null) {
                    Triple(
                        targetPlayer.uniqueId.toString(),
                        targetPlayer.username,
                        targetPlayer.remoteAddress.address.hostAddress
                    )
                } else {
                    // Look up offline player
                    val profile = radium.connectionHandler.findPlayerProfile(target)
                    if (profile != null) {
                        Triple(profile.uuid.toString(), profile.username, null)
                    } else {
                        actor.sendMessage(radium.yamlFactory.getMessageComponent("punishments.player_not_found"))
                        return@launch
                    }
                }

                val success = radium.punishmentManager.issuePunishment(
                    target = targetPlayer,
                    targetId = targetId,
                    targetName = targetName,
                    targetIp = targetIp,
                    type = PunishmentType.BAN,
                    reason = reason,
                    staff = actor,
                    duration = null,
                    silent = silent,
                    clearInventory = clearInventory,
                    priority = if (targetPlayer != null) 
                        PunishmentRequest.Priority.HIGH 
                    else 
                        PunishmentRequest.Priority.NORMAL
                )

                // Success and error messages are handled by PunishmentManager
            } catch (e: Exception) {
                radium.logger.error("Error executing ban command: ${e.message}", e)
                actor.sendMessage(radium.yamlFactory.getMessageComponent("punishments.error_occurred"))
            }
        }
    }

    @Command("ipban <target> <reason>")
    @CommandPermission("radium.punish.ipban")
    fun ipban(
        actor: Player,
        @OnlinePlayers target: String,
        reason: String,
        @Flag("s") silent: Boolean = false,
        @Flag("c") clearInventory: Boolean = false
    ) {
        if (target.isEmpty() || reason.isEmpty()) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.ipban.usage"))
            return
        }

        // Validate flags
        if (silent && !actor.hasPermission("radium.punish.silent")) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("punishments.no_silent_permission"))
            return
        }

        if (clearInventory && !actor.hasPermission("radium.punish.clearinventory")) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("punishments.no_clear_permission"))
            return
        }

        radium.scope.launch {
            try {
                // Find player online (required for IP bans)
                val targetPlayer = radium.server.getPlayer(target).orElse(null)
                if (targetPlayer == null) {
                    actor.sendMessage(
                        radium.yamlFactory.getMessageComponent("punishments.player_must_be_online_for_ipban")
                    )
                    return@launch
                }

                val targetId = targetPlayer.uniqueId.toString()
                val targetName = targetPlayer.username
                val targetIp = targetPlayer.remoteAddress.address.hostAddress

                val success = radium.punishmentManager.issuePunishment(
                    target = targetPlayer,
                    targetId = targetId,
                    targetName = targetName,
                    targetIp = targetIp,
                    type = PunishmentType.IP_BAN,
                    reason = reason,
                    staff = actor,
                    duration = null,
                    silent = silent,
                    clearInventory = clearInventory
                )

                // Success and error messages are handled by PunishmentManager
            } catch (e: Exception) {
                radium.logger.error("Error executing ipban command: ${e.message}", e)
                actor.sendMessage(radium.yamlFactory.getMessageComponent("punishments.error_occurred"))
            }
        }
    }

    @Command("unban <target> <reason>")
    @CommandPermission("radium.punish.unban")
    fun unban(
        actor: Player,
        target: String,
        reason: String,
        @Flag("s") silent: Boolean = false
    ) {
        // Check for both old and new permission formats for lobby compatibility
        if (!actor.hasPermission("radium.punish.unban") && !actor.hasPermission("radium.command.unban")) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("punishments.no_permission"))
            return
        }
        
        if (target.isEmpty() || reason.isEmpty()) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.unban.usage"))
            return
        }

        if (silent && !actor.hasPermission("radium.punish.silent")) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("punishments.no_silent_permission"))
            return
        }

        radium.scope.launch {
            try {
                // Look up the player (can be offline)
                val profile = radium.connectionHandler.findPlayerProfile(target)
                if (profile == null) {
                    actor.sendMessage(radium.yamlFactory.getMessageComponent("punishments.player_not_found"))
                    return@launch
                }

                val success = radium.punishmentManager.revokePunishment(
                    targetId = profile.uuid.toString(),
                    targetName = profile.username,
                    type = PunishmentType.BAN,
                    reason = reason,
                    staff = actor,
                    silent = silent
                )

                // Success and error messages are handled by PunishmentManager
            } catch (e: Exception) {
                radium.logger.error("Error executing unban command: ${e.message}", e)
                actor.sendMessage(radium.yamlFactory.getMessageComponent("punishments.error_occurred"))
            }
        }
    }
}
