package radium.backend.punishment.cache

import com.google.gson.Gson
import io.lettuce.core.api.async.RedisAsyncCommands
import kotlinx.coroutines.future.await
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import radium.backend.punishment.models.Punishment
import radium.backend.punishment.models.PunishmentType
import radium.backend.util.LettuceCache
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Redis-based caching system for active punishments
 * Provides high-performance lookups for punishment enforcement
 * Follows the pattern established by LettuceCache.kt
 */
class PunishmentCache(
    private val lettuceCache: LettuceCache,
    private val logger: ComponentLogger
) {
    private val gson = Gson()
    private val redis: RedisAsyncCommands<String, String> = lettuceCache.async()
    
    // Local cache for frequently accessed data (cache-aside pattern)
    private val localCache = ConcurrentHashMap<String, List<Punishment>>()
    private val localCacheExpiry = ConcurrentHashMap<String, Long>()
    
    companion object {
        private const val PUNISHMENT_PREFIX = "punishment:active:"
        private const val PLAYER_PUNISHMENT_PREFIX = "punishment:player:"
        private const val IP_PUNISHMENT_PREFIX = "punishment:ip:"
        private const val GLOBAL_PUNISHMENT_PREFIX = "punishment:global:"
        
        // Cache TTL settings
        private val DEFAULT_TTL = Duration.ofMinutes(5)
        private val LONG_TTL = Duration.ofMinutes(15)
        private val LOCAL_CACHE_TTL = Duration.ofMinutes(2).toMillis()
    }

    /**
     * Cache active punishments for a player
     */
    suspend fun cachePlayerPunishments(playerId: String, punishments: List<Punishment>) {
        try {
            val key = "$PLAYER_PUNISHMENT_PREFIX$playerId"
            val json = gson.toJson(punishments)
            
            redis.setex(key, DEFAULT_TTL.seconds, json).await()
            
            // Update local cache
            localCache[playerId] = punishments
            localCacheExpiry[playerId] = System.currentTimeMillis() + LOCAL_CACHE_TTL
            
            logger.debug(Component.text("Cached ${punishments.size} punishments for player $playerId"))
        } catch (e: Exception) {
            logger.warn(Component.text("Failed to cache punishments for player $playerId: ${e.message}", NamedTextColor.YELLOW))
        }
    }

    /**
     * Get cached active punishments for a player
     */
    suspend fun getPlayerPunishments(playerId: String): List<Punishment>? {
        try {
            // Check local cache first
            val localExpiry = localCacheExpiry[playerId]
            if (localExpiry != null && System.currentTimeMillis() < localExpiry) {
                localCache[playerId]?.let { 
                    logger.debug(Component.text("Retrieved punishments from local cache for player $playerId"))
                    return it 
                }
            }
            
            // Check Redis cache
            val key = "$PLAYER_PUNISHMENT_PREFIX$playerId"
            val json = redis.get(key).await() ?: return null
            
            val punishments = gson.fromJson(json, Array<Punishment>::class.java)?.toList() ?: emptyList()
            
            // Update local cache
            localCache[playerId] = punishments
            localCacheExpiry[playerId] = System.currentTimeMillis() + LOCAL_CACHE_TTL
            
            logger.debug(Component.text("Retrieved ${punishments.size} punishments from Redis for player $playerId"))
            return punishments
        } catch (e: Exception) {
            logger.warn(Component.text("Failed to get cached punishments for player $playerId: ${e.message}", NamedTextColor.YELLOW))
            return null
        }
    }

    /**
     * Cache IP-based punishments for quick lookup
     */
    suspend fun cacheIpPunishments(ip: String, punishments: List<Punishment>) {
        try {
            val key = "$IP_PUNISHMENT_PREFIX$ip"
            val json = gson.toJson(punishments)
            
            redis.setex(key, DEFAULT_TTL.seconds, json).await()
            logger.debug(Component.text("Cached ${punishments.size} IP punishments for $ip"))
        } catch (e: Exception) {
            logger.warn(Component.text("Failed to cache IP punishments for $ip: ${e.message}", NamedTextColor.YELLOW))
        }
    }

    /**
     * Get cached IP-based punishments
     */
    suspend fun getIpPunishments(ip: String): List<Punishment>? {
        try {
            val key = "$IP_PUNISHMENT_PREFIX$ip"
            val json = redis.get(key).await() ?: return null
            
            return gson.fromJson(json, Array<Punishment>::class.java)?.toList() ?: emptyList()
        } catch (e: Exception) {
            logger.warn(Component.text("Failed to get cached IP punishments for $ip: ${e.message}", NamedTextColor.YELLOW))
            return null
        }
    }

    /**
     * Cache a specific punishment by ID
     */
    suspend fun cachePunishment(punishment: Punishment) {
        try {
            val key = "$PUNISHMENT_PREFIX${punishment.id}"
            val json = gson.toJson(punishment)
            
            redis.setex(key, LONG_TTL.seconds, json).await()
            logger.debug(Component.text("Cached punishment ${punishment.id}"))
        } catch (e: Exception) {
            logger.warn(Component.text("Failed to cache punishment ${punishment.id}: ${e.message}", NamedTextColor.YELLOW))
        }
    }

    /**
     * Get a specific punishment by ID from cache
     */
    suspend fun getPunishment(punishmentId: String): Punishment? {
        try {
            val key = "$PUNISHMENT_PREFIX$punishmentId"
            val json = redis.get(key).await() ?: return null
            
            return gson.fromJson(json, Punishment::class.java)
        } catch (e: Exception) {
            logger.warn(Component.text("Failed to get cached punishment $punishmentId: ${e.message}", NamedTextColor.YELLOW))
            return null
        }
    }

    /**
     * Invalidate cached punishments for a player
     */
    suspend fun invalidatePlayerCache(playerId: String) {
        try {
            val key = "$PLAYER_PUNISHMENT_PREFIX$playerId"
            redis.del(key).await()
            
            // Clear local cache
            localCache.remove(playerId)
            localCacheExpiry.remove(playerId)
            
            logger.debug(Component.text("Invalidated punishment cache for player $playerId"))
        } catch (e: Exception) {
            logger.warn(Component.text("Failed to invalidate cache for player $playerId: ${e.message}", NamedTextColor.YELLOW))
        }
    }

    /**
     * Invalidate cached punishments for an IP
     */
    suspend fun invalidateIpCache(ip: String) {
        try {
            val key = "$IP_PUNISHMENT_PREFIX$ip"
            redis.del(key).await()
            logger.debug(Component.text("Invalidated punishment cache for IP $ip"))
        } catch (e: Exception) {
            logger.warn(Component.text("Failed to invalidate cache for IP $ip: ${e.message}", NamedTextColor.YELLOW))
        }
    }

    /**
     * Broadcast punishment updates to other proxy instances
     */
    suspend fun broadcastPunishmentUpdate(playerId: String, action: String, punishment: Punishment? = null) {
        try {
            val data = mapOf(
                "action" to action,
                "playerId" to playerId,
                "punishment" to punishment,
                "timestamp" to System.currentTimeMillis()
            )
            
            val json = gson.toJson(data)
            redis.publish("radium:punishment:update", json).await()
            
            logger.debug(Component.text("Broadcasted punishment update: $action for player $playerId"))
        } catch (e: Exception) {
            logger.warn(Component.text("Failed to broadcast punishment update: ${e.message}", NamedTextColor.YELLOW))
        }
    }

    /**
     * Check if a player has an active punishment of a specific type (fast lookup)
     */
    suspend fun hasActivePunishment(playerId: String, type: PunishmentType): Boolean {
        val punishments = getPlayerPunishments(playerId) ?: return false
        return punishments.any { it.type == type && it.isCurrentlyActive() }
    }

    /**
     * Clean up expired entries from local cache
     */
    fun cleanupLocalCache() {
        val now = System.currentTimeMillis()
        val expiredKeys = localCacheExpiry.entries
            .filter { now > it.value }
            .map { it.key }
        
        expiredKeys.forEach { key ->
            localCache.remove(key)
            localCacheExpiry.remove(key)
        }
        
        if (expiredKeys.isNotEmpty()) {
            logger.debug(Component.text("Cleaned up ${expiredKeys.size} expired local cache entries"))
        }
    }
}
