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
import radium.backend.punishment.models.PunishmentType
import java.util.*

fun Route.punishmentRoutes(plugin: Radium, server: ProxyServer, logger: ComponentLogger) {
    route("/punishments") {
        
        // Issue a punishment (ban, mute, warn, kick, etc.)
        post("/issue") {
            val request = call.receive<PunishmentRequest>()
            
            try {
                // Find the staff player who is issuing the punishment
                val staffPlayer = server.getPlayer(request.staffId).orElse(null)
                if (staffPlayer == null) {
                    call.respond(HttpStatusCode.BadRequest, 
                        ErrorResponse("Staff player not found", "Staff player ${request.staffId} is not online"))
                    return@post
                }
                
                // Find target player (can be offline)
                val targetPlayer = server.getPlayer(request.target).orElse(null)
                
                // Get target player info
                val (targetId, targetName, targetIp) = if (targetPlayer != null) {
                    Triple(
                        targetPlayer.uniqueId.toString(),
                        targetPlayer.username,
                        targetPlayer.remoteAddress.address.hostAddress
                    )
                } else {
                    // Look up offline player
                    val profile = plugin.connectionHandler.findPlayerProfile(request.target)
                    if (profile != null) {
                        Triple(profile.uuid.toString(), profile.username, null)
                    } else {
                        call.respond(HttpStatusCode.NotFound,
                            ErrorResponse("Player not found", "Player ${request.target} not found"))
                        return@post
                    }
                }
                
                // Parse punishment type
                val punishmentType = try {
                    PunishmentType.valueOf(request.type.uppercase())
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest,
                        ErrorResponse("Invalid punishment type", "Unknown punishment type: ${request.type}"))
                    return@post
                }
                
                // Issue the punishment
                val success = plugin.punishmentManager.issuePunishment(
                    target = targetPlayer,
                    targetId = targetId,
                    targetName = targetName,
                    targetIp = targetIp,
                    type = punishmentType,
                    reason = request.reason,
                    staff = staffPlayer,
                    duration = request.duration,
                    silent = request.silent,
                    clearInventory = request.clearInventory
                )
                
                if (success) {
                    call.respond(PunishmentResponse(
                        success = true,
                        target = targetName,
                        type = punishmentType.name,
                        reason = request.reason,
                        staff = staffPlayer.username,
                        message = "Punishment issued successfully"
                    ))
                } else {
                    call.respond(HttpStatusCode.BadRequest,
                        ErrorResponse("Failed to issue punishment", "Could not issue punishment"))
                }
                
            } catch (e: Exception) {
                logger.error("Failed to issue punishment via API: ${e.message}", e)
                call.respond(HttpStatusCode.InternalServerError,
                    ErrorResponse("Internal server error", e.message ?: "Unknown error"))
            }
        }
        
        // Revoke a punishment (unban, unmute, etc.)
        post("/revoke") {
            val request = call.receive<PunishmentRevokeRequest>()
            
            try {
                // Find the staff player who is revoking the punishment
                val staffPlayer = server.getPlayer(request.staffId).orElse(null)
                if (staffPlayer == null) {
                    call.respond(HttpStatusCode.BadRequest,
                        ErrorResponse("Staff player not found", "Staff player ${request.staffId} is not online"))
                    return@post
                }
                
                // Look up target player info
                val profile = plugin.connectionHandler.findPlayerProfile(request.target)
                if (profile == null) {
                    call.respond(HttpStatusCode.NotFound,
                        ErrorResponse("Player not found", "Player ${request.target} not found"))
                    return@post
                }
                
                // Parse punishment type
                val punishmentType = try {
                    PunishmentType.valueOf(request.type.uppercase())
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest,
                        ErrorResponse("Invalid punishment type", "Unknown punishment type: ${request.type}"))
                    return@post
                }
                
                // Revoke the punishment
                val success = plugin.punishmentManager.revokePunishment(
                    targetId = profile.uuid.toString(),
                    targetName = profile.username,
                    type = punishmentType,
                    reason = request.reason,
                    staff = staffPlayer,
                    silent = request.silent
                )
                
                if (success) {
                    call.respond(PunishmentResponse(
                        success = true,
                        target = profile.username,
                        type = punishmentType.name,
                        reason = request.reason,
                        staff = staffPlayer.username,
                        message = "Punishment revoked successfully"
                    ))
                } else {
                    call.respond(HttpStatusCode.BadRequest,
                        ErrorResponse("Failed to revoke punishment", "No active punishment found or revoke failed"))
                }
                
            } catch (e: Exception) {
                logger.error("Failed to revoke punishment via API: ${e.message}", e)
                call.respond(HttpStatusCode.InternalServerError,
                    ErrorResponse("Internal server error", e.message ?: "Unknown error"))
            }
        }
        
        // Check punishments for a player
        get("/{target}") {
            val target = call.parameters["target"] ?: run {
                call.respond(HttpStatusCode.BadRequest,
                    ErrorResponse("Missing target", "Target player parameter is required"))
                return@get
            }
            
            try {
                logger.debug("API: Looking up punishments for player: $target")
                
                // Use enhanced player lookup from PunishmentManager
                val (profile, targetName) = plugin.punishmentManager.lookupPlayerForPunishment(target)
                if (profile == null) {
                    logger.warn("API: Player not found: $target")
                    call.respond(HttpStatusCode.NotFound,
                        ErrorResponse("Player not found", "Player $target not found"))
                    return@get
                }
                
                logger.debug("API: Found player profile: ${profile.username} (${profile.uuid})")
                
                // Get punishments using PunishmentManager
                val punishments = plugin.punishmentManager.getPunishmentHistory(profile.uuid.toString())
                val activePunishments = punishments.filter { it.isCurrentlyActive() }
                
                logger.debug("API: Found ${punishments.size} total punishments, ${activePunishments.size} active for ${profile.username}")
                
                call.respond(PunishmentHistoryResponse(
                    target = profile.username,
                    targetId = profile.uuid.toString(),
                    totalPunishments = punishments.size,
                    activePunishments = activePunishments.size,
                    punishments = punishments.map { punishment ->
                        PunishmentInfo(
                            id = punishment.id,
                            type = punishment.type.name,
                            reason = punishment.reason,
                            issuedBy = punishment.issuedByName,
                            issuedAt = punishment.issuedAt.toEpochMilli(),
                            expiresAt = punishment.expiresAt?.toEpochMilli(),
                            active = punishment.isCurrentlyActive(),
                            revokedBy = null, // TODO: Add revoked info to Punishment model if needed
                            revokedAt = null, // TODO: Add revoked info to Punishment model if needed  
                            revokeReason = null // TODO: Add revoked info to Punishment model if needed
                        )
                    }
                ))
                
            } catch (e: Exception) {
                logger.error("Failed to get punishments via API for target '$target': ${e.message}", e)
                call.respond(HttpStatusCode.InternalServerError,
                    ErrorResponse("Internal server error", e.message ?: "Unknown error"))
            }
        }
    }
}
