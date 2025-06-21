package twizzy.tech.mythLink.player.staff

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.PlayerChatEvent
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import twizzy.tech.mythLink.MythLink
import twizzy.tech.mythLink.util.YamlFactory
import java.util.UUID

class StaffManager(private val mythLink: MythLink) {

    private val yamlFactory = mythLink.yamlFactory

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
        val profile = mythLink.connectionHandler.getPlayerProfile(player.uniqueId)

        // Check if autoVanish is enabled (default to false if profile is null or setting is not found)
        val autoVanishEnabled = profile?.getSetting("autoVanish")?.toBoolean() ?: false

        // If autoVanish is enabled, add them to the vanish map
        if (autoVanishEnabled) {
            vanish(player)
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
    fun vanish(player: Player): Boolean {
        if (vanishedStaff.containsKey(player.username)) {
            return false // Player already vanished
        }

        vanishedStaff[player.username] = player
        return true
    }

    /**
     * Unvanishes a player, making them visible to other players again.
     * Returns true if the player was successfully unvanished, false if they weren't vanished.
     */
    fun unvanish(player: Player): Boolean {
        if (!vanishedStaff.containsKey(player.username)) {
            return false // Player not vanished
        }

        vanishedStaff.remove(player.username)
        return true
    }

    /**
     * Toggles a player's vanish status.
     * Returns true if the player is now vanished, false if they are now visible.
     */
    fun vanishToggle(player: Player): Boolean {
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
        mythLink.logger.info((staffPrefix.append(message)))
    }

    @Subscribe(priority = 100) // High priority to ensure this runs before other chat handlers
    fun staffChatListener(event: PlayerChatEvent) {
        val player = event.player
        val message = event.message
        val status = messageChannel[player.uniqueId]

        // Only process messages from players who are both in the listening channel and talking mode
        if (status?.isTalking == true) {
            event.result = PlayerChatEvent.ChatResult.denied()

            // Use the chat format from the lang.yml
            val chatFormat = yamlFactory.getMessageComponent("staff.chat_format",
                "player" to player.username,
                "message" to message
            )

            sendStaffMessage(chatFormat)
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
}