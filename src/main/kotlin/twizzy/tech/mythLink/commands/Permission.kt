package twizzy.tech.mythLink.commands

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Optional
import revxrsal.commands.annotation.Subcommand
import revxrsal.commands.velocity.annotation.CommandPermission
import twizzy.tech.mythLink.MythLink
import twizzy.tech.mythLink.annotations.OnlinePlayers
import twizzy.tech.mythLink.player.Profile
import java.util.*

@Command("permission", "perms", "perm")
@CommandPermission("command.permission")
class Permission(private val mythLink: MythLink) {

    @Command("permission", "perms", "perm")
    fun permissionUsage(actor: Player, @Optional target: Player?) {
        actor.sendMessage(Component.text("Permission Management Commands:", NamedTextColor.GOLD))
        actor.sendMessage(Component.text("  /perms <player> add <permission>", NamedTextColor.YELLOW)
            .append(Component.text(" - Add a permission to a player", NamedTextColor.WHITE)))
        actor.sendMessage(Component.text("  /perms <player> remove <permission>", NamedTextColor.YELLOW)
            .append(Component.text(" - Remove a permission from a player", NamedTextColor.WHITE)))
        actor.sendMessage(Component.text("  /perms <player> list", NamedTextColor.YELLOW)
            .append(Component.text(" - List a player's permissions", NamedTextColor.WHITE)))
        actor.sendMessage(Component.text("  /perms <player> clear", NamedTextColor.YELLOW)
            .append(Component.text(" - Clear all permissions from a player", NamedTextColor.WHITE)))
    }



    @Subcommand("<target> add <permission> <duration>")
    @CommandPermission("command.permission.add")
    suspend fun addPermission(
        actor: Player,
        @OnlinePlayers target: String?,
        @Optional permission: String?,
        @Optional duration: String?) {

        if (target.isNullOrEmpty() || permission.isNullOrEmpty()) {
            actor.sendMessage(Component.text("Usage: /perm <target> add <permission> [duration]", NamedTextColor.YELLOW))
            return
        }


        val targetUuid = getPlayerUuid(actor, target) ?: return

        // Get the player's profile from cache or database
        val profile = mythLink.connectionHandler.getPlayerProfile(targetUuid)

        if (profile == null) {
            actor.sendMessage(Component.text("Error: Player profile not found for $target", NamedTextColor.RED))
            return
        }

        // Check if duration is provided, if so, parse it and add timed permission
        var added = false
        if (duration != null) {
            val durationObj = twizzy.tech.mythLink.util.DurationParser.parse(duration)
            if (durationObj != null) {
                // Calculate expiration time
                val expirationTime = java.time.Instant.now().plus(durationObj)
                added = profile.addPermission(permission, expirationTime, actor.username)

                if (added) {
                    // Format the expiration time for display
                    val formattedDuration = twizzy.tech.mythLink.util.DurationParser.format(durationObj)
                    actor.sendMessage(Component.text("Added permission '$permission' to $target for $formattedDuration", NamedTextColor.GREEN))

                    // Sync the profile immediately to Redis
                    mythLink.lettuceCache.cacheProfile(profile)

                    // Log the change
                    mythLink.logger.info("[Permissions] ${actor.username} added timed permission '$permission' to ${profile.username} (${profile.uuid}) for $duration")
                } else {
                    actor.sendMessage(Component.text("$target already has the permission '$permission'", NamedTextColor.YELLOW))
                }
                return
            } else {
                actor.sendMessage(Component.text("Invalid duration format: $duration", NamedTextColor.RED))
                actor.sendMessage(Component.text("Valid formats: 5s, 5m, 2h, 3d, 1w, 2mo, 1y", NamedTextColor.RED))
                return
            }
        }

        // If no duration or invalid duration, add permanent permission
        added = profile.addPermission(permission, actor.username)

        if (added) {
            actor.sendMessage(Component.text("Added permission '$permission' to $target permanently", NamedTextColor.GREEN))

            // Sync the profile immediately to Redis
            mythLink.lettuceCache.cacheProfile(profile)

            // Log the change
            mythLink.logger.info("[Permissions] ${actor.username} added permission '$permission' to ${profile.username} (${profile.uuid})")
        } else {
            actor.sendMessage(Component.text("$target already has the permission '$permission'", NamedTextColor.YELLOW))
        }
    }

