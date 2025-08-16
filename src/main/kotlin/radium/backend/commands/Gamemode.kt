package radium.backend.commands

import com.velocitypowered.api.proxy.Player
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Optional
import revxrsal.commands.velocity.annotation.CommandPermission
import radium.backend.Radium
import radium.backend.annotations.OnlinePlayers

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
            // Use Redis to communicate with the Minestom backend
            val message = mapOf(
                "type" to "gamemode",
                "target" to targetPlayer.uniqueId.toString(),
                "targetName" to targetPlayer.username,
                "gamemode" to normalizedGamemode,
                "executor" to actor.username,
                "server" to currentServer.serverInfo.name
            ).entries.joinToString(",") { "${it.key}=${it.value}" }
            
            // Publish to Redis channel that Minestom backend listens to
            radium.lettuceCache.sync().publish("radium:gamemode:change", message)
            
            // Send confirmation message
            if (target != null && target != actor.username) {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.gamemode.set_other", 
                    "gamemode" to normalizedGamemode, 
                    "target" to targetPlayer.username))
            } else {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.gamemode.set_self", 
                    "gamemode" to normalizedGamemode))
            }
            
        } catch (e: Exception) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.gamemode.failed"))
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
