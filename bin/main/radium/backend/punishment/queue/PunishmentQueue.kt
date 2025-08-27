package radium.backend.punishment.queue

import kotlinx.coroutines.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import radium.backend.punishment.models.PunishmentRequest
import radium.backend.punishment.models.PunishmentBatch
import radium.backend.punishment.models.PunishmentOperation
import radium.backend.punishment.PunishmentRepository
import radium.backend.punishment.cache.PunishmentCache
import radium.backend.util.DurationParser
import java.time.Instant
import java.util.concurrent.BlockingQueue
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Asynchronous punishment processing queue for high-volume operations
 * Implements batch processing and priority handling for optimal performance
 * Follows the coroutine patterns established in the project
 */
class PunishmentQueue(
    private val repository: PunishmentRepository,
    private val cache: PunishmentCache,
    private val logger: ComponentLogger,
    private val scope: CoroutineScope
) {
    // Priority queue for punishment requests
    private val requestQueue: BlockingQueue<PunishmentRequest> = PriorityBlockingQueue(
        1000,
        compareByDescending<PunishmentRequest> { it.priority.level }
            .thenBy { it.requestedAt }
    )
    
    // Processing statistics
    private val processedCount = AtomicLong(0)
    private val failedCount = AtomicLong(0)
    private val isRunning = AtomicBoolean(false)
    
    // Configuration
    private val batchSize = 50
    private val processingIntervalMs = 100L
    private val maxRetries = 3
    
    // Background processing job
    private var processingJob: Job? = null

    /**
     * Start the punishment queue processor
     */
    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            processingJob = scope.launch {
                logger.info(Component.text("Starting punishment queue processor", NamedTextColor.GREEN))
                processQueue()
            }
        }
    }

    /**
     * Stop the punishment queue processor
     */
    suspend fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            logger.info(Component.text("Stopping punishment queue processor", NamedTextColor.YELLOW))
            processingJob?.cancelAndJoin()
            
            // Process remaining items
            if (requestQueue.isNotEmpty()) {
                logger.info(Component.text("Processing ${requestQueue.size} remaining items", NamedTextColor.YELLOW))
                processRemainingItems()
            }
        }
    }

    /**
     * Add a punishment request to the queue
     */
    suspend fun queuePunishment(request: PunishmentRequest): Boolean {
        return try {
            if (!isRunning.get()) {
                logger.warn(Component.text("Punishment queue is not running, processing immediately", NamedTextColor.YELLOW))
                return processImmediately(request)
            }
            
            val success = requestQueue.offer(request)
            if (success) {
                logger.debug(Component.text("Queued punishment request ${request.id} for ${request.targetName}"))
            } else {
                logger.warn(Component.text("Failed to queue punishment request - queue full", NamedTextColor.YELLOW))
                // Fallback to immediate processing
                return processImmediately(request)
            }
            success
        } catch (e: Exception) {
            logger.error(Component.text("Failed to queue punishment request: ${e.message}", NamedTextColor.RED))
            false
        }
    }

    /**
     * Get queue statistics
     */
    fun getStatistics(): QueueStatistics {
        return QueueStatistics(
            queueSize = requestQueue.size,
            processedCount = processedCount.get(),
            failedCount = failedCount.get(),
            isRunning = isRunning.get()
        )
    }

    /**
     * Main queue processing loop
     */
    private suspend fun processQueue() {
        while (isRunning.get()) {
            try {
                val batch = collectBatch()
                if (batch.isNotEmpty()) {
                    processBatch(batch)
                } else {
                    delay(processingIntervalMs)
                }
            } catch (e: CancellationException) {
                logger.info(Component.text("Punishment queue processing cancelled", NamedTextColor.YELLOW))
                break
            } catch (e: Exception) {
                logger.error(Component.text("Error in punishment queue processing: ${e.message}", NamedTextColor.RED))
                delay(processingIntervalMs * 2) // Back off on error
            }
        }
    }

    /**
     * Collect a batch of requests for processing
     */
    private fun collectBatch(): List<PunishmentRequest> {
        val batch = mutableListOf<PunishmentRequest>()
        
        // Get first item (blocking)
        val first = requestQueue.poll() ?: return emptyList()
        batch.add(first)
        
        // Get additional items up to batch size (non-blocking)
        repeat(batchSize - 1) {
            requestQueue.poll()?.let { batch.add(it) }
        }
        
        return batch
    }

    /**
     * Process a batch of punishment requests
     */
    private suspend fun processBatch(requests: List<PunishmentRequest>) {
        try {
            logger.debug(Component.text("Processing batch of ${requests.size} punishment requests"))
            
            val operations = mutableListOf<PunishmentOperation>()
            
            // Convert requests to operations
            for (request in requests) {
                try {
                    val (durationMs, expiresAt) = parseDuration(request.duration)
                    val punishment = request.toPunishment(durationMs, expiresAt)
                    operations.add(PunishmentOperation.Insert(punishment))
                } catch (e: Exception) {
                    logger.error(Component.text("Failed to process request ${request.id}: ${e.message}", NamedTextColor.RED))
                    failedCount.incrementAndGet()
                }
            }
            
            if (operations.isNotEmpty()) {
                val batch = PunishmentBatch(operations)
                val success = repository.processBatch(batch)
                
                if (success) {
                    processedCount.addAndGet(operations.size.toLong())
                    logger.debug(Component.text("Successfully processed batch of ${operations.size} operations"))
                    
                    // Update cache for processed punishments
                    updateCacheForBatch(operations)
                } else {
                    failedCount.addAndGet(operations.size.toLong())
                    logger.error(Component.text("Failed to process batch of ${operations.size} operations", NamedTextColor.RED))
                }
            }
        } catch (e: Exception) {
            logger.error(Component.text("Error processing punishment batch: ${e.message}", NamedTextColor.RED))
            failedCount.addAndGet(requests.size.toLong())
        }
    }

    /**
     * Process remaining items when shutting down
     */
    private suspend fun processRemainingItems() {
        val remaining = mutableListOf<PunishmentRequest>()
        
        // Drain all remaining items
        while (true) {
            val item = requestQueue.poll() ?: break
            remaining.add(item)
        }
        
        if (remaining.isNotEmpty()) {
            // Process in smaller batches to avoid overwhelming the system
            remaining.chunked(batchSize).forEach { chunk ->
                processBatch(chunk)
            }
        }
    }

    /**
     * Process a single request immediately (fallback)
     */
    private suspend fun processImmediately(request: PunishmentRequest): Boolean {
        return try {
            val (durationMs, expiresAt) = parseDuration(request.duration)
            val punishment = request.toPunishment(durationMs, expiresAt)
            
            val success = repository.savePunishment(punishment)
            if (success) {
                // Update cache
                cache.invalidatePlayerCache(request.targetId)
                request.targetIp?.let { cache.invalidateIpCache(it) }
                cache.cachePunishment(punishment)
                
                processedCount.incrementAndGet()
                logger.debug(Component.text("Immediately processed punishment for ${request.targetName}"))
            } else {
                failedCount.incrementAndGet()
            }
            success
        } catch (e: Exception) {
            logger.error(Component.text("Failed to immediately process punishment: ${e.message}", NamedTextColor.RED))
            failedCount.incrementAndGet()
            false
        }
    }

    /**
     * Update cache for a batch of processed operations
     */
    private suspend fun updateCacheForBatch(operations: List<PunishmentOperation>) {
        try {
            val insertOps = operations.filterIsInstance<PunishmentOperation.Insert>()
            
            // Group by player for efficient cache updates
            val playerGroups = insertOps.groupBy { it.punishment.playerId }
            
            for ((playerId, playerOps) in playerGroups) {
                // Invalidate cache to force refresh
                cache.invalidatePlayerCache(playerId)
                
                // Cache individual punishments
                playerOps.forEach { op ->
                    cache.cachePunishment(op.punishment)
                    
                    // Broadcast update
                    cache.broadcastPunishmentUpdate(playerId, "created", op.punishment)
                }
                
                // Invalidate IP cache if applicable
                playerOps.mapNotNull { it.punishment.ip }.distinct().forEach { ip ->
                    cache.invalidateIpCache(ip)
                }
            }
        } catch (e: Exception) {
            logger.warn(Component.text("Failed to update cache for batch: ${e.message}", NamedTextColor.YELLOW))
        }
    }

    /**
     * Parse duration string to milliseconds and expiration instant
     */
    private fun parseDuration(duration: String?): Pair<Long?, Instant?> {
        if (duration.isNullOrBlank() || duration.equals("permanent", ignoreCase = true)) {
            return Pair(null, null)
        }
        
        return try {
            val durationMs = DurationParser.parseToMillis(duration)
            if (durationMs != null) {
                val expiresAt = Instant.now().plusMillis(durationMs)
                Pair(durationMs, expiresAt)
            } else {
                logger.warn(Component.text("Failed to parse duration '$duration', treating as permanent", NamedTextColor.YELLOW))
                Pair(null, null)
            }
        } catch (e: Exception) {
            logger.warn(Component.text("Failed to parse duration '$duration', treating as permanent", NamedTextColor.YELLOW))
            Pair(null, null)
        }
    }

    /**
     * Statistics data class
     */
    data class QueueStatistics(
        val queueSize: Int,
        val processedCount: Long,
        val failedCount: Long,
        val isRunning: Boolean
    )
}
