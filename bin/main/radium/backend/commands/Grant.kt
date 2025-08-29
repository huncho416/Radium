package radium.backend.commands

import com.velocitypowered.api.proxy.Player
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.TextDecoration
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Optional
import revxrsal.commands.velocity.annotation.CommandPermission
import radium.backend.Radium
import radium.backend.annotations.OnlinePlayers
import radium.backend.annotations.RankList
import radium.backend.player.Profile
import radium.backend.util.DurationParser
import java.time.Duration
import java.time.Instant
import java.util.UUID


@Command("grant")
@CommandPermission("radium.staff")
class Grant(private val radium: Radium) {

    @Command("grant <target> <rank>")
    @CommandPermission("radium.grant.use")
    suspend fun grant(
        actor: Player,
        @Optional @OnlinePlayers target: String,
        @Optional @RankList rank: String?,
        @Optional durationReason: String?
    ) {
        if (target.isNullOrEmpty() || rank.isNullOrEmpty()) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.grant.usage"))
            return
        }

        // Verify that the rank exists
        val rankObj = radium.rankManager.getRank(rank)
        if (rankObj == null) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.grant.rank_not_exist", "rank" to rank))
            // Show available ranks
            val availableRanks = radium.rankManager.getCachedRanks()
                .sortedByDescending { it.weight }
                .joinToString(", ") { it.name }
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.grant.available_ranks", "ranks" to availableRanks))
            return
        }


        // Get the player's profile from cache or database
        radium.logger.debug("Finding profile for target: $target")
        val profile = radium.connectionHandler.findPlayerProfile(target)
        radium.logger.debug("Profile search result for $target: ${profile?.let { "found (${it.username})" } ?: "not found"}")

        if (profile == null) {
            radium.logger.warn("Profile not found for target: $target")
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.grant.profile_not_found", "target" to target))
            return
        }

        // Parse duration and reason
        val (duration, reason) = parseDurationAndReason(durationReason)

        // Default to no duration if not specified
        val finalDuration = duration ?: Duration.ZERO

        // Apply the rank with or without expiration based on the duration
        if (finalDuration.isZero) {
            // Permanent rank with reason
            profile.addRank(rank, actor.username, reason)
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.grant.grant_permanent",
                "rank" to rank,
                "target" to target
            ))
        } else {
            // Timed rank with reason
            val expirationTime = Instant.now().plus(finalDuration)
            profile.addRank(rank, expirationTime, actor.username, reason)

            val formattedDuration = DurationParser.format(finalDuration)
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.grant.grant_temporary",
                "rank" to rank,
                "target" to target,
                "duration" to formattedDuration
            ))
        }

        // Log the reason if provided
        if (reason != null && reason.isNotEmpty()) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.grant.reason",
                "reason" to reason
            ))
        }

        // Sync the profile immediately to Redis
        radium.lettuceCache.cacheProfile(profile)
        
        // Update tab lists for all players to reflect rank changes
        GlobalScope.launch {
            radium.networkVanishManager.refreshAllTabLists()
        }
    }

    @Command("grants <target>")
    @CommandPermission("radium.grants.view")
    suspend fun listGrants(
        actor: Player,
        @Optional @OnlinePlayers target: String?
    ) {

        if (target.isNullOrEmpty()) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.grants.usage"))
            return
        }


        // Get the player's profile from cache or database
        val profile = radium.connectionHandler.findPlayerProfile(target)

        if (profile == null) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.grants.profile_not_found", "target" to target))
            return
        }

        // Get all ranks with status information
        val ranksWithStatus = profile.getAllRanksWithStatus()
        val now = Instant.now()

        if (ranksWithStatus.isEmpty()) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.grants.no_ranks", "target" to target))
            return
        }

        // Count active ranks
        val activeCount = ranksWithStatus.count { it.value.isActive }
        val inactiveCount = ranksWithStatus.size - activeCount

        actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.grants.header",
            "target" to target,
            "total" to ranksWithStatus.size.toString(),
            "active" to activeCount.toString(),
            "inactive" to inactiveCount.toString()
        ))

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
                .append(radium.yamlFactory.getMessageComponent("commands.grants.granted_by", "granter" to status.granter))
                .append(Component.newline())
                .append(radium.yamlFactory.getMessageComponent("commands.grants.added_on", "date" to addedDate))
                .append(Component.newline())

            // Add grant reason if available
            if (status.name in profile.getRankDetails()) {
                val reason = profile.getRankDetails()[status.name]?.reason
                if (reason != null && reason.isNotEmpty()) {
                    hoverTextBuilder
                        .append(radium.yamlFactory.getMessageComponent("commands.grants.grant_reason", "reason" to reason))
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
                    .append(radium.yamlFactory.getMessageComponent("commands.grants.hover.expired", "date" to expiryDate))
            }
            else if (status.isRevoked && status.revokedTime != null && status.revokedBy != null) {
                // Handle revoked rank
                val revokedDate = java.time.format.DateTimeFormatter
                    .ofPattern("MMM d, yyyy HH:mm")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(status.revokedTime)

                hoverTextBuilder
                    .append(radium.yamlFactory.getMessageComponent("commands.grants.hover.revoked_by", "revoker" to status.revokedBy))
                    .append(Component.newline())
                    .append(radium.yamlFactory.getMessageComponent("commands.grants.hover.revoked_on", "date" to revokedDate))

                // Add revocation reason if available
                if (status.revokedReason != null && status.revokedReason.isNotEmpty()) {
                    hoverTextBuilder
                        .append(Component.newline())
                        .append(radium.yamlFactory.getMessageComponent("commands.grants.hover.revoked_reason", "reason" to status.revokedReason))
                }
            }
            else if (status.expiryTime != null) {
                // Handle active time-limited rank
                val expiryDate = java.time.format.DateTimeFormatter
                    .ofPattern("MMM d, yyyy HH:mm")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(status.expiryTime)

                val remainingDuration = java.time.Duration.between(now, status.expiryTime)
                val formattedDuration = DurationParser.format(remainingDuration)

                hoverTextBuilder
                    .append(radium.yamlFactory.getMessageComponent("commands.grants.hover.expires_on", "date" to expiryDate))
                    .append(Component.newline())
                    .append(radium.yamlFactory.getMessageComponent("commands.grants.hover.time_remaining", "duration" to formattedDuration))
            }
            else {
                // Handle active permanent rank
                hoverTextBuilder
                    .append(radium.yamlFactory.getMessageComponent("commands.grants.hover.duration", "duration" to "Permanent"))
            }

            // Add click instruction for active ranks
            if (status.isActive) {
                hoverTextBuilder.append(Component.newline())
                    .append(radium.yamlFactory.getMessageComponent("commands.grants.hover.click_to_revoke"))
            }

            // Create the text component with appropriate styling
            val textComponent = if (status.isActive) {
                Component.text(" - ")
                    .append(Component.text(rankName))
            } else {
                // Use strikethrough for inactive (expired or revoked) ranks
                Component.text(" - ")
                    .append(
                        Component.text(rankName)
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

    /**
     * Parse a string containing both duration and reason
     * The duration can be at the beginning or end of the string
     *
     * @param input The combined duration and reason string
     * @return Pair of (Duration?, String?) where both can be null if not provided
     */
    private fun parseDurationAndReason(input: String?): Pair<Duration?, String?> {
        // If input is null or empty, return null for both duration and reason
        if (input == null || input.isBlank()) {
            return Pair(null, null)
        }

        // Check if duration is at the beginning
        val words = input.trim().split("\\s+".toRegex(), limit = 2)
        val firstWord = words[0]
        val durationAtStart = DurationParser.parse(firstWord)

        if (durationAtStart != null) {
            // Duration is at the start, rest is reason (or null if empty)
            val reason = if (words.size > 1 && words[1].isNotBlank()) words[1].trim() else null
            return Pair(durationAtStart, reason)
        }

        // Check if duration is at the end
        val lastWord = input.trim().split("\\s+".toRegex()).last()
        val durationAtEnd = DurationParser.parse(lastWord)

        if (durationAtEnd != null) {
            // Duration is at the end, everything before is reason (or null if empty)
            val reasonText = input.trim().removeSuffix(lastWord).trim()
            val reason = if (reasonText.isNotBlank()) reasonText else null
            return Pair(durationAtEnd, reason)
        }

        // No valid duration found, assume the entire input is just a reason with no duration
        return Pair(null, input.trim())
    }

}
