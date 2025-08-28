package radium.backend.player

import radium.backend.Radium
import java.time.Instant
import java.util.*

/**
 * Represents a player profile with permission information.
 * This class is used to manage in-memory player data that can
 * later be synchronized with Redis.
 */
class Profile {
    /**
     * Player's unique identifier
     */
    val uuid: UUID

    /**
     * Player's username
     */
    var username: String

    /**
     * Last time the player was seen online
     */
    var lastSeen: Instant = Instant.now()

    /**
     * Player settings stored as key-value pairs
     */
    private val settings: MutableMap<String, String> = HashMap()

    /**
     * Set of permissions this player has
     * For enhanced permissions, the format is "permission|granter|addedTimestamp|expirationTimestamp"
     * If no expiration, the format is "permission|granter|addedTimestamp"
     */
    private val permissions: MutableSet<String> = HashSet()

    /**
     * Set of ranks this player has
     * For ranks, the format is "rank|granter|addedTimestamp|expirationTimestamp"
     * If no expiration, the format is "rank|granter|addedTimestamp"
     */
    private val ranks: MutableSet<String> = HashSet()

    /**
     * Cached set of all effective permissions (direct + rank + inherited)
     * This is an in-memory only cache and is not persisted to the database
     */
    @Transient
    private var effectivePermissions: MutableSet<String> = HashSet()

    /**
     * Set of UUIDs of players who are friends with this player
     */
    private val friends: MutableSet<UUID> = HashSet()

    /**
     * Set of UUIDs of players who have sent a friend request to this player
     */
    private val incomingRequests: MutableSet<UUID> = HashSet()

    /**
     * Set of UUIDs of players to whom this player has sent a friend request
     */
    private val outgoingRequests: MutableSet<UUID> = HashSet()

    /**
     * In-memory cache of friends' last seen timestamps
     * This is not persisted to the database
     */
    @Transient
    private val friendsLastSeen: MutableMap<UUID, Instant> = HashMap()

    /**
     * Delimiter used to separate permission data fields
     */
    companion object {
        const val DELIMITER = "|"
        const val DEFAULT_GRANTER = "CONSOLE"

        // Position of each component in the split permission string
        const val PERM_NODE_INDEX = 0
        const val PERM_GRANTER_INDEX = 1
        const val PERM_ADDED_TIME_INDEX = 2
        const val PERM_EXPIRY_TIME_INDEX = 3

        // Position of revocation components in permission strings
        const val PERM_REVOKED_INDEX = 4
        const val PERM_REVOKED_BY_INDEX = 5
        const val PERM_REVOKED_TIME_INDEX = 6

        // Position of each component in the split rank string
        const val RANK_INDEX = 0
        const val RANK_GRANTER_INDEX = 1
        const val RANK_ADDED_TIME_INDEX = 2
        const val RANK_EXPIRY_TIME_INDEX = 3
        const val RANK_GRANT_REASON_INDEX = 4

        // Position of revocation components in rank strings
        const val RANK_REVOKED_INDEX = 5
        const val RANK_REVOKED_BY_INDEX = 6
        const val RANK_REVOKED_TIME_INDEX = 7
        const val RANK_REVOKED_REASON_INDEX = 8


        fun fromMap(data: Map<String, Any>): Profile {
            // Check if UUID is stored directly or as _id (for MongoDB)
            val uuidString = (data["uuid"] as? String) ?: (data["_id"] as? String)
            ?: throw IllegalArgumentException("Map must contain either 'uuid' or '_id' field")

            val uuid = UUID.fromString(uuidString)
            val username = data["username"] as String
            val profile = Profile(uuid, username)

            // Set lastSeen if it exists
            val lastSeenMillis = data["lastSeen"] as? Number
            if (lastSeenMillis != null) {
                profile.lastSeen = java.time.Instant.ofEpochMilli(lastSeenMillis.toLong())
            }

            // Add settings if they exist
            @Suppress("UNCHECKED_CAST")
            val settings = data["settings"] as? Map<String, String>
            if (settings != null) {
                for ((key, value) in settings) {
                    profile.setSetting(key, value)
                }
            }

            // Add permissions
            @Suppress("UNCHECKED_CAST")
            val permissions = data["permissions"] as? List<String>
            permissions?.forEach { permString ->
                profile.addRawPermission(permString)
            }

            // Add ranks
            @Suppress("UNCHECKED_CAST")
            val ranks = data["ranks"] as? List<String>
            ranks?.forEach { rankString ->
                profile.addRawRank(rankString)
            }

            // Add friends if they exist
            @Suppress("UNCHECKED_CAST")
            val friends = data["friends"] as? List<String>
            friends?.forEach { uuidString ->
                try {
                    profile.friends.add(UUID.fromString(uuidString))
                } catch (e: IllegalArgumentException) {
                    // Invalid UUID string, skip
                }
            }

            // Add incoming requests if they exist
            @Suppress("UNCHECKED_CAST")
            val incomingRequests = data["incomingRequests"] as? List<String>
            incomingRequests?.forEach { uuidString ->
                try {
                    profile.incomingRequests.add(UUID.fromString(uuidString))
                } catch (e: IllegalArgumentException) {
                    // Invalid UUID string, skip
                }
            }

            // Add outgoing requests if they exist
            @Suppress("UNCHECKED_CAST")
            val outgoingRequests = data["outgoingRequests"] as? List<String>
            outgoingRequests?.forEach { uuidString ->
                try {
                    profile.outgoingRequests.add(UUID.fromString(uuidString))
                } catch (e: IllegalArgumentException) {
                    // Invalid UUID string, skip
                }
            }

            return profile
        }


    }

    /**
     * Constructs a new Profile with the given UUID and username
     *
     * @param uuid Player's unique identifier
     * @param username Player's username
     */
    constructor(uuid: UUID, username: String) {
        this.uuid = uuid
        this.username = username
    }

    /**
     * Adds a permanent permission to this player's profile
     *
     * @param permission The permission to add
     * @param granter The name of who granted this permission
     * @return true if the permission was added, false if it already existed
     */
    fun addPermission(permission: String, granter: String = DEFAULT_GRANTER): Boolean {
        val lowercasePermission = permission.lowercase()

        // Remove any existing permission (including timed versions)
        removePermissionInternal(lowercasePermission)

        // Add the permission with granter and timestamp
        // Add null for expiration time to keep format consistent with timed permissions
        val permString = "$lowercasePermission$DELIMITER$granter$DELIMITER${Instant.now().toEpochMilli()}${DELIMITER}null"
        return permissions.add(permString)
    }

