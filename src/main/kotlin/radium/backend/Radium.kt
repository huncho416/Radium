package radium.backend

import com.github.shynixn.mccoroutine.velocity.SuspendingPluginContainer
import com.github.shynixn.mccoroutine.velocity.scope
import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.permission.PermissionsSetupEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.permission.PermissionFunction
import com.velocitypowered.api.permission.PermissionProvider
import com.velocitypowered.api.permission.PermissionSubject
import com.velocitypowered.api.permission.Tristate
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.PluginContainer
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import revxrsal.commands.autocomplete.SuggestionProvider
import revxrsal.commands.velocity.VelocityLamp
import revxrsal.commands.velocity.VelocityVisitors.brigadier
import radium.backend.annotations.OnlinePlayers
import radium.backend.annotations.RankList
import radium.backend.annotations.ColorList
import radium.backend.commands.Friend
import radium.backend.commands.Gamemode
import radium.backend.commands.Grant
import radium.backend.commands.Message
import radium.backend.commands.Permission
import radium.backend.commands.Rank
import radium.backend.commands.Reload
import radium.backend.commands.Revoke
import radium.backend.commands.LastSeen
import radium.backend.commands.StaffChat
import radium.backend.commands.Vanish
import radium.backend.player.ConnectionHandler
import radium.backend.player.RankManager
import radium.backend.player.ChatManager
import radium.backend.player.TabListManager
import radium.backend.player.staff.StaffManager
import radium.backend.util.LettuceCache
import radium.backend.util.MongoStream
import radium.backend.util.YamlFactory

