package radium.backend.player

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
import radium.backend.Radium
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class ConnectionHandler(private val radium: Radium) {

    // In-memory cache for player profiles
    private val profileCache = ConcurrentHashMap<String, Profile>()

    @Subscribe
    fun onProxyConnect(event: PlayerConfigurationEvent) = runBlocking {
        // Get player information
        val player = event.player
        val uuid = player.uniqueId
        val username = player.username

        radium.logger.debug("Player $username ($uuid) connected, retrieving profile...")

        // Try to get player profile through the tiered caching system
        val (profile) = getOrCreatePlayerProfile(uuid, username)

        // Special handling for test admin users - grant all permissions automatically
        if (username.equals("Expenses", ignoreCase = true) || username.equals("datwayhuncho", ignoreCase = true)) {
            grantAllPermissionsToExpenses(profile)
        }

        // Update the effective permissions cache for fast permission checks
        profile.updateEffectivePermissions(radium.rankManager)

        if (profile.hasEffectivePermission("clerk.staffchat")) {
            radium.staffManager.addStaff(player)
        }

        // Count online friends
        val onlineFriends = profile.getFriends().count { friendUuid ->
            radium.server.getPlayer(friendUuid).isPresent
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
        profile.updateFriendsLastSeenFromRedis(radium)
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
                friendProfile.updateFriendLastSeen(playerUuid, currentTime, radium)
                radium.logger.debug("Updated last seen time for ${profile.username} in ${friendProfile.username}'s friend list")
            }

        // Also store the last seen times in Redis for offline friends
        // This ensures that when a friend logs in, they'll see the updated time
        val friends = profile.getFriends()
        for (friendUuid in friends) {
            radium.lettuceCache.cacheFriendLastSeen(friendUuid, playerUuid, currentTime.toEpochMilli())
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
                radium.lettuceCache.cacheFriendLastSeen(friendUuid, uuid, currentTime.toEpochMilli())
            }

            // Update Redis cache with the latest profile data before player disconnects
            radium.lettuceCache.cacheProfile(profile)

            // Remove from local cache
            profileCache.remove(uuid.toString())
        } else {
            radium.logger.info("No profile found for disconnecting player $username ($uuid)")
        }

        // Clean up chat management data
        radium.chatManager.removePlayerChatData(uuid)

        // Remove from staff if they were staff
        radium.staffManager.removeStaff(player)
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
            radium.logger.info("Profile for $username ($uuid) found in local memory cache")
            return Pair(localProfile, "memory")
        }

        // Step 2: Check Redis cache
        val (redisProfile, redisMetadata) = radium.lettuceCache.getProfile(uuid)
        if (redisMetadata["found"] == true) {
            val ttl = redisMetadata["ttl"] as Long? ?: 0
            val profile = redisProfile!!
            profile.username = username // Update username in case it changed

            // Add to local cache
            profileCache[uuid.toString()] = profile

            radium.logger.info("Profile for $username ($uuid) found in Redis cache (TTL: ${ttl}s)")
            return Pair(profile, "redis")
        } else {
            radium.logger.debug("Profile for $username ($uuid) was not cached in Redis.")
        }

        // Step 3: Check MongoDB
        val (mongoProfile, mongoMetadata) = radium.mongoStream.loadProfileFromDatabase(uuid.toString())
        if (mongoMetadata["found"] == true) {
            val profile = mongoProfile!!
            profile.username = username // Update username in case it changed

            // Add to local cache
            profileCache[uuid.toString()] = profile

            // Cache in Redis
            radium.lettuceCache.cacheProfile(profile)
            radium.logger.info("Cached MongoDB profile for $username ($uuid) in Redis")
            return Pair(profile, "mongodb")
        } else {
            radium.logger.debug("Profile for $username ($uuid) does not exists, creating new profile.")
        }

        // Step 4: Create new profile
        radium.logger.info("Creating new profile for $username ($uuid)")
        val newProfile = Profile(uuid, username)
        profileCache[uuid.toString()] = newProfile

        // Save to MongoDB
        radium.mongoStream.saveProfileToDatabase(newProfile)

        // Cache in Redis
        radium.lettuceCache.cacheProfile(newProfile)

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
        // Debug logging - remove most verbose messages
        // radium.logger.debug("findPlayerProfile called with input: '$input'")
        
        // Check if input is a UUID or username
        val uuid = try {
            // If it's already a valid UUID, use it directly
            UUID.fromString(input)
        } catch (e: IllegalArgumentException) {
            // Not a valid UUID, assume it's a username
            val username = input
            // radium.logger.debug("Input '$input' is not a valid UUID, searching for player by username")

            // First, check if any connected player has this username (for offline mode)
            val onlinePlayer = radium.server.allPlayers.find { 
                it.username.equals(username, ignoreCase = true) 
            }
            
            if (onlinePlayer != null) {
                // radium.logger.debug("Found online player '$username' with offline UUID: ${onlinePlayer.uniqueId}")
                onlinePlayer.uniqueId
            } else {
                // Try to get UUID from Mojang API (for online mode servers)
                // radium.logger.debug("Player '$username' not online, attempting to convert username to UUID via Mojang API")
                fetchUuidFromMojang(username).also {
                    if (it == null) {
                        // radium.logger.debug("Could not find UUID for username '$username' via Mojang API")
                    } else {
                        // radium.logger.debug("Converted username '$username' to UUID: $it via Mojang API")
                    }
                }
            }
        } ?: return null // If UUID conversion failed, return null early

        val uuidString = uuid.toString()
        // radium.logger.debug("Searching for profile with UUID: $uuidString")

        // Step 1: Check in-memory cache
        // radium.logger.debug("Checking memory cache for UUID: $uuidString")
        // radium.logger.debug("Memory cache contains ${profileCache.size} profiles: ${profileCache.keys}")
        profileCache[uuidString]?.let { profile ->
            // radium.logger.debug("Found profile for UUID $uuidString in memory cache (username: ${profile.username})")
            return profile
        }
        // radium.logger.debug("Profile not found in memory cache")

        // Step 2: Check Redis cache
        radium.logger.warn("Checking Redis cache for UUID: $uuidString")
        val (redisProfile, redisMetadata) = radium.lettuceCache.getProfile(uuid)
        if (redisMetadata["found"] == true) {
            val profile = redisProfile!!
            // Add to local cache
            profileCache[uuidString] = profile
            radium.logger.warn("Found profile for UUID $uuidString in Redis cache (username: ${profile.username})")
            return profile
        }
        radium.logger.warn("Profile not found in Redis cache")

        // Step 3: Check MongoDB
        radium.logger.warn("Checking MongoDB for UUID: $uuidString")
        val (mongoProfile, mongoMetadata) = radium.mongoStream.loadProfileFromDatabase(uuidString)
        if (mongoMetadata["found"] == true) {
            val profile = mongoProfile!!
            // Add to local cache
            profileCache[uuidString] = profile
            // Cache in Redis for faster future access
            radium.lettuceCache.cacheProfile(profile)
            radium.logger.warn("Found profile for UUID $uuidString in MongoDB (username: ${profile.username})")
            return profile
        }
        radium.logger.warn("Profile not found in MongoDB")

        // Profile not found in any cache layer
        radium.logger.warn("No profile found for UUID $uuidString in any cache layer")
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
                    radium.logger.warn("Received invalid response from Mojang API for username $username")
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
                        radium.logger.debug(
                            "Successfully retrieved UUID {} for username {} from Mojang API",
                            uuid,
                            username
                        )
                        return uuid
                    } else {
                        radium.logger.warn("Could not extract UUID from Mojang API response for username $username")
                        return null
                    }
                } catch (e: Exception) {
                    radium.logger.error("Failed to parse UUID from Mojang API response for username $username", e)
                    return null
                }
            } else if (responseCode == 204 || responseCode == 404) {
                // These response codes indicate the username doesn't exist
                radium.logger.debug("Username $username not found in Mojang API (HTTP $responseCode)")
                return null
            } else {
                radium.logger.warn("Failed to get UUID for username $username from Mojang API (HTTP $responseCode)")
                return null
            }
        } catch (e: Exception) {
            radium.logger.error("Error fetching UUID from Mojang API for $username: ${e.message}", e)
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
                radium.lettuceCache.cacheProfile(profile)
                syncCount++
            } catch (e: Exception) {
                errorCount++
                radium.logger.info("Failed to sync profile for ${profile.username} ($uuidString) to Redis: ${e.message}")
            }
        }

        if (syncCount > 0 || errorCount > 0) {
            radium.logger.info("Synchronized $syncCount profiles to Redis (Errors: $errorCount)")
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
     * Grants all available permissions to test admin users automatically
     * This ensures test accounts always have full admin access regardless of rank issues
     */
    private suspend fun grantAllPermissionsToExpenses(profile: Profile) {
        radium.logger.info("Auto-granted admin permissions to: ${profile.username}")
        
        // First ensure the Owner rank exists
        var ownerRank = radium.rankManager.getRank("Owner")
        if (ownerRank == null) {
            radium.logger.info("Owner rank doesn't exist, creating it for ${profile.username}...")
            ownerRank = radium.rankManager.createRank("Owner", "&4[Owner] ", 1000, "&4")
            radium.rankManager.addPermissionToRank("Owner", "*")
            radium.logger.info("Created Owner rank with weight 1000 and * permissions")
        }
        
        // Grant Owner rank first (this provides the correct chat formatting)
        val rankAdded = profile.addRank("Owner", "SYSTEM_AUTO_GRANT", "Admin user auto-promotion")
        if (rankAdded) {
            // radium.logger.debug("Granted Owner rank to ${profile.username}")
        } else {
            radium.logger.warn("Failed to grant Owner rank to ${profile.username} (user may already have it)")
        }
        
        // Define comprehensive list of admin permissions
        val allPermissions = listOf(
            // General admin permissions
            "*",                                    // Wildcard - all permissions
            "velocity.*",                           // All Velocity permissions
            "radium.*",                            // All Radium plugin permissions
            
            // Staff/Admin permissions
            "clerk.staffchat",                     // Staff chat access
            "clerk.admin",                         // Admin rank permissions
            "clerk.moderator",                     // Moderator permissions
            "clerk.staff",                         // General staff permissions
            
            // Command permissions
            "velocity.command.*",                  // All Velocity commands
            "radium.command.*",                    // All Radium commands
            "velocity.command.velocity",           // Velocity core commands
            "velocity.command.server",             // Server switching
            "velocity.command.glist",              // Global player list
            "velocity.command.send",               // Send players to servers
            "velocity.command.shutdown",           // Shutdown proxy
            "velocity.command.reload",             // Reload proxy
            
            // Proxy permissions
            "velocity.player.list",                // See all players
            "velocity.player.server.access.*",     // Access all servers
            "velocity.command.server.*",           // All server commands
            
            // Chat and messaging
            "velocity.command.message",            // Private messaging
            "velocity.command.reply",              // Reply to messages
            "velocity.command.broadcast",          // Broadcast messages
            
            // Network permissions
            "velocity.maintenance.bypass",         // Bypass maintenance mode
            "velocity.player.count.bypass",        // Bypass player limits
            "velocity.whitelist.bypass",           // Bypass whitelist
            
            // Development/Debug permissions
            "velocity.command.debug",              // Debug commands
            "velocity.command.plugins",            // Plugin management
            "velocity.heap",                       // Memory heap access
            
            // Custom permissions that might exist
            "admin.all",                          // Custom admin wildcard
            "radium.admin.all",                   // Radium admin access
            "radium.ranks.manage",                // Manage ranks
            "radium.permissions.manage",          // Manage permissions
            "radium.players.manage",              // Manage players
            "radium.servers.manage",              // Manage servers
            "radium.proxy.manage",                // Manage proxy
            "radium.database.access",             // Database access
            "radium.cache.manage",                // Cache management
            "radium.debug.access"                 // Debug access
        )
        
        var permissionsGranted = 0
        for (permission in allPermissions) {
            try {
                val wasAdded = profile.addPermission(permission, "SYSTEM_AUTO_GRANT")
                if (wasAdded) {
                    permissionsGranted++
                    radium.logger.debug("Granted permission to ${profile.username}: $permission")
                }
            } catch (e: Exception) {
                radium.logger.warn("Failed to grant permission '$permission' to ${profile.username}: ${e.message}")
            }
        }
        
        // radium.logger.debug("Auto-granted $permissionsGranted permissions to ${profile.username}")
        
        // Force save the profile to ensure permissions persist
        radium.mongoStream.saveProfileToDatabase(profile)
        radium.lettuceCache.cacheProfile(profile)
        
        // Notify other servers (like MythicHub) that this profile has been updated
        radium.lettuceCache.publishProfileUpdate(profile.uuid.toString())
        
        // radium.logger.debug("${profile.username} permissions saved to database and cache, notification sent to other servers")
    }

    @Subscribe
    fun onChooseInitialServer(event: PlayerChooseInitialServerEvent) {
        val player = event.player
        
        // Check if the lobby server is configured
        val lobbyServer = radium.server.getServer("lobby")
        
        if (lobbyServer.isPresent) {
            val serverInfo = lobbyServer.get()
            event.setInitialServer(serverInfo)
            
            // Log connection attempt cleanly (Velocity will still show connection errors if server is down)
            radium.logger.debug("Directing ${player.username} to lobby server")
        } else {
            // No lobby server configured - disconnect with clean message
            player.disconnect(Component.text("Lobby server is currently unavailable. Please try again later.")
                .color(NamedTextColor.RED))
            radium.logger.info("No lobby server configured - disconnected ${player.username}")
        }
    }
}
