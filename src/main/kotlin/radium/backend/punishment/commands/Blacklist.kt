package radium.backend.punishment.commands

import com.velocitypowered.api.proxy.Player
import kotlinx.coroutines.launch
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Flag
import revxrsal.commands.velocity.annotation.CommandPermission
import radium.backend.Radium
import radium.backend.annotations.OnlinePlayers
import radium.backend.punishment.models.PunishmentType

@Command("blacklist", "unblacklist")
class Blacklist(private val radium: Radium) {

    @Command("blacklist <target> <reason>")
    @CommandPermission("radium.punish.blacklist")
    fun blacklist(
        actor: Player,
        @OnlinePlayers target: String,
        reason: String,
        @Flag("s") silent: Boolean = false,
        @Flag("c") clearInventory: Boolean = false
    ) {
        if (target.isEmpty() || reason.isEmpty()) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.blacklist.usage"))
            return
        }

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
                        actor.sendMessage(
                            radium.yamlFactory.getMessageComponent(
                                "punishments.player_not_found",
                                "player" to target
                            )
                        )
                        return@launch
                    }
                }

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
                    type = PunishmentType.BLACKLIST,
                    reason = reason,
                    staff = actor,
                    duration = null, // Blacklists are permanent
                    silent = silent,
                    clearInventory = clearInventory
                )

                if (success) {
                    actor.sendMessage(
                        radium.yamlFactory.getMessageComponent(
                            "punishments.blacklist_issued",
                            "player" to targetName,
                            "reason" to reason
                        )
                    )
                }
            } catch (e: Exception) {
                radium.logger.error("Error executing blacklist command: ${e.message}", e)
                actor.sendMessage(radium.yamlFactory.getMessageComponent("punishments.error_occurred"))
            }
        }
    }

    @Command("unblacklist <target> <reason>")
    @CommandPermission("radium.punish.unblacklist")
    fun unblacklist(
        actor: Player,
        target: String,
        reason: String,
        @Flag("s") silent: Boolean = false
    ) {
        if (target.isEmpty() || reason.isEmpty()) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.unblacklist.usage"))
            return
        }

        if (silent && !actor.hasPermission("radium.punish.silent")) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("punishments.no_silent_permission"))
            return
        }

        radium.scope.launch {
            try {
                // Look up player UUID
                val profile = radium.connectionHandler.findPlayerProfile(target)
                if (profile == null) {
                    actor.sendMessage(
                        radium.yamlFactory.getMessageComponent(
                            "punishments.player_not_found",
                            "player" to target
                        )
                    )
                    return@launch
                }

                val success = radium.punishmentManager.revokePunishment(
                    targetId = profile.uuid.toString(),
                    targetName = profile.username,
                    type = PunishmentType.BLACKLIST,
                    reason = reason,
                    staff = actor,
                    silent = silent
                )

                if (success) {
                    actor.sendMessage(
                        radium.yamlFactory.getMessageComponent(
                            "punishments.unblacklist_success",
                            "player" to profile.username,
                            "reason" to reason
                        )
                    )
                }
            } catch (e: Exception) {
                radium.logger.error("Error executing unblacklist command: ${e.message}", e)
                actor.sendMessage(radium.yamlFactory.getMessageComponent("punishments.error_occurred"))
            }
        }
    }
}
