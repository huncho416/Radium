package radium.backend.commands

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Description
import revxrsal.commands.annotation.Optional
import revxrsal.commands.annotation.Subcommand
import revxrsal.commands.velocity.annotation.CommandPermission
import radium.backend.Radium
import radium.backend.annotations.RankList
import radium.backend.annotations.ColorList


@Command("rank", "ranks")
@CommandPermission("radium.staff")
class Rank(private val radium: Radium) {

    private val rankManager = radium.rankManager


    @Command("rank", "ranks")
    @CommandPermission("radium.rank.use")
    fun rankUsage(actor: Player) {
        actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.header"))
        actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.usage.main"))
        actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.usage.delete"))
        actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.usage.setprefix"))
        actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.usage.setsuffix"))
        actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.usage.settabprefix"))
        actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.usage.settabsuffix"))
        actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.usage.setcolor"))
        actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.usage.setweight"))
        actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.usage.permission_add"))
        actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.usage.permission_remove"))
        actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.usage.inherit"))
        actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.usage.info"))
        actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.usage.list"))
    }

    @Subcommand("create")
    @CommandPermission("radium.rank.create")
    suspend fun createRank(
        actor: Player,
        @Optional name: String?
    ) {
        try {
            if (name.isNullOrEmpty()) {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.create.usage"))
                return
            }

            // Create rank with default values
            val rank = rankManager.createRank(name, "&7", 0)
            actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.create.success", "rank" to rank.name))
        } catch (e: Exception) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("general.failed_operation",
                "operation" to "create rank",
                "message" to e.message.toString()
            ))
        }
    }

    @Subcommand("delete")
    @CommandPermission("radium.rank.delete")
    suspend fun deleteRank(
        actor: Player,
        @Optional @RankList name: String?
    ) {
        try {
            if (name.isNullOrEmpty()) {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.delete.usage"))
                return
            }

            val success = rankManager.deleteRank(name)
            if (success) {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.delete.success", "rank" to name))
            } else {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.delete.not_found", "rank" to name))
            }
        } catch (e: Exception) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("general.failed_operation",
                "operation" to "delete rank",
                "message" to e.message.toString()
            ))
        }
    }

    @Subcommand("setprefix")
    @CommandPermission("radium.rank.setprefix")
    suspend fun setPrefix(
        actor: Player,
        @Optional @RankList name: String?,
        @Optional prefix: String?
    ){
        try {
            if (name.isNullOrEmpty() || prefix.isNullOrEmpty()) {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.setprefix.usage"))
                return
            }

            val success = rankManager.updateRank(name) { rank ->
                rank.copy(prefix = prefix)
            }

            if (success) {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.setprefix.success",
                    "rank" to name,
                    "prefix" to prefix
                ))
            } else {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("general.rank_not_found", "rank" to name))
            }
        } catch (e: Exception) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("general.failed_operation",
                "operation" to "set prefix",
                "message" to e.message.toString()
            ))
        }
    }

    @Subcommand("setcolor")
    @CommandPermission("radium.rank.setcolor")
    suspend fun setColor(
        actor: Player,
        @Optional @RankList name: String?,
        @Optional @ColorList color: String?
    ){
        try {
            if (name.isNullOrEmpty() || color.isNullOrEmpty()) {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.setcolor.usage"))
                return
            }

            // Validate color codes
            val validColors = setOf("&0", "&1", "&2", "&3", "&4", "&5", "&6", "&7", "&8", "&9", 
                                   "&a", "&b", "&c", "&d", "&e", "&f", 
                                   "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", 
                                   "dark_purple", "gold", "gray", "dark_gray", "blue", 
                                   "green", "aqua", "red", "light_purple", "yellow", "white")
            
            val normalizedColor = if (color.startsWith("&")) color else "&$color"
            val colorName = color.lowercase()
            
            if (!validColors.contains(normalizedColor) && !validColors.contains(colorName)) {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.setcolor.invalid_color", 
                    "color" to color))
                actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.setcolor.valid_colors"))
                return
            }

            // Convert named colors to color codes
            val finalColor = when (colorName) {
                "black" -> "&0"
                "dark_blue" -> "&1"
                "dark_green" -> "&2"
                "dark_aqua" -> "&3"
                "dark_red" -> "&4"
                "dark_purple" -> "&5"
                "gold" -> "&6"
                "gray" -> "&7"
                "dark_gray" -> "&8"
                "blue" -> "&9"
                "green" -> "&a"
                "aqua" -> "&b"
                "red" -> "&c"
                "light_purple" -> "&d"
                "yellow" -> "&e"
                "white" -> "&f"
                else -> normalizedColor
            }

            val success = rankManager.setRankColor(name, finalColor)

            if (success) {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.setcolor.success",
                    "rank" to name,
                    "color" to finalColor
                ))
                
                // Update tab lists for all players with this rank
                GlobalScope.launch {
                    radium.networkVanishManager.refreshAllTabLists()
                }
            } else {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("general.rank_not_found", "rank" to name))
            }
        } catch (e: Exception) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("general.failed_operation",
                "operation" to "set color",
                "message" to e.message.toString()
            ))
        }
    }

    @Subcommand("setweight")
    @CommandPermission("radium.rank.setweight")
    suspend fun setWeight(
        actor: Player,
        @Optional @RankList name: String?,
        @Optional weight: Int
    ){
        try {
            if (name.isNullOrEmpty()) {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.setweight.usage"))
                return
            }

            val success = rankManager.updateRank(name) { rank ->
                rank.copy(weight = weight)
            }

            if (success) {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.setweight.success",
                    "rank" to name,
                    "weight" to weight.toString()
                ))
            } else {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("general.rank_not_found", "rank" to name))
            }
        } catch (e: Exception) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("general.failed_operation",
                "operation" to "set weight",
                "message" to e.message.toString()
            ))
        }
    }

    @Subcommand("permission add")
    @CommandPermission("radium.rank.permission.add")
    suspend fun addPermission(
        actor: Player,
        @Optional @RankList name: String?,
        @Optional permission: String?
    ){
        try {
            if (name.isNullOrEmpty() || permission.isNullOrEmpty()) {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.permission.add.usage"))
                return
            }

            val success = rankManager.addPermissionToRank(name, permission)
            if (success) {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.permission.add.success",
                    "permission" to permission,
                    "rank" to name
                ))
            } else {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("general.rank_not_found", "rank" to name))
            }
        } catch (e: Exception) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("general.failed_operation",
                "operation" to "add permission",
                "message" to e.message.toString()
            ))
        }
    }

    @Subcommand("permission remove <name> <permission>")
    @CommandPermission("radium.rank.permission.remove")
    suspend fun removePermission(
        actor: Player,
        @Optional @RankList name: String?,
        @Optional permission: String?
    ) {

        if (name.isNullOrEmpty() || permission.isNullOrEmpty()) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.permission.remove.usage"))
            return
        }

        try {
            val success = rankManager.removePermissionFromRank(name, permission)
            if (success) {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.permission.remove.success",
                    "permission" to permission,
                    "rank" to name
                ))
            } else {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.permission.remove.not_found"))
            }
        } catch (e: Exception) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("general.failed_operation",
                "operation" to "remove permission",
                "message" to e.message.toString()
            ))
        }
    }

    @Subcommand("inherit <name> <inherit>")
    @CommandPermission("radium.rank.inherit")
    suspend fun toggleInheritance(
        actor: Player,
        @Optional @RankList name: String?,
        @Optional @RankList inherit: String?
    ) {
        try {
            if (name.isNullOrEmpty() || inherit.isNullOrEmpty()) {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.inherit.usage"))
                return
            }

            // Get the rank to check if it already inherits from the specified rank
            val rank = rankManager.getRank(name)
            if (rank == null) {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("general.rank_not_found", "rank" to name))
                return
            }

            val alreadyInherits = rank.inherits.contains(inherit)

            // Toggle inheritance based on current state
            val success = if (alreadyInherits) {
                // Already inherits, so remove it
                val removed = rankManager.removeInheritedRank(name, inherit)
                if (removed) {
                    actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.inherit.remove_success",
                        "rank" to name,
                        "inherit" to inherit
                    ))
                } else {
                    actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.inherit.failed",
                        "operation" to "remove",
                        "reason" to "Rank not found or no inheritance relationship exists."
                    ))
                }
                removed
            } else {
                // Doesn't inherit yet, so add it
                val added = rankManager.addInheritedRank(name, inherit)
                if (added) {
                    actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.inherit.add_success",
                        "rank" to name,
                        "inherit" to inherit
                    ))
                } else {
                    actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.inherit.failed",
                        "operation" to "add",
                        "reason" to "One of the ranks was not found."
                    ))
                }
                added
            }
        } catch (e: Exception) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("general.failed_operation",
                "operation" to "toggle inheritance",
                "message" to e.message.toString()
            ))
        }
    }


    @Subcommand("info <name>")
    @CommandPermission("radium.rank.info")
    suspend fun getRankInfo(
        actor: Player,
        @Optional @RankList name: String?
    )  {
        try {
            if (name.isNullOrEmpty()) {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.info.usage"))
                return
            }

            val rank = rankManager.getRank(name)
            if (rank != null) {
                val directInherits = rank.inherits
                val allInherits = rankManager.getAllInheritedRanks(name)

                // Get all permissions and their sources
                val permissionMap = mutableMapOf<String, String?>() // permission -> source rank (null for own rank)

                // Add own permissions
                rank.permissions.forEach { perm ->
                    permissionMap[perm] = null // null means it's from own rank
                }

                // Add inherited permissions with source
                directInherits.forEach { inheritedRankName ->
                    val inheritedRank = rankManager.getRank(inheritedRankName)
                    if (inheritedRank != null) {
                        inheritedRank.permissions.forEach { perm ->
                            if (!permissionMap.containsKey(perm)) {
                                permissionMap[perm] = inheritedRankName
                            }
                        }
                    }
                }

                // Build the component for display
                val component = Component.text()
                    .append(radium.yamlFactory.getMessageComponent("rank.info.header", "rank" to rank.name))
                    .appendNewline()
                    .append(radium.yamlFactory.getMessageComponent("rank.info.prefix", "prefix" to rank.prefix))
                    .appendNewline()
                    .append(radium.yamlFactory.getMessageComponent("rank.info.suffix", "suffix" to (rank.suffix ?: "None")))
                    .appendNewline()
                    .append(radium.yamlFactory.getMessageComponent("rank.info.tabprefix", "tabprefix" to (rank.tabPrefix ?: "Uses regular prefix")))
                    .appendNewline()
                    .append(radium.yamlFactory.getMessageComponent("rank.info.tabsuffix", "tabsuffix" to (rank.tabSuffix ?: "None")))
                    .appendNewline()
                    .append(radium.yamlFactory.getMessageComponent("rank.info.color", "color" to rank.color))
                    .appendNewline()
                    .append(radium.yamlFactory.getMessageComponent("rank.info.weight", "weight" to rank.weight.toString()))
                    .appendNewline()
                    .append(radium.yamlFactory.getMessageComponent("rank.info.inherits",
                        "inherits" to if (directInherits.isEmpty()) "None" else directInherits.joinToString(", ")
                    ))
                    .appendNewline()
                    .append(radium.yamlFactory.getMessageComponent("rank.info.permissions"))
                    .appendNewline()

                // Display permissions in requested format
                if (permissionMap.isEmpty()) {
                    component.append(radium.yamlFactory.getMessageComponent("rank.info.none"))
                } else {
                    // First show own permissions
                    permissionMap.entries.filter { it.value == null }.forEach { (perm, _) ->
                        component.append(radium.yamlFactory.getMessageComponent("rank.info.permission", "permission" to perm))
                            .appendNewline()
                    }

                    // Then show inherited permissions with source
                    permissionMap.entries.filter { it.value != null }.forEach { (perm, source) ->
                        component.append(radium.yamlFactory.getMessageComponent("rank.info.inherited_permission",
                            "permission" to perm,
                            "source" to source!!
                        ))
                        .appendNewline()
                    }
                }

                actor.sendMessage(component.build())
            } else {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("general.rank_not_found", "rank" to name))
            }
        } catch (e: Exception) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("general.failed_operation",
                "operation" to "get rank info",
                "message" to e.message.toString()
            ))
        }
    }

    @Subcommand("list")
    @CommandPermission("radium.rank.list")
    suspend fun listRanks(actor: Player
    ) {
        try {
            val ranks = rankManager.listRanksByWeight()
            if (ranks.isEmpty()) {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.list.none"))
                return
            }

            val component = Component.text()
                .append(radium.yamlFactory.getMessageComponent("rank.list.header"))
                .appendNewline()

            ranks.forEach { rank ->
                component.append(radium.yamlFactory.getMessageComponent("rank.list.entry",
                    "name" to rank.name,
                    "weight" to rank.weight.toString(),
                    "prefix" to rank.prefix
                ))
                .appendNewline()
            }

            actor.sendMessage(component.build())
        } catch (e: Exception) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("general.failed_operation",
                "operation" to "list ranks",
                "message" to e.message.toString()
            ))
        }
    }

    @Subcommand("settabprefix")
    @CommandPermission("radium.rank.settabprefix")
    suspend fun setTabPrefix(
        actor: Player,
        @Optional @RankList name: String?,
        @Optional prefix: String?
    ){
        try {
            if (name.isNullOrEmpty()) {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.settabprefix.usage"))
                return
            }

            val success = rankManager.updateRank(name) { rank ->
                rank.copy(tabPrefix = prefix)
            }

            if (success) {
                val displayPrefix = prefix ?: "null (will use regular prefix)"
                actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.settabprefix.success",
                    "rank" to name,
                    "prefix" to displayPrefix
                ))
                
                // Update tab lists for all players with this rank
                GlobalScope.launch {
                    radium.networkVanishManager.refreshAllTabLists()
                }
            } else {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("general.rank_not_found", "rank" to name))
            }
        } catch (e: Exception) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("general.failed_operation",
                "operation" to "set tab prefix",
                "message" to e.message.toString()
            ))
        }
    }

    @Subcommand("settabsuffix")
    @CommandPermission("radium.rank.settabsuffix")
    suspend fun setTabSuffix(
        actor: Player,
        @Optional @RankList name: String?,
        @Optional suffix: String?
    ){
        try {
            if (name.isNullOrEmpty()) {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.settabsuffix.usage"))
                return
            }

            val success = rankManager.updateRank(name) { rank ->
                rank.copy(tabSuffix = suffix)
            }

            if (success) {
                val displaySuffix = suffix ?: "null (no suffix)"
                actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.settabsuffix.success",
                    "rank" to name,
                    "suffix" to displaySuffix
                ))
                
                // Update tab lists for all players with this rank
                GlobalScope.launch {
                    radium.networkVanishManager.refreshAllTabLists()
                }
            } else {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("general.rank_not_found", "rank" to name))
            }
        } catch (e: Exception) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("general.failed_operation",
                "operation" to "set tab suffix",
                "message" to e.message.toString()
            ))
        }
    }

    @Subcommand("setsuffix")
    @CommandPermission("radium.rank.setsuffix")
    suspend fun setSuffix(
        actor: Player,
        @Optional @RankList name: String?,
        @Optional suffix: String?
    ){
        try {
            if (name.isNullOrEmpty()) {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.setsuffix.usage"))
                return
            }

            val success = rankManager.updateRank(name) { rank ->
                rank.copy(suffix = suffix)
            }

            if (success) {
                val displaySuffix = suffix ?: "null (no suffix)"
                actor.sendMessage(radium.yamlFactory.getMessageComponent("rank.setsuffix.success",
                    "rank" to name,
                    "suffix" to displaySuffix
                ))
            } else {
                actor.sendMessage(radium.yamlFactory.getMessageComponent("general.rank_not_found", "rank" to name))
            }
        } catch (e: Exception) {
            actor.sendMessage(radium.yamlFactory.getMessageComponent("general.failed_operation",
                "operation" to "set suffix",
                "message" to e.message.toString()
            ))
        }
    }
}
