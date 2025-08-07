package radium.backend.commands

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.TextDecoration
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Optional
import revxrsal.commands.annotation.Subcommand
import revxrsal.commands.velocity.annotation.CommandPermission
import radium.backend.Radium
import radium.backend.annotations.OnlinePlayers
import radium.backend.player.Profile
import radium.backend.util.DurationParser
import java.util.*

@Command("permission", "perms", "perm")
@CommandPermission("radium.staff")
class Permission(private val radium: Radium) {

    @Command("permission", "perms", "perm")
    @CommandPermission("radium.permission.use")
    fun permissionUsage(actor: Player, @Optional target: Player?) {
        actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.permission.header"))
        actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.permission.usage.add"))
        actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.permission.usage.remove"))
        actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.permission.usage.list"))
        actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.permission.usage.clear"))
    }

    @Subcommand("<target> add <permission> <duration>")
    @CommandPermission("radium.permission.add")
    suspend fun addPermission(
        actor: Player,
        @OnlinePlayers target: String?,
        @Optional permission: String?,
        @Optional duration: String?) {

        if (target.isNullOrEmpty() || permission.isNullOrEmpty()) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.permission.add.usage"))
            return
        }

        // Get the player's profile from cache or database
        val profile = radium.connectionHandler.findPlayerProfile(target)

