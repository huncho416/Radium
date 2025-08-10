package radium.backend.player

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.PlayerChatEvent
import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import radium.backend.Radium
import radium.backend.util.YamlFactory

class ChatManager(private val radium: Radium) {

    private val yamlFactory = radium.yamlFactory

    @Subscribe(priority = 50) // Lower priority than staff chat to run after it
    fun mainChatHandler(event: PlayerChatEvent) {
        val player = event.player
        val message = event.message

        // DISABLED: All chat modification disabled for 1.19.1+ compatibility
        // This prevents disconnections caused by modifying signed chat messages
        // Chat formatting is now handled by individual servers (e.g., MythicHub) using RadiumClient
        radium.logger.debug("Chat event received from ${player.username}: '$message' (formatting handled by backend servers)")
    }
}
