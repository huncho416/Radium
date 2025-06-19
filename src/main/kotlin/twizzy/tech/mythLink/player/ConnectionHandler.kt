package twizzy.tech.mythLink.player

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent
import kotlinx.coroutines.runBlocking
import twizzy.tech.mythLink.MythLink
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class ConnectionHandler(private val mythLink: MythLink) {

    // In-memory cache for player profiles
    private val profileCache = ConcurrentHashMap<String, Profile>()

    @Subscribe
    fun onProxyConnect(event: PlayerChooseInitialServerEvent) = runBlocking {
        // Get player information
        val player = event.player
        val uuid = player.uniqueId
        val username = player.username

        logInfo("Player $username ($uuid) connected, retrieving profile...")

        // Try to get player profile through the tiered caching system
        val (profile, source) = getOrCreatePlayerProfile(uuid, username)

        // Profile was retrieved or created successfully
        when (source) {
            "memory" -> logInfo("Using existing profile for $username ($uuid) from memory cache")
            "redis" -> logInfo("Retrieved profile for $username ($uuid) from Redis cache")
            "mongodb" -> logInfo("Retrieved profile for $username ($uuid) from MongoDB database")
            "created" -> logInfo("Created new profile for $username ($uuid)")
            else -> logWarn("Retrieved profile for $username ($uuid) from unknown source: $source")
        }
    }

    @Subscribe
    fun onProxyDisconnect(event: DisconnectEvent) = runBlocking {
        val player = event.player
        val uuid = player.uniqueId.toString()
        val username = player.username

        // Get the player's profile from cache
        val profile = profileCache[uuid]

        if (profile != null) {
            // Save profile to MongoDB
            mythLink.mongoStream.saveProfileToDatabase(profile)
            logDebug("Saved profile for $username ($uuid) to MongoDB")

            // Update Redis cache with the latest profile data before player disconnects
            mythLink.lettuceCache.cacheProfile(profile)
            logDebug("Updated profile for $username ($uuid) in Redis cache")

            // Remove from local cache
            profileCache.remove(uuid)
            logDebug("Removed profile for $username ($uuid) from local memory cache")
        } else {
            logWarn("No profile found for disconnecting player $username ($uuid)")
        }
    }

    /**
     * Gets or creates a player profile through tiered caching system
     *
     * @param uuid Player's UUID
     * @param username Player's username
     * @return Pair of (Profile, source)
     */
    private suspend fun getOrCreatePlayerProfile(uuid: UUID, username: String): Pair<Profile, String> {
        // Step 1: Check local memory cache
        val localProfile = profileCache[uuid.toString()]
        if (localProfile != null) {
            logDebug("Profile for $username ($uuid) found in local memory cache")
            return Pair(localProfile, "memory")
        }

        // Step 2: Check Redis cache
        val (redisProfile, redisMetadata) = mythLink.lettuceCache.getProfile(uuid)
        if (redisMetadata["found"] == true) {
            val ttl = redisMetadata["ttl"] as Long? ?: 0
            val profile = redisProfile!!
            profile.username = username // Update username in case it changed

            // Add to local cache
            profileCache[uuid.toString()] = profile

            logDebug("Profile for $username ($uuid) found in Redis cache (TTL: ${ttl}s)")
            return Pair(profile, "redis")
        } else {
            logDebug("Profile for $username ($uuid) not found in Redis. Reason: ${redisMetadata["reason"]}")
        }

        // Step 3: Check MongoDB
        val (mongoProfile, mongoMetadata) = mythLink.mongoStream.loadProfileFromDatabase(uuid.toString())
        if (mongoMetadata["found"] == true) {
            val profile = mongoProfile!!
            profile.username = username // Update username in case it changed

            // Add to local cache
            profileCache[uuid.toString()] = profile

            // Cache in Redis
            mythLink.lettuceCache.cacheProfile(profile)
            logDebug("Cached MongoDB profile for $username ($uuid) in Redis")

            logDebug("Profile for $username ($uuid) found in MongoDB database")
            return Pair(profile, "mongodb")
        } else {
            logDebug("Profile for $username ($uuid) not found in MongoDB. Reason: ${mongoMetadata["reason"]}")
        }

        // Step 4: Create new profile
        logInfo("Creating new profile for $username ($uuid)")
        val newProfile = Profile(uuid, username)
        profileCache[uuid.toString()] = newProfile

        // Save to MongoDB
        mythLink.mongoStream.saveProfileToDatabase(newProfile)
        logDebug("Saved new profile for $username ($uuid) to MongoDB")

        // Cache in Redis
        mythLink.lettuceCache.cacheProfile(newProfile)
        logDebug("Cached new profile for $username ($uuid) in Redis")

        return Pair(newProfile, "created")
    }

    /**
     * Gets a player profile from the cache, or retrieves it from Redis/MongoDB if not cached
     *
     * @param uuid The UUID of the player
     * @param username The username of the player (defaults to null)
     * @return The player profile, or null if not found
     */
    suspend fun getPlayerProfile(uuid: UUID, username: String? = null): Profile? {
        // Use helper method to get profile from tiered caching system
        val (profile, source) = getOrCreatePlayerProfile(uuid, username ?: "unknown") // Use the provided username if available
        logDebug("Retrieved profile for $uuid from $source")
        return profile
    }

    /**
     * Synchronizes all in-memory profiles with Redis
     */
    fun syncProfilesToRedis() {
        var syncCount = 0
        var errorCount = 0

        profileCache.forEach { (uuidString, profile) ->
            try {
                mythLink.lettuceCache.cacheProfile(profile)
                syncCount++
                logDebug("Synced profile for ${profile.username} ($uuidString) to Redis")
            } catch (e: Exception) {
                errorCount++
                logError("Failed to sync profile for ${profile.username} ($uuidString) to Redis: ${e.message}")
            }
        }

        if (syncCount > 0 || errorCount > 0) {
            logInfo("Synchronized $syncCount profiles to Redis (Errors: $errorCount)")
        }
    }

    /**
     * Gets all cached profiles
     * @return Collection of all profiles in the cache
     */
    fun getAllProfiles(): Collection<Profile> {
        return profileCache.values
    }

    /**
     * Get the number of profiles currently in the memory cache
     */
    fun getCachedProfileCount(): Int {
        return profileCache.size
    }

    // Centralized logging methods
    private fun logDebug(message: String) {
        mythLink.logger.debug("[Profiles] $message")
    }

    private fun logInfo(message: String) {
        mythLink.logger.info("[Profiles] $message")
    }

    private fun logWarn(message: String) {
        mythLink.logger.warn("[Profiles] $message")
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            mythLink.logger.error("[Profiles] $message", throwable)
        } else {
            mythLink.logger.error("[Profiles] $message")
        }
    }

    /**
     * Log a summary of the profile cache status
     */
    fun logCacheStatus() {
        logInfo("Cache Status: ${getCachedProfileCount()} profiles in memory")
    }
}