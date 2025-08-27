package radium.backend.punishment.events

import com.velocitypowered.api.event.EventTask
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.player.PlayerChatEvent
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import radium.backend.Radium
import radium.backend.punishment.models.PunishmentType

/**
 * Event listener for punishment enforcement
 * Follows the pattern of other event listeners in the project like StaffManager
 */
class PunishmentListener(private val radium: Radium) {

    @Subscribe
    fun onPlayerLogin(event: LoginEvent): EventTask {
        return EventTask.async {
            try {
                val player = event.player
                val playerId = player.uniqueId.toString()
                val playerIp = player.remoteAddress.address.hostAddress

                runBlocking {
                    // Check for IP-based punishments first
                    val ipPunishment = radium.punishmentManager.isIpBanned(playerIp)
                    if (ipPunishment != null) {
                        val message = when (ipPunishment.type) {
                            PunishmentType.BLACKLIST -> {
                                radium.yamlFactory.getMessageComponent(
                                    "punishments.player.blacklisted",
                                    "reason" to ipPunishment.reason
                                )
                            }
                            PunishmentType.IP_BAN -> {
                                if (ipPunishment.expiresAt != null) {
                                    radium.yamlFactory.getMessageComponent(
                                        "punishments.player.banned",
                                        "expires" to ipPunishment.expiresAt.toString(),
                                        "reason" to ipPunishment.reason
                                    )
                                } else {
                                    radium.yamlFactory.getMessageComponent(
                                        "punishments.player.banned_permanent",
                                        "reason" to ipPunishment.reason
                                    )
                                }
                            }
                            else -> Component.text("You are banned from this server.")
                        }

                        // Disconnect the player with the ban message
                        player.disconnect(message)

                        // Log the blocked connection attempt
                        radium.logger.info(
                            Component.text("Blocked login attempt from banned IP $playerIp (${player.username})")
                                .color(NamedTextColor.YELLOW)
                        )
                        return@runBlocking
                    }

                    // Check for player-specific punishments
                    val playerPunishment = radium.punishmentManager.isPlayerBanned(playerId)
                    if (playerPunishment != null) {
                        val message = when (playerPunishment.type) {
                            PunishmentType.BLACKLIST -> {
                                radium.yamlFactory.getMessageComponent(
                                    "punishments.player.blacklisted",
                                    "reason" to playerPunishment.reason
                                )
                            }
                            PunishmentType.BAN -> {
                                if (playerPunishment.expiresAt != null) {
                                    radium.yamlFactory.getMessageComponent(
                                        "punishments.player.banned",
                                        "expires" to playerPunishment.expiresAt.toString(),
                                        "reason" to playerPunishment.reason
                                    )
                                } else {
                                    radium.yamlFactory.getMessageComponent(
                                        "punishments.player.banned_permanent",
                                        "reason" to playerPunishment.reason
                                    )
                                }
                            }
                            else -> Component.text("You are banned from this server.")
                        }

                        // Disconnect the player with the ban message
                        player.disconnect(message)

                        // Log the blocked connection attempt
                        radium.logger.info(
                            Component.text("Blocked login attempt from banned player ${player.username} ($playerId)")
                                .color(NamedTextColor.YELLOW)
                        )
                        return@runBlocking
                    }

                    // Only log for staff members
                    if (player.hasPermission("radium.staff")) {
                        radium.logger.info(
                            Component.text("Staff member ${player.username} logged in")
                                .color(NamedTextColor.GREEN)
                        )
                    }
                }

            } catch (e: Exception) {
                radium.logger.error("Error checking punishments during login: ${e.message}", e)
                // Allow login on error to prevent false positives
            }
        }
    }

    @Subscribe(priority = 1000) // High priority to run before other chat handlers
    fun onPlayerChat(event: PlayerChatEvent): EventTask {
        return EventTask.async {
            try {
                val player = event.player
                val playerId = player.uniqueId.toString()

                runBlocking {
                    // Check if player is muted
                    val mutePunishment = radium.punishmentManager.isPlayerMuted(playerId)
                    if (mutePunishment != null) {
                        // For signed messages in 1.19.1+, replace with empty message instead of denying
                        if (event.result.isAllowed) {
                            event.result = PlayerChatEvent.ChatResult.message("")
                        }

                        // Send mute notification to player
                        val muteMessage = if (mutePunishment.expiresAt != null) {
                            radium.yamlFactory.getMessageComponent(
                                "punishments.player.chat_blocked_temporary",
                                "expires" to mutePunishment.expiresAt.toString(),
                                "reason" to mutePunishment.reason
                            )
                        } else {
                            radium.yamlFactory.getMessageComponent(
                                "punishments.player.chat_blocked",
                                "reason" to mutePunishment.reason
                            )
                        }

                        player.sendMessage(muteMessage)

                        // Log the blocked chat attempt
                        radium.logger.debug(
                            Component.text("Blocked chat from muted player ${player.username}: ${event.message}")
                                .color(NamedTextColor.YELLOW)
                        )

                        return@runBlocking
                    }
                }

            } catch (e: Exception) {
                radium.logger.error(
                    Component.text("Error checking mute status during chat: ${e.message}")
                        .color(NamedTextColor.RED)
                )
                // In case of error, allow chat to continue
            }
        }
    }
}
