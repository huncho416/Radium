package radium.backend.punishment.commands

import com.velocitypowered.api.proxy.Player
import kotlinx.coroutines.launch
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Flag
import revxrsal.commands.velocity.annotation.CommandPermission
import radium.backend.Radium
import radium.backend.annotations.OnlinePlayers
import radium.backend.punishment.models.PunishmentType

@Command("warn")
class Warn(private val radium: Radium) {

    @Command("warn <target> <reason>")
    @CommandPermission("radium.punish.warn")
    fun warn(
        actor: Player,
        @OnlinePlayers target: String,
        reason: String,
        @Flag("s") silent: Boolean = false
    ) {
        if (target.isEmpty() || reason.isEmpty()) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.warn.usage"))
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
                    type = PunishmentType.WARN,
                    reason = reason,
                    staff = actor,
                    duration = null,
                    silent = silent,
                    clearInventory = false
                )

                if (success) {
                    actor.sendMessage(
                        radium.yamlFactory.getMessageComponent(
                            "punishments.warn_issued",
                            "player" to targetName,
                            "reason" to reason
                        )
                    )

                    // Show current warning count
                    val warnCount = radium.punishmentManager.repository.countActiveWarnings(targetId)
                    val threshold = radium.yamlFactory.getConfig().get("punishments")?.let { punishments ->
                        (punishments as? Map<*, *>)?.get("warn")?.let { warn ->
                            (warn as? Map<*, *>)?.get("threshold") as? Int
                        }
                    } ?: 3

                    actor.sendMessage(
                        radium.yamlFactory.getMessageComponent(
                            "punishments.warn_count",
                            "player" to targetName,
                            "count" to warnCount.toString(),
                            "threshold" to threshold.toString()
                        )
                    )
                }
            } catch (e: Exception) {
                radium.logger.error("Error executing warn command: ${e.message}", e)
                actor.sendMessage(radium.yamlFactory.getMessageComponent("punishments.error_occurred"))
            }
        }
    }

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
