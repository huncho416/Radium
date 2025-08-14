package radium.backend.player.staff

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.PlayerChatEvent
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import radium.backend.Radium
import radium.backend.util.YamlFactory
import java.util.UUID

class StaffManager(private val radium: Radium) {

    private val yamlFactory = radium.yamlFactory

    // Map of online staff: UUID -> Player
    private val onlineStaff: MutableMap<UUID, Player> = mutableMapOf()

    // Map of vanished staff: UUID -> Player
    val vanishedStaff: MutableMap<String, Player> = mutableMapOf()

    // Track player's channel status (talking and/or listening)
    data class ChannelStatus(val player: Player, var isListening: Boolean = false, var isTalking: Boolean = false)

    // Single map to manage staff channel status
    private val messageChannel: MutableMap<UUID, ChannelStatus> = mutableMapOf()

    /**
     * Adds a player to the online staff map.
     */
    suspend fun addStaff(player: Player) {
        // Use the configurable connection message from lang.yml
        val connectionMessage = yamlFactory.getMessageComponent("staff.connected", "player" to player.username)
        sendStaffMessage(connectionMessage)

        onlineStaff[player.uniqueId] = player
        // Automatically enable staff messaging for new staff members
        setListening(player, true)
        setTalking(player, true)

        // Check if autoVanish is enabled for this staff member
        // Get the player's profile from cache or database
        val profile = radium.connectionHandler.getPlayerProfile(player.uniqueId)

        // Check if autoVanish is enabled (default to false if profile is null or setting is not found)
        val autoVanishEnabled = profile?.getSetting("autoVanish")?.toBoolean() ?: false

        // If autoVanish is enabled, add them to the vanish map
        if (autoVanishEnabled) {
            GlobalScope.launch {
                vanish(player)
            }
            player.sendMessage(yamlFactory.getMessageComponent("staff.auto_vanish_enabled"))
        }
    }

    /**
     * Removes a player from the online staff map.
     */
    fun removeStaff(player: Player) {
        onlineStaff.remove(player.uniqueId)
        messageChannel.remove(player.uniqueId)
    }

    /**
     * Checks if a player is currently recognized as online staff.
     */
    fun isStaffOnline(player: Player): Boolean {
        return onlineStaff.containsKey(player.uniqueId)
    }

    /**
     * Returns a list of all currently online staff players.
     */
    fun getOnlineStaff(): List<Player> {
        return onlineStaff.values.toList()
    }

    /**
     * Sets a player's listening status for the staff channel.
     */
    fun setListening(player: Player, listening: Boolean) {
        val status = messageChannel.getOrPut(player.uniqueId) { ChannelStatus(player) }
        status.isListening = listening
    }

    /**
     * Sets a player's talking status for the staff channel.
     * Note: A player can only talk if they are also listening.
     */
    fun setTalking(player: Player, talking: Boolean) {
        val status = messageChannel.getOrPut(player.uniqueId) { ChannelStatus(player) }
        // Only allow talking if listening is enabled or we're turning talking off
        if (talking && !status.isListening) {
            setListening(player, true)  // Auto-enable listening when talking is enabled
        }
        status.isTalking = talking
    }

    /**
     * Checks if a player is currently listening to the staff channel.
     */
    fun isListening(player: Player): Boolean {
        return messageChannel[player.uniqueId]?.isListening == true
    }

    /**
     * Checks if a player is currently in talking mode for the staff channel.
     */
    fun isTalking(player: Player): Boolean {
        return messageChannel[player.uniqueId]?.isTalking == true
    }

    /**
     * Vanishes a player, making them invisible to other players.
     * Returns true if the player was successfully vanished, false if they were already vanished.
     */
    suspend fun vanish(player: Player): Boolean {
        if (vanishedStaff.containsKey(player.username)) {
            return false // Player already vanished
        }

        vanishedStaff[player.username] = player
        
        // Send vanish command to backend server for actual player visibility
        val currentServer = player.currentServer.orElse(null)
        if (currentServer != null) {
            try {
                // Execute vanish command on the backend server
                player.spoofChatInput("/vanish")
            } catch (e: Exception) {
                radium.logger.warn("Failed to execute vanish command on backend server for ${player.username}: ${e.message}")
            }
        }
        
        // Get the player's profile to check their rank weight
        val vanishedProfile = radium.connectionHandler.getPlayerProfile(player.uniqueId)
        val vanishedWeight = vanishedProfile?.getHighestRank(radium.rankManager)?.weight ?: 0
        
        // Hide the vanished player from tab list for players who shouldn't see them
        radium.server.allPlayers.forEach { onlinePlayer ->
            if (onlinePlayer.uniqueId != player.uniqueId) {
                val shouldHide = shouldHideFromPlayer(onlinePlayer, vanishedWeight)
                if (shouldHide) {
                    onlinePlayer.tabList.removeEntry(player.uniqueId)
                }
            }
        }
        
        // Player is now vanished - the API can be used to check vanish status if needed

        return true
    }

