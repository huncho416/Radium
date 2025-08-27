package radium.backend.player

import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bson.Document
import radium.backend.util.MongoStream
import radium.backend.util.LettuceCache
import java.util.concurrent.ConcurrentHashMap

class RankManager(private val mongoStream: MongoStream, private val lettuceCache: LettuceCache) {

    // In-memory cache of ranks, keyed by rank name
    private val cachedRanks = ConcurrentHashMap<String, Rank>()

    data class Rank(
        val name: String,
        val prefix: String,
        val weight: Int,
        val color: String = "&f", // Color code for the player's name in chat
        val permissions: Set<String> = setOf(), // Set of additional permissions
        val inherits: List<String> = listOf(), // List of rank names that this rank inherits from
        val suffix: String? = null, // Optional general suffix (used in chat, etc.)
        val tabPrefix: String? = null, // Optional tab-specific prefix (falls back to prefix if null)
        val tabSuffix: String? = null // Optional tab-specific suffix
    )

    private val RANKS_COLLECTION = "ranks"
    private val DEFAULT_RANK_NAME = "Default"
    private val DEFAULT_RANK_PREFIX = "&7"
    private val DEFAULT_RANK_WEIGHT = 0

    /**
     * Initializes the RankManager by loading all ranks from MongoDB
     * Creates a default rank if no ranks exist
     */
    suspend fun initialize() {
        try {
            // Ensure MongoDB connection is established
            mongoStream.logger.info(Component.text("RankManager: Ensuring MongoDB connection...", NamedTextColor.YELLOW))
            val database = mongoStream.connectToDatabase()
            mongoStream.logger.info(Component.text("RankManager: MongoDB connection established", NamedTextColor.GREEN))
            
            // Add a small delay to let connections stabilize
            kotlinx.coroutines.delay(100)
            
            // First, check if the ranks collection exists with retry logic
            var collections: List<String>? = null
            for (attempt in 1..3) {
                try {
                    collections = database.listCollectionNames().asFlow().toList()
                    break
                } catch (e: Exception) {
                    mongoStream.logger.warn(Component.text("Attempt $attempt failed to list collections: ${e.message}", NamedTextColor.YELLOW))
                    if (attempt == 3) throw e
                    kotlinx.coroutines.delay(1000)
                }
            }
            
            val collectionExists = collections?.any { it == RANKS_COLLECTION } ?: false
            if (!collectionExists) {
                mongoStream.logger.warn(Component.text("Ranks collection does not exist in the database. It will be created.", NamedTextColor.YELLOW))
            }

            // Clear existing cache
            cachedRanks.clear()

            // Load all ranks from MongoDB
        val ranks = loadRanksFromMongo()

        // If no ranks exist, create default ranks
        if (ranks.isEmpty()) {
            mongoStream.logger.warn(Component.text("No ranks found in database. Creating default ranks...", NamedTextColor.YELLOW))

            // Create the default ranks with verbose logging
            try {
                val defaultRank = createRank(DEFAULT_RANK_NAME, DEFAULT_RANK_PREFIX, DEFAULT_RANK_WEIGHT)
                mongoStream.logger.info(Component.text("Created default rank: ${defaultRank.name}", NamedTextColor.GREEN))

                // Create additional ranks
                val memberRank = createRank("Member", "&a[Member] ", 10)
                mongoStream.logger.info(Component.text("Created member rank: ${memberRank.name}", NamedTextColor.GREEN))

                val adminRank = createRank("Admin", "&c[Admin] ", 100)
                mongoStream.logger.info(Component.text("Created admin rank: ${adminRank.name}", NamedTextColor.GREEN))

                val ownerRank = createRank("Owner", "&4[Owner] ", 1000)
                mongoStream.logger.info(Component.text("Created owner rank: ${ownerRank.name}", NamedTextColor.GREEN))

                // Add permissions to owner rank
                addPermissionToRank("Owner", "*")
                mongoStream.logger.info(Component.text("Added * permission to Owner rank", NamedTextColor.GREEN))

                // Add permissions to admin rank
                addPermissionToRank("Admin", "*")
                mongoStream.logger.info(Component.text("Added * permission to Admin rank", NamedTextColor.GREEN))

                // Add permissions to member rank
                addPermissionToRank("Member", "command.friend")
                mongoStream.logger.info(Component.text("Added command.friend permission to Member rank", NamedTextColor.GREEN))

                // Verify the ranks were saved by re-fetching them from MongoDB
                val verifyDefault = getRank(DEFAULT_RANK_NAME)
                val verifyMember = getRank("Member")
                val verifyAdmin = getRank("Admin")
                val verifyOwner = getRank("Owner")
                if (verifyDefault != null && verifyMember != null && verifyAdmin != null && verifyOwner != null) {
                    mongoStream.logger.info(Component.text("Verified all default ranks were successfully saved to database", NamedTextColor.GREEN))
                } else {
                    mongoStream.logger.error(Component.text("CRITICAL: Some default ranks were not saved properly!", NamedTextColor.RED))
                }
            } catch (e: Exception) {
                mongoStream.logger.error(Component.text("Failed to create default ranks: ${e.message}", NamedTextColor.RED), e)
            }
        } else {
            // Log the names of the ranks that were loaded
            val rankNames = ranks.joinToString { it.name }
            mongoStream.logger.info(Component.text("Loaded ranks: $rankNames", NamedTextColor.GREEN))

            // Check if we have all essential ranks and create missing ones
            val essentialRanks = mapOf(
                "Member" to ("&a[Member] " to 10),
                "Admin" to ("&c[Admin] " to 100)
            )

            essentialRanks.forEach { (rankName, rankData) ->
                val (prefix, weight) = rankData
                if (getRank(rankName) == null) {
                    mongoStream.logger.warn(Component.text("Essential rank '$rankName' missing. Creating...", NamedTextColor.YELLOW))
                    try {
                        createRank(rankName, prefix, weight)
                        if (rankName == "Admin") {
                            addPermissionToRank(rankName, "*")
                        } else if (rankName == "Member") {
                            addPermissionToRank(rankName, "command.friend")
                        }
                        mongoStream.logger.info(Component.text("Created missing essential rank: $rankName", NamedTextColor.GREEN))
                    } catch (e: Exception) {
                        mongoStream.logger.error(Component.text("Failed to create essential rank '$rankName': ${e.message}", NamedTextColor.RED), e)
                    }
                }
            }
        }

        } catch (e: Exception) {
            mongoStream.logger.error(Component.text("Error during RankManager initialization: ${e.message}", NamedTextColor.RED), e)

            // Even in case of database errors, check what's in the cache
            val cachedCount = cachedRanks.size
            if (cachedCount > 0) {
                mongoStream.logger.warn(Component.text("Using $cachedCount cached ranks despite database error", NamedTextColor.YELLOW))
            } else {
                mongoStream.logger.error(Component.text("No ranks in cache and database connection failed", NamedTextColor.RED))
            }
        }
    }

