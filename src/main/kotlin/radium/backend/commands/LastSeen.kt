package radium.backend.commands

import com.velocitypowered.api.proxy.Player
import revxrsal.commands.annotation.Command
import revxrsal.commands.velocity.annotation.CommandPermission
import radium.backend.Radium
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import java.time.Duration
import java.time.Instant

@Command("lastseen")
@CommandPermission("command.lastseen")
class LastSeen(private val radium: Radium) {

    private val yamlFactory = radium.yamlFactory

    @Command("lastseen")
    fun lastSeenUsage(actor: Player) {
        // Show usage information for the lastseen command
        actor.sendMessage(yamlFactory.getMessageComponent("commands.lastseen.header"))
        actor.sendMessage(yamlFactory.getMessageComponent("commands.lastseen.usage.main"))
    }

    @Command("lastseen <target>")
    suspend fun lastSeen(actor: Player, target: String) {
        // Enhanced profile lookup with better error handling
        val profile = try {
            radium.connectionHandler.findPlayerProfile(target)
        } catch (e: Exception) {
            radium.logger.warn("Error finding profile for $target: ${e.message}")
            null
        }

        if (profile == null) {
            actor.sendMessage(yamlFactory.getMessageComponent("commands.lastseen.profile_not_found", "target" to target))
            return
        }

        // Check if player is currently online by username (case-insensitive)
        val targetPlayer = radium.server.allPlayers.find { 
            it.username.equals(profile.username, ignoreCase = true) 
        }
        
        if (targetPlayer != null && targetPlayer.isActive) {
            actor.sendMessage(yamlFactory.getMessageComponent("commands.lastseen.online", "target" to profile.username))
            return
        }

        // Calculate time difference
        val now = Instant.now()
        val lastSeen = profile.lastSeen
        
        if (lastSeen == Instant.EPOCH) {
            actor.sendMessage(yamlFactory.getMessageComponent("commands.lastseen.never_joined", "target" to profile.username))
            return
        }
        
        val timeDiff = Duration.between(lastSeen, now)

        // Format the time in the same "time ago" format as friend list
        val timeAgo = formatTimeSince(lastSeen)

        // Send the message
        actor.sendMessage(yamlFactory.getMessageComponent("commands.lastseen.offline", 
            "target" to profile.username,
            "time" to timeAgo
        ))
    }

    /**
     * Format a time instant as a "time ago" string (same format as friend list)
     *
     * @param time The instant to format
     * @return A formatted string like "2 hours ago", "3 days ago"
     */
    private fun formatTimeSince(time: Instant): String {
        val now = Instant.now()
        val duration = Duration.between(time, now)
        
        return when {
            duration.toMinutes() < 1 -> "moments ago"
            duration.toHours() < 1 -> "${duration.toMinutes()} minutes ago"
            duration.toDays() < 1 -> "${duration.toHours()} hours ago"
            duration.toDays() < 30 -> "${duration.toDays()} days ago"
            duration.toDays() < 365 -> "${duration.toDays() / 30} months ago"
            else -> "${duration.toDays() / 365} years ago"
        }
    }
}
