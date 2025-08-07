package radium.backend.util

import java.time.Duration

/**
 * A utility class for parsing duration strings.
 * Supported formats:
 * - "Xs" - X seconds
 * - "Xm" - X minutes
 * - "Xh" - X hours
 * - "Xd" - X days
 * - "Xw" - X weeks
 * - "Xmo" - X months (30 days)
 * - "Xy" - X years (365 days)
 */
class DurationParser {
    companion object {
        private val DURATION_PATTERN = "^(\\d+)(s|m|h|d|w|mo|y)$".toRegex()

        /**
         * Parse a duration string into a Duration object.
         * @param input The duration string to parse.
         * @return The parsed Duration, or null if the string couldn't be parsed.
         */
        fun parse(input: String): Duration? {
            val match = DURATION_PATTERN.find(input.trim()) ?: return null
            val amount = match.groupValues[1].toLong()

            return when (match.groupValues[2]) {
                "s" -> Duration.ofSeconds(amount)
                "m" -> Duration.ofMinutes(amount)
                "h" -> Duration.ofHours(amount)
                "d" -> Duration.ofDays(amount)
                "w" -> Duration.ofDays(amount * 7)
                "mo" -> Duration.ofDays(amount * 30) // Approximate month as 30 days
                "y" -> Duration.ofDays(amount * 365) // Approximate year as 365 days
                else -> null
            }
        }

        /**
         * Parse a duration string into milliseconds.
         * @param input The duration string to parse.
         * @return The milliseconds value, or null if the string couldn't be parsed.
         */
        fun parseToMillis(input: String): Long? {
            return parse(input)?.toMillis()
        }

        /**
         * Format a duration object into a human-readable string.
         * @param duration The duration to format.
         * @return A formatted string representation of the duration.
         */
        fun format(duration: Duration): String {
            val days = duration.toDays()
            val hours = duration.toHours() % 24
            val minutes = duration.toMinutes() % 60
            val seconds = duration.toSeconds() % 60

            val parts = mutableListOf<String>()

            if (days > 0) parts.add("$days day${if (days > 1) "s" else ""}")
            if (hours > 0) parts.add("$hours hour${if (hours > 1) "s" else ""}")
            if (minutes > 0) parts.add("$minutes minute${if (minutes > 1) "s" else ""}")
            if (seconds > 0 || parts.isEmpty()) parts.add("$seconds second${if (seconds != 1L) "s" else ""}")

            return parts.joinToString(", ")
        }
    }
}
