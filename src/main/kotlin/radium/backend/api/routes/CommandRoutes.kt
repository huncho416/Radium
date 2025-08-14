package radium.backend.api.routes

import com.velocitypowered.api.proxy.ProxyServer
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
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
                server.commandManager.executeAsync(player, commandRequest.command).join()

                logger.info("Executed command '${commandRequest.command}' for player ${commandRequest.player} via API")
                call.respond(CommandResponse(commandRequest.player, commandRequest.command, true, true))
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
    }
}
