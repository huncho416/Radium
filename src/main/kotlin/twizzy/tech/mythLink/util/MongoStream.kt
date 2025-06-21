package twizzy.tech.mythLink.util

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.MongoTimeoutException
import com.mongodb.reactivestreams.client.MongoClient
import com.mongodb.reactivestreams.client.MongoClients
import com.mongodb.reactivestreams.client.MongoDatabase
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import org.bson.Document
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException

class MongoStream(val logger: ComponentLogger) {
    private var client: MongoClient? = null
    private var database: MongoDatabase? = null

    // Database name constant
    private val DB_NAME = "mythlink"

    // Collection names
    private val PROFILES_COLLECTION = "profiles"
    private val RANKS_COLLECTION = "ranks"

    /**
     * Initializes all required databases for the MythLink plugin
     * This is the central point for all database initialization
     */
    fun initializeDatabases() {
        try {
            // Connect to the mythlink database
            val mythlinkDb = connectToDatabase()
            // Create necessary indexes for profile collection if needed
            try {
                mythlinkDb.getCollection(PROFILES_COLLECTION).createIndex(org.bson.Document("_id", 1))
            } catch (e: Exception) {
                logger.warn(Component.text("Could not verify or create profile indexes: ${e.message}", NamedTextColor.YELLOW))
            }

            // Create necessary indexes for rank collection if needed
            try {
                mythlinkDb.getCollection(RANKS_COLLECTION).createIndex(org.bson.Document("_id", 1))
            } catch (e: Exception) {
                logger.warn(Component.text("Could not verify or create rank indexes: ${e.message}", NamedTextColor.YELLOW))
            }

            // Verify connection to the database
            val ping = mythlinkDb.runCommand(Document("ping", 1))

            if (ping != null) {
                logger.info(Component.text("Successfully verified connection to the database", NamedTextColor.GREEN))
            }

        } catch (e: Exception) {
            logger.error(Component.text("Failed to initialize databases: ${e.message}", NamedTextColor.RED), e)
            throw e
        }
    }

    /**
     * Connects to MongoDB using credentials from database.yml
     * @param databaseName The name of the database to connect to
     * @return The MongoDB database instance
     */
    fun connect(databaseName: String): MongoDatabase {
        // For the mythlink database, use the cached instance if available
        if (databaseName == DB_NAME && database != null) {
            logger.info(Component.text("Reusing existing MongoDB connection to database: $databaseName", NamedTextColor.GREEN))
            return database!!
        }

        try {
            // Create the client if it doesn't exist yet
            if (client == null) {
                val config = loadDatabaseConfig()
                val mongoConfig = config["mongodb"] as Map<String, Any>?
                    ?: throw RuntimeException("MongoDB configuration not found in database.yml")

                val host = mongoConfig["host"] as String? ?: "localhost"
                val port = mongoConfig["port"] as Int? ?: 27017
                val username = mongoConfig["username"] as String?
                val password = mongoConfig["password"] as String?
                val connectionStringBuilder = StringBuilder("mongodb://")

                // Add authentication if provided
                if (username != null && password != null) {
                    connectionStringBuilder.append("$username:****@")
                    logger.info(Component.text("Using authentication with username: $username", NamedTextColor.YELLOW))
                }

                // Add host and port
                connectionStringBuilder.append("$host:$port")

                // Create connection string
                val connectionString = ConnectionString(connectionStringBuilder.toString())

                // Build client settings
                val settings = MongoClientSettings.builder()
                    .applyConnectionString(connectionString)
                    .build()

                // Create client
                logger.info(Component.text("Attempting to connect to MongoDB at $host:$port...", NamedTextColor.YELLOW))
                client = MongoClients.create(settings)

                // Validate connection by accessing server information
                try {
                    // Simple command to verify connection
                    client!!.getDatabase("admin").runCommand(Document("ping", 1))
                } catch (e: Exception) {
                    logger.error(Component.text("Failed to ping MongoDB server. Connection may be unstable.", NamedTextColor.RED), e)
                }
            }

            // Get database
            val db = client!!.getDatabase(databaseName)

            // Store in the database field
            database = db
            return db
        } catch (e: MongoTimeoutException) {
            logger.error(Component.text("Timed out connecting to MongoDB. Please check if MongoDB server is running.", NamedTextColor.RED), e)
            throw e
        } catch (e: Exception) {
            logger.error(Component.text("Failed to connect to MongoDB", NamedTextColor.RED), e)
            throw e
        }
    }