    /**
     * Adds a permission with an expiration time to this player's profile
     *
     * @param permission The permission to add
     * @param expiration The time when the permission expires
     * @param granter The name of who granted this permission
     * @return true if the permission was added, false if it already existed
     */
    fun addPermission(permission: String, expiration: Instant, granter: String = DEFAULT_GRANTER): Boolean {
        val lowercasePermission = permission.lowercase()

        // Remove any existing permission (including timed versions)
        removePermissionInternal(lowercasePermission)

        // Add the permission with granter, timestamp and expiration time
        val addedTimestamp = Instant.now().toEpochMilli()
        val permString = "$lowercasePermission$DELIMITER$granter$DELIMITER$addedTimestamp$DELIMITER${expiration.toEpochMilli()}"
        return permissions.add(permString)
    }

    /**
     * Removes a permission from this player's profile
     * (Now actually revokes it rather than deleting it)
     *
     * @param permission The permission to revoke
     * @param revokedBy Who revoked the permission
     * @param reason Optional reason for revocation
     * @return true if the permission was revoked, false if it wasn't found
     */
    fun removePermission(permission: String, revokedBy: String = DEFAULT_GRANTER, reason: String? = null): Boolean {
        val lowercasePermission = permission.lowercase()

        // Find all active permissions with this name
        val toRevoke = permissions.filter { permString ->
            val parts = permString.split(DELIMITER)
            if (parts.isEmpty()) false
            else {
                // Permission matches and is not already revoked
                parts[PERM_NODE_INDEX] == lowercasePermission &&
                (parts.size <= PERM_REVOKED_INDEX || parts[PERM_REVOKED_INDEX] != "revoked")
            }
        }

        if (toRevoke.isEmpty()) {
            return false
        }

        // Revoke each permission by marking it and adding to the set
        val now = Instant.now().toEpochMilli()
        var success = false

        toRevoke.forEach { permString ->
            // Remove the current permission
            permissions.remove(permString)

            // Add it back with revocation information
            val revokedString = if (reason != null) {
                "$permString$DELIMITER${"revoked"}$DELIMITER$revokedBy$DELIMITER$now$DELIMITER$reason"
            } else {
                "$permString$DELIMITER${"revoked"}$DELIMITER$revokedBy$DELIMITER$now"
            }

            success = permissions.add(revokedString) || success
        }

        return success
    }

    /**
     * Internal implementation of permission removal
     * Now used only by addPermission to replace existing permissions with new ones
     *
     * @param lowercasePermission The permission to remove (lowercase)
     * @return true if any permission was removed, false otherwise
     */
    private fun removePermissionInternal(lowercasePermission: String): Boolean {
        // Check for permissions that start with this permission name followed by delimiter
        val toRemove = permissions.filter { permString ->
            val permPart = permString.split(DELIMITER, limit = 2).firstOrNull() ?: ""
            permPart == lowercasePermission
        }

        return if (toRemove.isNotEmpty()) {
            permissions.removeAll(toRemove)
            true
        } else {
            false
        }
    }

    /**
     * Checks if the player has a specific permission
     *
     * @param permission The permission to check
     * @return true if the player has the permission, false otherwise
     */
    fun hasPermission(permission: String): Boolean {
        val lowercasePermission = permission.lowercase()

        // If no rank has the permission, check the player's direct permissions
        val now = Instant.now().toEpochMilli()

        // First check for exact permission match
        if (permissions.any { permString ->
            val parts = permString.split(DELIMITER)
            if (parts.isEmpty()) {
                false
            } else {
                // Check if permission matches and is not revoked
                val matchesAndNotRevoked = parts[PERM_NODE_INDEX] == lowercasePermission &&
                        (parts.size <= PERM_REVOKED_INDEX || parts[PERM_REVOKED_INDEX] != "revoked")

                if (!matchesAndNotRevoked) {
                    false
                } else {
                    // Check if it's a timed permission and not expired
                    if (parts.size > PERM_EXPIRY_TIME_INDEX && parts[PERM_EXPIRY_TIME_INDEX] != "null") {
                        try {
                            val expiration = parts[PERM_EXPIRY_TIME_INDEX].toLong()
                            expiration > now
                        } catch (e: NumberFormatException) {
                            // Invalid format, consider it expired
                            false
                        }
                    } else {
                        // This is a permanent permission
                        true
                    }
                }
            }
        }) {
            return true
        }

        // If no exact match, check for wildcard permissions
        val permParts = lowercasePermission.split(".")

        // Check for wildcard at different levels (e.g., for command.grant.add, check command.* and command.grant.*)
        for (i in permParts.indices) {
            val wildcardBase = permParts.subList(0, i + 1).joinToString(".")
            val wildcard = "$wildcardBase.*"

            if (permissions.any { permString ->
                val parts = permString.split(DELIMITER)
                if (parts.isEmpty()) {
                    false
                } else {
                    // Check if permission matches the wildcard and is not revoked
                    val matchesAndNotRevoked = parts[PERM_NODE_INDEX] == wildcard &&
                            (parts.size <= PERM_REVOKED_INDEX || parts[PERM_REVOKED_INDEX] != "revoked")

                    if (!matchesAndNotRevoked) {
                        false
                    } else {
                        // Check if it's a timed permission and not expired
                        if (parts.size > PERM_EXPIRY_TIME_INDEX && parts[PERM_EXPIRY_TIME_INDEX] != "null") {
                            try {
                                val expiration = parts[PERM_EXPIRY_TIME_INDEX].toLong()
                                expiration > now
                            } catch (e: NumberFormatException) {
                                // Invalid format, consider it expired
                                false
                            }
                        } else {
                            // This is a permanent permission
                            true
                        }
                    }
                }
            }) {
                return true
            }
        }

        // Finally check for the global wildcard "*"
        return permissions.any { permString ->
            val parts = permString.split(DELIMITER)
            if (parts.isEmpty()) {
                false
            } else {
                // Check if permission is the global wildcard and is not revoked
                val isGlobalWildcard = parts[PERM_NODE_INDEX] == "*" &&
                        (parts.size <= PERM_REVOKED_INDEX || parts[PERM_REVOKED_INDEX] != "revoked")

                if (!isGlobalWildcard) {
                    false
                } else {
                    // Check if it's a timed permission and not expired
                    if (parts.size > PERM_EXPIRY_TIME_INDEX && parts[PERM_EXPIRY_TIME_INDEX] != "null") {
                        try {
                            val expiration = parts[PERM_EXPIRY_TIME_INDEX].toLong()
                            expiration > now
                        } catch (e: NumberFormatException) {
                            // Invalid format, consider it expired
                            false
                        }
                    } else {
                        // This is a permanent permission
                        true
                    }
                }
            }
        }
    }

