package radium.backend.util

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisConnectionException
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.api.reactive.RedisReactiveCommands
import io.lettuce.core.api.sync.RedisCommands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.time.Duration

/**
 * Redis cache implementation using Lettuce client
 * Provides access to Redis commands in synchronous, asynchronous, and reactive styles
 */
class LettuceCache(val logger: ComponentLogger) {
    private var client: RedisClient? = null
    private var connection: StatefulRedisConnection<String, String>? = null

    // Command interfaces for different programming styles
    private var syncCommands: RedisCommands<String, String>? = null
    private var asyncCommands: RedisAsyncCommands<String, String>? = null
    private var reactiveCommands: RedisReactiveCommands<String, String>? = null

    /**
     * Connects to Redis using credentials from database.yml
     * @return The Redis connection
     */
    fun connect(): StatefulRedisConnection<String, String> {
        if (connection != null && connection!!.isOpen) {
            logger.info(Component.text("Reusing existing Redis connection", NamedTextColor.GREEN))
            return connection!!
        }

        try {
            val config = loadRedisConfig()
            val host = config["host"] as String? ?: "localhost"
            val port = config["port"] as Int? ?: 6379
            val username = config["username"] as String? ?: null
            val password = config["password"] as String? ?: null
            // Build Redis URI
            val redisURIBuilder = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withTimeout(Duration.ofSeconds(10))

            // Add authentication if provided
            if (!username.isNullOrEmpty() && !password.isNullOrEmpty()) {
                redisURIBuilder.withAuthentication(username, password)
            } else if (!password.isNullOrEmpty()) {
                redisURIBuilder.withPassword(password.toCharArray())
                logger.info("Using Redis authentication with password only")
            }

            val redisURI = redisURIBuilder.build()

            // Create client and connection
            logger.info(Component.text("Attempting to connect to Redis at $host:$port...", NamedTextColor.YELLOW))
            client = RedisClient.create(redisURI)
            connection = client!!.connect()

            // Initialize commands
            syncCommands = connection!!.sync()
            asyncCommands = connection!!.async()
            reactiveCommands = connection!!.reactive()

            // Verify connection is working with a simple PING command
            try {
                val pong = syncCommands!!.ping()
                if (pong == "PONG") {
                    logger.info(Component.text("Successfully connected to Redis server (received PONG)", NamedTextColor.GREEN))
                } else {
                    logger.warn(Component.text("Redis connection established but received unexpected response to PING: $pong", NamedTextColor.YELLOW))
                }
            } catch (e: Exception) {
                logger.error(Component.text("Failed to ping Redis server. Connection may be unstable.", NamedTextColor.RED), e)
            }

            return connection!!

        } catch (e: RedisConnectionException) {
            logger.error(Component.text("Failed to connect to Redis. Please check if Redis server is running.", NamedTextColor.RED), e)
            throw e
        } catch (e: Exception) {
            logger.error(Component.text("Unexpected error while connecting to Redis", NamedTextColor.RED), e)
            throw e
        }
    }

    /**
     * Closes the Redis connection and releases resources
     */
    fun close() {
        try {
            logger.info(Component.text("Closing Redis connection...", NamedTextColor.YELLOW))
            connection?.close()
            client?.shutdown()
            connection = null
            client = null
            syncCommands = null
            asyncCommands = null
            reactiveCommands = null
            logger.info(Component.text("Redis connection closed successfully", NamedTextColor.GREEN))
        } catch (e: Exception) {
            logger.error(Component.text("Error while closing Redis connection", NamedTextColor.RED), e)
        }
    }

    /**
     * Gets the Redis client for pub/sub connections
     * @return Redis client
     * @throws IllegalStateException if not connected to Redis
     */
    fun getRedisClient(): RedisClient {
        return client ?: throw IllegalStateException("Redis client is not available. Call connect() first.")
    }

    /**
     * Gets the synchronous Redis commands interface
     * @return Redis synchronous commands
     * @throws IllegalStateException if not connected to Redis
     */
    fun sync(): RedisCommands<String, String> {
        if (connection == null || !connection!!.isOpen) {
            logger.info(Component.text("Redis connection not established or closed, reconnecting...", NamedTextColor.YELLOW))
            connect()
        }
        return syncCommands!!
    }

    /**
     * Gets the asynchronous Redis commands interface
     * @return Redis asynchronous commands
     * @throws IllegalStateException if not connected to Redis
     */
    fun async(): RedisAsyncCommands<String, String> {
        if (connection == null || !connection!!.isOpen) {
            logger.info(Component.text("Redis connection not established or closed, reconnecting...", NamedTextColor.YELLOW))
            connect()
        }
        return asyncCommands!!
    }

    /**
     * Gets the reactive Redis commands interface
     * @return Redis reactive commands
     * @throws IllegalStateException if not connected to Redis
     */
    fun reactive(): RedisReactiveCommands<String, String> {
        if (connection == null || !connection!!.isOpen) {
            logger.info(Component.text("Redis connection not established or closed, reconnecting...", NamedTextColor.YELLOW))
            connect()
        }
        return reactiveCommands!!
    }