    /**
     * Loads all ranks from MongoDB and caches them
     *
     * @return List of all ranks sorted by weight
     */
    private suspend fun loadRanksFromMongo(): List<Rank> {
        val ranks = listRanksByWeight()

        // Cache all ranks in memory
        ranks.forEach { rank ->
            cachedRanks[rank.name.lowercase()] = rank
        }

        // Cache all ranks in Redis for MythicHub compatibility
        try {
            ranks.forEach { rank ->
                cacheRankInRedis(rank)
            }
            mongoStream.logger.info(Component.text("Cached ${ranks.size} ranks in Redis", NamedTextColor.GREEN))
        } catch (e: Exception) {
            mongoStream.logger.warn(Component.text("Failed to cache ranks in Redis: ${e.message}", NamedTextColor.YELLOW))
        }

        return ranks
    }

    /**
     * Caches a rank in Redis using the format expected by MythicHub
     */
    private fun cacheRankInRedis(rank: Rank) {
        try {
            val key = "radium:rank:${rank.name.lowercase()}"
            val jsonData = rankToJson(rank)
            
            lettuceCache.sync().set(key, jsonData)
            lettuceCache.sync().expire(key, 3600) // 1 hour expiration
            
            mongoStream.logger.debug(Component.text("Cached rank ${rank.name} in Redis under key: $key", NamedTextColor.GREEN))
        } catch (e: Exception) {
            mongoStream.logger.warn(Component.text("Failed to cache rank ${rank.name} in Redis: ${e.message}", NamedTextColor.YELLOW))
        }
    }