    @Subcommand("<target> remove <permission>")
    @CommandPermission("command.permission.remove")
    suspend fun removePermission(
        actor: Player,
        @OnlinePlayers target: String?,
        @Optional permission: String?) {

        if (target.isNullOrEmpty() || permission.isNullOrEmpty()) {
            actor.sendMessage(Component.text("Usage: /perm <target> remove <permission>", NamedTextColor.YELLOW))
            return
        }

        val targetUuid = getPlayerUuid(actor, target) ?: return

        // Get the player's profile from cache or database
        val profile = mythLink.connectionHandler.getPlayerProfile(targetUuid)

        if (profile == null) {
            actor.sendMessage(Component.text("Error: Player profile not found for $target", NamedTextColor.RED))
            return
        }

        // Remove the permission from the profile, passing the actor's username as revoker
        val removed = profile.removePermission(permission, actor.username)

        if (removed) {
            actor.sendMessage(Component.text("Removed permission '$permission' from $target", NamedTextColor.GREEN))
            actor.sendMessage(Component.text("Revocation has been recorded with your name and the current date", NamedTextColor.GRAY))

            // Sync the profile immediately to Redis
            mythLink.lettuceCache.cacheProfile(profile)

            // Log the change
            mythLink.logger.info("[Permissions] ${actor.username} revoked permission '$permission' from ${profile.username} (${profile.uuid})")
        } else {
            actor.sendMessage(Component.text("$target doesn't have the permission '$permission'", NamedTextColor.YELLOW))
        }
    }