    /**
     * Loads the Redis configuration from plugins/Radium/database.yml
     * @return Map of Redis configuration options
     */
    private fun loadRedisConfig(): Map<String, Any> {
        val configFile = File("plugins/Radium/database.yml")

        if (!configFile.exists()) {
            logger.error(Component.text("Database configuration file not found at: ${configFile.absolutePath}", NamedTextColor.RED))
            throw FileNotFoundException("Database configuration file not found. Make sure to call YamlFactory.ensureDatabaseConfiguration() first.")
        }

        val yaml = Yaml()
        val config = yaml.load(FileInputStream(configFile)) as Map<String, Any>

        return config["redis"] as Map<String, Any>?
            ?: throw RuntimeException("Redis configuration not found in database.yml")
    }

    /**
     * Checks if the Redis connection is valid
     * @return true if connected and responsive, false otherwise
     */
    fun isConnectionValid(): Boolean {
        if (connection == null || !connection!!.isOpen) {
            logger.info("Redis connection is null or not open")
            return false
        }

        try {
            val pong = syncCommands!!.ping()
            return pong == "PONG"
        } catch (e: Exception) {
            logger.warn(Component.text("Failed to validate Redis connection: ${e.message}", NamedTextColor.YELLOW))
            return false
        }
    }

    /**
     * Stores a player profile in Redis
     * @param profile The profile to store
     * @param expireSeconds Optional expiration time in seconds, defaults to 30 minutes (1800 seconds)
     */
    fun cacheProfile(profile: radium.backend.player.Profile, expireSeconds: Long = 1800) {
        try {
            val jsonData = serializeProfile(profile)
            
            // Store under both key formats for compatibility
            val radiumKey = "profile:${profile.uuid}"        // Original Radium format
            val mythicKey = "radium:profile:${profile.uuid}"  // MythicHub expected format

            // Set both keys with the same data
            sync().set(radiumKey, jsonData)
            sync().set(mythicKey, jsonData)

            // Set expiration for both keys (default 30 minutes)
            sync().expire(radiumKey, expireSeconds)
            sync().expire(mythicKey, expireSeconds)

            // Reduce cache logging spam - only log for staff
            if (profile.hasPermission("radium.staff")) {
                logger.debug(Component.text("Cached staff profile for ${profile.username} in Redis with TTL of ${expireSeconds}s", NamedTextColor.GREEN))
            }
        } catch (e: Exception) {
            logger.error(Component.text("Failed to cache profile in Redis: ${e.message}", NamedTextColor.RED), e)
        }
    }

    /**
     * Retrieves a player profile from Redis
     * @param uuid The UUID of the player
     * @return A Pair containing the profile (or null if not found) and a map with metadata about the retrieval
     */
    fun getProfile(uuid: java.util.UUID): Pair<radium.backend.player.Profile?, Map<String, Any?>> {
        try {
            val radiumKey = "profile:$uuid"
            val mythicKey = "radium:profile:$uuid"

            // Try the primary Radium key first
            var jsonData = sync().get(radiumKey)
            var usedKey = radiumKey
            
            // If not found, try the MythicHub format
            if (jsonData == null) {
                jsonData = sync().get(mythicKey)
                usedKey = mythicKey
            }

            if (jsonData == null) {
                return Pair(null, mapOf(
                    "found" to false,
                    "reason" to "not_in_redis"
                ))
            }

            // Get the TTL of the key to include in metadata
            val ttl = sync().ttl(usedKey)
            val profile = deserializeProfile(jsonData)

            if (profile == null) {
                logger.warn(Component.text("Retrieved corrupted profile data from Redis for $uuid", NamedTextColor.YELLOW))
                return Pair(null, mapOf(
                    "found" to false,
                    "reason" to "corrupted_data",
                    "ttl" to ttl,
                    "key_used" to usedKey
                ))
            }

            return Pair(profile, mapOf(
                "found" to true,
                "ttl" to ttl,
                "source" to "redis",
                "key_used" to usedKey
            ))
        } catch (e: Exception) {
            logger.error(Component.text("Failed to retrieve profile from Redis: ${e.message}", NamedTextColor.RED), e)
            return Pair(null, mapOf(
                "found" to false,
                "reason" to "redis_error",
                "error" to e.message
            ))
        }
    }

    /**
     * Removes a player profile from Redis
     * @param uuid The UUID of the player
     */
    fun removeProfile(uuid: java.util.UUID) {
        try {
            val radiumKey = "profile:$uuid"
            val mythicKey = "radium:profile:$uuid"
            
            // Remove both key formats
            val deletedRadium = sync().del(radiumKey)
            val deletedMythic = sync().del(mythicKey)
            
            logger.info(Component.text("Removed profile for $uuid from Redis (radium key: $deletedRadium, mythic key: $deletedMythic)", NamedTextColor.GREEN))
        } catch (e: Exception) {
            logger.error(Component.text("Failed to remove profile from Redis: ${e.message}", NamedTextColor.RED), e)
        }
    }

