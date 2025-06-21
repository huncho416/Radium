package twizzy.tech.mythLink.player

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.GameProfileRequestEvent
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent
import com.velocitypowered.api.event.player.configuration.PlayerConfigurationEvent
import com.velocitypowered.api.event.player.configuration.PlayerEnterConfigurationEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.util.GameProfile
import com.velocitypowered.api.util.UuidUtils
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import twizzy.tech.mythLink.MythLink
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class ConnectionHandler(private val mythLink: MythLink) {

    // In-memory cache for player profiles
    private val profileCache = ConcurrentHashMap<String, Profile>()

    @Subscribe
    fun onProxyConnect(event: PlayerConfigurationEvent) = runBlocking {
        // Get player information
        val player = event.player
        val uuid = player.uniqueId
        val username = player.username

        mythLink.logger.debug("Player $username ($uuid) connected, retrieving profile...")

        // Try to get player profile through the tiered caching system
        val (profile) = getOrCreatePlayerProfile(uuid, username)

        // Update the effective permissions cache for fast permission checks
        profile.updateEffectivePermissions(mythLink.rankManager)

        if (profile.hasEffectivePermission("clerk.staffchat")) {
            mythLink.staffManager.addStaff(player)
        }

        // Count online friends
        val onlineFriends = profile.getFriends().count { friendUuid ->
            mythLink.server.getPlayer(friendUuid).isPresent
        }

        // Send message if they have online friends
        if (onlineFriends > 0) {
            val friendWord = if (onlineFriends == 1) "friend" else "friends"

            val message = Component.text()
                .append(Component.text("👥 You have ", NamedTextColor.GREEN))
                .append(Component.text(onlineFriends.toString(), NamedTextColor.DARK_GREEN))
                .append(Component.text(" $friendWord playing right now!", NamedTextColor.GREEN))
                .build()

            player.sendMessage(message)
        }

        // Notify about incoming friend requests
        val incomingRequests = profile.getIncomingRequests().size
        if (incomingRequests > 0) {
            val requestWord = if (incomingRequests == 1) "request" else "requests"

            val message = Component.text()
                .append(Component.text("📨 You have ", NamedTextColor.YELLOW))
                .append(Component.text(incomingRequests.toString(), NamedTextColor.GOLD))
                .append(Component.text(" incoming friend $requestWord!", NamedTextColor.YELLOW))
                .build()

            player.sendMessage(message)
        }

        // Update friends' last seen data:
        // 1. Update this player's last seen time for all their friends
        updateFriendsLastSeenInfo(profile)
        // 2. Load this player's friends' last seen times from Redis
        profile.updateFriendsLastSeenFromRedis(mythLink)
    }

    /**
     * Updates the last seen time for a player in all their friends' profiles
     *
     * @param profile The profile of the player who just connected
     */
    private fun updateFriendsLastSeenInfo(profile: Profile) {
        val currentTime = Instant.now()
        val playerUuid = profile.uuid

        // For all online players who are friends with this player
        profileCache.values
            .filter { it.isFriend(playerUuid) }
            .forEach { friendProfile ->
                // Update their friendsLastSeen cache with this player's current time
                friendProfile.updateFriendLastSeen(playerUuid, currentTime, mythLink)
                mythLink.logger.debug("Updated last seen time for ${profile.username} in ${friendProfile.username}'s friend list")
            }

        // Also store the last seen times in Redis for offline friends
        // This ensures that when a friend logs in, they'll see the updated time
        val friends = profile.getFriends()
        for (friendUuid in friends) {
            mythLink.lettuceCache.cacheFriendLastSeen(friendUuid, playerUuid, currentTime.toEpochMilli())
        }
    }

    @Subscribe
    fun onProxyDisconnect(event: DisconnectEvent) {
        val player = event.player
        val uuid = player.uniqueId
        val username = player.username

        // Get the player's profile from cache
        val profile = profileCache[uuid.toString()]

        if (profile != null) {
            // Update the player's last seen time before storing
            profile.lastSeen = Instant.now()

            // Update this time in all their friends' Redis keys
            val currentTime = profile.lastSeen
            val friends = profile.getFriends()
            for (friendUuid in friends) {
                mythLink.lettuceCache.cacheFriendLastSeen(friendUuid, uuid, currentTime.toEpochMilli())
            }

            // Update Redis cache with the latest profile data before player disconnects
            mythLink.lettuceCache.cacheProfile(profile)

            // Remove from local cache
            profileCache.remove(uuid.toString())
        } else {
            mythLink.logger.info("No profile found for disconnecting player $username ($uuid)")
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
            mythLink.logger.info("Profile for $username ($uuid) found in local memory cache")
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

            mythLink.logger.info("Profile for $username ($uuid) found in Redis cache (TTL: ${ttl}s)")
            return Pair(profile, "redis")
        } else {
            mythLink.logger.debug("Profile for $username ($uuid) was not cached in Redis.")
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
            mythLink.logger.info("Cached MongoDB profile for $username ($uuid) in Redis")
            return Pair(profile, "mongodb")
        } else {
            mythLink.logger.debug("Profile for $username ($uuid) does not exists, creating new profile.")
        }

        // Step 4: Create new profile
        mythLink.logger.info("Creating new profile for $username ($uuid)")
        val newProfile = Profile(uuid, username)
        profileCache[uuid.toString()] = newProfile

        // Save to MongoDB
        mythLink.mongoStream.saveProfileToDatabase(newProfile)

        // Cache in Redis
        mythLink.lettuceCache.cacheProfile(newProfile)

        return Pair(newProfile, "created")
    }

    /**
     * Gets a player profile from only the in-memory cache.
     * This method is not suspendable and can be used in synchronous contexts
     * like permission checks.
     *
     * @param uuid The UUID of the player
     * @param username The username of the player (optional)
     * @return The player profile from cache, or null if not found in memory
     */
    fun getPlayerProfile(uuid: UUID, username: String? = null): Profile? {
        val uuidString = uuid.toString()

        // Only check the in-memory cache
        val profile = profileCache[uuidString]

        // If the profile exists and username is provided, ensure the username is up to date
        if (profile != null && username != null && profile.username != username) {
            profile.username = username
        }
        return profile
    }

    /**
     * Find a player profile by input which can be either a UUID or username.
     * If username is provided, it will be converted to UUID via Mojang API.
     *
     * @param input Either a UUID string or username
     * @return The player profile if found, null otherwise
     */
    suspend fun findPlayerProfile(input: String): Profile? {
        // Check if input is a UUID or username
        val uuid = try {
            // If it's already a valid UUID, use it directly
            UUID.fromString(input)
        } catch (e: IllegalArgumentException) {
            // Not a valid UUID, assume it's a username and convert via Mojang API
            val username = input
            mythLink.logger.debug("Input '$input' is not a valid UUID, attempting to convert username to UUID via Mojang API")

            // Try to get UUID from Mojang API
            fetchUuidFromMojang(username).also {
                if (it == null) {
                    mythLink.logger.debug("Could not find UUID for username '$username' via Mojang API")
                } else {
                    mythLink.logger.debug("Converted username '$username' to UUID: $it via Mojang API")
                }
            }
        } ?: return null // If UUID conversion failed, return null early

        val uuidString = uuid.toString()
        mythLink.logger.debug("Searching for profile with UUID: $uuidString")

        // Step 1: Check in-memory cache
        profileCache[uuidString]?.let { profile ->
            mythLink.logger.debug("Found profile for UUID $uuidString in memory cache")
            return profile
        }

        // Step 2: Check Redis cache
        val (redisProfile, redisMetadata) = mythLink.lettuceCache.getProfile(uuid)
        if (redisMetadata["found"] == true) {
            val profile = redisProfile!!
            // Add to local cache
            profileCache[uuidString] = profile
            mythLink.logger.debug("Found profile for UUID $uuidString in Redis cache")
            return profile
        }

        // Step 3: Check MongoDB
        val (mongoProfile, mongoMetadata) = mythLink.mongoStream.loadProfileFromDatabase(uuidString)
        if (mongoMetadata["found"] == true) {
            val profile = mongoProfile!!
            // Add to local cache
            profileCache[uuidString] = profile
            // Cache in Redis for faster future access
            mythLink.lettuceCache.cacheProfile(profile)
            mythLink.logger.debug("Found profile for UUID $uuidString in MongoDB")
            return profile
        }

        // Profile not found in any cache layer
        mythLink.logger.debug("No profile found for UUID $uuidString in any cache layer")
        return null
    }

    /**
     * Fetches a UUID from the Mojang API for the given username
     *
     * @param username The username to look up
     * @return The UUID if found, null otherwise
     */
    private fun fetchUuidFromMojang(username: String): UUID? {
        try {
            val url = "https://api.mojang.com/users/profiles/minecraft/$username"

            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            val responseCode = connection.responseCode

            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }

                // Check if response is empty or not valid JSON
                if (response.isBlank() || (!response.startsWith("{") && !response.endsWith("}"))) {
                    mythLink.logger.warn("Received invalid response from Mojang API for username $username")
                    return null
                }

                try {
                    // Extract UUID using regex
                    val idRegex = "\"id\"\\s*:\\s*\"([0-9a-f]+)\"".toRegex()
                    val matchResult = idRegex.find(response)

                    if (matchResult != null) {
                        val id = matchResult.groupValues[1]

                        // Use Velocity's UuidUtils to convert the undashed UUID to a proper UUID object
                        val uuid = UuidUtils.fromUndashed(id)
                        mythLink.logger.debug(
                            "Successfully retrieved UUID {} for username {} from Mojang API",
                            uuid,
                            username
                        )
                        return uuid
                    } else {
                        mythLink.logger.warn("Could not extract UUID from Mojang API response for username $username")
                        return null
                    }
                } catch (e: Exception) {
                    mythLink.logger.error("Failed to parse UUID from Mojang API response for username $username", e)
                    return null
                }
            } else if (responseCode == 204 || responseCode == 404) {
                // These response codes indicate the username doesn't exist
                mythLink.logger.debug("Username $username not found in Mojang API (HTTP $responseCode)")
                return null
            } else {
                mythLink.logger.warn("Failed to get UUID for username $username from Mojang API (HTTP $responseCode)")
                return null
            }
        } catch (e: Exception) {
            mythLink.logger.error("Error fetching UUID from Mojang API for $username: ${e.message}", e)
            return null
        }
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
            } catch (e: Exception) {
                errorCount++
                mythLink.logger.info("Failed to sync profile for ${profile.username} ($uuidString) to Redis: ${e.message}")
            }
        }

        if (syncCount > 0 || errorCount > 0) {
            mythLink.logger.info("Synchronized $syncCount profiles to Redis (Errors: $errorCount)")
        }
    }

    /**
     * Gets all cached profiles
     * @return Collection of all profiles in the cache
     */
    fun getAllProfiles(): Collection<Profile> {
        return profileCache.values
    }

}