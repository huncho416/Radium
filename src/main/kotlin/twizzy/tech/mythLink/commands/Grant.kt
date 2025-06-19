package twizzy.tech.mythLink.commands

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Optional
import revxrsal.commands.velocity.annotation.CommandPermission
import twizzy.tech.mythLink.MythLink
import twizzy.tech.mythLink.annotations.OnlinePlayers
import twizzy.tech.mythLink.annotations.RankList
import twizzy.tech.mythLink.player.Profile
import java.time.Duration
import java.time.Instant
import java.util.UUID


@Command("grant")
@CommandPermission("command.grants")
class Grant(private val mythLink: MythLink) {

    @Command("grant <target> <rank>")
    suspend fun grant(
        actor: Player,
        @Optional @OnlinePlayers target: String,
        @Optional @RankList rank: String?,
        @Optional durationReason: String
    ) {
        if (target.isNullOrEmpty() || rank.isNullOrEmpty()) {
            actor.sendMessage(Component.text("Usage: /grant <target> <rank> [duration] <reason>", NamedTextColor.YELLOW))
            return
        }

        // Verify that the rank exists
        val rankObj = mythLink.rankManager.getRank(rank)
        if (rankObj == null) {
            actor.sendMessage(Component.text("Error: Rank '$rank' does not exist.", NamedTextColor.RED))
            // Show available ranks
            val availableRanks = mythLink.rankManager.getCachedRanks()
                .sortedByDescending { it.weight }
                .joinToString(", ") { it.name }
            actor.sendMessage(Component.text("Available ranks: $availableRanks", NamedTextColor.YELLOW))
            return
        }

        val targetUuid = getPlayerUuid(actor, target) ?: return

        // Get the player's profile from cache or database
        val profile = mythLink.connectionHandler.getPlayerProfile(targetUuid)

        if (profile == null) {
            actor.sendMessage(Component.text("Error: Player profile not found for $target.", NamedTextColor.RED))
            return
        }

        // Parse duration and reason
        val (duration, reason) = parseDurationAndReason(durationReason)

        if (duration == null) {
            actor.sendMessage(Component.text("Error: Invalid duration format.", NamedTextColor.RED))
            return
        }

        // Apply the rank with or without expiration based on the duration
        if (duration.isZero) {
            // Permanent rank with reason
            profile.addRank(rank, actor.username, reason)
            actor.sendMessage(
                Component.text("Granted permanent rank ")
                    .color(NamedTextColor.GREEN)
                    .append(Component.text(rank).color(NamedTextColor.GOLD))
                    .append(Component.text(" to ").color(NamedTextColor.GREEN))
                    .append(Component.text(target).color(NamedTextColor.GOLD))
                    .append(Component.text(".").color(NamedTextColor.GREEN))
            )
        } else {
            // Timed rank with reason
            val expirationTime = Instant.now().plus(duration)
            profile.addRank(rank, expirationTime, actor.username, reason)

            val formattedDuration = twizzy.tech.mythLink.util.DurationParser.format(duration)
            actor.sendMessage(
                Component.text("Granted rank ")
                    .color(NamedTextColor.GREEN)
                    .append(Component.text(rank).color(NamedTextColor.GOLD))
                    .append(Component.text(" to ").color(NamedTextColor.GREEN))
                    .append(Component.text(target).color(NamedTextColor.GOLD))
                    .append(Component.text(" for ").color(NamedTextColor.GREEN))
                    .append(Component.text(formattedDuration).color(NamedTextColor.GOLD))
                    .append(Component.text(".").color(NamedTextColor.GREEN))
            )
        }

        // Log the reason if provided
        if (reason.isNotEmpty()) {
            actor.sendMessage(Component.text("Reason: ").color(NamedTextColor.GREEN)
                .append(Component.text(reason).color(NamedTextColor.GRAY)))
        }

        // Sync the profile immediately to Redis
        mythLink.lettuceCache.cacheProfile(profile)
    }