    /**
     * Serializes a profile to JSON string
     * @param profile The profile to serialize
     * @return JSON representation of the profile
     */
    private fun serializeProfile(profile: radium.backend.player.Profile): String {
        // Use the profile's toMap method to get all profile data
        val data = profile.toMap()
        return com.google.gson.Gson().toJson(data)
    }

    /**
     * Deserializes a profile from JSON string
     * @param json JSON representation of the profile
     * @return Deserialized Profile object
     */
    private fun deserializeProfile(json: String): radium.backend.player.Profile? {
        try {
            val gson = com.google.gson.Gson()
            // Convert JSON to Map and use Profile's fromMap method
            @Suppress("UNCHECKED_CAST")
            val data = gson.fromJson(json, Map::class.java) as Map<String, Any>
            return radium.backend.player.Profile.fromMap(data)
        } catch (e: Exception) {
            logger.error(Component.text("Failed to deserialize profile: ${e.message}", NamedTextColor.RED), e)
            return null
        }
    }

    /**
     * Stores a friend's last seen timestamp in Redis
     * @param playerUuid The UUID of the player
     * @param friendUuid The UUID of the friend
     * @param lastSeen The last seen timestamp in milliseconds
     * @param expireSeconds Optional expiration time in seconds, defaults to 1 day (86400 seconds)
     */
    fun cacheFriendLastSeen(playerUuid: java.util.UUID, friendUuid: java.util.UUID, lastSeen: Long, expireSeconds: Long = 86400) {
        try {
            val key = "friend_lastseen:$playerUuid:$friendUuid"
            sync().set(key, lastSeen.toString())
            sync().expire(key, expireSeconds)

            logger.debug("Cached last seen time for friend $friendUuid of player $playerUuid in Redis")
        } catch (e: Exception) {
            logger.error(Component.text("Failed to cache friend's last seen time in Redis: ${e.message}", NamedTextColor.RED), e)
        }
    }

    /**
     * Retrieves all friends' last seen timestamps for a player from Redis
     * @param playerUuid The UUID of the player
     * @return A map of friend UUIDs to their last seen timestamps in milliseconds
     */
    fun getFriendsLastSeen(playerUuid: java.util.UUID): Map<java.util.UUID, java.time.Instant> {
        try {
            val pattern = "friend_lastseen:$playerUuid:*"
            val keys = sync().keys(pattern)

            val result = mutableMapOf<java.util.UUID, java.time.Instant>()

            for (key in keys) {
                try {
                    // Extract friend UUID from key (format: friend_lastseen:{playerUuid}:{friendUuid})
                    val friendUuid = java.util.UUID.fromString(key.split(":")[2])

                    // Get last seen timestamp
                    val timestamp = sync().get(key)?.toLong()
                    if (timestamp != null) {
                        result[friendUuid] = java.time.Instant.ofEpochMilli(timestamp)
                    }
                } catch (e: Exception) {
                    logger.debug("Skipping invalid friend last seen entry: $key, Error: ${e.message}")
                }
            }

            return result
        } catch (e: Exception) {
            logger.error(Component.text("Failed to retrieve friends' last seen times from Redis: ${e.message}", NamedTextColor.RED), e)
            return emptyMap()
        }
    }

    /**
     * Retrieves a specific friend's last seen timestamp from Redis
     * @param playerUuid The UUID of the player
     * @param friendUuid The UUID of the friend
     * @return The last seen timestamp as an Instant, or null if not found
     */
    fun getFriendLastSeen(playerUuid: java.util.UUID, friendUuid: java.util.UUID): java.time.Instant? {
        try {
            val key = "friend_lastseen:$playerUuid:$friendUuid"
            val timestamp = sync().get(key)?.toLong() ?: return null
            return java.time.Instant.ofEpochMilli(timestamp)
        } catch (e: Exception) {
            logger.error(Component.text("Failed to retrieve friend's last seen time from Redis: ${e.message}", NamedTextColor.RED), e)
            return null
        }
    }

    /**
     * Publishes a profile update notification to Redis
     * This notifies other servers (like MythicHub) that a profile has been updated and they should clear their cache
     *
     * @param playerUuid The UUID of the player whose profile was updated
     */
    fun publishProfileUpdate(playerUuid: String) {
        try {
            val channel = "radium:profile:updated"
            val message = playerUuid
            val result = sync().publish(channel, message)
            logger.debug("Published profile update notification for $playerUuid to channel $channel (subscribers: $result)")
        } catch (e: Exception) {
            logger.warn(Component.text("Failed to publish profile update notification for $playerUuid: ${e.message}", NamedTextColor.YELLOW), e)
        }
    }
}
