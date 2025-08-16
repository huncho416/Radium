package radium.backend.nametag.commands

import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import radium.backend.Radium
import radium.backend.nametag.NametagAPI
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Description
import revxrsal.commands.annotation.Optional
import revxrsal.commands.annotation.Subcommand
import java.time.Duration
import java.util.*

/**
 * Commands for managing nametags
 */
@Command("nametag")
@Description("Manage player nametags")
class NametagCommands(private val radium: Radium) {
    
    @Subcommand("reload")
    @Description("Reload nametag configuration")
    suspend fun reload(sender: CommandSource) {
        // Check permission
        if (!sender.hasPermission("radium.nametag.reload")) {
            sender.sendMessage(
                Component.text("You don't have permission to use this command.", NamedTextColor.RED)
            )
            return
        }
        
        try {
            NametagAPI.reload()
            sender.sendMessage(
                Component.text("✅ Nametag configuration reloaded successfully!", NamedTextColor.GREEN)
            )
        } catch (e: Exception) {
            sender.sendMessage(
                Component.text("❌ Failed to reload nametag configuration: ${e.message}", NamedTextColor.RED)
            )
            radium.logger.error("Failed to reload nametag configuration", e)
        }
    }
    
    @Subcommand("preview")
    @Description("Preview a nametag template for a player")
    suspend fun preview(
        sender: CommandSource,
        target: String,
        template: String,
        @Optional duration: String?
    ) {
        // Check permission
        if (!sender.hasPermission("radium.nametag.preview")) {
            sender.sendMessage(
                Component.text("You don't have permission to use this command.", NamedTextColor.RED)
            )
            return
        }
        
        // Find target player
        val targetPlayer = radium.server.getPlayer(target).orElse(null)
        if (targetPlayer == null) {
            sender.sendMessage(
                Component.text("Player '$target' not found or not online.", NamedTextColor.RED)
            )
            return
        }
        
        // Parse duration (default 30 seconds)
        val ttl = try {
            if (duration != null) {
                Duration.ofSeconds(duration.toLong())
            } else {
                Duration.ofSeconds(30)
            }
        } catch (e: NumberFormatException) {
            sender.sendMessage(
                Component.text("Invalid duration: $duration. Using default 30 seconds.", NamedTextColor.YELLOW)
            )
            Duration.ofSeconds(30)
        }
        
        try {
            // Apply temporary template
            NametagAPI.setTemporaryTemplate(targetPlayer.uniqueId, template, ttl)
            
            val ttlSeconds = ttl.seconds
            sender.sendMessage(
                Component.text("✅ Applied preview template to ", NamedTextColor.GREEN)
                    .append(Component.text(targetPlayer.username, NamedTextColor.AQUA))
                    .append(Component.text(" for $ttlSeconds seconds.", NamedTextColor.GREEN))
            )
            
            // Notify the target player
            targetPlayer.sendMessage(
                Component.text("👁 Your nametag is being previewed by ", NamedTextColor.GRAY)
                    .append(Component.text(getSenderName(sender), NamedTextColor.YELLOW))
                    .append(Component.text(" for $ttlSeconds seconds.", NamedTextColor.GRAY))
            )
            
        } catch (e: Exception) {
            sender.sendMessage(
                Component.text("❌ Failed to apply preview template: ${e.message}", NamedTextColor.RED)
            )
            radium.logger.error("Failed to apply preview template", e)
        }
    }
    
    @Subcommand("refresh")
    @Description("Refresh nametags for a player or all players")
    fun refresh(
        sender: CommandSource,
        @Optional target: String?
    ) {
        // Check permission
        if (!sender.hasPermission("radium.nametag.refresh")) {
            sender.sendMessage(
                Component.text("You don't have permission to use this command.", NamedTextColor.RED)
            )
            return
        }
        
        try {
            if (target != null) {
                // Refresh specific player
                val targetPlayer = radium.server.getPlayer(target).orElse(null)
                if (targetPlayer == null) {
                    sender.sendMessage(
                        Component.text("Player '$target' not found or not online.", NamedTextColor.RED)
                    )
                    return
                }
                
                NametagAPI.refresh(targetPlayer.uniqueId)
                sender.sendMessage(
                    Component.text("✅ Refreshed nametag for ", NamedTextColor.GREEN)
                        .append(Component.text(targetPlayer.username, NamedTextColor.AQUA))
                )
            } else {
                // Refresh all players
                NametagAPI.refreshAll()
                val onlineCount = radium.server.allPlayers.size
                sender.sendMessage(
                    Component.text("✅ Refreshed nametags for all $onlineCount online players.", NamedTextColor.GREEN)
                )
            }
        } catch (e: Exception) {
            sender.sendMessage(
                Component.text("❌ Failed to refresh nametags: ${e.message}", NamedTextColor.RED)
            )
            radium.logger.error("Failed to refresh nametags", e)
        }
    }
    
    @Subcommand("info")
    @Description("Show nametag information for a player")
    suspend fun info(
        sender: CommandSource,
        @Optional target: String?
    ) {
        // Check permission
        if (!sender.hasPermission("radium.nametag.info")) {
            sender.sendMessage(
                Component.text("You don't have permission to use this command.", NamedTextColor.RED)
            )
            return
        }
        
        val targetName = target ?: if (sender is Player) sender.username else null
        if (targetName == null) {
            sender.sendMessage(
                Component.text("You must specify a player name when using this command from console.", NamedTextColor.RED)
            )
            return
        }
        
        val targetPlayer = radium.server.getPlayer(targetName).orElse(null)
        if (targetPlayer == null) {
            sender.sendMessage(
                Component.text("Player '$targetName' not found or not online.", NamedTextColor.RED)
            )
            return
        }
        
        val profile = radium.connectionHandler.getPlayerProfile(targetPlayer.uniqueId)
        if (profile == null) {
            sender.sendMessage(
                Component.text("No profile found for player $targetName.", NamedTextColor.RED)
            )
            return
        }
        
        val primaryRank = profile.getHighestRank(radium.rankManager)
        val isVanished = radium.staffManager.vanishedStaff.containsKey(targetPlayer.username)
        
        sender.sendMessage(
            Component.text("📋 Nametag Info for ", NamedTextColor.AQUA)
                .append(Component.text(targetPlayer.username, NamedTextColor.WHITE))
        )
        sender.sendMessage(
            Component.text("  Rank: ", NamedTextColor.GRAY)
                .append(Component.text(primaryRank?.name ?: "DEFAULT", NamedTextColor.YELLOW))
        )
        sender.sendMessage(
            Component.text("  Weight: ", NamedTextColor.GRAY)
                .append(Component.text(primaryRank?.weight?.toString() ?: "0", NamedTextColor.YELLOW))
        )
        sender.sendMessage(
            Component.text("  Vanished: ", NamedTextColor.GRAY)
                .append(Component.text(if (isVanished) "Yes" else "No", if (isVanished) NamedTextColor.RED else NamedTextColor.GREEN))
        )
        
        // Show template if available
        primaryRank?.nametagTemplate?.let { template ->
            sender.sendMessage(
                Component.text("  Custom Template: ", NamedTextColor.GRAY)
                    .append(Component.text(template, NamedTextColor.YELLOW))
            )
        }
    }
    
    /**
     * Gets the name of the command sender
     */
    private fun getSenderName(sender: CommandSource): String {
        return when (sender) {
            is Player -> sender.username
            else -> "Console"
        }
    }
}
