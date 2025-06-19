package twizzy.tech.mythLink.util

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class YamlFactory {

    fun initConfigurations() {
        // Initialize YAML configurations here
        // This could involve loading default configurations, setting up file paths, etc.

        ensureDatabaseConfiguration()
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


}