    /**
     * Gets an unmodifiable view of all permissions this player has
     * For enhanced permissions, only the permission part is returned
     *
     * @return Set of all permissions
     */
    fun getPermissions(): Set<String> {
        // Remove expired permissions first
        cleanExpiredPermissions()

        // Extract just the permission part
        val result = mutableSetOf<String>()

        permissions.forEach { permString ->
            val parts = permString.split(DELIMITER)
            if (parts.isNotEmpty()) {
                result.add(parts[PERM_NODE_INDEX])
            }
        }

        return Collections.unmodifiableSet(result)
    }

    /**
     * Gets detailed information about all permissions
     *
     * @return Map of permission name to permission details (granter, added time, expiry time)
     */
    fun getPermissionDetails(): Map<String, PermissionDetails> {
        // Remove expired permissions first
        cleanExpiredPermissions()

        val result = mutableMapOf<String, PermissionDetails>()

        permissions.forEach { permString ->
            val parts = permString.split(DELIMITER)
            if (parts.isNotEmpty()) {
                val permName = parts[PERM_NODE_INDEX]
                val granter = if (parts.size > PERM_GRANTER_INDEX) parts[PERM_GRANTER_INDEX] else DEFAULT_GRANTER

                val addedTime = if (parts.size > PERM_ADDED_TIME_INDEX) {
                    try {
                        Instant.ofEpochMilli(parts[PERM_ADDED_TIME_INDEX].toLong())
                    } catch (e: NumberFormatException) {
                        Instant.now()
                    }
                } else {
                    Instant.now()
                }

                val expiryTime = if (parts.size > PERM_EXPIRY_TIME_INDEX) {
                    try {
                        Instant.ofEpochMilli(parts[PERM_EXPIRY_TIME_INDEX].toLong())
                    } catch (e: NumberFormatException) {
                        null
                    }
                } else {
                    null
                }

                result[permName] = PermissionDetails(granter, addedTime, expiryTime)
            }
        }

        return Collections.unmodifiableMap(result)
    }

    /**
     * Data class to hold permission details
     */
    data class PermissionDetails(
        val granter: String,
        val addedTime: Instant,
        val expiryTime: Instant?
    )

    /**
     * Gets all permissions with their expiration times
     *
     * @return Map of permissions to their expiration times (null for permanent permissions)
     */
    fun getPermissionExpirations(): Map<String, Instant?> {
        // Remove expired permissions first
        cleanExpiredPermissions()

        val result = mutableMapOf<String, Instant?>()

        permissions.forEach { permString ->
            val parts = permString.split(DELIMITER)
            if (parts.isNotEmpty()) {
                val permName = parts[PERM_NODE_INDEX]

                // Check if there is an expiry time
                val expiryTime = if (parts.size > PERM_EXPIRY_TIME_INDEX) {
                    try {
                        Instant.ofEpochMilli(parts[PERM_EXPIRY_TIME_INDEX].toLong())
                    } catch (e: NumberFormatException) {
                        null
                    }
                } else {
                    null
                }

                result[permName] = expiryTime
            }
        }

        return Collections.unmodifiableMap(result)
    }

    /**
     * Gets the expiration time for a permission
     *
     * @param permission The permission to check
     * @return The expiration time, or null if the permission is permanent or doesn't exist
     */
    fun getPermissionExpiration(permission: String): Instant? {
        val lowercasePermission = permission.lowercase()

        // Check for this permission
        val matchingPermission = permissions.find { permString ->
            val parts = permString.split(DELIMITER)
            if (parts.isEmpty()) false
            else parts[PERM_NODE_INDEX] == lowercasePermission
        } ?: return null

        // Extract expiration time if it exists
        val parts = matchingPermission.split(DELIMITER)
        return if (parts.size > PERM_EXPIRY_TIME_INDEX) {
            try {
                Instant.ofEpochMilli(parts[PERM_EXPIRY_TIME_INDEX].toLong())
            } catch (e: NumberFormatException) {
                null
            }
        } else {
            null
        }
    }

    /**
     * Gets the raw permission strings
     *
     * @return Set of raw permission strings
     */
    fun getRawPermissions(): Set<String> {
        return Collections.unmodifiableSet(permissions)
    }

    /**
     * Removes all expired permissions
     */
    private fun cleanExpiredPermissions() {
        val now = Instant.now().toEpochMilli()
        val expiredPermissions = permissions.filter { permString ->
            val parts = permString.split(DELIMITER)
            if (parts.size > PERM_EXPIRY_TIME_INDEX) {
                try {
                    val expiration = parts[PERM_EXPIRY_TIME_INDEX].toLong()
                    return@filter expiration <= now
                } catch (e: NumberFormatException) {
                    return@filter false
                }
            }
            false
        }

        if (expiredPermissions.isNotEmpty()) {
            permissions.removeAll(expiredPermissions)
        }
    }

    /**
     * Clears all permissions from this player's profile
     */
    fun clearPermissions() {
        permissions.clear()
    }

    /**
     * Adds a raw permission string directly to the permissions set
     * For internal use by serialization classes
     *
     * @param rawPermission The raw permission string
     */
    internal fun addRawPermission(rawPermission: String) {
        permissions.add(rawPermission)
    }

    /**
     * Adds a permanent rank to this player's profile
     *
     * @param rank The rank to add
     * @param granter The name of who granted this rank
     * @return true if the rank was added, false if it already existed
     */
    fun addRank(rank: String, granter: String = DEFAULT_GRANTER): Boolean {
        // Remove any existing rank (including timed versions)
        removeRankInternal(rank)

        // Add the rank with granter and timestamp
        // Format: rank|granter|timestamp|null
        val rankString = "$rank$DELIMITER$granter$DELIMITER${Instant.now().toEpochMilli()}${DELIMITER}null"
        return ranks.add(rankString)
    }