    /**
     * Unvanishes a player, making them visible to other players again.
     * Returns true if the player was successfully unvanished, false if they weren't vanished.
     */
    suspend fun unvanish(player: Player): Boolean {
        if (!vanishedStaff.containsKey(player.username)) {
            return false // Player not vanished
        }

        vanishedStaff.remove(player.username)
        
        // Send unvanish command to backend server for actual player visibility
        val currentServer = player.currentServer.orElse(null)
        if (currentServer != null) {
            try {
                // Execute vanish command again to toggle off on the backend server
                player.spoofChatInput("/vanish")
            } catch (e: Exception) {
                radium.logger.warn("Failed to execute unvanish command on backend server for ${player.username}: ${e.message}")
            }
        }
        
        // Show the player to all online players again by removing and re-adding them to tab list
        radium.server.allPlayers.forEach { onlinePlayer ->
            if (onlinePlayer.uniqueId != player.uniqueId) {
                // Remove and re-add to ensure visibility
                onlinePlayer.tabList.removeEntry(player.uniqueId)
                // The tab list entry will be automatically re-added by Velocity
            }
        }
        
        // Player is now unvanished - the API can be used to check vanish status if needed

        return true
    }

    /**
     * Determines if a vanished player should be hidden from another player
     * based on rank weight and permissions
     */
    private suspend fun shouldHideFromPlayer(viewer: Player, vanishedPlayerWeight: Int): Boolean {
        // Check if the viewer has the override permission to see all vanished players
        if (viewer.hasPermission("radium.vanish.see")) {
            return false
        }
        
        // Get the viewer's profile and rank weight
        val viewerProfile = radium.connectionHandler.getPlayerProfile(viewer.uniqueId)
        val viewerWeight = viewerProfile?.getHighestRank(radium.rankManager)?.weight ?: 0
        
        // Hide if the viewer's rank weight is lower than the vanished player's weight
        return viewerWeight < vanishedPlayerWeight
    }

    /**
     * Toggles a player's vanish status.
     * Returns true if the player is now vanished, false if they are now visible.
     */
    suspend fun vanishToggle(player: Player): Boolean {
        return if (isVanished(player)) {
            unvanish(player)
            false // Player is now visible
        } else {
            vanish(player)
            true // Player is now vanished
        }
    }

    /**
     * Checks if a player is currently vanished.
     */
    fun isVanished(player: Player): Boolean {
        return vanishedStaff.containsKey(player.username)
    }

    /**
     * Gets all currently vanished staff members.
     */
    fun getVanishedStaff(): List<Player> {
        return vanishedStaff.values.toList()
    }

    /**
     * Sends a message to all players listening to the staff channel and the console.
     */
    fun sendStaffMessage(message: Component) {
        val staffPrefix = yamlFactory.getMessageComponent("staff.prefix")
        // Send to all players listening to the staff channel
        messageChannel.values.filter { it.isListening }.forEach { status ->
            status.player.sendMessage(staffPrefix.append(message))
        }
        // Send to console
        radium.logger.info((staffPrefix.append(message)))
    }

    @Subscribe(priority = 1000) // Very high priority to run before other chat handlers
    fun staffChatListener(event: PlayerChatEvent) {
        val player = event.player
        val message = event.message
        val status = messageChannel[player.uniqueId]

        // Only process messages from players who are both in the listening channel and talking mode
        if (status?.isTalking == true) {
            radium.logger.info("[SC] ${player.username} → $message")
            
            // COMPLETELY DISABLED: Chat event modification for Minecraft 1.19.1+ compatibility
            // Any modification to PlayerChatEvent.result causes "illegal protocol state" errors
            // Staff chat messages will appear in both staff chat and public chat until a better solution is found
            radium.logger.warn("Staff chat detected but chat suppression disabled for 1.19.1+ compatibility")
            
            /* DISABLED: All chat event modification causes disconnects in 1.19.1+
            try {
                // First try denying the event completely
                event.result = PlayerChatEvent.ChatResult.denied()
            } catch (e: Exception) {
                try {
                    // If denied() doesn't work, try empty message
                    event.result = PlayerChatEvent.ChatResult.message("")
                } catch (e2: Exception) {
                    // If both approaches fail, log but continue with staff chat
                    radium.logger.warn("Could not suppress staff chat message due to signed chat restrictions. Message will appear in both staff and public chat.")
                }
            }
            */

            // Send to staff chat (this will work regardless of chat suppression)
            sendStaffChatMessage(player, message)
        }
    }
    
    /**
     * Sends a message to staff chat with proper formatting
     */
    private fun sendStaffChatMessage(player: Player, message: String) {
        // Launch coroutine to handle async profile lookup
        GlobalScope.launch {
            try {
                // Get player profile to access rank information
                val profile = radium.connectionHandler.findPlayerProfile(player.uniqueId.toString())
                if (profile != null) {
                    // Get highest rank for prefix and name color
                    val highestRank = profile.getHighestRank(radium.rankManager)
                    val prefix = highestRank?.prefix ?: ""
                    val chatColor = highestRank?.color ?: "&7"
                    
                    // Use the chat format from the lang.yml with proper formatting
                    val chatFormat = yamlFactory.getMessageComponent("staff.chat_format",
                        "prefix" to prefix,
                        "player" to player.username,
                        "chatColor" to chatColor,
                        "message" to message
                    )

                    sendStaffMessage(chatFormat)
                } else {
                    // Fallback if profile not found
                    val chatFormat = yamlFactory.getMessageComponent("staff.chat_format",
                        "prefix" to "",
                        "player" to player.username,
                        "chatColor" to "&7",
                        "message" to message
                    )
                    sendStaffMessage(chatFormat)
                }
            } catch (e: Exception) {
                radium.logger.warn("Failed to send staff chat message from ${player.username}: ${e.message}")
            }
        }
    }

