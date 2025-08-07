package radium.backend.commands

import com.velocitypowered.api.proxy.Player
import kotlinx.coroutines.launch
import revxrsal.commands.annotation.Command
import revxrsal.commands.velocity.annotation.CommandPermission
import radium.backend.Radium

@Command("radiumreload", "rreload")
@CommandPermission("command.reload")
class Reload(private val radium: Radium) {

    @Command("radiumreload", "rreload")
    fun reloadPlugin(actor: Player) {
        try {
            // Reload language configuration
            radium.yamlFactory.reloadLangConfiguration()
            
            // Update tab lists for all players
            radium.scope.launch {
                radium.tabListManager.updateAllPlayersTabList()
            }
            
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.reload.success"))
        } catch (e: Exception) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("commands.reload.error", "error" to e.message.toString()))
        }
    }
}