    /**
     * Adds a rank with an expiration time to this player's profile
     *
     * @param rank The rank to add
     * @param expiration The time when the rank expires
     * @param granter The name of who granted this rank
     * @return true if the rank was added, false if it already existed
     */
    fun addRank(rank: String, expiration: Instant, granter: String = DEFAULT_GRANTER): Boolean {
        // Remove any existing rank (including timed versions)
        removeRankInternal(rank)

        // Add the rank with granter, timestamp and expiration time
        // Format: rank|granter|timestamp|expirationTimestamp
        val addedTimestamp = Instant.now().toEpochMilli()
        val rankString = "$rank$DELIMITER$granter$DELIMITER$addedTimestamp$DELIMITER${expiration.toEpochMilli()}"
        return ranks.add(rankString)
    }

    /**
     * Adds a permanent rank to this player's profile with a reason
     *
     * @param rank The rank to add
     * @param granter The name of who granted this rank
     * @param reason The reason for granting the rank
     * @return true if the rank was added, false if it already existed
     */
    fun addRank(rank: String, granter: String = DEFAULT_GRANTER, reason: String? = null): Boolean {
        // Remove any existing rank (including timed versions)
        removeRankInternal(rank)

        // Add the rank with granter, timestamp, and reason if provided
        // Format: rank|granter|timestamp|null|reason
        val rankString = if (reason != null && reason.isNotEmpty()) {
            "$rank$DELIMITER$granter$DELIMITER${Instant.now().toEpochMilli()}${DELIMITER}null$DELIMITER$reason"
        } else {
            "$rank$DELIMITER$granter$DELIMITER${Instant.now().toEpochMilli()}${DELIMITER}null${DELIMITER}" // Added empty field for reason
        }
        return ranks.add(rankString)
    }

    /**
     * Adds a rank with an expiration time to this player's profile with a reason
     *
     * @param rank The rank to add
     * @param expiration The time when the rank expires
     * @param granter The name of who granted this rank
     * @param reason The reason for granting the rank
     * @return true if the rank was added, false if it already existed
     */
    fun addRank(rank: String, expiration: Instant, granter: String = DEFAULT_GRANTER, reason: String? = null): Boolean {
        // Remove any existing rank (including timed versions)
        removeRankInternal(rank)

        // Add the rank with granter, timestamp, expiration time, and reason if provided
        // Format: rank|granter|timestamp|expirationTimestamp|reason
        val addedTimestamp = Instant.now().toEpochMilli()
        val rankString = if (reason != null && reason.isNotEmpty()) {
            "$rank$DELIMITER$granter$DELIMITER$addedTimestamp$DELIMITER${expiration.toEpochMilli()}$DELIMITER$reason"
        } else {
            "$rank$DELIMITER$granter$DELIMITER$addedTimestamp$DELIMITER${expiration.toEpochMilli()}$DELIMITER" // Added empty field for reason
        }
        return ranks.add(rankString)
    }

    /**
     * Removes a rank from this player's profile
     * (Now actually revokes it rather than deleting it)
     *
     * @param rank The rank to revoke
     * @param revokedBy Who revoked the rank
     * @param reason Optional reason for revocation
     * @return true if the rank was revoked, false if it wasn't found
     */
    fun removeRank(rank: String, revokedBy: String = DEFAULT_GRANTER, reason: String? = null): Boolean {
        // Find all active ranks with this name
        val toRevoke = ranks.filter { rankString ->
            val parts = rankString.split(DELIMITER)
            if (parts.isEmpty()) false
            else {
                // Rank matches and is not already revoked
                parts[RANK_INDEX] == rank &&
                (parts.size <= RANK_REVOKED_INDEX || parts[RANK_REVOKED_INDEX] != "revoked")
            }
        }

        if (toRevoke.isEmpty()) {
            return false
        }

        // Revoke each rank by marking it and adding to the set
        val now = Instant.now().toEpochMilli()
        var success = false

        toRevoke.forEach { rankString ->
            // Remove the current rank
            ranks.remove(rankString)

            // Add it back with revocation information
            // Format: rank|granter|timestamp|expirationTimestamp|reason|revoked|revoker|revokeTimestamp|revokeReason
            val revokedString = if (reason != null && reason.isNotEmpty()) {
                "$rankString${DELIMITER}revoked$DELIMITER$revokedBy$DELIMITER$now$DELIMITER$reason"
            } else {
                "$rankString${DELIMITER}revoked$DELIMITER$revokedBy$DELIMITER$now$DELIMITER" // Added an empty field for consistency
            }

            success = ranks.add(revokedString) || success
        }

        return success
    }

    /**
     * Internal implementation of rank removal
     * Now used only by addRank to replace existing ranks with new ones
     *
     * @param rank The rank to remove
     * @return true if any rank was removed, false otherwise
     */
    private fun removeRankInternal(rank: String): Boolean {
        // Check for ranks that match this rank name
        val toRemove = ranks.filter { rankString ->
            val rankPart = rankString.split(DELIMITER, limit = 2).firstOrNull() ?: ""
            rankPart == rank
        }

        return if (toRemove.isNotEmpty()) {
            ranks.removeAll(toRemove)
            true
        } else {
            false
        }
    }

    /**
     * Checks if the player has a specific rank
     *
     * @param rank The rank to check
     * @return true if the player has the rank, false otherwise
     */
    fun hasRank(rank: String): Boolean {
        // Check all ranks
        val matchingRanks = ranks.filter { rankString ->
            val parts = rankString.split(DELIMITER)
            if (parts.isEmpty()) false
            else {
                // Rank matches and is not already revoked
                parts[RANK_INDEX] == rank &&
                (parts.size <= RANK_REVOKED_INDEX || parts[RANK_REVOKED_INDEX] != "revoked")
            }
        }

        if (matchingRanks.isEmpty()) {
            return false
        }

        // Check if any of the ranks are still valid
        val now = Instant.now().toEpochMilli()
        var hasValid = false
        val expiredRanks = mutableListOf<String>()

        for (rankString in matchingRanks) {
            val parts = rankString.split(DELIMITER)
            // Check if this is a timed rank
            if (parts.size > RANK_EXPIRY_TIME_INDEX) {
                try {
                    val expiration = parts[RANK_EXPIRY_TIME_INDEX].toLong()
                    if (expiration > now) {
                        hasValid = true
                    } else {
                        expiredRanks.add(rankString)
                    }
                } catch (e: NumberFormatException) {
                    // Invalid format, consider it expired
                    expiredRanks.add(rankString)
                }
            } else {
                // This is a permanent rank
                hasValid = true
            }
        }

        // Remove all expired ranks
        if (expiredRanks.isNotEmpty()) {
            ranks.removeAll(expiredRanks)
        }

        return hasValid
    }