    /**
     * Converts a rank to JSON format for Redis storage
     */
    private fun rankToJson(rank: Rank): String {
        val permissionsJson = rank.permissions.joinToString(",") { "\"$it\"" }
        return """
        {
            "name": "${rank.name}",
            "prefix": "${rank.prefix}",
            "weight": ${rank.weight},
            "color": "${rank.color}",
            "permissions": [$permissionsJson],
            "inherits": [${rank.inherits.joinToString(",") { "\"$it\"" }}]
        }
        """.trimIndent()
    }

    /**
     * Refreshes the in-memory cache from MongoDB
     */
    suspend fun refreshCache() {
        loadRanksFromMongo()
    }

    /**
     * Creates a new rank and stores it in the database and cache
     *
     * @param name The name of the rank
     * @param prefix The prefix for the rank
     * @param weight The weight/priority of the rank (higher numbers have more priority)
     * @param permission The base permission for the rank
     * @return The newly created rank object
     */
    suspend fun createRank(name: String, prefix: String, weight: Int, color: String = "&f"): Rank {
        val rank = Rank(name, prefix, weight, color)
        saveRank(rank)
        // Update memory cache
        cachedRanks[name.lowercase()] = rank
        // Update Redis cache
        cacheRankInRedis(rank)
        return rank
    }


    /**
     * Deletes a rank from the database and cache
     *
     * @param name The name of the rank to delete
     * @return True if the rank was deleted, false if it didn't exist
     */
    suspend fun deleteRank(name: String): Boolean {
        val filter = Filters.eq("_id", name)
        val result = mongoStream.getDatabase().getCollection(RANKS_COLLECTION)
            .deleteOne(filter)
            .awaitFirst()

        // Remove from cache
        cachedRanks.remove(name.lowercase())

        return result.deletedCount > 0
    }

    /**
     * Adds an inherited rank to an existing rank
     *
     * @param rankName The name of the rank to modify
     * @param inheritRankName The name of the rank to inherit from
     * @return True if successful, false if either rank doesn't exist
     */
    suspend fun addInheritedRank(rankName: String, inheritRankName: String): Boolean {
        // Get the ranks
        val rank = getRank(rankName) ?: return false
        val inheritRank = getRank(inheritRankName) ?: return false

        // Check if already inheriting this rank
        if (rank.inherits.contains(inheritRankName)) {
            return true // Already inheriting, consider it a success
        }

        // Check for circular inheritance
        if (wouldCreateCircularInheritance(inheritRankName, rankName)) {
            throw IllegalArgumentException("Cannot add inherited rank: would create circular inheritance")
        }

        // Add the inheritance
        val updatedInherits = rank.inherits + inheritRankName
        val updatedRank = rank.copy(inherits = updatedInherits)

        // Save the updated rank
        saveRank(updatedRank)

        // Update cache
        cachedRanks[rankName.lowercase()] = updatedRank

        return true
    }

    /**
     * Removes an inherited rank from an existing rank
     *
     * @param rankName The name of the rank to modify
     * @param inheritRankName The name of the rank to stop inheriting from
     * @return True if successful, false if the rank doesn't exist or wasn't inheriting
     */
    suspend fun removeInheritedRank(rankName: String, inheritRankName: String): Boolean {
        // Get the rank
        val rank = getRank(rankName) ?: return false

        // Check if not inheriting this rank
        if (!rank.inherits.contains(inheritRankName)) {
            return false // Wasn't inheriting, so can't remove
        }

        // Remove the inheritance
        val updatedInherits = rank.inherits.filter { it != inheritRankName }
        val updatedRank = rank.copy(inherits = updatedInherits)

        // Save the updated rank
        saveRank(updatedRank)

        // Update cache
        cachedRanks[rankName.lowercase()] = updatedRank

        return true
    }

