package radium.backend.nametag

import kotlinx.coroutines.launch
import radium.backend.Radium
import radium.backend.nametag.commands.NametagCommands

/**
 * Bootstrap class for initializing the nametag system on Velocity proxy
 */
class NameTagBootstrap(private val radium: Radium) {
    
    private lateinit var nameTagService: NameTagService
    private lateinit var nameTagListeners: NameTagListeners
    
    /**
     * Initializes the nametag system
     */
    fun initialize() {
        try {
            radium.logger.info("Initializing NameTag system...")
            
            // Initialize the service
            nameTagService = NameTagService(radium, radium.yamlFactory)
            
            // Initialize the API
            NametagAPI.initialize(nameTagService)
            
            // Set up event listeners
            nameTagListeners = NameTagListeners(radium, nameTagService, radium.lettuceCache)
            nameTagListeners.registerListeners()
            
            // Register commands
            registerCommands()
            
            // Apply nametags to already online players (for hot reload)
            applyToExistingPlayers()
            
            radium.logger.info("✅ NameTag system initialized successfully!")
            
        } catch (e: Exception) {
            radium.logger.error("❌ Failed to initialize NameTag system: ${e.message}", e)
        }
    }
    
    /**
     * Registers nametag commands
     */
    private fun registerCommands() {
        try {
            // The commands will be registered through Radium's main command system
            // We just need to make sure the NametagCommands class is instantiated
            val nametagCommands = NametagCommands(radium)
            
            // Register with Radium's lamp command system
            radium.lamp.register(nametagCommands)
            
            radium.logger.info("Registered nametag commands")
            
        } catch (e: Exception) {
            radium.logger.error("Failed to register nametag commands: ${e.message}", e)
        }
    }
    
    /**
     * Applies nametags to players who were already online (for hot reload scenarios)
     */
    private fun applyToExistingPlayers() {
        try {
            val onlinePlayers = radium.server.allPlayers.toList()
            if (onlinePlayers.isNotEmpty()) {
                radium.logger.info("Applying nametags to ${onlinePlayers.size} existing online players...")
                
                onlinePlayers.forEach { player ->
                    try {
                        radium.scope.launch {
                            nameTagService.applyFor(player)
                        }
                    } catch (e: Exception) {
                        radium.logger.error("Failed to apply nametag for ${player.username}: ${e.message}")
                    }
                }
                
                radium.logger.info("Applied nametags to existing players")
            }
        } catch (e: Exception) {
            radium.logger.error("Failed to apply nametags to existing players: ${e.message}", e)
        }
    }
    
    /**
     * Shuts down the nametag system
     */
    fun shutdown() {
        try {
            if (::nameTagService.isInitialized) {
                nameTagService.shutdown()
            }
            radium.logger.info("NameTag system shut down")
        } catch (e: Exception) {
            radium.logger.error("Error during NameTag system shutdown: ${e.message}", e)
        }
    }
    
    /**
     * Gets the nametag service instance
     */
    fun getService(): NameTagService? {
        return if (::nameTagService.isInitialized) nameTagService else null
    }
}
