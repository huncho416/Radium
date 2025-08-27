package radium.backend.punishment.commands

import com.velocitypowered.api.proxy.Player
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Optional
import revxrsal.commands.velocity.annotation.CommandPermission
import radium.backend.Radium
import radium.backend.punishment.models.PunishmentType
import java.time.format.DateTimeFormatter
import java.time.ZoneId

@Command("checkpunishments", "punishinfo")
class CheckPunishments(private val radium: Radium) {

    @Command("checkpunishments <target>")
    @CommandPermission("radium.punish.check")
    fun checkPunishments(
        actor: Player,
        target: String,
        @Optional page: Int = 1
    ) {
        // Check for both old and new permission formats for lobby compatibility
        if (!actor.hasPermission("radium.punish.check") && !actor.hasPermission("radium.command.checkpunishments")) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("punishments.no_permission"))
            return
        }
        
        if (target.isEmpty()) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.checkpunishments.usage"))
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

                val pageSize = 10
                val actualPage = maxOf(1, page) - 1 // Convert to 0-based

                // Get total count first
                val totalCount = radium.punishmentManager.repository.countPunishments(profile.uuid.toString())
                
                if (totalCount == 0L) {
                    actor.sendMessage(
                        radium.yamlFactory.getMessageComponent(
                            "punishments.no_punishments_found",
                            "player" to profile.username
                        )
                    )
                    return@launch
                }

                // Get punishment history
                val punishments = radium.punishmentManager.getPunishmentHistory(
                    profile.uuid.toString(),
                    actualPage,
                    pageSize
                )

                val totalPages = ((totalCount + pageSize - 1) / pageSize).toInt()

                if (punishments.isEmpty() && actualPage >= totalPages) {
                    actor.sendMessage(
                        radium.yamlFactory.getMessageComponent(
                            "commands.checkpunishments.invalid_page",
                            "page" to (actualPage + 1).toString(),
                            "maxPage" to totalPages.toString()
                        )
                    )
                    return@launch
                }

                // Header
                actor.sendMessage(
                    Component.text("=== Punishment History for ${profile.username} ===")
                        .color(NamedTextColor.GOLD)
                        .decorate(TextDecoration.BOLD)
                )

                actor.sendMessage(
                    Component.text("Page ${actualPage + 1} of $totalPages ($totalCount total)")
                        .color(NamedTextColor.GRAY)
                )

                actor.sendMessage(Component.empty())

                // List punishments
                val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
                    .withZone(ZoneId.systemDefault())

                punishments.forEachIndexed { index, punishment ->
                    val statusColor = if (punishment.isCurrentlyActive()) NamedTextColor.RED else NamedTextColor.GREEN
                    val statusText = if (punishment.isCurrentlyActive()) "ACTIVE" else "INACTIVE"

                    val typeComponent = Component.text("[${punishment.type.displayName}]")
                        .color(getTypeColor(punishment.type))
                        .decorate(TextDecoration.BOLD)

                    val statusComponent = Component.text("[$statusText]")
                        .color(statusColor)

                    val dateComponent = Component.text(dateFormatter.format(punishment.issuedAt))
                        .color(NamedTextColor.GRAY)

                    val reasonComponent = Component.text(punishment.reason)
                        .color(NamedTextColor.WHITE)

                    val staffComponent = Component.text("by ${punishment.issuedByName}")
                        .color(NamedTextColor.YELLOW)

                    val line = Component.text("${actualPage * pageSize + index + 1}. ")
                        .color(NamedTextColor.WHITE)
                        .append(typeComponent)
                        .append(Component.text(" "))
                        .append(statusComponent)
                        .append(Component.text(" - "))
                        .append(reasonComponent)
                        .append(Component.text(" "))
                        .append(staffComponent)

                    // Add duration info if applicable
                    val detailsLine = if (punishment.expiresAt != null) {
                        val expiryText = if (punishment.isExpired()) {
                            "Expired: ${dateFormatter.format(punishment.expiresAt)}"
                        } else {
                            "Expires: ${dateFormatter.format(punishment.expiresAt)}"
                        }
                        Component.text("   Issued: ")
                            .color(NamedTextColor.GRAY)
                            .append(dateComponent)
                            .append(Component.text(" | $expiryText").color(NamedTextColor.GRAY))
                    } else {
                        Component.text("   Issued: ")
                            .color(NamedTextColor.GRAY)
                            .append(dateComponent)
                            .append(Component.text(" | Permanent").color(NamedTextColor.RED))
                    }

                    actor.sendMessage(line)
                    actor.sendMessage(detailsLine)

                    if (punishment.silent) {
                        actor.sendMessage(
                            Component.text("   (Silent punishment)")
                                .color(NamedTextColor.DARK_GRAY)
                        )
                    }

                    actor.sendMessage(Component.empty())
                }

                // Navigation footer
                if (totalPages > 1) {
                    val footer = Component.text("Page Navigation: ")
                        .color(NamedTextColor.GOLD)

                    if (actualPage > 0) {
                        footer.append(
                            Component.text("[Previous]")
                                .color(NamedTextColor.GREEN)
                                .clickEvent(ClickEvent.runCommand("/checkpunishments $target ${actualPage}"))
                                .hoverEvent(HoverEvent.showText(Component.text("Go to previous page")))
                        ).append(Component.text(" "))
                    }

                    if (actualPage < totalPages - 1) {
                        footer.append(
                            Component.text("[Next]")
                                .color(NamedTextColor.GREEN)
                                .clickEvent(ClickEvent.runCommand("/checkpunishments $target ${actualPage + 2}"))
                                .hoverEvent(HoverEvent.showText(Component.text("Go to next page")))
                        )
                    }

                    actor.sendMessage(footer)
                }

                // Show current active punishments summary
                val activePunishments = radium.punishmentManager.repository.findActivePunishments(profile.uuid.toString())
                if (activePunishments.isNotEmpty()) {
                    actor.sendMessage(Component.empty())
                    actor.sendMessage(
                        Component.text("Currently Active:")
                            .color(NamedTextColor.RED)
                            .decorate(TextDecoration.BOLD)
                    )

                    activePunishments.forEach { punishment ->
                        val summary = Component.text("• ${punishment.type.displayName}: ${punishment.reason}")
                            .color(NamedTextColor.RED)
                        actor.sendMessage(summary)
                    }
                }

            } catch (e: Exception) {
                radium.logger.error("Error executing checkpunishments command: ${e.message}", e)
                actor.sendMessage(radium.yamlFactory.getMessageComponent("punishments.error_occurred"))
            }
        }
    }

    @Command("punishinfo")
    @CommandPermission("radium.punish.info")
    fun punishInfo(actor: Player) {
        actor.sendMessage(
            Component.text("=== Radium Punishment System ===")
                .color(NamedTextColor.GOLD)
                .decorate(TextDecoration.BOLD)
        )

        actor.sendMessage(Component.empty())

        actor.sendMessage(
            Component.text("Available Commands:")
                .color(NamedTextColor.YELLOW)
                .decorate(TextDecoration.UNDERLINED)
        )

        val commands = listOf(
            "/ban <player> [duration] <reason> [-s] [-c]" to "Ban a player",
            "/unban <player> <reason> [-s]" to "Unban a player",
            "/ipban <player> [duration] <reason> [-s] [-c]" to "IP ban a player",
            "/mute <player> [duration] <reason> [-s]" to "Mute a player",
            "/unmute <player> <reason> [-s]" to "Unmute a player",
            "/warn <player> [duration] <reason> [-s]" to "Warn a player",
            "/kick <player> <reason> [-s]" to "Kick a player",
            "/blacklist <player> <reason> [-s] [-c]" to "Blacklist a player",
            "/unblacklist <player> <reason> [-s]" to "Remove blacklist",
            "/checkpunishments <player> [page]" to "View punishment history"
        )

        commands.forEach { (command, description) ->
            actor.sendMessage(
                Component.text("• ")
                    .color(NamedTextColor.GRAY)
                    .append(Component.text(command).color(NamedTextColor.GREEN))
                    .append(Component.text(" - $description").color(NamedTextColor.WHITE))
            )
        }

        actor.sendMessage(Component.empty())

        actor.sendMessage(
            Component.text("Flags:")
                .color(NamedTextColor.YELLOW)
                .decorate(TextDecoration.UNDERLINED)
        )

        actor.sendMessage(
            Component.text("• -s : Silent (staff-only notification)")
                .color(NamedTextColor.WHITE)
        )
        actor.sendMessage(
            Component.text("• -c : Clear inventory")
                .color(NamedTextColor.WHITE)
        )

        actor.sendMessage(Component.empty())

        val config = radium.yamlFactory.getConfig()
        val warnThreshold = radium.yamlFactory.getInt("punishments.warn.threshold", 3)
        val escalateDuration = radium.yamlFactory.getString("punishments.warn.escalateToBanDuration", "7d")

        actor.sendMessage(
            Component.text("Configuration:")
                .color(NamedTextColor.YELLOW)
                .decorate(TextDecoration.UNDERLINED)
        )

        actor.sendMessage(
            Component.text("• Warning threshold: $warnThreshold warnings")
                .color(NamedTextColor.WHITE)
        )
        actor.sendMessage(
            Component.text("• Auto-ban duration: $escalateDuration")
                .color(NamedTextColor.WHITE)
        )

        actor.sendMessage(Component.empty())

        actor.sendMessage(
            Component.text("Duration Examples: 10m, 2h, 1d, 7d, perm")
                .color(NamedTextColor.GRAY)
        )
    }

    private fun getTypeColor(type: PunishmentType): NamedTextColor {
        return when (type) {
            PunishmentType.BAN -> NamedTextColor.RED
            PunishmentType.IP_BAN -> NamedTextColor.DARK_RED
            PunishmentType.MUTE -> NamedTextColor.YELLOW
            PunishmentType.WARN -> NamedTextColor.GOLD
            PunishmentType.KICK -> NamedTextColor.BLUE
            PunishmentType.BLACKLIST -> NamedTextColor.DARK_PURPLE
        }
    }
}