    /**
     * Checks if adding an inheritance would create a circular reference
     *
     * @param parentRankName The rank that would be inherited
     * @param childRankName The rank that would inherit
     * @return True if it would create a circular inheritance
     */
    private suspend fun wouldCreateCircularInheritance(parentRankName: String, childRankName: String): Boolean {
        // If parentRank already inherits from childRank (directly or indirectly), adding childRank -> parentRank would create a circle
        return getAllInheritedRanks(parentRankName).contains(childRankName.lowercase())
    }

    /**
     * Gets all ranks that a given rank inherits from (directly or indirectly)
     *
     * @param rankName The name of the rank
     * @return Set of rank names that this rank inherits from (directly or indirectly)
     */
    suspend fun getAllInheritedRanks(rankName: String): Set<String> {
        val rank = getRank(rankName) ?: return emptySet()
        val result = mutableSetOf<String>()

        // Add direct inherits
        rank.inherits.forEach {
            result.add(it.lowercase())

            // Add indirect inherits recursively
            result.addAll(getAllInheritedRanks(it))
        }

        return result
    }

    /**
     * Gets all permissions for a rank, including those from inherited ranks
     *
     * @param rankName The name of the rank
     * @return List of all permissions for this rank (including inherited ones)
     */
    suspend fun getAllPermissions(rankName: String): List<String> {
        val rank = getRank(rankName) ?: return emptyList()
        val permissions = mutableListOf<String>()

        // Add this rank's additional permissions
        permissions.addAll(rank.permissions)

        // Add inherited permissions
        for (inheritRankName in rank.inherits) {
            val inheritRank = getRank(inheritRankName)
            if (inheritRank != null) {
                permissions.addAll(inheritRank.permissions) // Add additional permissions from inherited rank

                // Recursively get permissions from ranks that this inherited rank inherits from
                permissions.addAll(getAllInheritedPermissions(inheritRank))
            }
        }

        return permissions.distinct()
    }

    /**
     * Helper method to get all inherited permissions for a rank without adding its direct permission
     *
     * @param rank The rank to get inherited permissions for
     * @return List of inherited permissions
     */
    private suspend fun getAllInheritedPermissions(rank: Rank): List<String> {
        val permissions = mutableListOf<String>()

        for (inheritRankName in rank.inherits) {
            val inheritRank = getRank(inheritRankName)
            if (inheritRank != null) {
                permissions.addAll(inheritRank.permissions) // Add additional permissions
                permissions.addAll(getAllInheritedPermissions(inheritRank))
            }
        }

        return permissions
    }

    /**
     * Updates a rank's inheritance list
     *
     * @param name The name of the rank to update
     * @param inherits List of rank names that this rank should inherit from
     * @return True if the rank was updated, false if it didn't exist
     */
    suspend fun updateRankInheritance(name: String, inherits: List<String>): Boolean {
        val rank = getRank(name) ?: return false

        // Verify all the ranks in the inheritance list exist
        for (inheritRankName in inherits) {
            if (getRank(inheritRankName) == null) {
                throw IllegalArgumentException("Cannot inherit from non-existent rank: $inheritRankName")
            }

            // Check for circular inheritance
            if (inheritRankName.equals(name, ignoreCase = true) ||
                (inherits.contains(inheritRankName) && wouldCreateCircularInheritance(inheritRankName, name))) {
                throw IllegalArgumentException("Cannot update inherited ranks: would create circular inheritance")
            }
        }

        val updatedRank = rank.copy(inherits = inherits)
        saveRank(updatedRank)
        // Update cache
        cachedRanks[name.lowercase()] = updatedRank
        return true
    }

    /**
     * Gets a rank by name, first checking the cache, then the database if not found
     *
     * @param name The name of the rank to retrieve
     * @return The rank object, or null if not found
     */
    suspend fun getRank(name: String): Rank? {
        // First try to get from cache
        val cachedRank = cachedRanks[name.lowercase()]
        if (cachedRank != null) {
            return cachedRank
        }

        // If not in cache, get from database
        val filter = Filters.eq("_id", name)
        val document = mongoStream.getDatabase().getCollection(RANKS_COLLECTION)
            .find(filter)
            .first()
            .awaitFirstOrNull() ?: return null

        val rank = documentToRank(document)
        // Update cache
        cachedRanks[name.lowercase()] = rank
        return rank
    }