        if (profile == null) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.permission.profile_not_found", "target" to target))
            return
        }

        // Check if duration is provided, if so, parse it and add timed permission
        var added = false
        if (duration != null) {
            val durationObj = DurationParser.parse(duration)
            if (durationObj != null) {
                // Calculate expiration time
                val expirationTime = java.time.Instant.now().plus(durationObj)
                added = profile.addPermission(permission, expirationTime, actor.username)

                if (added) {
                    // Format the expiration time for display
                    val formattedDuration = DurationParser.format(durationObj)
                    actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.permission.add.temporary",
                        "permission" to permission,
                        "target" to target,
                        "duration" to formattedDuration
                    ))

                    // Sync the profile immediately to Redis
                    radium.lettuceCache.cacheProfile(profile)

                    // Log the change
                    radium.logger.info("[Permissions] ${actor.username} added timed permission '$permission' to ${profile.username} (${profile.uuid}) for $duration")
                } else {
                    actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.permission.add.already_has",
                        "target" to target,
                        "permission" to permission
                    ))
                }
                return
            } else {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.permission.add.invalid_duration", "duration" to duration))
                actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.permission.add.valid_formats"))
                return
            }
        }

        // If no duration or invalid duration, add permanent permission
        added = profile.addPermission(permission, actor.username)

        if (added) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.permission.add.success",
                "permission" to permission,
                "target" to target
            ))

            // Sync the profile immediately to Redis
            radium.lettuceCache.cacheProfile(profile)

            // Log the change
            radium.logger.info("[Permissions] ${actor.username} added permission '$permission' to ${profile.username} (${profile.uuid})")
        } else {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.permission.add.already_has",
                "target" to target,
                "permission" to permission
            ))
        }
    }

    @Subcommand("<target> remove <permission>")
    @CommandPermission("radium.permission.remove")
    suspend fun removePermission(
        actor: Player,
        @OnlinePlayers target: String?,
        @Optional permission: String?) {

        if (target.isNullOrEmpty() || permission.isNullOrEmpty()) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.permission.remove.usage"))
            return
        }

        // Get the player's profile from cache or database
        val profile = radium.connectionHandler.findPlayerProfile(target)

        if (profile == null) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.permission.profile_not_found", "target" to target))
            return
        }

        // Remove the permission from the profile, passing the actor's username as revoker
        val removed = profile.removePermission(permission, actor.username)

        if (removed) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.permission.remove.success",
                "permission" to permission,
                "target" to target
            ))

            // Sync the profile immediately to Redis
            radium.lettuceCache.cacheProfile(profile)

            // Log the change
            radium.logger.info("[Permissions] ${actor.username} revoked permission '$permission' from ${profile.username} (${profile.uuid})")
        } else {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.permission.remove.not_found",
                "target" to target,
                "permission" to permission
            ))
        }
    }

    @Subcommand("<target> list")
    @CommandPermission("radium.permission.list")
    suspend fun listPermissions(
        actor: Player,
        @OnlinePlayers target: String) {
        if (target.isNullOrEmpty()) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.permission.list.usage"))
            return
        }

        // Get the player's profile from cache or database
        val profile = radium.connectionHandler.findPlayerProfile(target)

        if (profile == null) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.permission.profile_not_found", "target" to target))
            return
        }

        // Get all permissions with status information
        val permissionsWithStatus = profile.getAllPermissionsWithStatus()
        val now = java.time.Instant.now()

        if (permissionsWithStatus.isEmpty()) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.permission.list.none", "target" to target))
        } else {
            // Count active permissions
            val activeCount = permissionsWithStatus.count { it.value.isActive }
            val inactiveCount = permissionsWithStatus.size - activeCount

            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.permission.list.header",
                "target" to target,
                "total" to permissionsWithStatus.size.toString(),
                "active" to activeCount.toString(),
                "inactive" to inactiveCount.toString()
            ))

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
                    .append(radium.yamlFactory.getMessageComponent("commands.permission.list.granted_by", "granter" to status.granter))
                    .append(Component.newline())
                    .append(radium.yamlFactory.getMessageComponent("commands.permission.list.added_on", "date" to addedDate))
                    .append(Component.newline())

                // Check if expired
                if (status.isExpired && status.expiryTime != null) {
                    val expiryDate = java.time.format.DateTimeFormatter
                        .ofPattern("MMM d, yyyy HH:mm")
                        .withZone(java.time.ZoneId.systemDefault())
                        .format(status.expiryTime)

                    hoverTextBuilder
                        .append(radium.yamlFactory.getMessageComponent("commands.permission.list.expired_on", "date" to expiryDate))
                }
                // Check if revoked
                else if (status.isRevoked && status.revokedTime != null && status.revokedBy != null) {
                    val revokedDate = java.time.format.DateTimeFormatter
                        .ofPattern("MMM d, yyyy HH:mm")
                        .withZone(java.time.ZoneId.systemDefault())
                        .format(status.revokedTime)

                    hoverTextBuilder
                        .append(radium.yamlFactory.getMessageComponent("commands.permission.list.revoked_by", "revoker" to status.revokedBy))
                        .append(Component.newline())
                        .append(radium.yamlFactory.getMessageComponent("commands.permission.list.revoked_on", "date" to revokedDate))
                }
                // For active time-limited permissions
                else if (status.expiryTime != null) {
                    val expiryDate = java.time.format.DateTimeFormatter
                        .ofPattern("MMM d, yyyy HH:mm")
                        .withZone(java.time.ZoneId.systemDefault())
                        .format(status.expiryTime)

                    // Calculate remaining time for timed permissions
                    val remainingDuration = java.time.Duration.between(now, status.expiryTime)
                    val formattedDuration = DurationParser.format(remainingDuration)

                    hoverTextBuilder
                        .append(radium.yamlFactory.getMessageComponent("commands.permission.list.expires_on", "date" to expiryDate))
                        .append(Component.newline())
                        .append(radium.yamlFactory.getMessageComponent("commands.permission.list.time_remaining", "duration" to formattedDuration))
                        .append(Component.newline())
                }
                // For active permanent permissions
                else {
                    hoverTextBuilder
                        .append(radium.yamlFactory.getMessageComponent("commands.permission.list.duration", "duration" to "Permanent"))
                        .append(Component.newline())
                }

                // Add click instruction
                if (status.isActive) {
                    hoverTextBuilder.append(radium.yamlFactory.getMessageComponent("commands.permission.list.click_to_remove"))
                }

                // Create the actual text component
                val textComponent = if (status.isActive) {
                    Component.text(" - ")
                        .append(Component.text(permName))
                } else {
                    // Use strikethrough for inactive (expired or revoked) permissions, but only for the permission node
                    Component.text(" - ")
                        .append(
                            Component.text(permName).decoration(TextDecoration.STRIKETHROUGH, true)
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
    @CommandPermission("radium.permission.clear")
    suspend fun clearPermissions(
        actor: Player,
        target: String
    ) {
        if (target.isNullOrEmpty()) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.permission.clear.usage"))
            return
        }
        // Get the player's profile from cache or database
        val profile = radium.connectionHandler.findPlayerProfile(target)

        if (profile == null) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.permission.profile_not_found", "target" to target))
            return
        }

        // Clear all permissions from the profile
        val permCount = profile.getPermissions().size
        profile.clearPermissions()

        actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.permission.clear.success",
            "target" to target,
            "count" to permCount.toString()
        ))

        // Sync the profile immediately to Redis
        radium.lettuceCache.cacheProfile(profile)

        // Log the change
        radium.logger.info("[Permissions] ${actor.username} cleared all permissions from ${profile.username} (${profile.uuid})")
    }
}
