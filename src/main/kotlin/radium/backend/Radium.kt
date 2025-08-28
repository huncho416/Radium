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
import radium.backend.commands.ChatSlow
import radium.backend.commands.ChatMute
import radium.backend.commands.ChatClear
import radium.backend.commands.ChatUnmute
import radium.backend.player.ConnectionHandler
import radium.backend.player.RankManager
import radium.backend.player.ChatManager
import radium.backend.player.TabListManager
import radium.backend.player.staff.StaffManager
import radium.backend.vanish.NetworkVanishManager
import radium.backend.util.LettuceCache
import radium.backend.util.MongoStream
import radium.backend.api.RadiumApiServer
import radium.backend.util.YamlFactory
import radium.backend.punishment.PunishmentRepository
import radium.backend.punishment.PunishmentManager
import radium.backend.punishment.commands.Ban
import radium.backend.punishment.commands.Mute
import radium.backend.punishment.commands.Warn
import radium.backend.punishment.commands.Kick
import radium.backend.punishment.commands.Blacklist
import radium.backend.punishment.commands.CheckPunishments
import radium.backend.punishment.commands.PunishmentAdmin
import radium.backend.punishment.events.PunishmentListener

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

    val rankManager = RankManager(mongoStream, lettuceCache)

    val connectionHandler = ConnectionHandler(this)
    val staffManager = StaffManager(this)
    val chatManager = ChatManager(this)
    val tabListManager = TabListManager(this)
    val networkVanishManager = NetworkVanishManager(this)

    // Punishment System - initialized after database connection
    lateinit var punishmentRepository: PunishmentRepository
    lateinit var punishmentManager: PunishmentManager
    lateinit var punishmentListener: PunishmentListener

    var messageCommand: Message? = null
    lateinit var apiServer: RadiumApiServer
    lateinit var lamp: revxrsal.commands.Lamp<revxrsal.commands.velocity.actor.VelocityCommandActor>
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
        
        // Try to connect to Redis, but continue without it if it fails
        try {
            lettuceCache.connect()
        } catch (e: Exception) {
            logger.warn(Component.text("Failed to connect to Redis. Continuing without Redis functionality.", NamedTextColor.YELLOW))
            logger.warn(Component.text("Cross-server messaging will not work until Redis is properly configured.", NamedTextColor.YELLOW))
            logger.warn(Component.text("Error: ${e.message}", NamedTextColor.RED))
        }

        lamp = VelocityLamp.builder(this, server)
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
        
        // Register commands
        lamp.register(Permission(this))
        lamp.register(Rank(this))
        lamp.register(Grant(this))
        lamp.register(Revoke(this))
        messageCommand = Message(this)
        lamp.register(messageCommand!!)
        lamp.register(Reload(this))
        lamp.register(StaffChat(this))
        lamp.register(LastSeen(this))
        lamp.register(Vanish(this))
        lamp.register(Friend(this))
        lamp.register(Gamemode(this))
        lamp.register(ChatSlow(this))
        lamp.register(ChatMute(this))
        lamp.register(ChatClear(this))
        lamp.register(ChatUnmute(this))

        // Register punishment commands
        lamp.register(Ban(this))
        lamp.register(Mute(this))
        lamp.register(Warn(this))
        lamp.register(Kick(this))
        lamp.register(Blacklist(this))
        lamp.register(CheckPunishments(this))
        lamp.register(PunishmentAdmin(this))
        logger.info(Component.text("Punishment commands registered", NamedTextColor.GREEN))

        // Accept brigadier visitor
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
        // Initialize API server (replaces MythicHub Redis integration)
        apiServer = RadiumApiServer(this, server, logger, scope)

        // Initialize punishment system after database is connected
        try {
            logger.info(Component.text("Initializing punishment system...", NamedTextColor.YELLOW))
            punishmentRepository = PunishmentRepository(mongoStream.getDatabase(), logger)
            punishmentManager = PunishmentManager(this, punishmentRepository, logger)
            punishmentListener = PunishmentListener(this)
            logger.info(Component.text("Punishment system components initialized", NamedTextColor.GREEN))
        } catch (e: Exception) {
            logger.error(Component.text("Failed to initialize punishment system components: ${e.message}", NamedTextColor.RED), e)
            throw e
        }

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
        server.eventManager.register(this, networkVanishManager)
        
        // Register punishment event listener
        server.eventManager.register(this, punishmentListener)

        // Initialize punishment system
        scope.launch {
            try {
                punishmentManager.initialize()
                logger.info(Component.text("Punishment system initialized successfully", NamedTextColor.GREEN))
            } catch (e: Exception) {
                logger.error(Component.text("Failed to initialize punishment system: ${e.message}", NamedTextColor.RED), e)
            }
        }

        // Initialize vanish manager
        networkVanishManager.initialize()

        // Start the HTTP API server
        apiServer.start()

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

                try {
                    logger.info("Shutting down proxy communication...")
                    apiServer.shutdown()
                } catch (e: Exception) {
                    logger.error("Error while shutting down proxy communication", e)
                }

                try {
                    logger.info("Shutting down enhanced punishment system...")
                    if (::punishmentManager.isInitialized) {
                        punishmentManager.shutdown()
                    }
                } catch (e: Exception) {
                    logger.error("Error while shutting down punishment system", e)
                }

                logger.info("Database connections closed successfully")
            } catch (e: Exception) {
                logger.error("Error during shutdown synchronization", e)
            }
        }
    }
}