    /**
     * Gets all ranks from the in-memory cache
     *
     * @return List of all cached ranks
     */
    fun getCachedRanks(): List<Rank> {
        return cachedRanks.values.sortedByDescending { it.weight }
    }

    /**
     * Lists all ranks, sorted by weight (highest weight first)
     * Queries directly from the database to ensure fresh data
     *
     * @return List of ranks sorted by weight in descending order
     */
    suspend fun listRanksByWeight(): List<Rank> {
        for (attempt in 1..3) {
            try {
                // Debug logging about the database query
                mongoStream.logger.info(Component.text("Fetching ranks from MongoDB collection: $RANKS_COLLECTION (attempt $attempt)", NamedTextColor.YELLOW))

                val documents = mongoStream.getDatabase().getCollection(RANKS_COLLECTION)
                    .find()
                    .sort(Document("weight", -1))  // Sort by weight in descending order
                    .asFlow()
                    .toList()
                // If no documents are found, log this as it might indicate an issue
                if (documents.isEmpty()) {
                    mongoStream.logger.warn(Component.text("No ranks found in MongoDB collection $RANKS_COLLECTION", NamedTextColor.YELLOW))
                    // Try to list all collections to see if ours exists
                    val collections = mongoStream.getDatabase().listCollectionNames().asFlow().toList()
                    mongoStream.logger.info(Component.text("Available collections: ${collections.joinToString()}", NamedTextColor.YELLOW))
                }

                // Process each document with error handling
                val ranks = mutableListOf<Rank>()
                documents.forEachIndexed { index, document ->
                    try {
                        val rank = documentToRank(document)
                        ranks.add(rank)
                        mongoStream.logger.debug(Component.text("Loaded rank: ${rank.name} (weight: ${rank.weight})", NamedTextColor.GREEN))
                    } catch (e: Exception) {
                        mongoStream.logger.error(Component.text("Failed to convert document at index $index to rank: ${e.message}", NamedTextColor.RED))
                        mongoStream.logger.debug(Component.text("Document content: $document", NamedTextColor.RED))
                    }
                }

                mongoStream.logger.info(Component.text("Successfully loaded ${ranks.size} ranks from database", NamedTextColor.GREEN))
                return ranks
            } catch (e: Exception) {
                mongoStream.logger.warn(Component.text("Attempt $attempt to load ranks failed: ${e.message}", NamedTextColor.YELLOW))
                if (attempt == 3) {
                    mongoStream.logger.error(Component.text("All attempts to load ranks failed. Using empty ranks list.", NamedTextColor.RED))
                    return emptyList()
                }
                kotlinx.coroutines.delay(1000)
            }
        }
        return emptyList()
    }

    /**
     * Saves a rank to the database
     *
     * @param rank The rank to save
     */
    private suspend fun saveRank(rank: Rank) {
        val document = rankToDocument(rank)
        val filter = Filters.eq("_id", rank.name)
        val options = ReplaceOptions().upsert(true)

        mongoStream.getDatabase().getCollection(RANKS_COLLECTION)
            .replaceOne(filter, document, options)
            .awaitFirst()
    }

    /**
     * Converts a Rank object to a MongoDB Document
     *
     * @param rank The rank to convert
     * @return The MongoDB Document representation
     */
    private fun rankToDocument(rank: Rank): Document {
        return Document()
            .append("_id", rank.name)  // Use name as the primary key
            .append("prefix", rank.prefix)
            .append("weight", rank.weight)
            .append("color", rank.color)  // Store the rank color
            .append("permissions", rank.permissions.toList())  // Store the set of additional permissions
            .append("inherits", rank.inherits)  // Store the list of inherited ranks
            .append("suffix", rank.suffix)  // Store optional general suffix
            .append("tabPrefix", rank.tabPrefix)  // Store optional tab-specific prefix
            .append("tabSuffix", rank.tabSuffix)  // Store optional tab-specific suffix
    }