    @Command("grants <target>")
    @CommandPermission("command.grants.view")
    suspend fun listGrants(
        actor: Player,
        @Optional @OnlinePlayers target: String?
    ) {

        if (target.isNullOrEmpty()) {
            actor.sendMessage(Component.text("Usage: /grants <target>", NamedTextColor.YELLOW))
            return
        }

        val targetUuid = getPlayerUuid(actor, target) ?: return

        // Get the player's profile from cache or database
        val profile = mythLink.connectionHandler.getPlayerProfile(targetUuid)

        if (profile == null) {
            actor.sendMessage(Component.text("Error: Player profile not found for $target.", NamedTextColor.RED))
            return
        }

        // Get all ranks with status information
        val ranksWithStatus = profile.getAllRanksWithStatus()
        val now = Instant.now()

        if (ranksWithStatus.isEmpty()) {
            actor.sendMessage(Component.text("$target has no ranks", NamedTextColor.YELLOW))
            return
        }

        // Count active ranks
        val activeCount = ranksWithStatus.count { it.value.isActive }
        val inactiveCount = ranksWithStatus.size - activeCount

        actor.sendMessage(Component.text("Ranks for $target (${ranksWithStatus.size} total, $activeCount active, $inactiveCount inactive):", NamedTextColor.GOLD))

        // Sort ranks: active first, then by name
        val sortedRanks = ranksWithStatus.entries.sortedWith(
            compareByDescending<Map.Entry<String, Profile.RankStatus>> { it.value.isActive }
                .thenBy { it.key }
        )

        sortedRanks.forEach { (rankName, status) ->
            // Create the click event to execute the revoke command for active ranks
            val clickEvent = ClickEvent.suggestCommand("/revoke $target $rankName")

            // Format date when the rank was added
            val addedDate = java.time.format.DateTimeFormatter
                .ofPattern("MMM d, yyyy HH:mm")
                .withZone(java.time.ZoneId.systemDefault())
                .format(status.addedTime)

            // Build hover text
            val hoverTextBuilder = Component.text()
                .append(Component.text("Granted by: ", NamedTextColor.GOLD))
                .append(Component.text(status.granter, NamedTextColor.WHITE))
                .append(Component.newline())
                .append(Component.text("Added on: ", NamedTextColor.GOLD))
                .append(Component.text(addedDate, NamedTextColor.WHITE))
                .append(Component.newline())

            // Add grant reason if available
            if (status.name in profile.getRankDetails()) {
                val reason = profile.getRankDetails()[status.name]?.reason
                if (reason != null && reason.isNotEmpty()) {
                    hoverTextBuilder
                        .append(Component.text("Grant reason: ", NamedTextColor.GOLD))
                        .append(Component.text(reason, NamedTextColor.WHITE))
                        .append(Component.newline())
                }
            }

            // Check status and add appropriate details
            if (status.isExpired && status.expiryTime != null) {
                // Handle expired rank
                val expiryDate = java.time.format.DateTimeFormatter
                    .ofPattern("MMM d, yyyy HH:mm")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(status.expiryTime)

                hoverTextBuilder
                    .append(Component.text("Expired on: ", NamedTextColor.GOLD))
                    .append(Component.text(expiryDate, NamedTextColor.RED))
            }
            else if (status.isRevoked && status.revokedTime != null && status.revokedBy != null) {
                // Handle revoked rank
                val revokedDate = java.time.format.DateTimeFormatter
                    .ofPattern("MMM d, yyyy HH:mm")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(status.revokedTime)

                hoverTextBuilder
                    .append(Component.text("Revoked by: ", NamedTextColor.GOLD))
                    .append(Component.text(status.revokedBy, NamedTextColor.RED))
                    .append(Component.newline())
                    .append(Component.text("Revoked on: ", NamedTextColor.GOLD))
                    .append(Component.text(revokedDate, NamedTextColor.RED))

                // Add revocation reason if available
                if (status.revokedReason != null && status.revokedReason.isNotEmpty()) {
                    hoverTextBuilder
                        .append(Component.newline())
                        .append(Component.text("Revocation reason: ", NamedTextColor.GOLD))
                        .append(Component.text(status.revokedReason, NamedTextColor.RED))
                }
            }
            else if (status.expiryTime != null) {
                // Handle active time-limited rank
                val expiryDate = java.time.format.DateTimeFormatter
                    .ofPattern("MMM d, yyyy HH:mm")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(status.expiryTime)

                val remainingDuration = java.time.Duration.between(now, status.expiryTime)
                val formattedDuration = twizzy.tech.mythLink.util.DurationParser.format(remainingDuration)

                hoverTextBuilder
                    .append(Component.text("Status: ", NamedTextColor.GOLD))
                    .append(Component.text("ACTIVE", NamedTextColor.GREEN))
                    .append(Component.newline())
                    .append(Component.text("Expires on: ", NamedTextColor.GOLD))
                    .append(Component.text(expiryDate, NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text("Time remaining: ", NamedTextColor.GOLD))
                    .append(Component.text(formattedDuration, NamedTextColor.YELLOW))
            }
            else {
                // Handle active permanent rank
                hoverTextBuilder
                    .append(Component.text("Status: ", NamedTextColor.GOLD))
                    .append(Component.text("ACTIVE", NamedTextColor.GREEN))
                    .append(Component.newline())
                    .append(Component.text("Duration: ", NamedTextColor.GOLD))
                    .append(Component.text("Permanent", NamedTextColor.GREEN))
            }

            // Add click instruction for active ranks
            if (status.isActive) {
                hoverTextBuilder.append(Component.newline())
                    .append(Component.text("Click to revoke this rank", NamedTextColor.RED))
            }

            // Create the text component with appropriate styling
            val textComponent = if (status.isActive) {
                Component.text(" - ", NamedTextColor.WHITE)
                    .append(Component.text(rankName, NamedTextColor.WHITE))
            } else {
                // Use strikethrough for inactive (expired or revoked) ranks
                Component.text(" - ", NamedTextColor.GRAY)
                    .append(
                        Component.text(rankName, NamedTextColor.GRAY)
                            .decoration(TextDecoration.STRIKETHROUGH, true)
                    )
            }

            // Apply hover and click events
            val finalComponent = if (status.isActive) {
                textComponent
                    .hoverEvent(hoverTextBuilder.build())
                    .clickEvent(clickEvent)
            } else {
                // Just hover for inactive ranks
                textComponent
                    .hoverEvent(hoverTextBuilder.build())
            }

            actor.sendMessage(finalComponent)
        }
    }

    @Command("revoke <target> <rank> <reason>", "rg <target> <rank> <reason>", "rev <target> <rank> <reason>")
    @CommandPermission("command.grants.revoke")
    suspend fun revokeGrants(
        actor: Player,
        @Optional @OnlinePlayers target: String?,
        @Optional @RankList rank: String?,
        @Optional reason: String?
    ) {

        if (target.isNullOrEmpty() || rank.isNullOrEmpty()) {
            actor.sendMessage(Component.text("Usage: /revoke <target> <rank> <reason>", NamedTextColor.YELLOW))
            return
        }

        val targetUuid = getPlayerUuid(actor, target) ?: return

        // Get the player's profile from cache or database
        val profile = mythLink.connectionHandler.getPlayerProfile(targetUuid)

        if (profile == null) {
            actor.sendMessage(Component.text("Error: Player profile not found for $target.", NamedTextColor.RED))
            return
        }

        // Check if the player has the rank first
        if (!profile.hasRank(rank)) {
            actor.sendMessage(Component.text("$target doesn't have the rank '$rank'", NamedTextColor.YELLOW))
            return
        }

        // Remove the rank
        val removed = if (reason != null && reason.isNotEmpty()) {
            profile.removeRank(rank, actor.username, reason)
        } else {
            profile.removeRank(rank, actor.username)
        }

        if (removed) {
            actor.sendMessage(Component.text("Revoked rank '$rank' from $target", NamedTextColor.GREEN))

            // If there was a reason provided, show it
            if (reason != null && reason.isNotEmpty()) {
                actor.sendMessage(Component.text("Reason: ").color(NamedTextColor.GREEN)
                    .append(Component.text(reason).color(NamedTextColor.GRAY)))
            }

            // Sync the profile immediately to Redis
            mythLink.lettuceCache.cacheProfile(profile)

            // Log the change
            val logMessage = if (reason != null && reason.isNotEmpty()) {
                "[Ranks] ${actor.username} revoked rank '$rank' from ${profile.username} (${profile.uuid}). Reason: $reason"
            } else {
                "[Ranks] ${actor.username} revoked rank '$rank' from ${profile.username} (${profile.uuid})"
            }
            mythLink.logger.info(logMessage)
        } else {
            actor.sendMessage(Component.text("Failed to revoke rank '$rank' from $target", NamedTextColor.RED))
        }
    }

    /**
     * Parse a string containing both duration and reason
     * The duration can be at the beginning or end of the string
     *
     * @param input The combined duration and reason string
     * @return Pair of (Duration, reason string)
     */
    private fun parseDurationAndReason(input: String): Pair<Duration?, String> {
        // If input is empty, return null duration and empty reason
        if (input.isBlank()) {
            return Pair(null, "")
        }

        // Check if duration is at the beginning
        val words = input.trim().split("\\s+".toRegex(), limit = 2)
        val firstWord = words[0]
        val durationAtStart = twizzy.tech.mythLink.util.DurationParser.parse(firstWord)

        if (durationAtStart != null) {
            // Duration is at the start, rest is reason
            val reason = if (words.size > 1) words[1].trim() else ""
            return Pair(durationAtStart, reason)
        }

        // Check if duration is at the end
        val lastWord = input.trim().split("\\s+".toRegex()).last()
        val durationAtEnd = twizzy.tech.mythLink.util.DurationParser.parse(lastWord)

        if (durationAtEnd != null) {
            // Duration is at the end, everything before is reason
            val reason = input.trim().removeSuffix(lastWord).trim()
            return Pair(durationAtEnd, reason)
        }

        // No valid duration found, assume permanent and use entire string as reason
        return Pair(Duration.ZERO, input.trim())
    }

    /**
     * Helper method to get a player's UUID from their username
     *
     * @param actor The player executing the command
     * @param target The target player's username
     * @return The target player's UUID, or null if not found
     */
    private fun getPlayerUuid(actor: Player, target: String): UUID? {
        // First, check if the player is online
        val onlinePlayer = mythLink.server.getPlayer(target).orElse(null)
        if (onlinePlayer != null) {
            return onlinePlayer.uniqueId
        }

        // Try to parse as UUID if it looks like a UUID
        try {
            if (target.length > 30 && target.contains("-")) {
                return UUID.fromString(target)
            }
        } catch (e: IllegalArgumentException) {
            // Not a valid UUID, continue
        }

        // Could implement offline player lookup here if needed
        actor.sendMessage(Component.text("Player $target not found or not online", NamedTextColor.RED))
        return null
    }
}