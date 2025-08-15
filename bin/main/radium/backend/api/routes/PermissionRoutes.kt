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

fun Route.permissionRoutes(plugin: Radium, server: ProxyServer, logger: ComponentLogger) {
    route("/permissions") {
        // Check if player has permission
        get("/{player}/{permission}") {
            val playerName = call.parameters["player"] ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Missing player parameter")
            )

            val permission = call.parameters["permission"] ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Missing permission parameter")
            )

            val player = server.getPlayer(playerName).orElse(null)
            if (player == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Player not found"))
                return@get
            }

            val hasPermission = player.hasPermission(permission)
            call.respond(PermissionCheckResponse(playerName, permission, hasPermission))
        }

        // Get all permissions for a player
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

            val profile = plugin.connectionHandler.getPlayerProfile(player.uniqueId)
            val permissions = if (profile != null) {
                val highestRank = profile.getHighestRank(plugin.rankManager)
                highestRank?.permissions ?: emptyList()
            } else {
                emptyList()
            }

            call.respond(mapOf("player" to playerName, "permissions" to permissions))
        }
    }
}
