package radium.backend.api

import com.google.gson.Gson
import com.velocitypowered.api.proxy.ProxyServer
import io.ktor.http.*
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import radium.backend.Radium
import radium.backend.api.dto.*
import radium.backend.api.routes.*
import java.util.*

/**
 * HTTP API Server for Radium
 * Replaces the Redis-based MythicHub integration with RESTful API endpoints
 */
class RadiumApiServer(
    private val plugin: Radium,
    private val server: ProxyServer,
    private val logger: ComponentLogger,
    private val scope: CoroutineScope,
    private val port: Int = 8080,
    private val apiKey: String? = null
) {
    private var ktorServer: NettyApplicationEngine? = null
    private val gson = Gson()

    fun start() {
        ktorServer = embeddedServer(Netty, port = port) {
            install(ContentNegotiation) {
                gson()
            }

            install(CORS) {
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Put)
                allowMethod(HttpMethod.Delete)
                allowMethod(HttpMethod.Patch)
                allowHeader(HttpHeaders.Authorization)
                allowHeader(HttpHeaders.ContentType)
                allowHeader("X-API-Key")
                anyHost() // Configure this more restrictively in production
            }

            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    logger.error("API error: ${cause.message}", cause)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse("Internal server error", cause.message ?: "Unknown error")
                    )
                }
            }

            // API Key authentication if configured
            if (apiKey != null) {
                install(Authentication) {
                    bearer("api-key") {
                        authenticate { tokenCredential ->
                            if (tokenCredential.token == apiKey) {
                                UserIdPrincipal("api-user")
                            } else {
                                null
                            }
                        }
                    }
                }
            }

            routing {
                // Health check endpoint
                get("/health") {
                    call.respond(HealthResponse("ok", System.currentTimeMillis()))
                }

                // API routes (protected by authentication if API key is set)
                route("/api/v1") {
                    if (apiKey != null) {
                        authenticate("api-key") {
                            setupApiRoutes()
                        }
                    } else {
                        setupApiRoutes()
                    }
                }
            }
        }.start(wait = false)

        logger.info("Radium API server started on port $port")
    }

    private fun Route.setupApiRoutes() {
        // Player routes
        playerRoutes(plugin, server, logger)

        // Server routes
        serverRoutes(plugin, server, logger)

        // Permission routes
        permissionRoutes(plugin, server, logger)

        // Profile routes
        profileRoutes(plugin, server, logger)

        // Command routes
        commandRoutes(plugin, server, logger)

        // Rank routes
        rankRoutes(plugin, server, logger)

        // Punishment routes
        punishmentRoutes(plugin, server, logger)
    }

    fun stop() {
        ktorServer?.stop(1000, 2000)
        logger.info("Radium API server stopped")
    }

    fun shutdown() {
        stop()
    }
}
