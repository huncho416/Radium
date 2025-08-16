package radium.backend.commands

import com.velocitypowered.api.proxy.Player
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Optional
import revxrsal.commands.velocity.annotation.CommandPermission
import radium.backend.Radium
import radium.backend.annotations.OnlinePlayers
import java.io.IOException

@Command("gamemode")
@CommandPermission("radium.staff")
class Gamemode(private val radium: Radium) {

    // Main gamemode command with aliases
    @Command("gamemode <gamemode>", "gm <gamemode>")
    @CommandPermission("radium.gamemode.use")
    fun setGamemode(
        actor: Player,
        gamemode: String,
        @Optional @OnlinePlayers target: String?
    ) {
        val targetPlayer = if (target != null) {
            val foundPlayer = radium.server.getPlayer(target).orElse(null)
            if (foundPlayer == null) {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("general.player_not_found", "player" to target))
                return
            }
            foundPlayer
        } else {
            actor
        }

        // Check if target player has permission if setting someone else's gamemode
        if (target != null && target != actor.username) {
            if (!actor.hasPermission("radium.gamemode.others")) {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("general.no_permission"))
                return
            }
        }

        val normalizedGamemode = normalizeGamemode(gamemode)
        if (normalizedGamemode == null) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.gamemode.invalid_gamemode", "gamemode" to gamemode))
            return
        }

        // Get the server the target player is connected to
        val currentServer = targetPlayer.currentServer.orElse(null)
        if (currentServer == null) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.gamemode.not_connected"))
            return
        }

        // Execute the gamemode command on the backend server
        try {
            // Use HTTP API to communicate with the Minestom lobby
            val client = OkHttpClient()
            val json = """
                {
                    "type": "gamemode",
                    "target": "${targetPlayer.uniqueId}",
                    "targetName": "${targetPlayer.username}",
                    "gamemode": "$normalizedGamemode",
                    "executor": "${actor.username}",
                    "server": "${currentServer.serverInfo.name}"
                }
            """.trimIndent()
            
            // Get the lobby server URL - you'll need to configure this
            val lobbyUrl = radium.yamlFactory.getString("lobby.api.url", "http://localhost:8080")
            
            val requestBody = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$lobbyUrl/api/gamemode")
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .build()
            
            // Make async HTTP call
            radium.scope.launch {
                try {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            // Send confirmation message
                            if (target != null && target != actor.username) {
                                actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.gamemode.success_other", 
                                    "gamemode" to normalizedGamemode, 
                                    "target" to targetPlayer.username))
                            } else {
                                actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.gamemode.success_self", 
                                    "gamemode" to normalizedGamemode))
                            }
                        } else {
                            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.gamemode.redis_error"))
                            radium.logger.warn("Failed to set gamemode via API: ${response.code} - ${response.message}")
                        }
                    }
                } catch (e: IOException) {
                    actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.gamemode.redis_error"))
                    radium.logger.warn("Failed to communicate with lobby API: ${e.message}")
                }
            }
            
        } catch (e: Exception) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.gamemode.redis_error"))
            radium.logger.warn("Failed to execute gamemode command: ${e.message}")
        }
    }

    // Shortcut commands for specific gamemodes
    @Command("gms")
    @CommandPermission("radium.gamemode.survival")
    fun survival(actor: Player, @Optional @OnlinePlayers target: String?) {
        setGamemode(actor, "survival", target)
    }

    @Command("gmc")
    @CommandPermission("radium.gamemode.creative")
    fun creative(actor: Player, @Optional @OnlinePlayers target: String?) {
        setGamemode(actor, "creative", target)
    }

    // Numeric shortcuts
    @Command("gm0")
    @CommandPermission("radium.gamemode.survival")
    fun gm0(actor: Player, @Optional @OnlinePlayers target: String?) {
        setGamemode(actor, "0", target)
    }

    @Command("gm1")
    @CommandPermission("radium.gamemode.creative")
    fun gm1(actor: Player, @Optional @OnlinePlayers target: String?) {
        setGamemode(actor, "1", target)
    }

    @Command("gm2")
    @CommandPermission("radium.gamemode.adventure")
    fun gm2(actor: Player, @Optional @OnlinePlayers target: String?) {
        setGamemode(actor, "2", target)
    }

    @Command("gm3")
    @CommandPermission("radium.gamemode.spectator")
    fun gm3(actor: Player, @Optional @OnlinePlayers target: String?) {
        setGamemode(actor, "3", target)
    }

    /**
     * Normalize gamemode input to standard names
     * Supports numbers, short names, and full names
     */
    private fun normalizeGamemode(input: String): String? {
        return when (input.lowercase()) {
            "0", "s", "survival" -> "survival"
            "1", "c", "creative" -> "creative"
            "2", "a", "adventure" -> "adventure"
            "3", "sp", "spectator" -> "spectator"
            else -> null
        }
    }
}
