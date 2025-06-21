package twizzy.tech.mythLink.util

import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

class YamlFactory {
    private val langConfig = mutableMapOf<String, Any>()

    fun initConfigurations() {
        // Initialize YAML configurations here
        // This could involve loading default configurations, setting up file paths, etc.

        ensureDatabaseConfiguration()
        ensureLangConfiguration()
        loadLangConfiguration() // Load the language configuration after ensuring it exists
    }


    fun ensureDatabaseConfiguration() {
        // Define the path for the plugins/MythLink directory
        val pluginDir = File("plugins/MythLink")

        // Create the directory if it doesn't exist
        if (!pluginDir.exists()) {
            pluginDir.mkdirs()
        }

        // Define the target database.yml file
        val configFile = File(pluginDir, "database.yml")

        // Check if the configuration file exists
        if (!configFile.exists()) {
            // Get the resource as a stream
            val resourceStream = javaClass.classLoader.getResourceAsStream("database.yml")

            if (resourceStream != null) {
                // Create the file and copy the content from resources
                configFile.createNewFile()
                Files.copy(resourceStream, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                resourceStream.close()
            } else {
                throw RuntimeException("Could not find database.yml in resources")
            }
        }
    }

    fun ensureLangConfiguration() {
        // Define the path for the plugins/MythLink directory
        val pluginDir = File("plugins/MythLink")

        // Create the directory if it doesn't exist
        if (!pluginDir.exists()) {
            pluginDir.mkdirs()
        }

        // Define the target lang.yml file
        val configFile = File(pluginDir, "lang.yml")

        // Check if the configuration file exists
        if (!configFile.exists()) {
            // Get the resource as a stream
            val resourceStream = javaClass.classLoader.getResourceAsStream("lang.yml")

            if (resourceStream != null) {
                // Create the file and copy the content from resources
                configFile.createNewFile()
                Files.copy(resourceStream, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                resourceStream.close()
            } else {
                throw RuntimeException("Could not find lang.yml in resources")
            }
        }
    }

    fun loadLangConfiguration() {
        val langFile = File("plugins/MythLink/lang.yml")
        if (langFile.exists()) {
            try {
                val inputStream = FileInputStream(langFile)
                val reader = InputStreamReader(inputStream, StandardCharsets.UTF_8)
                val yaml = Yaml()

                @Suppress("UNCHECKED_CAST")
                val loadedConfig = yaml.load<Map<String, Any>>(reader)
                if (loadedConfig != null) {
                    langConfig.putAll(loadedConfig)
                }

                reader.close()
                inputStream.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Get a message from the lang configuration
     * @param key The key to the message
     * @param replacements Key-value pairs for placeholder replacements
     * @return The message with placeholders replaced and color codes translated from & to §
     */
    fun getMessage(key: String, vararg replacements: Pair<String, String>): String {
        // Navigate nested keys (e.g., "commands.grant.usage")
        val parts = key.split(".")
        var current: Any? = langConfig

        for (part in parts) {
            if (current is Map<*, *>) {
                current = (current as Map<*, *>)[part]
            } else {
                return key // Key not found, return the key itself
            }
        }

        var message = current?.toString() ?: key

        // Replace placeholders
        for ((placeholder, value) in replacements) {
            message = message.replace("{$placeholder}", value)
        }

        // Translate color codes from & to §
        return translateColorCodes(message)
    }

    /**
     * Converts legacy color codes to Adventure Component
     * @param text Text with & or § color codes
     * @return Adventure Component with proper formatting
     */
    private fun translateColorCodes(text: String): String {
        // This version still returns a string, but now it's the legacy format which is properly recognized
        return text.replace('&', '§')
    }

    /**
     * Get a message from the lang configuration as an Adventure Component
     * @param key The key to the message
     * @param replacements Key-value pairs for placeholder replacements
     * @return The message as an Adventure Component with proper formatting
     */
    fun getMessageComponent(key: String, vararg replacements: Pair<String, String>): Component {
        // Get the message as a string with legacy formatting (§)
        val legacyText = getMessage(key, *replacements)

        // Convert to component using LegacyComponentSerializer
        return LegacyComponentSerializer.legacySection().deserialize(legacyText)
    }

    /**
     * Get a nested section from the lang configuration
     * @param key The key to the section
     * @return The section as a Map
     */
    @Suppress("UNCHECKED_CAST")
    fun getSection(key: String): Map<String, Any> {
        val parts = key.split(".")
        var current: Any? = langConfig

        for (part in parts) {
            if (current is Map<*, *>) {
                current = (current as Map<*, *>)[part]
            } else {
                return emptyMap()
            }
        }

        return if (current is Map<*, *>) current as Map<String, Any> else emptyMap()
    }
}