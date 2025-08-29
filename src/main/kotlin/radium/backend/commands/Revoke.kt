package radium.backend.commands

import com.velocitypowered.api.proxy.Player
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Optional
import revxrsal.commands.velocity.annotation.CommandPermission
import radium.backend.Radium
import radium.backend.annotations.OnlinePlayers
import radium.backend.annotations.RankList

@Command("revoke")
@CommandPermission("radium.staff")
class Revoke(private val radium: Radium) {

    @Command("revoke <target> <rank>")
    @CommandPermission("radium.revoke.use")
    suspend fun revokeRank(
        actor: Player,
        @OnlinePlayers target: String,
        @RankList rank: String,
        @Optional reason: String?
    ) {
        // Validate rank exists
        val rankObj = radium.rankManager.getRank(rank)
        if (rankObj == null) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.delete.not_found", "rank" to rank))
            return
        }

        // Get the player's profile from cache or database
        val profile = radium.connectionHandler.findPlayerProfile(target)
        if (profile == null) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("revoke.profile_not_found", "target" to target))
            return
        }

        // Remove the rank
        val removed = if (reason != null && reason.isNotEmpty()) {
            profile.removeRank(rank, actor.username, reason)
        } else {
            profile.removeRank(rank, actor.username)
        }

        if (removed) {
            radium.logger.info("Successfully revoked rank '$rank' from ${profile.username}")
            
            actor.sendMessage(radium.yamlFactory.getMessageComponent("revoke.success", 
                "rank" to rank, 
                "target" to target
            ))

            // If there was a reason provided, show it
            if (reason != null && reason.isNotEmpty()) {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("revoke.reason", "reason" to reason))
            }

            // Sync the profile immediately to Redis
            radium.lettuceCache.cacheProfile(profile)
            radium.logger.info("Cached updated profile for ${profile.username}")
            
            // Update tab lists for all players to reflect rank changes
            GlobalScope.launch {
                // First try to update the specific player if they're online
                val targetPlayer = radium.server.getPlayer(target).orElse(null)
                if (targetPlayer != null) {
                    // radium.logger.debug("Updating tab list for online player: ${targetPlayer.username}")
                    radium.networkVanishManager.updateTabListForNewPlayer(targetPlayer)
                } else {
                    radium.logger.info("Target player '$target' is not online, skipping individual update")
                }
                
                // Then update all players' tab lists to ensure consistency
                radium.logger.info("Updating all players' tab lists")
                radium.networkVanishManager.refreshAllTabLists()
            }

            // Log the change
            val logMessage = if (reason != null && reason.isNotEmpty()) {
                "[Ranks] ${actor.username} revoked rank '$rank' from ${profile.username} (${profile.uuid}). Reason: $reason"
            } else {
                "[Ranks] ${actor.username} revoked rank '$rank' from ${profile.username} (${profile.uuid})"
            }
            radium.logger.info(logMessage)
        } else {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("revoke.not_found", 
                "target" to target, 
                "rank" to rank
            ))
        }
    }
}