    /**
     * Gets an unmodifiable view of all active ranks this player has
     * Only the rank name is returned (excludes revoked ranks)
     *
     * @return Set of active ranks
     */
    fun getRanks(): Set<String> {
        // Remove expired ranks first
        cleanExpiredRanks()

        // Extract just the rank part, excluding revoked ranks
        val result = mutableSetOf<String>()

        ranks.forEach { rankString ->
            val parts = rankString.split(DELIMITER)
            if (parts.isNotEmpty()) {
                // Check if rank is revoked
                val isRevoked = parts.size > RANK_REVOKED_INDEX && parts[RANK_REVOKED_INDEX] == "revoked"
                if (!isRevoked) {
                    result.add(parts[RANK_INDEX])
                }
            }
        }

        return Collections.unmodifiableSet(result)
    }

    /**
     * Data class to hold rank details
     */
    data class RankDetails(
        val granter: String,
        val addedTime: Instant,
        val expiryTime: Instant?,
        val reason: String? = null
    )

    /**
     * Gets detailed information about all active ranks (excludes revoked ranks)
     *
     * @return Map of rank name to rank details (granter, added time, expiry time)
     */
    fun getRankDetails(): Map<String, RankDetails> {
        // Remove expired ranks first
        cleanExpiredRanks()

        val result = mutableMapOf<String, RankDetails>()

        ranks.forEach { rankString ->
            val parts = rankString.split(DELIMITER)
            if (parts.isNotEmpty()) {
                // Check if rank is revoked
                val isRevoked = parts.size > RANK_REVOKED_INDEX && parts[RANK_REVOKED_INDEX] == "revoked"
                if (!isRevoked) {
                    val rankName = parts[RANK_INDEX]
                    val granter = if (parts.size > RANK_GRANTER_INDEX) parts[RANK_GRANTER_INDEX] else DEFAULT_GRANTER

                    val addedTime = if (parts.size > RANK_ADDED_TIME_INDEX) {
                        try {
                            Instant.ofEpochMilli(parts[RANK_ADDED_TIME_INDEX].toLong())
                        } catch (e: NumberFormatException) {
                            Instant.now()
                        }
                    } else {
                        Instant.now()
                    }

                    val expiryTime = if (parts.size > RANK_EXPIRY_TIME_INDEX) {
                        try {
                            Instant.ofEpochMilli(parts[RANK_EXPIRY_TIME_INDEX].toLong())
                        } catch (e: NumberFormatException) {
                            null
                        }
                    } else {
                        null
                    }

                    // Get the reason if it exists using RANK_GRANT_REASON_INDEX
                    val reason = if (parts.size > RANK_GRANT_REASON_INDEX) parts[RANK_GRANT_REASON_INDEX] else null

                    result[rankName] = RankDetails(granter, addedTime, expiryTime, reason)
                }
            }
        }

        return Collections.unmodifiableMap(result)
    }

    /**
     * Gets the expiration time for a rank
     *
     * @param rank The rank to check
     * @return The expiration time, or null if the rank is permanent or doesn't exist
     */
    fun getRankExpiration(rank: String): Instant? {
        // Check for this rank
        val matchingRank = ranks.find { rankString ->
            val parts = rankString.split(DELIMITER)
            if (parts.isEmpty()) false
            else parts[RANK_INDEX] == rank
        } ?: return null

        // Extract expiration time if it exists
        val parts = matchingRank.split(DELIMITER)
        return if (parts.size > RANK_EXPIRY_TIME_INDEX) {
            try {
                Instant.ofEpochMilli(parts[RANK_EXPIRY_TIME_INDEX].toLong())
            } catch (e: NumberFormatException) {
                null
            }
        } else {
            null
        }
    }

    /**
     * Gets the raw rank strings
     *
     * @return Set of raw rank strings
     */
    fun getRawRanks(): Set<String> {
        return Collections.unmodifiableSet(ranks)
    }

    /**
     * Removes only expired ranks from active consideration
     * Revoked ranks are kept in the data for history but excluded from active rank calculations
     */
    private fun cleanExpiredRanks() {
        val now = Instant.now().toEpochMilli()
        val expiredRanks = ranks.filter { rankString ->
            val parts = rankString.split(DELIMITER)
            
            // Don't remove revoked ranks - keep them for grants history
            val isRevoked = parts.size > RANK_REVOKED_INDEX && parts[RANK_REVOKED_INDEX] == "revoked"
            if (isRevoked) {
                return@filter false
            }
            
            // Only remove ranks that are expired by time
            if (parts.size > RANK_EXPIRY_TIME_INDEX && parts[RANK_EXPIRY_TIME_INDEX] != "null") {
                try {
                    val expiration = parts[RANK_EXPIRY_TIME_INDEX].toLong()
                    return@filter expiration <= now
                } catch (e: NumberFormatException) {
                    return@filter false
                }
            }
            false
        }

        if (expiredRanks.isNotEmpty()) {
            ranks.removeAll(expiredRanks)
        }
    }

    /**
     * Clears all ranks from this player's profile
     */
    fun clearRanks() {
        ranks.clear()
    }

    /**
     * Adds a raw rank string directly to the ranks set
     * For internal use by serialization classes
     *
     * @param rawRank The raw rank string
     */
    internal fun addRawRank(rawRank: String) {
        ranks.add(rawRank)
    }

