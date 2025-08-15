package radium.backend.api.routes

import com.velocitypowered.api.proxy.ProxyServer
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import radium.backend.Radium
import radium.backend.api.dto.*

fun Route.serverRoutes(plugin: Radium, server: ProxyServer, logger: ComponentLogger) {
    route("/servers") {
        // Get all servers
        get {
            val servers = server.allServers.map { serverInfo ->
                ServerResponse(
                    name = serverInfo.serverInfo.name,
                    address = serverInfo.serverInfo.address.toString(),
                    playerCount = serverInfo.playersConnected.size,
                    isOnline = true // Assume online if registered
                )
            }

            val totalPlayers = servers.sumOf { it.playerCount }
            call.respond(ServerListResponse(servers = servers, totalPlayers = totalPlayers))
        }

        // Get specific server info
        get("/{server}") {
            val serverName = call.parameters["server"] ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Missing server parameter")
            )

            val serverInfo = server.getServer(serverName).orElse(null)
            if (serverInfo == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                return@get
            }

            val response = ServerResponse(
                name = serverInfo.serverInfo.name,
                address = serverInfo.serverInfo.address.toString(),
                playerCount = serverInfo.playersConnected.size,
                isOnline = true
            )

            call.respond(response)
        }

        // Get players on a specific server
        get("/{server}/players") {
            val serverName = call.parameters["server"] ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Missing server parameter")
            )

            val serverInfo = server.getServer(serverName).orElse(null)
            if (serverInfo == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                return@get
            }

            val players = serverInfo.playersConnected.map { player ->
                PlayerResponse(
                    username = player.username,
                    uuid = player.uniqueId.toString(),
                    server = serverName,
                    isOnline = true
                )
            }

            call.respond(PlayerListResponse(players = players, total = players.size))
        }

        // Get total player count across all servers
        get("/count") {
            val totalPlayers = server.playerCount
            call.respond(mapOf("totalPlayers" to totalPlayers))
        }
    }
}
