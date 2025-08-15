package radium.backend.api.routes

import com.velocitypowered.api.proxy.ProxyServer
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import radium.backend.Radium
import radium.backend.api.dto.*

fun Route.rankRoutes(plugin: Radium, server: ProxyServer, logger: ComponentLogger) {
    route("/ranks") {
        // Get all ranks
        get {
            val ranks = plugin.rankManager.getCachedRanks().map { rank ->
                RankResponse(
                    name = rank.name,
                    weight = rank.weight,
                    prefix = rank.prefix,
                    color = rank.color,
                    permissions = rank.permissions.toList()
                )
            }

            call.respond(RankListResponse(ranks = ranks, total = ranks.size))
        }

        // Get specific rank
        get("/{rank}") {
            val rankName = call.parameters["rank"] ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Missing rank parameter")
            )

            val rank = plugin.rankManager.getCachedRanks().find { it.name.equals(rankName, ignoreCase = true) }
            if (rank == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Rank not found"))
                return@get
            }

            val response = RankResponse(
                name = rank.name,
                weight = rank.weight,
                prefix = rank.prefix,
                color = rank.color,
                permissions = rank.permissions.toList()
            )

            call.respond(response)
        }

        // Get players with a specific rank
        get("/{rank}/players") {
            val rankName = call.parameters["rank"] ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Missing rank parameter")
            )

            val rank = plugin.rankManager.getCachedRanks().find { it.name.equals(rankName, ignoreCase = true) }
            if (rank == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Rank not found"))
                return@get
            }

            val playersWithRank = server.allPlayers.mapNotNull { player ->
                val profile = plugin.connectionHandler.getPlayerProfile(player.uniqueId)
                if (profile != null && profile.hasRank(rank.name)) {
                    PlayerResponse(
                        username = player.username,
                        uuid = player.uniqueId.toString(),
                        server = player.currentServer.orElse(null)?.serverInfo?.name,
                        isOnline = true
                    )
                } else null
            }

            call.respond(PlayerListResponse(players = playersWithRank, total = playersWithRank.size))
        }
    }
}