    /**
     * Updates the effective permissions cache for this profile by combining:
     * - Direct permissions from the profile
     * - Permissions from all ranks the player has
     * - Permissions from all ranks inherited by the player's ranks
     *
     * This method is memory-only and the cache is not persisted to the database.
     *
     * @param rankManager The rank manager to get rank permissions from
     */
    suspend fun updateEffectivePermissions(rankManager: RankManager) {
        // Start with the player's direct permissions
        val allPermissions = mutableSetOf<String>()
        allPermissions.addAll(getPermissions())

        // Add permissions from all ranks and their inherited ranks
        val playerRanks = getRanks()
        for (rankName in playerRanks) {
            val rank = rankManager.getRank(rankName)
            if (rank != null) {
                // Add the rank's direct permissions
                allPermissions.addAll(rank.permissions)

                // Add inherited permissions
                val inheritedRanks = rankManager.getAllInheritedRanks(rankName)
                for (inheritedRankName in inheritedRanks) {
                    val inheritedRank = rankManager.getRank(inheritedRankName)
                    if (inheritedRank != null) {
                        allPermissions.addAll(inheritedRank.permissions)
                    }
                }
            }
        }

        // Update the effective permissions cache
        effectivePermissions = allPermissions
    }

    /**
     * Gets the highest weight rank this player has
     * @param rankManager The rank manager to get rank information from
     * @return The rank object with the highest weight, or null if no ranks
     */
    suspend fun getHighestRank(rankManager: RankManager): RankManager.Rank? {
        val playerRanks = getRanks()
        if (playerRanks.isEmpty()) return null
        
        var highestRank: RankManager.Rank? = null
        var highestWeight = Int.MIN_VALUE
        
        for (rankName in playerRanks) {
            val rank = rankManager.getRank(rankName)
            if (rank != null && rank.weight > highestWeight) {
                highestWeight = rank.weight
                highestRank = rank
            }
        }
        
        return highestRank
    }

    /**
     * Gets the effective rank for this player, falling back to default rank if none assigned
     */
    suspend fun getEffectiveRank(rankManager: RankManager): RankManager.Rank {
        return getHighestRank(rankManager) ?: getDefaultRank(rankManager)
    }
    
    /**
     * Gets the default rank for players with no assigned ranks
     */
    private suspend fun getDefaultRank(rankManager: RankManager): RankManager.Rank {
        return rankManager.getRank("Default") ?: RankManager.Rank(
            name = "Default",
            prefix = "&7",
            weight = 0,
            color = "&7",
            permissions = setOf(),
            inherits = listOf(),
            suffix = "",
            tabPrefix = "",
            tabSuffix = ""
        )
    }

    /**
     * Fast permission check using the effective permissions cache.
     * Make sure to call updateEffectivePermissions() first to ensure the cache is up to date.
     *
     * @param permission The permission to check
     * @return true if the player has the permission, false otherwise
     */
    fun hasEffectivePermission(permission: String): Boolean {
        val lowercasePermission = permission.lowercase()

        // Direct match
        if (effectivePermissions.contains(lowercasePermission)) {
            return true
        }

        // Wildcard matching
        val permParts = lowercasePermission.split(".")
        for (i in permParts.indices) {
            val wildcardBase = permParts.subList(0, i + 1).joinToString(".")
            val wildcard = "$wildcardBase.*"

            if (effectivePermissions.contains(wildcard)) {
                return true
            }
        }

        // Global wildcard
        return effectivePermissions.contains("*")
    }

    /**
     * Gets all effective permissions for this player (direct + from ranks + inherited)
     * @return An unmodifiable set of all effective permissions
     */
    fun getEffectivePermissions(): Set<String> {
        return Collections.unmodifiableSet(effectivePermissions)
    }

    /**
     * Clears the effective permissions cache
     */
    fun clearEffectivePermissionsCache() {
        effectivePermissions.clear()
    }

    /**
     * Gets all permissions including revoked or expired ones
     * Each permission is returned with its status (active, revoked, expired)
     *
     * @return Map of permission name to status information
     */
    fun getAllPermissionsWithStatus(): Map<String, PermissionStatus> {
        val now = Instant.now().toEpochMilli()
        val result = mutableMapOf<String, PermissionStatus>()

        permissions.forEach { permString ->
            val parts = permString.split(DELIMITER)
            if (parts.isNotEmpty()) {
                val permName = parts[PERM_NODE_INDEX]
                val granter = if (parts.size > PERM_GRANTER_INDEX) parts[PERM_GRANTER_INDEX] else DEFAULT_GRANTER

                val addedTime = if (parts.size > PERM_ADDED_TIME_INDEX) {
                    try {
                        Instant.ofEpochMilli(parts[PERM_ADDED_TIME_INDEX].toLong())
                    } catch (e: NumberFormatException) {
                        Instant.now()
                    }
                } else {
                    Instant.now()
                }

                // Check if revoked
                val isRevoked = parts.size > PERM_REVOKED_INDEX && parts[PERM_REVOKED_INDEX] == "revoked"
                val revokedBy = if (isRevoked && parts.size > PERM_REVOKED_BY_INDEX) parts[PERM_REVOKED_BY_INDEX] else null
                val revokedTime = if (isRevoked && parts.size > PERM_REVOKED_TIME_INDEX) {
                    try {
                        Instant.ofEpochMilli(parts[PERM_REVOKED_TIME_INDEX].toLong())
                    } catch (e: NumberFormatException) {
                        null
                    }
                } else null

                // Check if expired
                var isExpired = false
                var expiryTime: Instant? = null

                if (parts.size > PERM_EXPIRY_TIME_INDEX) {
                    try {
                        val expiration = parts[PERM_EXPIRY_TIME_INDEX].toLong()
                        if (expiration <= now) {
                            isExpired = true
                        }
                        expiryTime = Instant.ofEpochMilli(expiration)
                    } catch (e: NumberFormatException) {
                        // Invalid format
                    }
                }

                // Create status object
                val status = PermissionStatus(
                    permName,
                    granter,
                    addedTime,
                    expiryTime,
                    isActive = !isRevoked && !isExpired,
                    isRevoked = isRevoked,
                    isExpired = isExpired,
                    revokedBy = revokedBy,
                    revokedTime = revokedTime
                )

                // Add to result map (we might get multiple entries for the same permission,
                // but we're only interested in the most recent one)
                if (!result.containsKey(permName) ||
                    (result[permName]!!.addedTime.isBefore(addedTime) && !status.isRevoked)) {
                    result[permName] = status
                }
            }
        }

        return result
    }

