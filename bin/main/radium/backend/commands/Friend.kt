package radium.backend.commands

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Optional
import revxrsal.commands.annotation.Subcommand
import radium.backend.Radium
import radium.backend.player.Profile
import java.time.Duration
import java.time.Instant
import java.util.*

@Command("friend")
class Friend(private val radium: Radium) {

    private val yamlFactory = radium.yamlFactory

    @Command("friend")
    fun friendUsage(actor: Player) {
        // Display usage information for the friend command
        val header = yamlFactory.getMessageComponent("friend.usage.header")
        actor.sendMessage(header)

        // Send each command help individually
        actor.sendMessage(yamlFactory.getMessageComponent("friend.usage.main"))
        actor.sendMessage(yamlFactory.getMessageComponent("friend.usage.add"))
        actor.sendMessage(yamlFactory.getMessageComponent("friend.usage.remove"))
        actor.sendMessage(yamlFactory.getMessageComponent("friend.usage.deny"))
        actor.sendMessage(yamlFactory.getMessageComponent("friend.usage.list"))
        actor.sendMessage(yamlFactory.getMessageComponent("friend.usage.requests"))
    }

    @Subcommand("add <target>")
    suspend fun friendAdd(actor: Player, target: String) {
        // Get profiles
        val actorProfile = radium.connectionHandler.getPlayerProfile(actor.uniqueId)
        if (actorProfile == null) {
            radium.logger.warn("Actor profile not found in memory cache for ${actor.username} (${actor.uniqueId})")
            actor.sendMessage(yamlFactory.getMessageComponent("general.database_error"))
            return
        }
        
        radium.logger.debug("Finding profile for target: $target")
        val targetProfile = radium.connectionHandler.findPlayerProfile(target)
        
        // Check if target profile exists
        if (targetProfile == null) {
            val notFoundMsg = yamlFactory.getMessageComponent("friend.add.user_not_found", "target" to target)
            actor.sendMessage(notFoundMsg)
            return
        }
        
        // Check if trying to add self
        if (actor.uniqueId == targetProfile.uuid) {
            val selfMsg = yamlFactory.getMessageComponent("friend.add.self")
            actor.sendMessage(selfMsg)
            return
        }
        
        // Check if target accepts friend requests
        val acceptsRequests = targetProfile.getSetting("friendRequests")?.toBoolean() ?: true
        if (!acceptsRequests) {
            val disabledMsg = yamlFactory.getMessageComponent("friend.add.requests_disabled",
                "target" to targetProfile.username)
            actor.sendMessage(disabledMsg)
            return
        }
        
        // Try to add friend
        when (actorProfile.addFriend(targetProfile)) {
            Profile.FriendResult.SENT -> {
                // Friend request sent
                val sentMsg = yamlFactory.getMessageComponent("friend.add.request_sent",
                    "target" to targetProfile.username)
                actor.sendMessage(sentMsg)
                
                // Notify target player if online
                val targetPlayer = radium.server.getPlayer(targetProfile.uuid)
                if (targetPlayer.isPresent) {
                    val notifyMsg = yamlFactory.getMessageComponent("friend.add.incoming_request",
                        "player" to actor.username)
                    
                    // Create clickable buttons for accept/deny
                    val acceptText = yamlFactory.getMessage("friend.add.accept_hover")
                    val denyText = yamlFactory.getMessage("friend.add.deny_hover")

                    val acceptButton = Component.text("     [ $acceptText ]")
                        .clickEvent(ClickEvent.runCommand("/friend add ${actor.username}"))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to accept")))
                        .color(NamedTextColor.DARK_GREEN)
                    
                    val denyButton = Component.text("   [ $denyText ]")
                        .clickEvent(ClickEvent.runCommand("/friend deny ${actor.username}"))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to deny")))
                        .color(NamedTextColor.RED)
                    
                    targetPlayer.get().sendMessage(
                        Component.empty()
                            .append(notifyMsg)
                            .append(acceptButton)
                            .append(denyButton)
                    )
                }
            }
            Profile.FriendResult.ACCEPTED -> {
                // Friend request accepted
                val acceptedMsg = yamlFactory.getMessageComponent("friend.add.now_friends",
                    "target" to targetProfile.username)
                actor.sendMessage(acceptedMsg)
                
                // Notify target player if online
                val targetPlayer = radium.server.getPlayer(targetProfile.uuid)
                if (targetPlayer.isPresent) {
                    val acceptedMsg = yamlFactory.getMessageComponent("friend.add.now_friends",
                        "target" to actor.username)
                    targetPlayer.get().sendMessage(acceptedMsg)
                }
            }
            Profile.FriendResult.ALREADY_FRIENDS -> {
                // Already friends
                val alreadyMsg = yamlFactory.getMessageComponent("friend.add.already_friends",
                    "target" to targetProfile.username)
                actor.sendMessage(alreadyMsg)
            }
            Profile.FriendResult.ALREADY_SENT -> {
                // Already sent request
                val alreadySentMsg = yamlFactory.getMessageComponent("friend.add.request_already_sent",
                    "target" to targetProfile.username)
                actor.sendMessage(alreadySentMsg)
            }
            else -> {
                // Error
                val errorMsg = yamlFactory.getMessageComponent("friend.add.error")
                actor.sendMessage(errorMsg)
            }
        }
    }