    /**
     * Closes the MongoDB connection
     */
    fun close() {
        try {
            logger.info(Component.text("Closing MongoDB connection...", NamedTextColor.YELLOW))
            client?.close()
            client = null
            database = null
            logger.info(Component.text("MongoDB connection closed successfully", NamedTextColor.GREEN))
        } catch (e: Exception) {
            logger.error(Component.text("Error while closing MongoDB connection", NamedTextColor.RED), e)
        }
    }

    /**
     * Loads the database configuration from plugins/MythLink/database.yml
     */
    private fun loadDatabaseConfig(): Map<String, Any> {
        val configFile = File("plugins/MythLink/database.yml")

        if (!configFile.exists()) {
            throw FileNotFoundException("Database configuration file not found. Make sure to call YamlFactory.ensureDatabaseConfiguration() first.")
        }

        val yaml = Yaml()
        return yaml.load(FileInputStream(configFile)) as Map<String, Any>
    }

    /**
     * Gets the currently connected database
     * @throws IllegalStateException if not connected to a database
     */
    fun getDatabase(): MongoDatabase {
        return database ?: throw IllegalStateException("Not connected to a database. Call connect() first.")
    }

    /**
     * Connects to the mythlink database
     * @return The MongoDB database instance for mythlink
     */
    fun connectToDatabase(): MongoDatabase {
        if (database != null) {
            logger.info(Component.text("Reusing existing connection to mythlink database", NamedTextColor.GREEN))
            return database!!
        }

        database = connect(DB_NAME)

        return database!!
    }

    /**
     * Saves a player profile to the MongoDB database
     *
     * @param profile The profile to save
     */
    suspend fun saveProfileToDatabase(profile: twizzy.tech.mythLink.player.Profile) {
        val document = profileToDocument(profile)
        val filter = com.mongodb.client.model.Filters.eq("_id", profile.uuid.toString())
        val options = com.mongodb.client.model.ReplaceOptions().upsert(true)

        getDatabase().getCollection(PROFILES_COLLECTION).replaceOne(filter, document, options).awaitFirst()
    }

    /**
     * Loads a player profile from the MongoDB database
     *
     * @param uuid The UUID of the player as a string
     * @return A Pair containing the loaded profile (or null if not found) and metadata about the retrieval
     */
    suspend fun loadProfileFromDatabase(uuid: String): Pair<twizzy.tech.mythLink.player.Profile?, Map<String, Any?>> {
        try {
            val filter = com.mongodb.client.model.Filters.eq("_id", uuid)
            val document = getDatabase().getCollection(PROFILES_COLLECTION).find(filter).first()
                .awaitFirstOrNull()

            if (document == null) {
                return Pair(null, mapOf(
                    "found" to false,
                    "reason" to "not_in_mongodb",
                    "uuid" to uuid
                ))
            }

            val profile = documentToProfile(document)
            return Pair(profile, mapOf(
                "found" to true,
                "source" to "mongodb",
                "uuid" to uuid
            ))
        } catch (e: Exception) {
            logger.error(Component.text("Error loading profile from MongoDB: ${e.message}", NamedTextColor.RED), e)
            return Pair(null, mapOf(
                "found" to false,
                "reason" to "mongodb_error",
                "error" to e.message,
                "uuid" to uuid
            ))
        }
    }

    /**
     * Converts a Profile object to a MongoDB Document
     *
     * @param profile The profile to convert
     * @return The MongoDB Document representation
     */
    fun profileToDocument(profile: twizzy.tech.mythLink.player.Profile): org.bson.Document {
        // Convert the profile to a map and then to a Document
        return org.bson.Document(profile.toMap())
    }

    /**
     * Converts a MongoDB Document to a Profile object
     *
     * @param document The document to convert
     * @return The Profile object
     */
    fun documentToProfile(document: org.bson.Document): twizzy.tech.mythLink.player.Profile {
        // Convert Document to Map and use Profile's fromMap method
        @Suppress("UNCHECKED_CAST")
        val dataMap = document.toMutableMap() as Map<String, Any>
        return twizzy.tech.mythLink.player.Profile.fromMap(dataMap)
    }
}