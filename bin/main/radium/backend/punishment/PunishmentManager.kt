package radium.backend.punishment

import com.velocitypowered.api.proxy.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import radium.backend.Radium
import radium.backend.punishment.models.Punishment
import radium.backend.punishment.models.PunishmentType
import radium.backend.util.DurationParser
import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import radium.backend.punishment.models.PunishmentRequest
import radium.backend.punishment.cache.PunishmentCache
import radium.backend.punishment.queue.PunishmentQueue

/**
 * Main service for punishment management
 * Follows the pattern established by RankManager and StaffManager
 */
class PunishmentManager(
    private val radium: Radium,
    val repository: PunishmentRepository,
    private val logger: ComponentLogger
) {

    // Enhanced asynchronous components (optional for backward compatibility)
    val cache: PunishmentCache? get() = _cache
    val queue: PunishmentQueue? get() = _queue
    private var _cache: PunishmentCache? = null
    private var _queue: PunishmentQueue? = null
    private val maintenanceRunning = AtomicBoolean(false)
    private val scope: CoroutineScope = radium.scope

    // Rate limiting for punishment commands
    private val rateLimiter = mutableMapOf<String, Long>()
    private val rateLimit = 1000L // 1 second between punishment commands

    /**
     * Initialize the punishment system with optional enhanced features
     */
    suspend fun initialize() {
        try {
            repository.initializeIndexes()
            
            // Initialize enhanced features
            try {
                _cache = PunishmentCache(radium.lettuceCache, logger)
                _queue = PunishmentQueue(repository, _cache!!, logger, scope)
                _queue?.start()
                startMaintenanceTasks()
                logger.info(Component.text("Enhanced punishment system initialized successfully", NamedTextColor.GREEN))
            } catch (e: Exception) {
                logger.warn(Component.text("Enhanced features failed to initialize, using basic mode: ${e.message}", NamedTextColor.YELLOW))
            }
            
            logger.info(Component.text("Punishment system initialized successfully", NamedTextColor.GREEN))
        } catch (e: Exception) {
            logger.error(Component.text("Failed to initialize punishment system: ${e.message}", NamedTextColor.RED))
        }
    }

    /**
     * Issue a new punishment with optional asynchronous processing
     */
    suspend fun issuePunishment(
        target: Player?,
        targetId: String,
        targetName: String,
        targetIp: String?,
        type: PunishmentType,
        reason: String,
        staff: Player,
        duration: String? = null,
        silent: Boolean = false,
        clearInventory: Boolean = false,
        priority: PunishmentRequest.Priority = PunishmentRequest.Priority.NORMAL
    ): Boolean {
        try {
            // Rate limiting check
            if (!checkRateLimit(staff)) {
                staff.sendMessage(radium.yamlFactory.getMessageComponent("punishments.rate_limit"))
                return false
            }

            // Validate target exists for applicable punishment types
            if (type.preventJoin && target == null) {
                // For offline bans, we still allow them
                logger.debug(Component.text("Issuing $type for offline player $targetName"))
            }

            // Check for existing active punishment of same type (except warnings)
            if (type != PunishmentType.WARN && type != PunishmentType.KICK) {
                val existing = repository.findActivePunishment(targetId, type)
                if (existing != null) {
                    staff.sendMessage(
                        radium.yamlFactory.getMessageComponent(
                            "punishments.already_punished",
                            "player" to targetName,
                            "type" to type.displayName.lowercase()
                        )
                    )
                    return false
                }
            }

            // Parse duration if provided
            val (durationMs, expiresAt) = if (duration != null && type.canHaveDuration) {
                val parsedDuration = DurationParser.parse(duration)
                if (parsedDuration == null) {
                    staff.sendMessage(
                        radium.yamlFactory.getMessageComponent("punishments.invalid_duration", "duration" to duration)
                    )
                    return false
                }
                Pair(parsedDuration.toMillis(), Instant.now().plusMillis(parsedDuration.toMillis()))
            } else {
                Pair(null, null)
            }

            // Create punishment record
            val punishment = Punishment(
                playerId = targetId,
                playerName = targetName,
                ip = targetIp,
                type = type,
                reason = reason,
                issuedBy = staff.uniqueId.toString(),
                issuedByName = staff.username,
                durationMs = durationMs,
                expiresAt = expiresAt,
                silent = silent,
                clearedInventory = clearInventory
            )

            // Save to database
            if (!repository.savePunishment(punishment)) {
                staff.sendMessage(
                    radium.yamlFactory.getMessageComponent("punishments.save_failed")
                )
                return false
            }

            // Update cache if available
            cache?.let { cacheInstance ->
                cacheInstance.invalidatePlayerCache(targetId)
                targetIp?.let { ip -> cacheInstance.invalidateIpCache(ip) }
                cacheInstance.cachePunishment(punishment)
                scope.launch {
                    cacheInstance.broadcastPunishmentUpdate(targetId, "created", punishment)
                }
            }

            // Handle immediate effects
            when (type) {
                PunishmentType.KICK -> {
                    target?.let { executeKick(it, reason) }
                }
                PunishmentType.BAN, PunishmentType.IP_BAN, PunishmentType.BLACKLIST -> {
                    target?.let { executeBan(it, punishment) }
                }
                PunishmentType.MUTE -> {
                    // Mute is handled by chat listener
                }
                PunishmentType.WARN -> {
                    target?.let {
                        it.sendMessage(
                            radium.yamlFactory.getMessageComponent(
                                "punishments.player.warn_notice",
                                "reason" to reason
                            )
                        )
                    }
                    // Check for warning escalation
                    checkWarningEscalation(targetId, targetName, staff)
                }
            }

            // Clear inventory if requested
            if (clearInventory && target != null) {
                clearPlayerInventory(target, staff)
            }

            // Send success message to staff
            val successMessageKey = if (silent) {
                "punishments.${type.name.lowercase()}.success_silent"
            } else {
                "punishments.${type.name.lowercase()}.success"
            }
            
            staff.sendMessage(
                radium.yamlFactory.getMessageComponent(
                    successMessageKey,
                    "target" to targetName,
                    "reason" to reason,
                    "duration" to (duration ?: "permanent")
                )
            )

            // Broadcast or notify staff
            if (silent) {
                notifyStaff(punishment, staff)
            } else {
                broadcastPunishment(punishment)
            }

            // Emit event for other systems
            // TODO: Implement event system integration

            return true
        } catch (e: Exception) {
            logger.error(Component.text("Failed to issue punishment: ${e.message}", NamedTextColor.RED))
            staff.sendMessage(
                radium.yamlFactory.getMessageComponent("punishments.error_occurred")
            )
            return false
        }
    }

    /**
     * Revoke a punishment (unban, unmute, etc.)
     */
    suspend fun revokePunishment(
        targetId: String,
        targetName: String,
        type: PunishmentType,
        reason: String,
        staff: Player,
        silent: Boolean = false
    ): Boolean {
        try {
            val activePunishment = repository.findActivePunishment(targetId, type)
            if (activePunishment == null) {
                staff.sendMessage(
                    radium.yamlFactory.getMessageComponent(
                        "punishments.no_active_punishment",
                        "player" to targetName,
                        "type" to type.displayName.lowercase()
                    )
                )
                return false
            }

            // Deactivate the punishment
            if (!repository.deactivatePunishment(activePunishment.id)) {
                staff.sendMessage(
                    radium.yamlFactory.getMessageComponent("punishments.revoke_failed")
                )
                return false
            }

            // Send success message to staff
            val successMessageKey = "punishments.un${type.name.lowercase()}.success"
            staff.sendMessage(
                radium.yamlFactory.getMessageComponent(
                    successMessageKey,
                    "target" to targetName
                )
            )

            // Broadcast or notify staff
            val broadcastMessageKey = "punishments.un${type.name.lowercase()}.broadcast"
            val message = radium.yamlFactory.getMessageComponent(
                broadcastMessageKey,
                "target" to targetName,
                "staff" to staff.username
            )

            if (silent) {
                notifyStaffRevocation(targetName, type, staff, reason)
            } else {
                radium.server.allPlayers.forEach { it.sendMessage(message) }
            }

            return true
        } catch (e: Exception) {
            logger.error(Component.text("Failed to revoke punishment: ${e.message}", NamedTextColor.RED))
            return false
        }
    }

    /**
     * Check if a player is banned
     */
    suspend fun isPlayerBanned(playerId: String): Punishment? {
        val banTypes = listOf(PunishmentType.BAN, PunishmentType.BLACKLIST)
        return repository.findActivePunishments(playerId)
            .find { it.type in banTypes && it.isCurrentlyActive() }
    }

    /**
     * Check if an IP is banned
     */
    suspend fun isIpBanned(ip: String): Punishment? {
        val banTypes = listOf(PunishmentType.IP_BAN, PunishmentType.BLACKLIST)
        return repository.findActivePunishmentsByIp(ip)
            .find { it.type in banTypes && it.isCurrentlyActive() }
    }

    /**
     * Check if a player is muted
     */
    suspend fun isPlayerMuted(playerId: String): Punishment? {
        return repository.findActivePunishment(playerId, PunishmentType.MUTE)
            ?.takeIf { it.isCurrentlyActive() }
    }

    /**
     * Get punishment history for a player
     */
    suspend fun getPunishmentHistory(playerId: String, page: Int = 0, pageSize: Int = 10): List<Punishment> {
        return repository.getPunishmentHistory(playerId, page, pageSize)
    }

    /**
     * Get alternative accounts by last known IP
     */
    suspend fun getAltsByLastIp(playerId: String): List<String> {
        // This would integrate with the existing Profile system to find players with same IP
        // For now, return empty list as placeholder
        return emptyList()
    }

    /**
     * Check for warning escalation
     */
    private suspend fun checkWarningEscalation(targetId: String, targetName: String, staff: Player) {
        val config = radium.yamlFactory.getConfig()
        val threshold = radium.yamlFactory.getInt("punishments.warn.threshold", 3)
        val escalateDuration = radium.yamlFactory.getString("punishments.warn.escalateToBanDuration", "7d")
        val deactivateWarns = radium.yamlFactory.getBoolean("punishments.warn.deactivateWarnsAfterEscalation", true)

        val warnCount = repository.countActiveWarnings(targetId)
        if (warnCount.toInt() >= threshold) {
            // Issue automatic ban
            val target = radium.server.getPlayer(UUID.fromString(targetId)).orElse(null)

            radium.scope.launch {
                issuePunishment(
                    target = target,
                    targetId = targetId,
                    targetName = targetName,
                    targetIp = null,
                    type = PunishmentType.BAN,
                    reason = "Automatic ban due to $threshold warnings",
                    staff = staff,
                    duration = escalateDuration,
                    silent = false,
                    clearInventory = false
                )

                if (deactivateWarns) {
                    repository.deactivateActiveWarnings(targetId)
                }
            }

            // Notify staff
            radium.staffManager.sendStaffMessage(
                radium.yamlFactory.getMessageComponent(
                    "punishments.warn_escalation",
                    "player" to targetName,
                    "warnings" to threshold.toString(),
                    "duration" to escalateDuration
                )
            )
        }
    }

    /**
     * Execute kick punishment
     */
    private fun executeKick(player: Player, reason: String) {
        val kickMessage = radium.yamlFactory.getMessageComponent(
            "punishments.player.kicked",
            "reason" to reason
        )
        player.disconnect(kickMessage)
    }

    /**
     * Execute ban punishment (disconnect player)
     */
    private fun executeBan(player: Player, punishment: Punishment) {
        val messageKey = when {
            punishment.type == PunishmentType.BLACKLIST -> "punishments.player.blacklisted"
            punishment.expiresAt != null -> "punishments.player.banned"
            else -> "punishments.player.banned_permanent"
        }

        val banMessage = if (punishment.expiresAt != null) {
            radium.yamlFactory.getMessageComponent(
                messageKey,
                "expires" to punishment.expiresAt.toString(),
                "reason" to punishment.reason
            )
        } else {
            radium.yamlFactory.getMessageComponent(
                messageKey,
                "reason" to punishment.reason
            )
        }

        player.disconnect(banMessage)
    }

    /**
     * Clear player inventory (placeholder - would integrate with existing inventory API)
     */
    private fun clearPlayerInventory(player: Player, staff: Player) {
        // This would integrate with the server's inventory clearing mechanism
        // For now, just log the action
        logger.info(Component.text("Clearing inventory for ${player.username} by ${staff.username}"))

        staff.sendMessage(
            radium.yamlFactory.getMessageComponent(
                "punishments.inventory_cleared",
                "player" to player.username
            )
        )
    }

    /**
     * Broadcast punishment to all players
     */
    private fun broadcastPunishment(punishment: Punishment) {
        val messageKey = "punishments.${punishment.type.name.lowercase()}.broadcast"

        val message = radium.yamlFactory.getMessageComponent(
            messageKey,
            "target" to punishment.playerName,
            "staff" to punishment.issuedByName,
            "reason" to punishment.reason
        )

        radium.server.allPlayers.forEach { it.sendMessage(message) }
    }

    /**
     * Notify staff about silent punishment
     */
    private fun notifyStaff(punishment: Punishment, staff: Player) {
        val messageKey = "punishments.${punishment.type.name.lowercase()}.broadcast"
        val silentSuffix = radium.yamlFactory.getMessageComponent("punishments.silent_suffix")

        val message = radium.yamlFactory.getMessageComponent(
            messageKey,
            "target" to punishment.playerName,
            "staff" to punishment.issuedByName,
            "reason" to punishment.reason
        ).append(Component.text(" ")).append(silentSuffix)

        radium.staffManager.sendStaffMessage(message)
    }

    /**
     * Notify staff about punishment revocation
     */
    private fun notifyStaffRevocation(playerName: String, type: PunishmentType, staff: Player, reason: String) {
        val message = radium.yamlFactory.getMessageComponent(
            "punishments.revoked_silent",
            "player" to playerName,
            "type" to type.displayName,
            "staff" to staff.username,
            "reason" to reason
        )

        radium.staffManager.sendStaffMessage(message)
    }

    /**
     * Process expired punishments (called by scheduler)
     */
    suspend fun processExpiredPunishments() {
        try {
            val expired = repository.findExpiredPunishments()
            if (expired.isNotEmpty()) {
                val ids = expired.map { it.id }
                repository.deactivatePunishments(ids)

                logger.info(Component.text("Processed ${expired.size} expired punishments"))
            }
        } catch (e: Exception) {
            logger.error(Component.text("Failed to process expired punishments: ${e.message}", NamedTextColor.RED))
        }
    }

    /**
     * Shutdown the punishment system gracefully
     */
    suspend fun shutdown() {
        try {
            logger.info(Component.text("Shutting down punishment system...", NamedTextColor.YELLOW))
            
            // Stop queue processing
            _queue?.stop()
            
            // Stop maintenance tasks
            maintenanceRunning.set(false)
            
            logger.info(Component.text("Punishment system shutdown complete", NamedTextColor.GREEN))
        } catch (e: Exception) {
            logger.error(Component.text("Error during punishment system shutdown: ${e.message}", NamedTextColor.RED))
        }
    }

    /**
     * Rate limiting for punishment commands
     */
    private fun checkRateLimit(staff: Player): Boolean {
        val now = System.currentTimeMillis()
        val lastUsed = rateLimiter[staff.uniqueId.toString()]
        
        if (lastUsed != null && now - lastUsed < rateLimit) {
            return false
        }
        
        rateLimiter[staff.uniqueId.toString()] = now
        return true
    }

    /**
     * Start background maintenance tasks
     */
    private fun startMaintenanceTasks() {
        if (maintenanceRunning.compareAndSet(false, true)) {
            // Cleanup expired punishments every 5 minutes
            radium.scope.launch {
                while (maintenanceRunning.get()) {
                    try {
                        delay(300000) // 5 minutes
                        val cleaned = repository.cleanupExpiredPunishments()
                        if (cleaned > 0) {
                            logger.info(Component.text("Maintenance: Cleaned up $cleaned expired punishments", NamedTextColor.GREEN))
                        }
                    } catch (e: Exception) {
                        logger.error(Component.text("Error in punishment cleanup: ${e.message}", NamedTextColor.RED))
                    }
                }
            }
            
            // Clean local cache every 2 minutes
            radium.scope.launch {
                while (maintenanceRunning.get()) {
                    try {
                        delay(120000) // 2 minutes
                        _cache?.cleanupLocalCache()
                    } catch (e: Exception) {
                        logger.error(Component.text("Error in cache cleanup: ${e.message}", NamedTextColor.RED))
                    }
                }
            }
            
            // Rate limiter cleanup every minute
            scope.launch {
                while (maintenanceRunning.get()) {
                    try {
                        delay(60000) // 1 minute
                        val now = System.currentTimeMillis()
                        rateLimiter.entries.removeIf { now - it.value > 300000 } // Remove entries older than 5 minutes
                    } catch (e: Exception) {
                        logger.error(Component.text("Error in rate limiter cleanup: ${e.message}", NamedTextColor.RED))
                    }
                }
            }
        }
    }

    /**
     * Get punishment statistics (enhanced version)
     */
    data class PunishmentSystemStatistics(
        val totalPunishments: Long,
        val activePunishments: Long,
        val punishmentsByType: Map<PunishmentType, Long>,
        val queueSize: Int,
        val processedCount: Long,
        val failedCount: Long,
        val queueRunning: Boolean
    )

    suspend fun getStatistics(): PunishmentSystemStatistics {
        val repoStats = repository.getPunishmentStatistics()
        val queueStats = _queue?.getStatistics()
        
        return PunishmentSystemStatistics(
            totalPunishments = repoStats.totalPunishments,
            activePunishments = repoStats.activePunishments,
            punishmentsByType = repoStats.punishmentsByType,
            queueSize = queueStats?.queueSize ?: 0,
            processedCount = queueStats?.processedCount ?: 0,
            failedCount = queueStats?.failedCount ?: 0,
            queueRunning = queueStats?.isRunning ?: false
        )
    }

    /**
     * Enhanced player profile lookup that handles both UUID and username inputs
     */
    private suspend fun findPlayerProfile(identifier: String): radium.backend.player.Profile? {
        return try {
            // Try UUID first
            val uuid = UUID.fromString(identifier)
            radium.connectionHandler.findPlayerProfile(uuid.toString())
        } catch (e: IllegalArgumentException) {
            // Try username
            radium.connectionHandler.findPlayerProfile(identifier)
        }
    }
    
    /**
     * Enhanced player lookup for punishment commands that logs attempts
     */
    suspend fun lookupPlayerForPunishment(identifier: String): Pair<radium.backend.player.Profile?, String> {
        try {
            logger.debug(Component.text("Looking up player: $identifier", NamedTextColor.YELLOW))
            
            val profile = findPlayerProfile(identifier)
            if (profile != null) {
                logger.debug(Component.text("Found player profile: ${profile.username} (${profile.uuid})", NamedTextColor.GREEN))
                return Pair(profile, profile.username)
            } else {
                logger.warn(Component.text("Player profile not found for: $identifier", NamedTextColor.RED))
                return Pair(null, identifier)
            }
        } catch (e: Exception) {
            logger.error(Component.text("Error looking up player $identifier: ${e.message}", NamedTextColor.RED))
            return Pair(null, identifier)
        }
    }
}
