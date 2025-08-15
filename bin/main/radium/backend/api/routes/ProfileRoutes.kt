package radium.backend.api.routes

import com.velocitypowered.api.proxy.ProxyServer
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import radium.backend.Radium
import radium.backend.api.dto.*

fun Route.profileRoutes(plugin: Radium, server: ProxyServer, logger: ComponentLogger) {
    route("/profiles") {
        // Get player profile
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
            if (profile == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Profile not found"))
                return@get
            }

            val highestRank = profile.getHighestRank(plugin.rankManager)
            val rankResponse = if (highestRank != null) {
                RankResponse(
                    name = highestRank.name,
                    weight = highestRank.weight,
                    prefix = highestRank.prefix,
                    color = highestRank.color,
                    permissions = highestRank.permissions.toList()
                )
            } else null

            val response = ProfileResponse(
                username = player.username,
                uuid = player.uniqueId.toString(),
                rank = rankResponse,
                permissions = highestRank?.permissions?.toList() ?: emptyList(),
                prefix = highestRank?.prefix,
                color = highestRank?.color,
                isVanished = plugin.staffManager.isVanished(player),
                lastSeen = profile.lastSeen?.toEpochMilli()
            )

            call.respond(response)
        }

        // Get profile by UUID
        get("/uuid/{uuid}") {
            val uuidString = call.parameters["uuid"] ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Missing uuid parameter")
            )

            val uuid = try {
                java.util.UUID.fromString(uuidString)
            } catch (e: IllegalArgumentException) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Invalid UUID format")
                )
            }

            val profile = plugin.connectionHandler.getPlayerProfile(uuid)
            if (profile == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Profile not found"))
                return@get
            }

            val player = server.getPlayer(uuid).orElse(null)
            val highestRank = profile.getHighestRank(plugin.rankManager)
            val rankResponse = if (highestRank != null) {
                RankResponse(
                    name = highestRank.name,
                    weight = highestRank.weight,
                    prefix = highestRank.prefix,
                    color = highestRank.color,
                    permissions = highestRank.permissions.toList()
                )
            } else null

            val response = ProfileResponse(
                username = profile.username,
                uuid = uuid.toString(),
                rank = rankResponse,
                permissions = highestRank?.permissions?.toList() ?: emptyList(),
                prefix = highestRank?.prefix,
                color = highestRank?.color,
                isVanished = player?.let { plugin.staffManager.isVanished(it) } ?: false,
                lastSeen = profile.lastSeen?.toEpochMilli()
            )

            call.respond(response)
        }
    }
}
