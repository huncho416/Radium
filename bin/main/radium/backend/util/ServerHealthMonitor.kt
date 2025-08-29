package radium.backend.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import radium.backend.Radium
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Monitors the health of backend servers to provide better error handling
 */
class ServerHealthMonitor(
    private val radium: Radium,
    private val logger: ComponentLogger,
    private val scope: CoroutineScope
) {
    
    private val serverHealth = ConcurrentHashMap<String, Boolean>()
    private var isMonitoring = false
    
    /**
     * Start monitoring server health
     */
    fun startMonitoring() {
        if (isMonitoring) return
        
        isMonitoring = true
        scope.launch {
            while (isMonitoring) {
                checkAllServers()
                delay(30000) // Check every 30 seconds
            }
        }
        
        logger.info("Server health monitoring started")
    }
    
    /**
     * Stop monitoring server health
     */
    fun stopMonitoring() {
        isMonitoring = false
        logger.info("Server health monitoring stopped")
    }
    
    /**
     * Check if a specific server is healthy
     */
    fun isServerHealthy(serverName: String): Boolean {
        return serverHealth[serverName] ?: false
    }
    
    /**
     * Get health status of all servers
     */
    fun getServerHealthStatus(): Map<String, Boolean> {
        return serverHealth.toMap()
    }
    
    /**
     * Check health of all registered servers
     */
    private suspend fun checkAllServers() {
        radium.server.allServers.forEach { registeredServer ->
            val serverName = registeredServer.serverInfo.name
            val address = registeredServer.serverInfo.address
            
            try {
                val isHealthy = checkServerConnection(address)
                val wasHealthy = serverHealth[serverName] ?: false
                
                serverHealth[serverName] = isHealthy
                
                // Log status changes only
                if (isHealthy != wasHealthy) {
                    if (isHealthy) {
                        logger.info("Server '$serverName' is now available")
                    } else {
                        logger.warn("Server '$serverName' is now unavailable")
                    }
                }
                
            } catch (e: Exception) {
                serverHealth[serverName] = false
                logger.debug("Health check failed for server '$serverName': ${e.message}")
            }
        }
    }
    
    /**
     * Test connection to a server address
     */
    private fun checkServerConnection(address: InetSocketAddress): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(address, 5000) // 5 second timeout
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
