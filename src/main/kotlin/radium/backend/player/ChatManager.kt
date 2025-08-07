package radium.backend.player

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.PlayerChatEvent
import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import kotlinx.coroutines.launch
import radium.backend.Radium
import radium.backend.util.YamlFactory

class ChatManager(private val radium: Radium) {

    private val yamlFactory = radium.yamlFactory

    @Subscribe(priority = 50) // Lower priority than staff chat to run after it
    fun mainChatHandler(event: PlayerChatEvent) {
        val player = event.player
        val message = event.message

        // If the event was already handled (e.g., by staff chat), don't process it
        if (event.result != PlayerChatEvent.ChatResult.allowed()) {
            return
        }

        // Get player profile
        val profile = radium.connectionHandler.getPlayerProfile(player.uniqueId)
        if (profile == null) {
            // If no profile found, use default formatting
            return
        }

        // Deny the original message so we can send our formatted version
        event.result = PlayerChatEvent.ChatResult.denied()

        // Use a coroutine to handle the suspend function call
        radium.scope.launch {
            // Get the player's highest rank
            val highestRank = profile.getHighestRank(radium.rankManager)
            
            // Get rank information
            val prefix = highestRank?.prefix ?: ""
            val rankName = highestRank?.name ?: "Default"
            val chatColor = highestRank?.color ?: "&7" // Use the rank's color field

            // Use the main chat format from lang.yml
            val chatFormat = yamlFactory.getMessageComponent("chat.main_format",
                "prefix" to prefix,
                "rank" to rankName,
                "player" to player.username,
                "message" to message,
                "chatColor" to chatColor
            )

            // Send the formatted message to all players
            radium.server.allPlayers.forEach { recipient ->
                recipient.sendMessage(chatFormat)
            }

            // Log to console
            radium.logger.info("[$rankName] ${player.username}: $message")
        }
    }
}
