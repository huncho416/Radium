package radium.backend.api.routes

import com.velocitypowered.api.proxy.ProxyServer
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import radium.backend.Radium
import radium.backend.api.dto.*

fun Route.commandRoutes(plugin: Radium, server: ProxyServer, logger: ComponentLogger) {
    route("/commands") {
        // Execute command as player
        post("/execute") {
            val commandRequest = call.receive<CommandRequest>()

            val player = server.getPlayer(commandRequest.player).orElse(null)
            if (player == null) {
                call.respond(HttpStatusCode.NotFound,
                    CommandResponse(commandRequest.player, commandRequest.command, false, false, "Player not found"))
                return@post
            }

            try {
                // Execute the command as if the player typed it on the proxy
                val result = server.commandManager.executeAsync(player, commandRequest.command)
                
                result.thenAccept { commandResult ->
                    logger.info("Executed command '${commandRequest.command}' for player ${commandRequest.player} via API - Result: $commandResult")
                }.exceptionally { throwable ->
                    logger.error("Command execution failed for '${commandRequest.command}' by ${commandRequest.player}: ${throwable.message}", throwable)
                    null
                }
                
                // Wait for completion
                result.join()

                logger.info("Command '${commandRequest.command}' executed successfully for player ${commandRequest.player} via API")
                call.respond(CommandResponse(commandRequest.player, commandRequest.command, true, true, "Command executed"))
            } catch (e: Exception) {
                logger.error("Failed to execute command '${commandRequest.command}' for player ${commandRequest.player}: ${e.message}", e)
                call.respond(HttpStatusCode.InternalServerError,
                    CommandResponse(commandRequest.player, commandRequest.command, false, false, e.message))
            }
        }

        // Send global message
        post("/global-message") {
            val messageRequest = call.receive<GlobalMessageRequest>()

            try {
                // Use the existing ProxyCommunicationManager functionality if available
                // Otherwise broadcast directly to all players
                server.allPlayers.forEach { player ->
                    player.sendMessage(net.kyori.adventure.text.Component.text(messageRequest.message))
                }

                logger.info("Sent global message via API: ${messageRequest.message}")
                call.respond(SuccessResponse(true, "Global message sent"))
            } catch (e: Exception) {
                logger.error("Failed to send global message: ${e.message}", e)
                call.respond(HttpStatusCode.InternalServerError,
                    ErrorResponse("Failed to send global message", e.message))
            }
        }

        // Execute command and return result to lobby
        post("/execute-with-response") {
            val commandRequest = call.receive<CommandRequest>()

            val player = server.getPlayer(commandRequest.player).orElse(null)
            if (player == null) {
                call.respond(HttpStatusCode.NotFound,
                    CommandResponse(commandRequest.player, commandRequest.command, false, false, "Player not found on proxy"))
                return@post
            }

            try {
                // For punishment commands, we need to capture the response differently
                when {
                    commandRequest.command.startsWith("ban ") -> {
                        val parts = commandRequest.command.split(" ", limit = 3)
                        if (parts.size >= 3) {
                            val target = parts[1]
                            val reason = parts.drop(2).joinToString(" ")  // Join all remaining parts as reason
                            
                            // Execute ban and check result
                            server.commandManager.executeAsync(player, commandRequest.command).join()
                            
                            // Check if the ban was successful by looking for the player in punishment history
                            val targetProfile = runBlocking { plugin.connectionHandler.findPlayerProfile(target) }
                            if (targetProfile != null) {
                                val activeBan = runBlocking { 
                                    plugin.punishmentRepository.findActivePunishment(
                                        targetProfile.uuid.toString(), 
                                        radium.backend.punishment.models.PunishmentType.BAN
                                    )
                                }
                                if (activeBan != null) {
                                    call.respond(CommandResponse(
                                        commandRequest.player, 
                                        commandRequest.command, 
                                        true, 
                                        true, 
                                        "Player $target has been banned successfully for: $reason"
                                    ))
                                } else {
                                    call.respond(CommandResponse(
                                        commandRequest.player, 
                                        commandRequest.command, 
                                        false, 
                                        true, 
                                        "Command executed but ban may have failed - no active ban found for $target"
                                    ))
                                }
                            } else {
                                call.respond(CommandResponse(
                                    commandRequest.player, 
                                    commandRequest.command, 
                                    false, 
                                    false, 
                                    "Target player profile not found for $target"
                                ))
                            }
                        } else {
                            call.respond(CommandResponse(
                                commandRequest.player, 
                                commandRequest.command, 
                                false, 
                                false, 
                                "Invalid ban command format. Usage: /ban <player> <reason>"
                            ))
                        }
                    }
                    
                    commandRequest.command.startsWith("unban ") -> {
                        val parts = commandRequest.command.split(" ", limit = 3)
                        if (parts.size >= 2) {  // Only need target, reason is optional
                            val target = parts[1]
                            val reason = if (parts.size >= 3) parts.drop(2).joinToString(" ") else "No reason provided"
                            
                            server.commandManager.executeAsync(player, commandRequest.command).join()
                            
                            call.respond(CommandResponse(
                                commandRequest.player, 
                                commandRequest.command, 
                                true, 
                                true, 
                                "Unban command executed for $target"
                            ))
                        } else {
                            call.respond(CommandResponse(
                                commandRequest.player, 
                                commandRequest.command, 
                                false, 
                                false, 
                                "Invalid unban command format. Usage: /unban <player> [reason]"
                            ))
                        }
                    }
                    
                    commandRequest.command.startsWith("mute ") -> {
                        val parts = commandRequest.command.split(" ", limit = 4)
                        if (parts.size >= 4) {
                            val target = parts[1]
                            val duration = parts[2]
                            val reason = parts.drop(3).joinToString(" ")  // Join remaining parts as reason
                            
                            server.commandManager.executeAsync(player, commandRequest.command).join()
                            
                            call.respond(CommandResponse(
                                commandRequest.player, 
                                commandRequest.command, 
                                true, 
                                true, 
                                "Player $target has been muted for $duration: $reason"
                            ))
                        } else {
                            call.respond(CommandResponse(
                                commandRequest.player, 
                                commandRequest.command, 
                                false, 
                                false, 
                                "Invalid mute command format. Usage: /mute <player> <duration> <reason>"
                            ))
                        }
                    }
                    
                    commandRequest.command.startsWith("unmute ") -> {
                        val parts = commandRequest.command.split(" ", limit = 3)
                        if (parts.size >= 2) {  // Only target required, reason is optional
                            val target = parts[1]
                            val reason = if (parts.size >= 3) parts.drop(2).joinToString(" ") else "No reason provided"
                            
                            server.commandManager.executeAsync(player, commandRequest.command).join()
                            
                            call.respond(CommandResponse(
                                commandRequest.player, 
                                commandRequest.command, 
                                true, 
                                true, 
                                "Player $target has been unmuted"
                            ))
                        } else {
                            call.respond(CommandResponse(
                                commandRequest.player, 
                                commandRequest.command, 
                                false, 
                                false, 
                                "Invalid unmute command format. Usage: /unmute <player> [reason]"
                            ))
                        }
                    }
                    
                    commandRequest.command.startsWith("kick ") -> {
                        val parts = commandRequest.command.split(" ", limit = 3)
                        if (parts.size >= 3) {
                            val target = parts[1]
                            val reason = parts.drop(2).joinToString(" ")  // Join remaining parts as reason
                            
                            server.commandManager.executeAsync(player, commandRequest.command).join()
                            
                            call.respond(CommandResponse(
                                commandRequest.player, 
                                commandRequest.command, 
                                true, 
                                true, 
                                "Player $target has been kicked for: $reason"
                            ))
                        } else {
                            call.respond(CommandResponse(
                                commandRequest.player, 
                                commandRequest.command, 
                                false, 
                                false, 
                                "Invalid kick command format. Usage: /kick <player> <reason>"
                            ))
                        }
                    }
                    
                    commandRequest.command.startsWith("tempban ") -> {
                        val parts = commandRequest.command.split(" ", limit = 4)
                        if (parts.size >= 4) {
                            val target = parts[1]
                            val duration = parts[2]
                            val reason = parts.drop(3).joinToString(" ")  // Join remaining parts as reason
                            
                            server.commandManager.executeAsync(player, commandRequest.command).join()
                            
                            call.respond(CommandResponse(
                                commandRequest.player, 
                                commandRequest.command, 
                                true, 
                                true, 
                                "Player $target has been temporarily banned for $duration: $reason"
                            ))
                        } else {
                            call.respond(CommandResponse(
                                commandRequest.player, 
                                commandRequest.command, 
                                false, 
                                false, 
                                "Invalid tempban command format. Usage: /tempban <player> <duration> <reason>"
                            ))
                        }
                    }
                    
                    commandRequest.command.startsWith("checkpunishments ") -> {
                        val parts = commandRequest.command.split(" ", limit = 2)
                        if (parts.size >= 2) {
                            val target = parts[1]
                            
                            try {
                                // Find target profile
                                val targetProfile = runBlocking { plugin.connectionHandler.findPlayerProfile(target) }
                                if (targetProfile != null) {
                                    // Get punishment history
                                    val punishments: List<radium.backend.punishment.models.Punishment> = runBlocking { 
                                        plugin.punishmentRepository.findPunishmentsForPlayer(targetProfile.uuid.toString())
                                    }
                                    
                                    val activePunishments = punishments.filter { it.isCurrentlyActive() }
                                    val totalPunishments = punishments.size
                                    
                                    val response = if (activePunishments.isEmpty()) {
                                        if (totalPunishments == 0) {
                                            "$target has no punishment history"
                                        } else {
                                            "$target has $totalPunishments total punishments, but none are currently active"
                                        }
                                    } else {
                                        val activeCount = activePunishments.size
                                        "$target has $activeCount active punishment(s) and $totalPunishments total punishment(s)"
                                    }
                                    
                                    call.respond(CommandResponse(
                                        commandRequest.player, 
                                        commandRequest.command, 
                                        true, 
                                        true, 
                                        response
                                    ))
                                } else {
                                    call.respond(CommandResponse(
                                        commandRequest.player, 
                                        commandRequest.command, 
                                        false, 
                                        false, 
                                        "Player $target not found"
                                    ))
                                }
                            } catch (e: Exception) {
                                logger.error("Error checking punishments for $target: ${e.message}", e)
                                call.respond(CommandResponse(
                                    commandRequest.player, 
                                    commandRequest.command, 
                                    false, 
                                    false, 
                                    "Error checking punishments: ${e.message}"
                                ))
                            }
                        } else {
                            call.respond(CommandResponse(
                                commandRequest.player, 
                                commandRequest.command, 
                                false, 
                                false, 
                                "Invalid checkpunishments command format. Usage: /checkpunishments <player>"
                            ))
                        }
                    }
                    
                    commandRequest.command.startsWith("blacklist ") -> {
                        val parts = commandRequest.command.split(" ", limit = 3)
                        if (parts.size >= 3) {
                            val target = parts[1]
                            val reason = parts.drop(2).joinToString(" ")  // Join remaining parts as reason
                            
                            server.commandManager.executeAsync(player, commandRequest.command).join()
                            
                            call.respond(CommandResponse(
                                commandRequest.player, 
                                commandRequest.command, 
                                true, 
                                true, 
                                "Blacklist command executed for $target: $reason"
                            ))
                        } else {
                            call.respond(CommandResponse(
                                commandRequest.player, 
                                commandRequest.command, 
                                false, 
                                false, 
                                "Invalid blacklist command format. Usage: /blacklist <player> <reason>"
                            ))
                        }
                    }
                    
                    commandRequest.command.startsWith("warn ") -> {
                        val parts = commandRequest.command.split(" ", limit = 3)
                        if (parts.size >= 3) {
                            val target = parts[1]
                            val reason = parts.drop(2).joinToString(" ")  // Join remaining parts as reason
                            
                            server.commandManager.executeAsync(player, commandRequest.command).join()
                            
                            call.respond(CommandResponse(
                                commandRequest.player, 
                                commandRequest.command, 
                                true, 
                                true, 
                                "Player $target has been warned for: $reason"
                            ))
                        } else {
                            call.respond(CommandResponse(
                                commandRequest.player, 
                                commandRequest.command, 
                                false, 
                                false, 
                                "Invalid warn command format. Usage: /warn <player> <reason>"
                            ))
                        }
                    }
                    
                    commandRequest.command.startsWith("unblacklist ") -> {
                        val parts = commandRequest.command.split(" ", limit = 3)
                        if (parts.size >= 2) {  // Only target required, reason is optional
                            val target = parts[1]
                            val reason = if (parts.size >= 3) parts.drop(2).joinToString(" ") else "No reason provided"
                            
                            server.commandManager.executeAsync(player, commandRequest.command).join()
                            
                            call.respond(CommandResponse(
                                commandRequest.player, 
                                commandRequest.command, 
                                true, 
                                true, 
                                "Unblacklist command executed for $target"
                            ))
                        } else {
                            call.respond(CommandResponse(
                                commandRequest.player, 
                                commandRequest.command, 
                                false, 
                                false, 
                                "Invalid unblacklist command format. Usage: /unblacklist <player> [reason]"
                            ))
                        }
                    }
                    
                    else -> {
                        // For other commands, execute normally
                        server.commandManager.executeAsync(player, commandRequest.command).join()
                        call.respond(CommandResponse(
                            commandRequest.player, 
                            commandRequest.command, 
                            true, 
                            true, 
                            "Command executed"
                        ))
                    }
                }

                logger.info("Executed command '${commandRequest.command}' for player ${commandRequest.player} via API with response")
            } catch (e: Exception) {
                logger.error("Failed to execute command '${commandRequest.command}' for player ${commandRequest.player}: ${e.message}", e)
                call.respond(HttpStatusCode.InternalServerError,
                    CommandResponse(commandRequest.player, commandRequest.command, false, false, "Error: ${e.message}"))
            }
        }
    }
}
