package twizzy.tech.mythLink.commands

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Description
import revxrsal.commands.annotation.Optional
import revxrsal.commands.annotation.Subcommand
import revxrsal.commands.velocity.annotation.CommandPermission
import twizzy.tech.mythLink.MythLink
import twizzy.tech.mythLink.annotations.RankList


@Command("rank", "ranks")
@CommandPermission("command.rank")
class Rank(private val mythLink: MythLink) {

    private val rankManager = mythLink.rankManager


    @Command("rank", "ranks")
    fun rankUsage(actor: Player) {
        actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.header"))
        actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.usage.main"))
        actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.usage.delete"))
        actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.usage.setprefix"))
        actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.usage.setweight"))
        actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.usage.permission_add"))
        actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.usage.permission_remove"))
        actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.usage.inherit"))
        actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.usage.info"))
        actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.usage.list"))
    }

    @Subcommand("create")
    @CommandPermission("command.rank.create")
    suspend fun createRank(
        actor: Player,
        @Optional name: String?
    ) {
        try {
            if (name.isNullOrEmpty()) {
                actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.create.usage"))
                return
            }

            // Create rank with default values
            val rank = rankManager.createRank(name, "&7", 0)
            actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.create.success", "rank" to rank.name))
        } catch (e: Exception) {
            actor.sendMessage(mythLink.yamlFactory.getMessageComponent("general.failed_operation",
                "operation" to "create rank",
                "message" to e.message.toString()
            ))
        }
    }

    @Subcommand("delete")
    @CommandPermission("command.rank.delete")
    suspend fun deleteRank(
        actor: Player,
        @Optional @RankList name: String?
    ) {
        try {
            if (name.isNullOrEmpty()) {
                actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.delete.usage"))
                return
            }

            val success = rankManager.deleteRank(name)
            if (success) {
                actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.delete.success", "rank" to name))
            } else {
                actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.delete.not_found", "rank" to name))
            }
        } catch (e: Exception) {
            actor.sendMessage(mythLink.yamlFactory.getMessageComponent("general.failed_operation",
                "operation" to "delete rank",
                "message" to e.message.toString()
            ))
        }
    }