    /**
     * Data class to hold permission status information
     */
    data class PermissionStatus(
        val name: String,
        val granter: String,
        val addedTime: Instant,
        val expiryTime: Instant?,
        val isActive: Boolean,
        val isRevoked: Boolean,
        val isExpired: Boolean,
        val revokedBy: String?,
        val revokedTime: Instant?
    )

    /**
     * Gets all ranks including revoked or expired ones
     * Each rank is returned with its status (active, revoked, expired)
     *
     * @return Map of rank name to status information
     */
    fun getAllRanksWithStatus(): Map<String, RankStatus> {
        val now = Instant.now().toEpochMilli()
        val result = mutableMapOf<String, RankStatus>()

        ranks.forEach { rankString ->
            val parts = rankString.split(DELIMITER)
            if (parts.isNotEmpty()) {
                val rankName = parts[RANK_INDEX]
                val granter = if (parts.size > RANK_GRANTER_INDEX) parts[RANK_GRANTER_INDEX] else DEFAULT_GRANTER

                val addedTime = if (parts.size > RANK_ADDED_TIME_INDEX) {
                    try {
                        Instant.ofEpochMilli(parts[RANK_ADDED_TIME_INDEX].toLong())
                    } catch (e: NumberFormatException) {
                        Instant.now()
                    }
                } else {
                    Instant.now()
                }

                // Check if revoked
                val isRevoked = parts.size > RANK_REVOKED_INDEX && parts[RANK_REVOKED_INDEX] == "revoked"
                val revokedBy = if (isRevoked && parts.size > RANK_REVOKED_BY_INDEX) parts[RANK_REVOKED_BY_INDEX] else null
                val revokedTime = if (isRevoked && parts.size > RANK_REVOKED_TIME_INDEX) {
                    try {
                        Instant.ofEpochMilli(parts[RANK_REVOKED_TIME_INDEX].toLong())
                    } catch (e: NumberFormatException) {
                        null
                    }
                } else null

                // Get revocation reason if available
                val revokedReason = if (isRevoked && parts.size > RANK_REVOKED_REASON_INDEX) parts[RANK_REVOKED_REASON_INDEX] else null

                // Check if expired
                var isExpired = false
                var expiryTime: Instant? = null

                if (parts.size > RANK_EXPIRY_TIME_INDEX && parts[RANK_EXPIRY_TIME_INDEX] != "null") {
                    try {
                        val expiration = parts[RANK_EXPIRY_TIME_INDEX].toLong()
                        if (expiration <= now) {
                            isExpired = true
                        }
                        expiryTime = Instant.ofEpochMilli(expiration)
                    } catch (e: NumberFormatException) {
                        // Invalid format
                    }
                }

                // Create status object
                val status = RankStatus(
                    rankName,
                    granter,
                    addedTime,
                    expiryTime,
                    isActive = !isRevoked && !isExpired,
                    isRevoked = isRevoked,
                    isExpired = isExpired,
                    revokedBy = revokedBy,
                    revokedTime = revokedTime,
                    revokedReason = revokedReason
                )

                // Add to result map (we might get multiple entries for the same rank,
                // but we're only interested in the most recent one)
                if (!result.containsKey(rankName) ||
                    (result[rankName]!!.addedTime.isBefore(addedTime) && !status.isRevoked)) {
                    result[rankName] = status
                }
            }
        }

        return result
    }

    /**
     * Data class to hold rank status information
     */
    data class RankStatus(
        val name: String,
        val granter: String,
        val addedTime: Instant,
        val expiryTime: Instant?,
        val isActive: Boolean,
        val isRevoked: Boolean,
        val isExpired: Boolean,
        val revokedBy: String?,
        val revokedTime: Instant?,
        val revokedReason: String?
    )

    /**
     * Gets the value of a specific setting
     *
     * @param key The setting key
     * @return The setting value, or null if not set
     */
    fun getSetting(key: String): String? {
        return settings[key]
    }

    /**
     * Sets the value of a specific setting
     *
     * @param key The setting key
     * @param value The setting value
     */
    fun setSetting(key: String, value: String) {
        settings[key] = value
    }

    /**
     * Removes a specific setting
     *
     * @param key The setting key
     */
    fun removeSetting(key: String) {
        settings.remove(key)
    }

    /**
     * Clears all settings
     */
    fun clearSettings() {
        settings.clear()
    }

    /**
     * Gets all settings as an unmodifiable map
     *
     * @return Map of all settings
     */
    fun getSettings(): Map<String, String> {
        return Collections.unmodifiableMap(settings)
    }

    /**
     * Converts the profile to a Map representation for storage
     *
     * @return Map containing all profile data
     */
    fun toMap(): Map<String, Any> {
        return mapOf(
            // Always include uuid for Redis and other stores
            "uuid" to uuid.toString(),
            "username" to username,
            "lastSeen" to lastSeen.toEpochMilli(),
            "settings" to settings,
            "permissions" to permissions.toList(),
            "ranks" to ranks.toList(),
            "friends" to friends.map { it.toString() },
            "incomingRequests" to incomingRequests.map { it.toString() },
            "outgoingRequests" to outgoingRequests.map { it.toString() }
        )
    }

    /**
     * Enum representing the possible outcomes of friend operations
     */
    enum class FriendResult {
        SENT,              // Friend request sent successfully
        ACCEPTED,          // Friend request accepted
        ALREADY_FRIENDS,   // Players are already friends
        ALREADY_SENT,      // Friend request already sent
        REMOVED,           // Friend removed successfully
        CANCELLED_OUTGOING, // Outgoing friend request cancelled
        CANCELLED_INCOMING, // Incoming friend request rejected
        NOT_FOUND,         // No friend relationship or request found
        SELF               // Attempted to add self as friend
    }