    @Subcommand("remove <target>")
    suspend fun friendRemove(actor: Player, target: String) {
        // Get profiles
        val actorProfile = radium.connectionHandler.getPlayerProfile(actor.uniqueId) ?: return
        val targetProfile = radium.connectionHandler.findPlayerProfile(target)
        
        // Check if target profile exists
        if (targetProfile == null) {
            val notFoundMsg = yamlFactory.getMessageComponent("friend.add.user_not_found", "target" to target)
            actor.sendMessage(notFoundMsg)
            return
        }
        
        // Try to remove friend
        when (actorProfile.removeFriend(targetProfile)) {
            Profile.FriendResult.REMOVED -> {
                // Friend removed
                val removedMsg = yamlFactory.getMessageComponent("friend.remove.removed",
                    "target" to targetProfile.username)
                actor.sendMessage(removedMsg)
                
                // Notify target player if online
                val targetPlayer = radium.server.getPlayer(targetProfile.uuid)
                if (targetPlayer.isPresent) {
                    val removedMsg = yamlFactory.getMessageComponent("friend.remove.removed",
                        "target" to actor.username)
                    targetPlayer.get().sendMessage(removedMsg)
                }
            }
            Profile.FriendResult.CANCELLED_OUTGOING -> {
                // Cancelled outgoing request
                val cancelledMsg = yamlFactory.getMessageComponent("friend.remove.request_cancelled",
                    "target" to targetProfile.username)
                actor.sendMessage(cancelledMsg)
            }
            Profile.FriendResult.CANCELLED_INCOMING -> {
                // Denied incoming request (handled by deny command, but just in case)
                val deniedMsg = yamlFactory.getMessageComponent("friend.deny.success",
                    "target" to targetProfile.username)
                actor.sendMessage(deniedMsg)
            }
            else -> {
                // Not friends
                val notFriendsMsg = yamlFactory.getMessageComponent("friend.remove.not_friends",
                    "target" to targetProfile.username)
                actor.sendMessage(notFriendsMsg)
            }
        }
    }

    @Subcommand("deny <target>")
    suspend fun friendDeny(actor: Player, target: String) {
        // Get profiles
        val actorProfile = radium.connectionHandler.getPlayerProfile(actor.uniqueId) ?: return
        val targetProfile = radium.connectionHandler.findPlayerProfile(target)
        
        // Check if target profile exists
        if (targetProfile == null) {
            val notFoundMsg = yamlFactory.getMessageComponent("friend.add.user_not_found", "target" to target)
            actor.sendMessage(notFoundMsg)
            return
        }
        
        // Check if there's an incoming request
        if (actorProfile.hasIncomingRequest(targetProfile.uuid)) {
            // Use the removeFriend method which properly handles both sides of the relationship
            actorProfile.removeFriend(targetProfile)

            val deniedMsg = yamlFactory.getMessageComponent("friend.deny.success",
                "target" to targetProfile.username)
            actor.sendMessage(deniedMsg)

        }
    }

    @Subcommand("list")
    suspend fun friendList(actor: Player) {
        val actorProfile = radium.connectionHandler.getPlayerProfile(actor.uniqueId) ?: return
        
        // Update friends last seen data
        actorProfile.updateFriendsLastSeenFromRedis(radium)
        
        // Get all friends
        val friends = actorProfile.getFriends()
        val header = yamlFactory.getMessageComponent("friend.list.header")

        actor.sendMessage(header)
        
        if (friends.isEmpty()) {
            val noneMsg = yamlFactory.getMessageComponent("friend.list.none")
            actor.sendMessage(noneMsg)
            return
        }
        
        // Build list of online and offline friends
        friends.forEach { friendId ->
            val friendProfile = radium.connectionHandler.findPlayerProfile(friendId.toString())
            if (friendProfile != null) {
                val friendOptional = radium.server.getPlayer(friendId)
                if (friendOptional.isPresent) {
                    // Friend is online
                    val server = friendOptional.get().currentServer
                    val serverName = if (server.isPresent) server.get().serverInfo.name else "Unknown"
                    
                    val onlineFormat = yamlFactory.getMessageComponent("friend.list.online_format",
                        "username" to friendProfile.username)
                    
                    val hoverText = yamlFactory.getMessage("friend.list.online_hover",
                        "server" to serverName)
                    
                    actor.sendMessage(
                        onlineFormat.hoverEvent(HoverEvent.showText(Component.text(hoverText)))
                    )
                } else {
                    // Friend is offline
                    val lastSeen = actorProfile.getFriendLastSeen(friendId) ?: Instant.EPOCH
                    
                    val formattedLastSeen = if (lastSeen == Instant.EPOCH) {
                        yamlFactory.getMessage("friend.list.lastseen_unknown")
                    } else {
                        formatTimeSince(lastSeen)
                    }
                    
                    val offlineFormat = yamlFactory.getMessageComponent("friend.list.offline_format",
                        "username" to friendProfile.username)
                    
                    val hoverText = yamlFactory.getMessage("friend.list.offline_hover",
                        "lastSeen" to formattedLastSeen)
                    
                    actor.sendMessage(
                        offlineFormat.hoverEvent(HoverEvent.showText(Component.text(hoverText)))
                    )
                }
            }
        }
    }