    @Subcommand("setprefix")
    @CommandPermission("command.rank.setprefix")
    suspend fun setPrefix(
        actor: Player,
        @Optional @RankList name: String?,
        @Optional prefix: String?
    ){
        try {
            if (name.isNullOrEmpty() || prefix.isNullOrEmpty()) {
                actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.setprefix.usage"))
                return
            }

            val success = rankManager.updateRank(name) { rank ->
                rank.copy(prefix = prefix)
            }

            if (success) {
                actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.setprefix.success",
                    "rank" to name,
                    "prefix" to prefix
                ))
            } else {
                actor.sendMessage(mythLink.yamlFactory.getMessageComponent("general.rank_not_found", "rank" to name))
            }
        } catch (e: Exception) {
            actor.sendMessage(mythLink.yamlFactory.getMessageComponent("general.failed_operation",
                "operation" to "set prefix",
                "message" to e.message.toString()
            ))
        }
    }

    @Subcommand("setweight")
    @CommandPermission("command.rank.setweight")
    suspend fun setWeight(
        actor: Player,
        @Optional @RankList name: String?,
        @Optional weight: Int
    ){
        try {
            if (name.isNullOrEmpty()) {
                actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.setweight.usage"))
                return
            }

            val success = rankManager.updateRank(name) { rank ->
                rank.copy(weight = weight)
            }

            if (success) {
                actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.setweight.success",
                    "rank" to name,
                    "weight" to weight.toString()
                ))
            } else {
                actor.sendMessage(mythLink.yamlFactory.getMessageComponent("general.rank_not_found", "rank" to name))
            }
        } catch (e: Exception) {
            actor.sendMessage(mythLink.yamlFactory.getMessageComponent("general.failed_operation",
                "operation" to "set weight",
                "message" to e.message.toString()
            ))
        }
    }

    @Subcommand("permission add")
    @CommandPermission("command.rank.permission.add")
    suspend fun addPermission(
        actor: Player,
        @Optional @RankList name: String?,
        @Optional permission: String?
    ){
        try {
            if (name.isNullOrEmpty() || permission.isNullOrEmpty()) {
                actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.permission.add.usage"))
                return
            }

            val success = rankManager.addPermissionToRank(name, permission)
            if (success) {
                actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.permission.add.success",
                    "permission" to permission,
                    "rank" to name
                ))
            } else {
                actor.sendMessage(mythLink.yamlFactory.getMessageComponent("general.rank_not_found", "rank" to name))
            }
        } catch (e: Exception) {
            actor.sendMessage(mythLink.yamlFactory.getMessageComponent("general.failed_operation",
                "operation" to "add permission",
                "message" to e.message.toString()
            ))
        }
    }

    @Subcommand("permission remove <name> <permission>")
    @CommandPermission("command.rank.permission.remove")
    suspend fun removePermission(
        actor: Player,
        @Optional @RankList name: String?,
        @Optional permission: String?
    ) {

        if (name.isNullOrEmpty() || permission.isNullOrEmpty()) {
            actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.permission.remove.usage"))
            return
        }

        try {
            val success = rankManager.removePermissionFromRank(name, permission)
            if (success) {
                actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.permission.remove.success",
                    "permission" to permission,
                    "rank" to name
                ))
            } else {
                actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.permission.remove.not_found"))
            }
        } catch (e: Exception) {
            actor.sendMessage(mythLink.yamlFactory.getMessageComponent("general.failed_operation",
                "operation" to "remove permission",
                "message" to e.message.toString()
            ))
        }
    }

    @Subcommand("inherit <name> <inherit>")
    @CommandPermission("command.rank.inherit")
    suspend fun toggleInheritance(
        actor: Player,
        @Optional @RankList name: String?,
        @Optional @RankList inherit: String?
    ) {
        try {
            if (name.isNullOrEmpty() || inherit.isNullOrEmpty()) {
                actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.inherit.usage"))
                return
            }

            // Get the rank to check if it already inherits from the specified rank
            val rank = rankManager.getRank(name)
            if (rank == null) {
                actor.sendMessage(mythLink.yamlFactory.getMessageComponent("general.rank_not_found", "rank" to name))
                return
            }

            val alreadyInherits = rank.inherits.contains(inherit)

            // Toggle inheritance based on current state
            val success = if (alreadyInherits) {
                // Already inherits, so remove it
                val removed = rankManager.removeInheritedRank(name, inherit)
                if (removed) {
                    actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.inherit.remove_success",
                        "rank" to name,
                        "inherit" to inherit
                    ))
                } else {
                    actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.inherit.failed",
                        "operation" to "remove",
                        "reason" to "Rank not found or no inheritance relationship exists."
                    ))
                }
                removed
            } else {
                // Doesn't inherit yet, so add it
                val added = rankManager.addInheritedRank(name, inherit)
                if (added) {
                    actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.inherit.add_success",
                        "rank" to name,
                        "inherit" to inherit
                    ))
                } else {
                    actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.inherit.failed",
                        "operation" to "add",
                        "reason" to "One of the ranks was not found."
                    ))
                }
                added
            }
        } catch (e: Exception) {
            actor.sendMessage(mythLink.yamlFactory.getMessageComponent("general.failed_operation",
                "operation" to "toggle inheritance",
                "message" to e.message.toString()
            ))
        }
    }


    @Subcommand("info <name>")
    @CommandPermission("command.rank.info")
    suspend fun getRankInfo(
        actor: Player,
        @Optional @RankList name: String?
    )  {
        try {
            if (name.isNullOrEmpty()) {
                actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.info.usage"))
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
                    .append(mythLink.yamlFactory.getMessageComponent("commands.rank.info.header", "rank" to rank.name))
                    .appendNewline()
                    .append(mythLink.yamlFactory.getMessageComponent("commands.rank.info.prefix", "prefix" to rank.prefix))
                    .appendNewline()
                    .append(mythLink.yamlFactory.getMessageComponent("commands.rank.info.weight", "weight" to rank.weight.toString()))
                    .appendNewline()
                    .append(mythLink.yamlFactory.getMessageComponent("commands.rank.info.inherits",
                        "inherits" to if (directInherits.isEmpty()) "None" else directInherits.joinToString(", ")
                    ))
                    .appendNewline()
                    .append(mythLink.yamlFactory.getMessageComponent("commands.rank.info.permissions"))
                    .appendNewline()

                // Display permissions in requested format
                if (permissionMap.isEmpty()) {
                    component.append(mythLink.yamlFactory.getMessageComponent("commands.rank.info.none"))
                } else {
                    // First show own permissions
                    permissionMap.entries.filter { it.value == null }.forEach { (perm, _) ->
                        component.append(mythLink.yamlFactory.getMessageComponent("commands.rank.info.permission", "permission" to perm))
                            .appendNewline()
                    }

                    // Then show inherited permissions with source
                    permissionMap.entries.filter { it.value != null }.forEach { (perm, source) ->
                        component.append(mythLink.yamlFactory.getMessageComponent("commands.rank.info.inherited_permission",
                            "permission" to perm,
                            "source" to source!!
                        ))
                        .appendNewline()
                    }
                }

                actor.sendMessage(component.build())
            } else {
                actor.sendMessage(mythLink.yamlFactory.getMessageComponent("general.rank_not_found", "rank" to name))
            }
        } catch (e: Exception) {
            actor.sendMessage(mythLink.yamlFactory.getMessageComponent("general.failed_operation",
                "operation" to "get rank info",
                "message" to e.message.toString()
            ))
        }
    }

    @Subcommand("list")
    @CommandPermission("command.rank")
    suspend fun listRanks(actor: Player
    ) {
        try {
            val ranks = rankManager.listRanksByWeight()
            if (ranks.isEmpty()) {
                actor.sendMessage(mythLink.yamlFactory.getMessageComponent("commands.rank.list.none"))
                return
            }

            val component = Component.text()
                .append(mythLink.yamlFactory.getMessageComponent("commands.rank.list.header"))
                .appendNewline()

            ranks.forEach { rank ->
                component.append(mythLink.yamlFactory.getMessageComponent("commands.rank.list.entry",
                    "name" to rank.name,
                    "weight" to rank.weight.toString(),
                    "prefix" to rank.prefix
                ))
                .appendNewline()
            }

            actor.sendMessage(component.build())
        } catch (e: Exception) {
            actor.sendMessage(mythLink.yamlFactory.getMessageComponent("general.failed_operation",
                "operation" to "list ranks",
                "message" to e.message.toString()
            ))
        }
    }
}