    /**
     * Adds a friend or sends a friend request to another player
     *
     * @param targetProfile The profile of the player to add as a friend
     * @return A FriendResult indicating the outcome of the operation
     */
    fun addFriend(targetProfile: Profile): FriendResult {
        val targetUuid = targetProfile.uuid

        // Don't allow adding yourself as a friend
        if (targetUuid == this.uuid) {
            return FriendResult.SELF
        }

        // Check if already friends
        if (friends.contains(targetUuid)) {
            return FriendResult.ALREADY_FRIENDS
        }

        // Check if target has sent us a friend request already
        if (incomingRequests.contains(targetUuid)) {
            // Accept the request
            incomingRequests.remove(targetUuid)
            friends.add(targetUuid)

            // Add ourselves to their friends list and remove their outgoing request
            targetProfile.friends.add(this.uuid)
            targetProfile.outgoingRequests.remove(this.uuid)

            return FriendResult.ACCEPTED
        }

        // Check if we already sent a request
        if (outgoingRequests.contains(targetUuid)) {
            return FriendResult.ALREADY_SENT
        }

        // Send a new friend request
        outgoingRequests.add(targetUuid)
        targetProfile.incomingRequests.add(this.uuid)

        return FriendResult.SENT
    }

    /**
     * Removes a friend or cancels a friend request
     *
     * @param targetUuid The UUID of the player to remove
     * @return A FriendResult indicating the outcome of the operation
     */
    fun removeFriend(targetUuid: UUID): FriendResult {
        // Check if they are a friend
        if (friends.contains(targetUuid)) {
            friends.remove(targetUuid)
            return FriendResult.REMOVED
        }

        // Check if there's an outgoing request
        if (outgoingRequests.contains(targetUuid)) {
            outgoingRequests.remove(targetUuid)
            return FriendResult.CANCELLED_OUTGOING
        }

        // Check if there's an incoming request
        if (incomingRequests.contains(targetUuid)) {
            incomingRequests.remove(targetUuid)
            return FriendResult.CANCELLED_INCOMING
        }

        return FriendResult.NOT_FOUND
    }

    /**
     * Removes a friend or cancels a friend request
     *
     * @param targetProfile The profile of the player to remove
     * @return A FriendResult indicating the outcome of the operation
     */
    fun removeFriend(targetProfile: Profile): FriendResult {
        val result = removeFriend(targetProfile.uuid)

        // If they were a friend, also remove from their friend list
        if (result == FriendResult.REMOVED) {
            targetProfile.friends.remove(this.uuid)
        }

        // If we had an outgoing request, remove the incoming request from their side
        if (result == FriendResult.CANCELLED_OUTGOING) {
            targetProfile.incomingRequests.remove(this.uuid)
        }

        // If we had an incoming request, remove the outgoing request from their side
        if (result == FriendResult.CANCELLED_INCOMING) {
            targetProfile.outgoingRequests.remove(this.uuid)
        }

        return result
    }

    /**
     * Updates the in-memory cache of friends' last seen timestamps from Redis
     * This method should be called to refresh the cache when needed
     *
     * @param radium The Radium instance to access Redis
     */
    fun updateFriendsLastSeenFromRedis(radium: Radium) {
        friendsLastSeen.clear()

        // Load all friends' last seen times from Redis
        val redisData = radium.lettuceCache.getFriendsLastSeen(this.uuid)
        friendsLastSeen.putAll(redisData)

        // Filter to keep only actual friends
        val friendIds = getFriends()
        friendsLastSeen.keys.retainAll(friendIds)

        radium.logger.debug("Loaded ${friendsLastSeen.size} friend last seen times from Redis for ${this.username}")
    }

    /**
     * Updates a friend's last seen time and caches it in Redis
     *
     * @param friendId The UUID of the friend
     * @param lastSeen The last seen timestamp
     * @param radium The Radium instance to access Redis
     * @return true if updated, false if the player is not a friend
     */
    fun updateFriendLastSeen(friendId: UUID, lastSeen: Instant, radium: Radium): Boolean {
        if (!isFriend(friendId)) {
            return false
        }

        // Update in-memory cache
        friendsLastSeen[friendId] = lastSeen

        // Update in Redis
        radium.lettuceCache.cacheFriendLastSeen(this.uuid, friendId, lastSeen.toEpochMilli())
        return true
    }

    /**
     * Gets the last seen timestamp for a friend
     * Returns null if the player is not a friend or their last seen info is not cached
     *
     * @param friendId UUID of the friend to check
     * @return The last seen timestamp, or null if not available
     */
    fun getFriendLastSeen(friendId: UUID): Instant? {
        if (!isFriend(friendId)) {
            return null
        }

        return friendsLastSeen[friendId]
    }

    /**
     * Gets all cached friends' last seen timestamps
     *
     * @return An unmodifiable map of friend UUIDs to their last seen times
     */
    fun getAllFriendsLastSeen(): Map<UUID, Instant> {
        return Collections.unmodifiableMap(friendsLastSeen)
    }

    /**
     * Clears the friends' last seen cache
     */
    fun clearFriendsLastSeenCache() {
        friendsLastSeen.clear()
    }

    /**
     * Gets an unmodifiable set of friends' UUIDs
     *
     * @return Set of friends' UUIDs
     */
    fun getFriends(): Set<UUID> {
        return Collections.unmodifiableSet(friends)
    }

    /**
     * Gets an unmodifiable set of incoming friend requests
     *
     * @return Set of UUIDs of players who have sent a friend request
     */
    fun getIncomingRequests(): Set<UUID> {
        return Collections.unmodifiableSet(incomingRequests)
    }

    /**
     * Gets an unmodifiable set of outgoing friend requests
     *
     * @return Set of UUIDs of players to whom a friend request has been sent
     */
    fun getOutgoingRequests(): Set<UUID> {
        return Collections.unmodifiableSet(outgoingRequests)
    }

    /**
     * Checks if a player is a friend
     *
     * @param uuid The UUID of the player to check
     * @return true if the player is a friend, false otherwise
     */
    fun isFriend(uuid: UUID): Boolean {
        return friends.contains(uuid)
    }

    /**
     * Checks if there is an incoming friend request from a player
     *
     * @param uuid The UUID of the player to check
     * @return true if there is an incoming request, false otherwise
     */
    fun hasIncomingRequest(uuid: UUID): Boolean {
        return incomingRequests.contains(uuid)
    }

    /**
     * Checks if there is an outgoing friend request to a player
     *
     * @param uuid The UUID of the player to check
     * @return true if there is an outgoing request, false otherwise
     */
    fun hasOutgoingRequest(uuid: UUID): Boolean {
        return outgoingRequests.contains(uuid)
    }
}
