package radium.backend.punishment.commands

import com.velocitypowered.api.proxy.Player
import kotlinx.coroutines.launch
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Flag
import revxrsal.commands.annotation.Optional
import revxrsal.commands.velocity.annotation.CommandPermission
import radium.backend.Radium
import radium.backend.annotations.OnlinePlayers
import radium.backend.punishment.models.PunishmentType

@Command("mute", "unmute")
class Mute(private val radium: Radium) {

    @Command("mute <target> <reason>")
    @CommandPermission("radium.punish.mute")
    fun mute(
        actor: Player,
        @OnlinePlayers target: String,
        reason: String,
        @Flag("s") silent: Boolean = false
    ) {
        // Check for both old and new permission formats for lobby compatibility
        if (!actor.hasPermission("radium.punish.mute") && !actor.hasPermission("radium.command.mute")) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("punishments.no_permission"))
            return
        }
        
        if (target.isEmpty() || reason.isEmpty()) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.mute.usage"))
            return
        }

        if (silent && !actor.hasPermission("radium.punish.silent")) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("punishments.no_silent_permission"))
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
                    type = PunishmentType.MUTE,
                    reason = reason,
                    staff = actor,
                    duration = null,
                    silent = silent,
                    clearInventory = false
                )

                if (success) {
                    actor.sendMessage(
                        radium.yamlFactory.getMessageComponent(
                            "punishments.mute_issued_permanent",
                            "player" to targetName,
                            "reason" to reason
                        )
                    )

                    // Notify the muted player if online
                    targetPlayer?.let { player ->
                        val muteMessage = radium.yamlFactory.getMessageComponent(
                            "punishments.player.muted_permanent",
                            "reason" to reason
                        )
                        player.sendMessage(muteMessage)
                    }
                }
            } catch (e: Exception) {
                radium.logger.error("Error executing mute command: ${e.message}", e)
                actor.sendMessage(radium.yamlFactory.getMessageComponent("punishments.error_occurred"))
            }
        }
    }

    @Command("unmute <target> <reason>")
    @CommandPermission("radium.punish.unmute")
    fun unmute(
        actor: Player,
        target: String,
        reason: String,
        @Flag("s") silent: Boolean = false
    ) {
        // Check for both old and new permission formats for lobby compatibility
        if (!actor.hasPermission("radium.punish.unmute") && !actor.hasPermission("radium.command.unmute")) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("punishments.no_permission"))
            return
        }
        
        if (target.isEmpty() || reason.isEmpty()) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.unmute.usage"))
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
                    type = PunishmentType.MUTE,
                    reason = reason,
                    staff = actor,
                    silent = silent
                )

                if (success) {
                    actor.sendMessage(
                        radium.yamlFactory.getMessageComponent(
                            "punishments.unmute_success",
                            "player" to profile.username,
                            "reason" to reason
                        )
                    )

                    // Notify the unmuted player if online
                    radium.server.getPlayer(profile.username).ifPresent { player ->
                        player.sendMessage(
                            radium.yamlFactory.getMessageComponent(
                                "punishments.player.unmuted",
                                "reason" to reason
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                radium.logger.error("Error executing unmute command: ${e.message}", e)
                actor.sendMessage(radium.yamlFactory.getMessageComponent("punishments.error_occurred"))
            }
        }
    }

    /**
     * Parse duration and reason from command arguments
     */
    private fun parseDurationAndReason(duration: String?, reason: String): Pair<String?, String> {
        return if (duration != null) {
            Pair(duration, reason)
        } else {
            val reasonParts = reason.split(" ", limit = 2)
            if (reasonParts.size >= 2 && isDurationString(reasonParts[0])) {
                Pair(reasonParts[0], reasonParts[1])
            } else {
                Pair(null, reason)
            }
        }
    }

    private fun isDurationString(str: String): Boolean {
        return str.matches(Regex("^\\d+[smhdwy]|perm|permanent$", RegexOption.IGNORE_CASE))
    }
}
