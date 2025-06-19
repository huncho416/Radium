package twizzy.tech.mythLink.util

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

        logger.info(Component.text("Initializing Redis connection...", NamedTextColor.YELLOW))

        try {
            val config = loadRedisConfig()
            val host = config["host"] as String? ?: "localhost"
            val port = config["port"] as Int? ?: 6379
            val username = config["username"] as String? ?: null
            val password = config["password"] as String? ?: null

            logger.info(Component.text("Redis configuration loaded - Host: $host, Port: $port, Auth: ${username != null || password != null}", NamedTextColor.YELLOW))

            // Build Redis URI
            val redisURIBuilder = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withTimeout(Duration.ofSeconds(10))

            // Add authentication if provided
            if (!username.isNullOrEmpty() && !password.isNullOrEmpty()) {
                redisURIBuilder.withAuthentication(username, password)
                logger.debug("Using Redis authentication with username: $username")
            } else if (!password.isNullOrEmpty()) {
                redisURIBuilder.withPassword(password.toCharArray())
                logger.debug("Using Redis authentication with password only")
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

            logger.info(Component.text("Redis client initialized successfully", NamedTextColor.GREEN))
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
     * Loads the Redis configuration from plugins/MythLink/database.yml
     * @return Map of Redis configuration options
     */
    private fun loadRedisConfig(): Map<String, Any> {
        val configFile = File("plugins/MythLink/database.yml")

        if (!configFile.exists()) {
            logger.error(Component.text("Database configuration file not found at: ${configFile.absolutePath}", NamedTextColor.RED))
            throw FileNotFoundException("Database configuration file not found. Make sure to call YamlFactory.ensureDatabaseConfiguration() first.")
        }

        logger.debug("Loading Redis configuration from ${configFile.absolutePath}")
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
            logger.debug("Redis connection is null or not open")
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
    fun cacheProfile(profile: twizzy.tech.mythLink.player.Profile, expireSeconds: Long = 1800) {
        try {
            val key = "profile:${profile.uuid}"
            val jsonData = serializeProfile(profile)

            sync().set(key, jsonData)

            // Set expiration (default 30 minutes)
            sync().expire(key, expireSeconds)

            logger.debug(Component.text("Cached profile for ${profile.username} (${profile.uuid}) in Redis with TTL of ${expireSeconds}s", NamedTextColor.GREEN))
        } catch (e: Exception) {
            logger.error(Component.text("Failed to cache profile in Redis: ${e.message}", NamedTextColor.RED), e)
        }
    }

    /**
     * Retrieves a player profile from Redis
     * @param uuid The UUID of the player
     * @return A Pair containing the profile (or null if not found) and a map with metadata about the retrieval
     */
    fun getProfile(uuid: java.util.UUID): Pair<twizzy.tech.mythLink.player.Profile?, Map<String, Any?>> {
        try {
            val key = "profile:$uuid"

            // Get the profile data from Redis
            val jsonData = sync().get(key)

            if (jsonData == null) {
                return Pair(null, mapOf(
                    "found" to false,
                    "reason" to "not_in_redis"
                ))
            }

            // Get the TTL of the key to include in metadata
            val ttl = sync().ttl(key)
            val profile = deserializeProfile(jsonData)

            if (profile == null) {
                logger.warn(Component.text("Retrieved corrupted profile data from Redis for $uuid", NamedTextColor.YELLOW))
                return Pair(null, mapOf(
                    "found" to false,
                    "reason" to "corrupted_data",
                    "ttl" to ttl
                ))
            }

            return Pair(profile, mapOf(
                "found" to true,
                "ttl" to ttl,
                "source" to "redis"
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
            val key = "profile:$uuid"
            sync().del(key)
            logger.debug(Component.text("Removed profile for $uuid from Redis", NamedTextColor.GREEN))
        } catch (e: Exception) {
            logger.error(Component.text("Failed to remove profile from Redis: ${e.message}", NamedTextColor.RED), e)
        }
    }

    /**
     * Serializes a profile to JSON string
     * @param profile The profile to serialize
     * @return JSON representation of the profile
     */
    private fun serializeProfile(profile: twizzy.tech.mythLink.player.Profile): String {
        val data = mapOf(
            "uuid" to profile.uuid.toString(),
            "username" to profile.username,
            "permissions" to profile.getRawPermissions().toList(),
            "ranks" to profile.getRawRanks().toList()
        )

        return com.google.gson.Gson().toJson(data)
    }

    /**
     * Deserializes a profile from JSON string
     * @param json JSON representation of the profile
     * @return Deserialized Profile object
     */
    private fun deserializeProfile(json: String): twizzy.tech.mythLink.player.Profile? {
        try {
            val gson = com.google.gson.Gson()
            val data = gson.fromJson(json, Map::class.java)

            val uuid = java.util.UUID.fromString(data["uuid"] as String)
            val username = data["username"] as String
            val profile = twizzy.tech.mythLink.player.Profile(uuid, username)

            // Add permissions (can be simple permissions or permission|timestamp format)
            @Suppress("UNCHECKED_CAST")
            val permissions = data["permissions"] as List<String>? ?: emptyList()

            // Add all permissions directly as raw permission strings
            permissions.forEach { permString ->
                profile.addRawPermission(permString)
            }

            // Add ranks
            @Suppress("UNCHECKED_CAST")
            val ranks = data["ranks"] as List<String>? ?: emptyList()

            // Add all ranks directly as raw rank strings
            ranks.forEach { rankString ->
                profile.addRawRank(rankString)
            }

            return profile
        } catch (e: Exception) {
            logger.error(Component.text("Failed to deserialize profile: ${e.message}", NamedTextColor.RED), e)
            return null
        }
    }
}