    @Subcommand("<target> list")
    @CommandPermission("command.permission.list")
    suspend fun listPermissions(
        actor: Player,
        @OnlinePlayers target: String) {
        if (target.isNullOrEmpty()) {
            actor.sendMessage(Component.text("Usage: /perm <target> list", NamedTextColor.YELLOW))
            return
        }

        val targetUuid = getPlayerUuid(actor, target) ?: return

        // Get the player's profile from cache or database
        val profile = mythLink.connectionHandler.getPlayerProfile(targetUuid)

        if (profile == null) {
            actor.sendMessage(Component.text("Error: Player profile not found for $target", NamedTextColor.RED))
            return
        }

        // Get all permissions with status information
        val permissionsWithStatus = profile.getAllPermissionsWithStatus()
        val now = java.time.Instant.now()

        if (permissionsWithStatus.isEmpty()) {
            actor.sendMessage(Component.text("$target has no permissions", NamedTextColor.YELLOW))
        } else {
            // Count active permissions
            val activeCount = permissionsWithStatus.count { it.value.isActive }
            val inactiveCount = permissionsWithStatus.size - activeCount

            actor.sendMessage(Component.text("Permissions for $target (${permissionsWithStatus.size} total, $activeCount active, $inactiveCount inactive):", NamedTextColor.GOLD))

            // Sort permissions: active first, then by name
            val sortedPermissions = permissionsWithStatus.entries.sortedWith(
                compareByDescending<Map.Entry<String, Profile.PermissionStatus>> { it.value.isActive }
                .thenBy { it.key }
            )

            sortedPermissions.forEach { (permName, status) ->
                // Create the click event to execute the remove command
                val clickEvent = ClickEvent.runCommand("/perm $target remove $permName")

                // Format date when the permission was added
                val addedDate = java.time.format.DateTimeFormatter
                    .ofPattern("MMM d, yyyy HH:mm")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(status.addedTime)

                // Build hover text component builder
                val hoverTextBuilder = Component.text()
                    .append(Component.text("Granted by: ", NamedTextColor.GOLD))
                    .append(Component.text(status.granter, NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text("Added on: ", NamedTextColor.GOLD))
                    .append(Component.text(addedDate, NamedTextColor.WHITE))
                    .append(Component.newline())

                // Check if expired
                if (status.isExpired && status.expiryTime != null) {
                    val expiryDate = java.time.format.DateTimeFormatter
                        .ofPattern("MMM d, yyyy HH:mm")
                        .withZone(java.time.ZoneId.systemDefault())
                        .format(status.expiryTime)

                    hoverTextBuilder
                        .append(Component.text("Expired on: ", NamedTextColor.GOLD))
                        .append(Component.text(expiryDate, NamedTextColor.RED))
                }
                // Check if revoked
                else if (status.isRevoked && status.revokedTime != null && status.revokedBy != null) {
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
                }
                // For active time-limited permissions
                else if (status.expiryTime != null) {
                    val expiryDate = java.time.format.DateTimeFormatter
                        .ofPattern("MMM d, yyyy HH:mm")
                        .withZone(java.time.ZoneId.systemDefault())
                        .format(status.expiryTime)

                    // Calculate remaining time for timed permissions
                    val remainingDuration = java.time.Duration.between(now, status.expiryTime)
                    val formattedDuration = twizzy.tech.mythLink.util.DurationParser.format(remainingDuration)

                    hoverTextBuilder
                        .append(Component.text("Expires on: ", NamedTextColor.GOLD))
                        .append(Component.text(expiryDate, NamedTextColor.WHITE))
                        .append(Component.newline())
                        .append(Component.text("Time remaining: ", NamedTextColor.GOLD))
                        .append(Component.text(formattedDuration, NamedTextColor.YELLOW))
                        .append(Component.newline())
                }
                // For active permanent permissions
                else {
                    hoverTextBuilder
                        .append(Component.text("Duration: ", NamedTextColor.GOLD))
                        .append(Component.text("Permanent", NamedTextColor.GREEN))
                        .append(Component.newline())
                }

                // Add click instruction
                if (status.isActive) {
                    hoverTextBuilder.append(Component.text("Click to remove this permission", NamedTextColor.RED))
                }

                // Create the actual text component
                val textComponent = if (status.isActive) {
                    Component.text(" - ", NamedTextColor.WHITE)
                        .append(Component.text(permName, NamedTextColor.WHITE))
                } else {
                    // Use strikethrough for inactive (expired or revoked) permissions, but only for the permission node
                    Component.text(" - ", NamedTextColor.GRAY)
                        .append(
                            Component.text(permName, NamedTextColor.GRAY).decoration(TextDecoration.STRIKETHROUGH, true)
                        )
                }

                // Apply hover and click events
                if (status.isActive) {
                    val finalComponent = textComponent
                        .hoverEvent(hoverTextBuilder.build())
                        .clickEvent(clickEvent)
                    actor.sendMessage(finalComponent)
                } else {
                    // For inactive permissions, just hover
                    val finalComponent = textComponent
                        .hoverEvent(hoverTextBuilder.build())
                    actor.sendMessage(finalComponent)
                }

            }
        }
    }


    @Subcommand("<target> clear")
    @CommandPermission("command.permission.clear")
    suspend fun clearPermissions(
        actor: Player,
        target: String
    ) {
        val targetUuid = getPlayerUuid(actor, target) ?: return

        // Get the player's profile from cache or database
        val profile = mythLink.connectionHandler.getPlayerProfile(targetUuid)

        if (profile == null) {
            actor.sendMessage(Component.text("Error: Player profile not found for $target", NamedTextColor.RED))
            return
        }

        // Clear all permissions from the profile
        val permCount = profile.getPermissions().size
        profile.clearPermissions()

        actor.sendMessage(Component.text("Cleared all permissions ($permCount) from $target", NamedTextColor.GREEN))

        // Sync the profile immediately to Redis
        mythLink.lettuceCache.cacheProfile(profile)

        // Log the change
        mythLink.logger.info("[Permissions] ${actor.username} cleared all permissions from ${profile.username} (${profile.uuid})")
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