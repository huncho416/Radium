package radium.backend.punishment.commands

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Subcommand
import revxrsal.commands.velocity.annotation.CommandPermission
import radium.backend.Radium

/**
 * Command for monitoring the enhanced punishment system
 * Provides statistics and queue management functionality
 * Follows the pattern established by other command classes
 */
@Command("punishmentadmin", "padmin")
@CommandPermission("radium.punishment.admin")
class PunishmentAdmin(private val radium: Radium) {

    @Command("punishmentadmin", "padmin")
    @CommandPermission("radium.punishment.admin")
    fun usage(actor: Player) {
        actor.sendMessage(radium.yamlFactory.getMessageComponent("admin.header"))
        actor.sendMessage(radium.yamlFactory.getMessageComponent("admin.usage.stats"))
        actor.sendMessage(radium.yamlFactory.getMessageComponent("admin.usage.queue"))
        actor.sendMessage(radium.yamlFactory.getMessageComponent("admin.usage.cache"))
        actor.sendMessage(radium.yamlFactory.getMessageComponent("admin.usage.cleanup"))
    }

    @Subcommand("stats")
    @CommandPermission("radium.admin.stats")
    suspend fun showStatistics(actor: Player) {
        try {
            val stats = radium.punishmentManager.getStatistics()
            
            val component = Component.text()
                .append(radium.yamlFactory.getMessageComponent("admin.stats.header"))
                .appendNewline()
                .append(radium.yamlFactory.getMessageComponent("admin.stats.total", 
                    "count" to stats.totalPunishments.toString()))
                .appendNewline()
                .append(radium.yamlFactory.getMessageComponent("admin.stats.active", 
                    "count" to stats.activePunishments.toString()))
                .appendNewline()
                .append(radium.yamlFactory.getMessageComponent("admin.stats.queue_size", 
                    "size" to stats.queueSize.toString()))
                .appendNewline()
                .append(radium.yamlFactory.getMessageComponent("admin.stats.processed", 
                    "count" to stats.processedCount.toString()))
                .appendNewline()
                .append(radium.yamlFactory.getMessageComponent("admin.stats.failed", 
                    "count" to stats.failedCount.toString()))
                .appendNewline()
                .append(radium.yamlFactory.getMessageComponent("admin.stats.queue_status", 
                    "status" to if (stats.queueRunning) "Running" else "Stopped"))
                .appendNewline()

            // Show breakdown by type
            component.append(radium.yamlFactory.getMessageComponent("admin.stats.by_type"))
                .appendNewline()
            
            stats.punishmentsByType.entries.sortedByDescending { it.value }.forEach { (type, count) ->
                component.append(radium.yamlFactory.getMessageComponent("admin.stats.type_entry",
                    "type" to type.name,
                    "count" to count.toString()))
                .appendNewline()
            }
            
            actor.sendMessage(component.build())
        } catch (e: Exception) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("admin.error", 
                "message" to e.message.toString()))
        }
    }

    @Subcommand("queue")
    @CommandPermission("radium.admin.queue")
    suspend fun showQueueInfo(actor: Player) {
        try {
            val queue = radium.punishmentManager.queue
            if (queue == null) {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("admin.queue.disabled"))
                return
            }
            
            val queueStats = queue.getStatistics()
            
            val component = Component.text()
                .append(radium.yamlFactory.getMessageComponent("admin.queue.header"))
                .appendNewline()
                .append(radium.yamlFactory.getMessageComponent("admin.queue.size", 
                    "size" to queueStats.queueSize.toString()))
                .appendNewline()
                .append(radium.yamlFactory.getMessageComponent("admin.queue.processed", 
                    "count" to queueStats.processedCount.toString()))
                .appendNewline()
                .append(radium.yamlFactory.getMessageComponent("admin.queue.failed", 
                    "count" to queueStats.failedCount.toString()))
                .appendNewline()
                .append(radium.yamlFactory.getMessageComponent("admin.queue.status", 
                    "status" to if (queueStats.isRunning) "Running" else "Stopped"))
                .appendNewline()
            
            val successRate = if (queueStats.processedCount + queueStats.failedCount > 0) {
                (queueStats.processedCount.toDouble() / (queueStats.processedCount + queueStats.failedCount) * 100).toInt()
            } else {
                100
            }
            
            component.append(radium.yamlFactory.getMessageComponent("admin.queue.success_rate", 
                "rate" to "$successRate%"))
            
            actor.sendMessage(component.build())
        } catch (e: Exception) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("admin.error", 
                "message" to e.message.toString()))
        }
    }

    @Subcommand("cache clear <player>")
    @CommandPermission("radium.admin.cache")
    suspend fun clearPlayerCache(actor: Player, player: String) {
        try {
            // Try to find player by name first
            val targetPlayer = radium.server.getPlayer(player).orElse(null)
            val playerId = if (targetPlayer != null) {
                targetPlayer.uniqueId.toString()
            } else {
                // Look up offline player
                val profile = radium.connectionHandler.findPlayerProfile(player)
                profile?.uuid?.toString()
            }
            
            if (playerId == null) {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("admin.player_not_found", 
                    "player" to player))
                return
            }
            
            val cache = radium.punishmentManager.cache
            if (cache == null) {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("admin.cache.disabled"))
                return
            }
            
            cache.invalidatePlayerCache(playerId)
            actor.sendMessage(radium.yamlFactory.getMessageComponent("admin.cache.cleared", 
                "player" to player))
        } catch (e: Exception) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("admin.error", 
                "message" to e.message.toString()))
        }
    }

    @Subcommand("cleanup")
    @CommandPermission("radium.admin.cleanup")
    suspend fun runCleanup(actor: Player) {
        try {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("admin.cleanup.starting"))
            
            val cleaned = radium.punishmentManager.repository.cleanupExpiredPunishments()
            
            if (cleaned > 0) {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("admin.cleanup.success", 
                    "count" to cleaned.toString()))
            } else {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("admin.cleanup.none"))
            }
        } catch (e: Exception) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("admin.error", 
                "message" to e.message.toString()))
        }
    }

    @Subcommand("test performance")
    @CommandPermission("radium.admin.test")
    suspend fun testPerformance(actor: Player) {
        try {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("admin.test.starting"))
            
            val startTime = System.currentTimeMillis()
            
            // Test cache performance
            val testPlayerId = "test-performance-" + System.currentTimeMillis()
            val cachedPunishments = radium.punishmentManager.repository.findActivePunishments(testPlayerId)
            
            val cacheTime = System.currentTimeMillis() - startTime
            
            // Test queue statistics
            val queue = radium.punishmentManager.queue
            val queueStats = queue?.getStatistics()
            
            val component = Component.text()
                .append(radium.yamlFactory.getMessageComponent("admin.test.results"))
                .appendNewline()
                .append(radium.yamlFactory.getMessageComponent("admin.test.cache_time", 
                    "time" to "${cacheTime}ms"))
                .appendNewline()
                .append(radium.yamlFactory.getMessageComponent("admin.test.queue_size", 
                    "size" to (queueStats?.queueSize ?: 0).toString()))
                .appendNewline()
                .append(radium.yamlFactory.getMessageComponent("admin.test.queue_health", 
                    "health" to if (queueStats?.isRunning == true) "Healthy" else "Warning"))
            
            actor.sendMessage(component.build())
        } catch (e: Exception) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("admin.error", 
                "message" to e.message.toString()))
        }
    }
}