    /**
     * Converts a MongoDB Document to a Rank object
     *
     * @param document The document to convert
     * @return The Rank object
     */
    private fun documentToRank(document: Document): Rank {
        // Get the list of inherited ranks, or empty list if not present
        @Suppress("UNCHECKED_CAST")
        val inherits = document.getList("inherits", String::class.java) ?: emptyList()

        // Get the set of additional permissions, or empty set if not present
        @Suppress("UNCHECKED_CAST")
        val permissions = document.getList("permissions", String::class.java)?.toSet() ?: emptySet()

        return Rank(
            name = document.getString("_id"),
            prefix = document.getString("prefix"),
            weight = document.getInteger("weight"),
            color = document.getString("color") ?: "&f", // Default to white if color not found
            permissions = permissions,
            inherits = inherits,
            suffix = document.getString("suffix"),
            tabPrefix = document.getString("tabPrefix"),
            tabSuffix = document.getString("tabSuffix")
        )
    }

    /**
     * Updates a rank's properties (prefix and weight only)
     *
     * @param name The name of the rank to update
     * @param prefix The new prefix for the rank
     * @param weight The new weight for the rank
     * @return True if the rank was updated, false if it didn't exist
     */
    suspend fun updateRankProperties(name: String, prefix: String, weight: Int): Boolean {
        val rank = getRank(name) ?: return false

        val updatedRank = rank.copy(prefix = prefix, weight = weight)
        saveRank(updatedRank)
        // Update cache
        cachedRanks[name.lowercase()] = updatedRank
        return true
    }

    /**
     * Updates a single property of a rank
     *
     * @param name The name of the rank to update
     * @param updateFunction A function that takes the current rank and returns an updated version
     * @return True if the rank was updated, false if it didn't exist
     */
    suspend fun updateRank(name: String, updateFunction: (Rank) -> Rank): Boolean {
        val rank = getRank(name) ?: return false

        val updatedRank = updateFunction(rank)
        saveRank(updatedRank)
        // Update cache
        cachedRanks[name.lowercase()] = updatedRank
        return true
    }

    /**
     * Sets the prefix of a rank
     *
     * @param name The name of the rank to update
     * @param prefix The new prefix for the rank
     * @return True if the rank was updated, false if it didn't exist
     */
    suspend fun setRankPrefix(name: String, prefix: String): Boolean {
        return updateRank(name) { it.copy(prefix = prefix) }
    }

    /**
     * Sets the weight of a rank
     *
     * @param name The name of the rank to update
     * @param weight The new weight for the rank
     * @return True if the rank was updated, false if it didn't exist
     */
    suspend fun setRankWeight(name: String, weight: Int): Boolean {
        return updateRank(name) { it.copy(weight = weight) }
    }

    /**
     * Sets the color of a rank
     *
     * @param name The name of the rank to update
     * @param color The new color code for the rank (e.g., "&c", "&a", "&b")
     * @return True if the rank was updated, false if it didn't exist
     */
    suspend fun setRankColor(name: String, color: String): Boolean {
        return updateRank(name) { it.copy(color = color) }
    }

    /**
     * Adds a permission to a rank
     *
     * @param rankName The name of the rank to modify
     * @param permission The permission to add
     * @return True if successful, false if the rank doesn't exist
     */
    suspend fun addPermissionToRank(rankName: String, permission: String): Boolean {
        val rank = getRank(rankName) ?: return false

        // If it's already in the permissions set, nothing to do
        if (rank.permissions.contains(permission)) {
            return true
        }

        // Add the permission
        val updatedPermissions = rank.permissions + permission
        val updatedRank = rank.copy(permissions = updatedPermissions)

        // Save the updated rank
        saveRank(updatedRank)

        // Update cache
        cachedRanks[rankName.lowercase()] = updatedRank

        return true
    }

    /**
     * Removes a permission from a rank
     *
     * @param rankName The name of the rank to modify
     * @param permission The permission to remove
     * @return True if successful, false if the rank doesn't exist or didn't have the permission
     */
    suspend fun removePermissionFromRank(rankName: String, permission: String): Boolean {
        val rank = getRank(rankName) ?: return false

        // If it's not in the permissions set, nothing to do
        if (!rank.permissions.contains(permission)) {
            return false
        }

        // Remove the permission
        val updatedPermissions = rank.permissions - permission
        val updatedRank = rank.copy(permissions = updatedPermissions)

        // Save the updated rank
        saveRank(updatedRank)

        // Update cache
        cachedRanks[rankName.lowercase()] = updatedRank

        return true
    }
}