    @Subcommand("requests")
    suspend fun friendRequests(actor: Player, @Optional toggle: String?) {
        val actorProfile = radium.connectionHandler.getPlayerProfile(actor.uniqueId) ?: return
        
        // Check if toggling requests
        if (toggle != null && toggle.equals("toggle", ignoreCase = true)) {
            val currentSetting = actorProfile.getSetting("friendRequests")?.toBoolean() ?: true
            actorProfile.setSetting("friendRequests", (!currentSetting).toString())
            
            val toggleMsg = if (currentSetting) {
                yamlFactory.getMessageComponent("friend.requests.toggle_off")
            } else {
                yamlFactory.getMessageComponent("friend.requests.toggle_on")
            }
            
            actor.sendMessage(toggleMsg)
            return
        }
        
        // Show request list
        val headerMsg = yamlFactory.getMessageComponent("friend.requests.header")
        actor.sendMessage(headerMsg)
        
        val incomingRequests = actorProfile.getIncomingRequests()
        val outgoingRequests = actorProfile.getOutgoingRequests()
        
        if (incomingRequests.isEmpty() && outgoingRequests.isEmpty()) {
            val noneMsg = yamlFactory.getMessageComponent("friend.requests.none")
            actor.sendMessage(noneMsg)
        } else {
            // Show incoming requests
            if (incomingRequests.isNotEmpty()) {
                val incomingHeaderMsg = yamlFactory.getMessageComponent("friend.requests.incoming_header")
                actor.sendMessage(incomingHeaderMsg)
                
                incomingRequests.forEach { requesterId ->
                    val requesterProfile = radium.connectionHandler.findPlayerProfile(requesterId.toString())
                    if (requesterProfile != null) {
                        val username = requesterProfile.username
                        val requestFormat = yamlFactory.getMessageComponent("friend.requests.incoming_format",
                            "username" to username)
                        
                        // Create accept button
                        val acceptButton = Component.text(" ")
                            .append(yamlFactory.getMessageComponent("friend.requests.accept_button"))
                            .clickEvent(ClickEvent.runCommand("/friend add $username"))
                            .hoverEvent(HoverEvent.showText(
                                Component.text(yamlFactory.getMessage("friend.requests.accept_hover"))
                            ))
                        
                        // Create deny button
                        val denyButton = Component.text(" ")
                            .append(yamlFactory.getMessageComponent("friend.requests.deny_button"))
                            .clickEvent(ClickEvent.runCommand("/friend deny $username"))
                            .hoverEvent(HoverEvent.showText(
                                Component.text(yamlFactory.getMessage("friend.requests.deny_hover"))
                            ))
                        
                        actor.sendMessage(
                            Component.empty()
                                .append(requestFormat)
                                .append(acceptButton)
                                .append(denyButton)
                        )
                    }
                }
            }
            
            // Show outgoing requests
            if (outgoingRequests.isNotEmpty()) {
                val outgoingHeaderMsg = yamlFactory.getMessageComponent("friend.requests.outgoing_header")
                actor.sendMessage(outgoingHeaderMsg)
                
                outgoingRequests.forEach { targetId ->
                    val targetProfile = radium.connectionHandler.findPlayerProfile(targetId.toString())
                    if (targetProfile != null) {
                        val username = targetProfile.username
                        val requestFormat = yamlFactory.getMessageComponent("friend.requests.outgoing_format",
                            "username" to username)
                        
                        // Create cancel button
                        val cancelButton = Component.empty()
                            .append(yamlFactory.getMessageComponent("friend.requests.cancel_button"))
                            .clickEvent(ClickEvent.runCommand("/friend remove $username"))
                            .hoverEvent(HoverEvent.showText(
                                Component.text(yamlFactory.getMessage("friend.requests.cancel_hover"))
                            ))
                        
                        actor.sendMessage(
                            Component.empty()
                                .append(requestFormat)
                                .append(cancelButton)
                        )
                    }
                }
            }
            
            // Show toggle info
            val toggleInfoMsg = yamlFactory.getMessageComponent("friend.requests.toggle_info")
            actor.sendMessage(toggleInfoMsg)
        }
    }
    
    /**
     * Format a time instant as a "time ago" string
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
