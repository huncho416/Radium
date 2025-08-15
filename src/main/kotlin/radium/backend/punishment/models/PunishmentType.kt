package radium.backend.punishment.models

/**
 * Enumeration of punishment types supported by the system
 */
enum class PunishmentType(val displayName: String, val canHaveDuration: Boolean, val preventJoin: Boolean) {
    BAN("Ban", true, true),
    IP_BAN("IP Ban", true, true),
    MUTE("Mute", true, false),
    WARN("Warning", true, false),
    KICK("Kick", false, false),
    BLACKLIST("Blacklist", false, true);

    companion object {
        fun fromString(type: String): PunishmentType? {
            return values().find { it.name.equals(type, ignoreCase = true) }
        }
    }
}
