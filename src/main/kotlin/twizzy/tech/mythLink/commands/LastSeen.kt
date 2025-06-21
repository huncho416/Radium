package twizzy.tech.mythLink.commands

import com.velocitypowered.api.proxy.Player
import revxrsal.commands.annotation.Command
import twizzy.tech.mythLink.MythLink
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import java.time.Duration
import java.time.Instant

class LastSeen(private val mythLink: MythLink) {

    @Command("lastseen <target>")
    suspend fun lastSeen(actor: Player, target: String) {
        val profile = mythLink.connectionHandler.findPlayerProfile(target)

        if (profile == null) {
            actor.sendMessage(Component.text("Player not found: $target").color(NamedTextColor.RED))
            return
        }

        // Check if player is currently online
        val targetPlayer = mythLink.server.getPlayer(profile.username).orElse(null)
        if (targetPlayer != null && targetPlayer.isActive) {
            actor.sendMessage(
                Component.text("${profile.username} is currently online!").color(NamedTextColor.GREEN)
            )
            return
        }

        // Calculate time difference
        val now = Instant.now()
        val lastSeen = profile.lastSeen
        val timeDiff = Duration.between(lastSeen, now)

        // Format the duration in a human-readable way
        val formattedTime = formatDuration(timeDiff)

        // Send the message
        actor.sendMessage(
            Component.text(profile.username)
                .color(NamedTextColor.YELLOW)
                .append(Component.text(" was last seen: ").color(NamedTextColor.GOLD))
                .append(Component.text("$formattedTime ago").color(NamedTextColor.YELLOW))
        )
    }

    /**
     * Formats a duration into a human-readable string
     *
     * @param duration The duration to format
     * @return A formatted string like "2 days, 5 hours, 30 minutes"
     */
    private fun formatDuration(duration: Duration): String {
        val days = duration.toDays()
        val hours = duration.toHours() % 24
        val minutes = duration.toMinutes() % 60
        val seconds = duration.toSeconds() % 60

        return when {
            days > 0 -> {
                if (hours > 0) "$days day${if (days > 1) "s" else ""}, $hours hour${if (hours > 1) "s" else ""}"
                else "$days day${if (days > 1) "s" else ""}"
            }
            hours > 0 -> {
                if (minutes > 0) "$hours hour${if (hours > 1) "s" else ""}, $minutes minute${if (minutes > 1) "s" else ""}"
                else "$hours hour${if (hours > 1) "s" else ""}"
            }
            minutes > 0 -> "$minutes minute${if (minutes > 1) "s" else ""}"
            else -> "$seconds second${if (seconds > 1) "s" else ""}"
        }
    }
}