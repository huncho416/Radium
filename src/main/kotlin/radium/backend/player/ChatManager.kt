package radium.backend.player

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.PlayerChatEvent
import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import radium.backend.Radium
import radium.backend.util.YamlFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

class ChatManager(private val radium: Radium) {

    private val yamlFactory = radium.yamlFactory
    
    // Chat management state
    private var chatMuted = false
    private var chatSlowDelay = 0 // in seconds
    private val lastChatTimes = ConcurrentHashMap<UUID, Long>()

    @Subscribe(priority = 50) // Lower priority than staff chat to run after it
    fun mainChatHandler(event: PlayerChatEvent) {
        val player = event.player
        val message = event.message

        // Check for chat restrictions (only if player doesn't have bypass permission)
        if (!player.hasPermission("radium.chat.bypass")) {
            // Check if chat is muted
            if (chatMuted) {
                player.sendMessage(yamlFactory.getMessageComponent("chat.muted"))
                    // For signed messages in 1.19.1+, we can't cancel - just send error message
                if (event.result.isAllowed) {
                    event.result = PlayerChatEvent.ChatResult.message("")
                }
                return
            }
            
            // Check chat slow delay
            if (chatSlowDelay > 0) {
                val currentTime = System.currentTimeMillis()
                val lastChatTime = lastChatTimes[player.uniqueId] ?: 0L
                val timeDiff = (currentTime - lastChatTime) / 1000.0
                
                if (timeDiff < chatSlowDelay) {
                    val remainingTime = (chatSlowDelay - timeDiff).toInt() + 1
                    player.sendMessage(yamlFactory.getMessageComponent("chat.slow", "time" to remainingTime.toString()))
                    // For signed messages, replace with empty message instead of denying
                    if (event.result.isAllowed) {
                        event.result = PlayerChatEvent.ChatResult.message("")
                    }
                    return
                }
                
                // Update last chat time
                lastChatTimes[player.uniqueId] = currentTime
            }
        }

        // DISABLED: All chat modification disabled for 1.19.1+ compatibility
        // This prevents disconnections caused by modifying signed chat messages
        // Chat formatting is now handled by individual servers (e.g., MythicHub) using RadiumClient
        radium.logger.debug("Chat event received from ${player.username}: '$message' (formatting handled by backend servers)")
    }
    
    /**
     * Check if chat is currently muted
     */
    fun isChatMuted(): Boolean = chatMuted
    
    /**
     * Set chat muted state
     */
    fun setChatMuted(muted: Boolean) {
        this.chatMuted = muted
    }
    
    /**
     * Get current chat slow delay in seconds
     */
    fun getChatSlowDelay(): Int = chatSlowDelay
    
    /**
     * Set chat slow delay in seconds
     */
    fun setChatSlowDelay(delaySeconds: Int) {
        this.chatSlowDelay = delaySeconds
        // Clear existing chat times if disabling slow
        if (delaySeconds <= 0) {
            lastChatTimes.clear()
        }
    }
    
    /**
     * Remove a player's chat timing data (called when player disconnects)
     */
    fun removePlayerChatData(playerUuid: UUID) {
        lastChatTimes.remove(playerUuid)
    }
}
