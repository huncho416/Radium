package radium.backend.util

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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException

class MongoStream(val logger: ComponentLogger) {
    private var client: MongoClient? = null
    private var database: MongoDatabase? = null

    // Database name constant
    private val DB_NAME = "radium"

    // Collection names
    private val PROFILES_COLLECTION = "profiles"
    private val RANKS_COLLECTION = "ranks"

    /**
     * Initializes all required databases for the Radium plugin
     * This is the central point for all database initialization
     */
    fun initializeDatabases() {
        try {
            // Connect to the radium database
            val radiumDb = connectToDatabase()
            // Create necessary indexes for profile collection if needed
            try {
                radiumDb.getCollection(PROFILES_COLLECTION).createIndex(org.bson.Document("_id", 1))
            } catch (e: Exception) {
                logger.warn(Component.text("Could not verify or create profile indexes: ${e.message}", NamedTextColor.YELLOW))
            }

            // Create necessary indexes for rank collection if needed
            try {
                radiumDb.getCollection(RANKS_COLLECTION).createIndex(org.bson.Document("_id", 1))
            } catch (e: Exception) {
                logger.warn(Component.text("Could not verify or create rank indexes: ${e.message}", NamedTextColor.YELLOW))
            }

            // Verify connection to the database
            val ping = radiumDb.runCommand(Document("ping", 1))

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
        // For the radium database, use the cached instance if available
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
                val database = mongoConfig["database"] as String? ?: "radium"
                val connectionStringBuilder = StringBuilder("mongodb://")

                // Add authentication if provided
                if (username != null && password != null) {
                    // For simple passwords, try without URL encoding first
                    connectionStringBuilder.append("$username:$password@")
                    logger.info(Component.text("Using authentication with username: $username", NamedTextColor.YELLOW))
                }

                // Add host and port
                connectionStringBuilder.append("$host:$port")
                
                // Add database and authentication source - use admin for auth but radium for operations
                connectionStringBuilder.append("/$database?authSource=admin")

                // Create connection string
                val connectionString = ConnectionString(connectionStringBuilder.toString())
                logger.info(Component.text("MongoDB connection string: mongodb://$username:****@$host:$port/$database?authSource=admin", NamedTextColor.YELLOW))

                // Build client settings with specific authentication configuration
                val settings = MongoClientSettings.builder()
                    .applyConnectionString(connectionString)
                    .build()

                // Create client
                logger.info(Component.text("Attempting to connect to MongoDB at $host:$port...", NamedTextColor.YELLOW))
                client = MongoClients.create(settings)

                // Validate connection by accessing server information
                try {
                    // Simple command to verify connection using the specified database
                    client!!.getDatabase(database).runCommand(Document("ping", 1))
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
     * Loads the database configuration from plugins/Radium/database.yml
     */
    private fun loadDatabaseConfig(): Map<String, Any> {
        val configFile = File("plugins/Radium/database.yml")

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
        logger.debug(Component.text("getDatabase() called - database is ${if (database != null) "not null" else "null"}", NamedTextColor.GRAY))
        return database ?: throw IllegalStateException("Not connected to a database. Call connect() first.")
    }

    /**
     * Connects to the radium database
     * @return The MongoDB database instance for radium
     */
    fun connectToDatabase(): MongoDatabase {
        if (database != null) {
            logger.info(Component.text("Reusing existing connection to radium database", NamedTextColor.GREEN))
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
    suspend fun saveProfileToDatabase(profile: radium.backend.player.Profile) {
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
    suspend fun loadProfileFromDatabase(uuid: String): Pair<radium.backend.player.Profile?, Map<String, Any?>> {
        try {
            val filter = com.mongodb.client.model.Filters.eq("uuid", uuid)
            val document = getDatabase().getCollection(PROFILES_COLLECTION).find(filter).first()
                .awaitFirstOrNull()

            if (document == null) {
                return Pair(null, mapOf(
                    "found" to false,
                    "reason" to "not_in_mongodb",
                    "uuid" to uuid
                ))
            }

            // Debug the raw document before conversion
            logger.info("Raw MongoDB document: $document")
            logger.info("Document ranks field: ${document["ranks"]}")
            logger.info("Document ranks type: ${document["ranks"]?.javaClass?.simpleName}")

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
    fun profileToDocument(profile: radium.backend.player.Profile): org.bson.Document {
        // Convert the profile to a map and then to a Document
        return org.bson.Document(profile.toMap())
    }

    /**
     * Converts a MongoDB Document to a Profile object
     *
     * @param document The document to convert
     * @return The Profile object
     */
    fun documentToProfile(document: org.bson.Document): radium.backend.player.Profile {
        // Convert Document to Map and use Profile's fromMap method
        @Suppress("UNCHECKED_CAST")
        val dataMap = document.toMutableMap() as MutableMap<String, Any>
        
        // Debug logging for profile conversion
        logger.info("Converting MongoDB document to Profile:")
        logger.info("  UUID: ${dataMap["uuid"]}")
        logger.info("  Username: ${dataMap["username"]}")
        logger.info("  Raw document ranks: ${document["ranks"]}")
        logger.info("  Raw document ranks type: ${document["ranks"]?.javaClass?.simpleName}")
        logger.info("  Ranks in dataMap: ${dataMap["ranks"]}")
        logger.info("  Ranks type: ${dataMap["ranks"]?.javaClass?.simpleName}")
        
        // Manual fix for ranks field - extract directly from document
        val ranksFromDocument = document["ranks"]
        logger.info("  Direct ranks extraction: $ranksFromDocument")
        logger.info("  Direct ranks type: ${ranksFromDocument?.javaClass?.simpleName}")
        
        if (ranksFromDocument is List<*>) {
            @Suppress("UNCHECKED_CAST")
            val ranksList = ranksFromDocument as List<String>
            dataMap["ranks"] = ranksList
            logger.info("  Fixed ranks in dataMap: ${dataMap["ranks"]}")
        }
        
        val profile = radium.backend.player.Profile.fromMap(dataMap)
        
        logger.info("After fromMap conversion:")
        logger.info("  Profile ranks: ${profile.getRanks()}")
        logger.info("  Profile ranks size: ${profile.getRanks().size}")
        
        return profile
    }
}
