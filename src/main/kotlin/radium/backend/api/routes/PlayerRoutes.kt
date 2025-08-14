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

fun Route.playerRoutes(plugin: Radium, server: ProxyServer, logger: ComponentLogger) {
    route("/players") {
        // Get all online players
        get {
            val players = server.allPlayers.map { player ->
                val currentServer = player.currentServer.orElse(null)?.serverInfo?.name
                PlayerResponse(
                    username = player.username,
                    uuid = player.uniqueId.toString(),
                    server = currentServer,
                    isOnline = true
                )
            }

            call.respond(PlayerListResponse(players = players, total = players.size))
        }

        // Get specific player info
        get("/{player}") {
            val playerName = call.parameters["player"] ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Missing player parameter")
            )

            val player = server.getPlayer(playerName).orElse(null)
            if (player == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Player not found"))
                return@get
            }

            val currentServer = player.currentServer.orElse(null)?.serverInfo?.name
            val response = PlayerResponse(
                username = player.username,
                uuid = player.uniqueId.toString(),
                server = currentServer,
                isOnline = true
            )

            call.respond(response)
        }

        // Transfer player to server
        post("/{player}/transfer") {
            val playerName = call.parameters["player"] ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Missing player parameter")
            )

            val transferRequest = call.receive<TransferRequest>()
            val player = server.getPlayer(playerName).orElse(null)

            if (player == null) {
                call.respond(HttpStatusCode.NotFound,
                    TransferResponse(playerName, transferRequest.server, false, "Player not found"))
                return@post
            }

            val targetServer = server.getServer(transferRequest.server).orElse(null)
            if (targetServer == null) {
                call.respond(HttpStatusCode.NotFound,
                    TransferResponse(playerName, transferRequest.server, false, "Server not found"))
                return@post
            }

            try {
                player.createConnectionRequest(targetServer).fireAndForget()
                logger.info("Transferred player $playerName to server ${transferRequest.server} via API")
                call.respond(TransferResponse(playerName, transferRequest.server, true))
            } catch (e: Exception) {
                logger.error("Failed to transfer player $playerName: ${e.message}", e)
                call.respond(HttpStatusCode.InternalServerError,
                    TransferResponse(playerName, transferRequest.server, false, e.message))
            }
        }

        // Send message to player
        post("/{player}/message") {
            val playerName = call.parameters["player"] ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Missing player parameter")
            )

            val messageRequest = call.receive<MessageRequest>()
            val player = server.getPlayer(playerName).orElse(null)

            if (player == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Player not found"))
                return@post
            }

            try {
                // Use the existing message command functionality
                plugin.messageCommand?.sendDirectMessage(messageRequest.from, player, messageRequest.message)
                call.respond(SuccessResponse(true, "Message sent"))
            } catch (e: Exception) {
                logger.error("Failed to send message to player $playerName: ${e.message}", e)
                call.respond(HttpStatusCode.InternalServerError,
                    ErrorResponse("Failed to send message", e.message))
            }
        }

        // Get player vanish status
        get("/{player}/vanish") {
            val playerName = call.parameters["player"] ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Missing player parameter")
            )

            val player = server.getPlayer(playerName).orElse(null)
            if (player == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Player not found"))
                return@get
            }

            val profile = plugin.connectionHandler.getPlayerProfile(player.uniqueId)
            val isVanished = profile?.isVanished ?: false

            call.respond(VanishResponse(playerName, isVanished, true))
        }

        // Set player vanish status
        post("/{player}/vanish") {
            val playerName = call.parameters["player"] ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Missing player parameter")
            )

            val vanishRequest = call.receive<VanishRequest>()
            val player = server.getPlayer(playerName).orElse(null)

            if (player == null) {
                call.respond(HttpStatusCode.NotFound,
                    VanishResponse(playerName, vanishRequest.vanished, false))
                return@post
            }

            try {
                if (vanishRequest.vanished) {
                    plugin.staffManager.vanishPlayer(player)
                } else {
                    plugin.staffManager.unvanishPlayer(player)
                }
                call.respond(VanishResponse(playerName, vanishRequest.vanished, true))
            } catch (e: Exception) {
                logger.error("Failed to change vanish status for player $playerName: ${e.message}", e)
                call.respond(HttpStatusCode.InternalServerError,
                    VanishResponse(playerName, vanishRequest.vanished, false))
            }
        }
    }
}
