package radium.backend.punishment.models

import org.bson.Document

/**
 * Represents a batch of punishment operations for efficient database writes
 * Follows the pattern established by the existing MongoDB utilities
 */
data class PunishmentBatch(
    val operations: List<PunishmentOperation>,
    val batchId: String = java.util.UUID.randomUUID().toString(),
    val createdAt: java.time.Instant = java.time.Instant.now()
) {
    /**
     * Get all documents for batch insert
     */
    fun getInsertDocuments(): List<Document> {
        return operations.filterIsInstance<PunishmentOperation.Insert>()
            .map { it.punishment.toDocument() }
    }

    /**
     * Get all update operations
     */
    fun getUpdateOperations(): List<PunishmentOperation.Update> {
        return operations.filterIsInstance<PunishmentOperation.Update>()
    }

    /**
     * Get operation count for logging
     */
    fun getOperationCount(): Int = operations.size
}

/**
 * Represents different types of punishment operations for batching
 */
sealed class PunishmentOperation {
    data class Insert(val punishment: Punishment) : PunishmentOperation()
    
    data class Update(
        val punishmentId: String,
        val updates: Document
    ) : PunishmentOperation()
    
    data class Deactivate(
        val punishmentId: String,
        val reason: String? = null
    ) : PunishmentOperation() {
        fun toUpdateOperation(): Update {
            val updates = Document("active", false)
            reason?.let { updates.append("deactivatedReason", it) }
            updates.append("deactivatedAt", java.util.Date.from(java.time.Instant.now()))
            return Update(punishmentId, updates)
        }
    }
}