@Plugin(
    id = "radium", name = "Radium", version = BuildConstants.VERSION
)
class Radium @Inject constructor(
    val logger: ComponentLogger,
    val server: ProxyServer,
    val suspendingContainer: SuspendingPluginContainer,
    val pluginContainer: PluginContainer
    ) {
    val mcCoroutine: SuspendingPluginContainer
    val mongoStream = MongoStream(logger)
    val lettuceCache = LettuceCache(logger)
    val yamlFactory = YamlFactory()

    val rankManager = RankManager(mongoStream)

    val connectionHandler = ConnectionHandler(this)
    val staffManager = StaffManager(this)
    val chatManager = ChatManager(this)
    val tabListManager = TabListManager(this)
    private var syncTaskRunning = false

    // Sync intervals (in milliseconds)
    private val redisIntervalMs = 60000  // 1 minute = 60000
    private val mongoIntervalMs = 300000 // 5 minutes

    // Trackers for sync scheduling
    private var lastRedisSyncTime = System.currentTimeMillis()
    private var lastMongoSyncTime = System.currentTimeMillis()

    init {
        this.mcCoroutine = suspendingContainer
        suspendingContainer.initialize(this)

        logger.info(Component.text("Initializing Radium plugin..."))

        yamlFactory.initConfigurations()

        mongoStream.initializeDatabases()
        lettuceCache.connect()

        val lamp = VelocityLamp.builder(this, server)
            .suggestionProviders { providers ->

                providers.addProviderForAnnotation(OnlinePlayers::class.java) { onlinePlayers: OnlinePlayers ->
                    SuggestionProvider { _ ->
                        val onlinePlayers = server.allPlayers
                            .map { it.username }

                        // Return the filtered list of online player names
                        onlinePlayers
                    }
                }

                providers.addProviderForAnnotation(RankList::class.java) { onlinePlayers: RankList ->
                    SuggestionProvider { _ ->
                        val allRanks = rankManager.getCachedRanks()
                            .map { it.name }

                        // Return the filtered list of online player names
                        allRanks
                    }
                }

                providers.addProviderForAnnotation(ColorList::class.java) { colorList: ColorList ->
                    SuggestionProvider { _ ->
                        listOf("black", "dark_blue", "dark_green", "dark_aqua", "dark_red", 
                               "dark_purple", "gold", "gray", "dark_gray", "blue", 
                               "green", "aqua", "red", "light_purple", "yellow", "white")
                    }
                }
            }





            .build()
        lamp.register(Permission(this))
        lamp.register(Rank(this))
        lamp.register(Grant(this))
        lamp.register(Revoke(this))
        lamp.register(Message(this))
        lamp.register(Reload(this))
        lamp.register(StaffChat(this))
        lamp.register(LastSeen(this))
        lamp.register(Vanish(this))
        lamp.register(Friend(this))
        lamp.register(Gamemode(this))

        lamp.register()
        lamp.accept(brigadier(server))
    }

    val scope by lazy { pluginContainer.scope }


    @Subscribe
    fun permissionSetup(event: PermissionsSetupEvent) {
        event.provider = object : PermissionProvider {
            override fun createFunction(subject: PermissionSubject): PermissionFunction {
                return PermissionFunction { permission ->
                    if (subject is Player) {
                        val uuid = subject.uniqueId
                        val username = subject.username

                        // Use the in-memory cached profile with pre-calculated permissions
                        val profile = connectionHandler.getPlayerProfile(uuid, username)
                        if (profile != null) {
                            // Use the fast effectivePermissions cache to check permission
                            if (profile.hasEffectivePermission(permission)) {
                                return@PermissionFunction Tristate.TRUE
                            } else {
                                return@PermissionFunction Tristate.FALSE
                            }
                        } else {
                            // No profile found, default to no permission
                            return@PermissionFunction Tristate.FALSE
                        }
                    } else {
                        return@PermissionFunction Tristate.UNDEFINED
                    }
                }
            }
        }
    }

    /**
     * Helper method to check if a permission exists in a set of permissions,
     * including wildcard matching
     *
     * @param rankPermissions Set of permissions to check against
     * @param permission The specific permission to look for
     * @return true if the permission is found or matches a wildcard, false otherwise
     */
    private fun hasPermissionInRank(rankPermissions: Set<String>, permission: String): Boolean {
        val lowercasePermission = permission.lowercase()

        // Direct match
        if (rankPermissions.contains(lowercasePermission)) {
            return true
        }

        // Wildcard matching
        // Split the permission into parts (e.g., "command.grant.add" -> ["command", "grant", "add"])
        val permParts = lowercasePermission.split(".")

        // Check for wildcards at different levels
        for (i in permParts.indices) {
            // Build a potential wildcard (e.g., "command.*", "command.grant.*")
            val wildcardBase = permParts.subList(0, i + 1).joinToString(".")
            val wildcard = "$wildcardBase.*"

            if (rankPermissions.contains(wildcard)) {
                return true
            }
        }

        // Also check for the most generic wildcard
        if (rankPermissions.contains("*")) {
            return true
        }

        return false
    }

    @Subscribe
    fun onProxyInitialization(event: ProxyInitializeEvent) {
        // Initialize RankManager - load ranks from MongoDB into memory
        scope.launch {
            try {
                rankManager.initialize()
            } catch (e: Exception) {
                logger.error(Component.text("Failed to initialize RankManager: ${e.message}", NamedTextColor.RED), e)
            }
        }

        server.eventManager.register(this, connectionHandler)
        server.eventManager.register(this, staffManager)
        server.eventManager.register(this, chatManager)
        server.eventManager.register(this, tabListManager)
        startSyncTask()
    }


    /**
     * Starts the tiered synchronization task for Redis and MongoDB
     * - Memory → Redis sync happens every minute
     * - Redis → MongoDB sync happens every 5 minutes
     */
    private fun startSyncTask() {
        if (syncTaskRunning) return

        syncTaskRunning = true
        lastRedisSyncTime = System.currentTimeMillis()
        lastMongoSyncTime = System.currentTimeMillis()

        logger.info("Starting tiered synchronization task:")
        logger.info("  - Memory → Redis sync interval: ${redisIntervalMs / 1000} seconds")
        logger.info("  - Redis → MongoDB sync interval: ${mongoIntervalMs / 1000} seconds")

        scope.launch {
            while (syncTaskRunning) {
                try {
                    val currentTime = System.currentTimeMillis()

                    // Check if it's time for Redis sync (every minute)
                    if (currentTime - lastRedisSyncTime >= redisIntervalMs) {
                        lastRedisSyncTime = currentTime
                        syncMemoryToRedis()
                    }

                    // Check if it's time for MongoDB sync (every 5 minutes)
                    if (currentTime - lastMongoSyncTime >= mongoIntervalMs) {
                        lastMongoSyncTime = currentTime
                        syncRedisToMongo()
                    }

                    // Wait a short time before next check
                    delay(1000) // Check every second

                } catch (e: Exception) {
                    logger.error("Error in synchronization task: ${e.message}", e)
                    // Don't stop the task on error, wait and try again
                }
            }
        }
    }

    /**
     * Synchronizes in-memory profiles to Redis
     */
    private fun syncMemoryToRedis() {
        try {
            val profiles = connectionHandler.getAllProfiles()
            logger.debug("[Sync] Synchronizing ${profiles.size} profiles from memory to Redis")

            connectionHandler.syncProfilesToRedis()
        } catch (e: Exception) {
            logger.error("Failed to sync profiles from memory to Redis: ${e.message}", e)
        }
    }

    /**
     * Synchronizes profiles from Redis to MongoDB
     */
    private suspend fun syncRedisToMongo() {
        try {
            logger.info("Running scheduled Redis → MongoDB sync...")

            // Get all Redis keys for profiles
            val keys = lettuceCache.sync().keys("profile:*")

            logger.info("[Sync] Synchronizing ${keys.size} profiles from Redis to MongoDB")
            var successCount = 0
            var errorCount = 0

            for (key in keys) {
                try {
                    // Extract UUID from key (format: "profile:uuid")
                    val uuid = key.substringAfter("profile:")

                    // Get profile from Redis
                    val (profile, metadata) = lettuceCache.getProfile(java.util.UUID.fromString(uuid))

                    if (profile != null) {
                        // Save to MongoDB
                        mongoStream.saveProfileToDatabase(profile)
                        successCount++
                    } else {
                        errorCount++
                        logger.warn("[Sync] Failed to get profile from Redis for sync to MongoDB: $uuid, reason: ${metadata["reason"]}")
                    }
                } catch (e: Exception) {
                    errorCount++
                    logger.error("[Sync] Error synchronizing profile $key to MongoDB: ${e.message}")
                }
            }

            logger.info("[Sync] Redis → MongoDB sync completed: $successCount profiles synchronized, $errorCount errors")
        } catch (e: Exception) {
            logger.error("Failed to sync profiles from Redis to MongoDB: ${e.message}", e)
        }
    }

    /**
     * Stops the synchronization task
     */
    private fun stopSyncTask() {
        logger.info("Stopping tiered synchronization task")
        syncTaskRunning = false
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        logger.info(Component.text("Radium is shutting down..."))

        // Stop the synchronization task
        stopSyncTask()

        // Perform a final sync to ensure all data is saved
        // Use runBlocking instead of launch to ensure operations complete before server shutdown
        runBlocking {
            try {
                logger.info("Performing final data synchronization...")

                // Final sync from memory to Redis
                syncMemoryToRedis()

                // Final sync from Redis to MongoDB
                syncRedisToMongo()

                logger.info("Final data synchronization completed")

                // Close database connections
                logger.info("Closing database connections...")

                try {
                    logger.info("Closing MongoDB connection...")
                    mongoStream.close()
                } catch (e: Exception) {
                    logger.error("Error while closing MongoDB connection", e)
                }

                try {
                    logger.info("Closing Redis connection...")
                    lettuceCache.close()
                } catch (e: Exception) {
                    logger.error("Error while closing Redis connection", e)
                }

                logger.info("Database connections closed successfully")
            } catch (e: Exception) {
                logger.error("Error during shutdown synchronization", e)
            }
        }
    }
}