    @Subscribe(priority = 100)
    fun staffServerChange(event: ServerPostConnectEvent) {
        val player = event.player
        val from = event.previousServer?.serverInfo
        val to = event.player.currentServer.get().serverInfo

        // Only handle if the player is listening to staff messages and switched servers
        if (isListening(player) && from != null) {
            val serverSwitchMessage = yamlFactory.getMessageComponent("staff.server_switch",
                "player" to player.username,
                "from" to from.name,
                "to" to to.name
            )
            sendStaffMessage(serverSwitchMessage)
        }
    }

    /**
     * Notifies a player with a message when their staff chat talking mode is enabled.
     */
    fun notifyStaffChatEnabled(player: Player) {
        player.sendMessage(yamlFactory.getMessageComponent("staff.chat_enabled"))
    }

    /**
     * Notifies a player with a message when their staff chat talking mode is disabled.
     */
    fun notifyStaffChatDisabled(player: Player) {
        player.sendMessage(yamlFactory.getMessageComponent("staff.chat_disabled"))
    }

    /**
     * Notifies a player with a message when their staff chat listening mode is enabled.
     */
    fun notifyStaffListeningEnabled(player: Player) {
        player.sendMessage(yamlFactory.getMessageComponent("staff.listening_disabled")) // Note: Message key seems reversed but matches the content
    }

    /**
     * Notifies a player with a message when their staff chat listening mode is disabled.
     */
    fun notifyStaffListeningDisabled(player: Player) {
        player.sendMessage(yamlFactory.getMessageComponent("staff.listening_enabled")) // Note: Message key seems reversed but matches the content
    }

    /**
     * Notifies a player with a message when they are vanished.
     */
    fun notifyVanishEnabled(player: Player) {
        player.sendMessage(yamlFactory.getMessageComponent("vanish.now_vanished"))
    }

    /**
     * Notifies a player with a message when they are unvanished.
     */
    fun notifyVanishDisabled(player: Player) {
        player.sendMessage(yamlFactory.getMessageComponent("vanish.now_visible"))
    }

    /**
     * Notifies a player with a message when auto-vanish is enabled.
     */
    fun notifyAutoVanishEnabled(player: Player) {
        player.sendMessage(yamlFactory.getMessageComponent("vanish.auto_enabled"))
    }

    /**
     * Notifies a player with a message when auto-vanish is disabled.
     */
    fun notifyAutoVanishDisabled(player: Player) {
        player.sendMessage(yamlFactory.getMessageComponent("vanish.auto_disabled"))
    }

    /**
     * Checks if a player can see another vanished player based on rank weight and permissions.
     * Higher rank weight staff can see lower rank vanished staff.
     * Players with radium.vanish.see permission can see all vanished players.
     */
    suspend fun canSeeVanishedPlayer(viewer: Player, vanishedPlayer: Player): Boolean {
        // If the viewer has the special permission to see all vanished players
        if (viewer.hasPermission("radium.vanish.see")) {
            return true
        }
        
        // If they're the same player, they can always see themselves
        if (viewer.uniqueId == vanishedPlayer.uniqueId) {
            return true
        }
        
        // Get both players' profiles to compare rank weights
        val viewerProfile = radium.connectionHandler.findPlayerProfile(viewer.uniqueId.toString())
        val vanishedProfile = radium.connectionHandler.findPlayerProfile(vanishedPlayer.uniqueId.toString())
        
        // If either profile is not found, default to not being able to see
        if (viewerProfile == null || vanishedProfile == null) {
            return false
        }
        
        // Get the highest rank for both players
        val viewerHighestRank = viewerProfile.getHighestRank(radium.rankManager)
        val vanishedHighestRank = vanishedProfile.getHighestRank(radium.rankManager)
        
        // If either player has no ranks, use default behavior
        val viewerWeight = viewerHighestRank?.weight ?: 0
        val vanishedWeight = vanishedHighestRank?.weight ?: 0
        
        // Viewer can see vanished player if their rank weight is higher or equal
        return viewerWeight >= vanishedWeight
    }
    
    /**
     * Gets all vanished players that a specific player can see based on rank weight.
     */
    suspend fun getVisibleVanishedPlayers(viewer: Player): List<Player> {
        val visibleVanished = mutableListOf<Player>()
        
        for (vanishedPlayer in vanishedStaff.values) {
            if (canSeeVanishedPlayer(viewer, vanishedPlayer)) {
                visibleVanished.add(vanishedPlayer)
            }
        }
        
        return visibleVanished
    }
}
