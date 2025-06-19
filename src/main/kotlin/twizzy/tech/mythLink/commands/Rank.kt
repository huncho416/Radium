package twizzy.tech.mythLink.commands

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
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
        actor.sendMessage(Component.text("Rank Management Commands:", NamedTextColor.GOLD))
        actor.sendMessage(Component.text("  /rank create <name>", NamedTextColor.YELLOW)
            .append(Component.text(" - Creates a new rank", NamedTextColor.WHITE)))
        actor.sendMessage(Component.text("  /rank delete <name>", NamedTextColor.YELLOW)
            .append(Component.text(" - Deletes an existing rank", NamedTextColor.WHITE)))
        actor.sendMessage(Component.text("  /rank setprefix <name> <prefix>", NamedTextColor.YELLOW)
            .append(Component.text(" - Sets a rank's prefix", NamedTextColor.WHITE)))
        actor.sendMessage(Component.text("  /rank setweight <name> <weight>", NamedTextColor.YELLOW)
            .append(Component.text(" - Sets a rank's weight", NamedTextColor.WHITE)))
        actor.sendMessage(Component.text("  /rank permission add <name> <permission>", NamedTextColor.YELLOW)
            .append(Component.text(" - Adds a permission to a rank", NamedTextColor.WHITE)))
        actor.sendMessage(Component.text("  /rank permission remove <name> <permission>", NamedTextColor.YELLOW)
            .append(Component.text(" - Removes a permission from a rank", NamedTextColor.WHITE)))
        actor.sendMessage(Component.text("  /rank inherit <name> <inherit>", NamedTextColor.YELLOW)
            .append(Component.text(" - Toggles inheritance from another rank", NamedTextColor.WHITE)))
        actor.sendMessage(Component.text("  /rank info <name>", NamedTextColor.YELLOW)
            .append(Component.text(" - Shows information about a rank", NamedTextColor.WHITE)))
        actor.sendMessage(Component.text("  /rank list", NamedTextColor.YELLOW)
            .append(Component.text(" - Lists all ranks sorted by weight", NamedTextColor.WHITE)))
    }

    @Subcommand("create")
    @CommandPermission("command.rank.create")
    suspend fun createRank(
        actor: Player,
        @Optional name: String?
    ) {
        try {
            if (name.isNullOrEmpty()) {
                actor.sendMessage(Component.text("Usage: /rank create <name>", NamedTextColor.YELLOW))
                return
            }
            
            // Create rank with default values
            val rank = rankManager.createRank(name, "&7", 0)
            actor.sendMessage(Component.text()
                .append(Component.text("Successfully created rank: ", NamedTextColor.GREEN))
                .append(Component.text(rank.name, NamedTextColor.GOLD)))
        } catch (e: Exception) {
            actor.sendMessage(Component.text("Failed to create rank: ${e.message}", NamedTextColor.RED))
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
                actor.sendMessage(Component.text("Usage: /rank delete <name>", NamedTextColor.YELLOW))
                return
            }

            val success = rankManager.deleteRank(name)
            if (success) {
                actor.sendMessage(Component.text("Successfully deleted rank: $name", NamedTextColor.GREEN))
            } else {
                actor.sendMessage(Component.text("Rank not found: $name", NamedTextColor.YELLOW))
            }
        } catch (e: Exception) {
            actor.sendMessage(Component.text("Failed to delete rank: ${e.message}", NamedTextColor.RED))
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
                actor.sendMessage(Component.text("Usage: /rank setprefix <name> <prefix>", NamedTextColor.YELLOW))
                return
            }

            val success = rankManager.updateRank(name) { rank ->
                rank.copy(prefix = prefix)
            }

            if (success) {
                actor.sendMessage(Component.text("Successfully set prefix of $name to $prefix", NamedTextColor.GREEN))
            } else {
                actor.sendMessage(Component.text("Rank not found: $name", NamedTextColor.YELLOW))
            }
        } catch (e: Exception) {
            actor.sendMessage(Component.text("Failed to set prefix: ${e.message}", NamedTextColor.RED))
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
                actor.sendMessage(Component.text("Usage: /rank setweight <name> <weight>", NamedTextColor.YELLOW))
                return
            }

            val success = rankManager.updateRank(name) { rank ->
                rank.copy(weight = weight)
            }

            if (success) {
                actor.sendMessage(Component.text("Successfully set weight of $name to $weight", NamedTextColor.GREEN))
            } else {
                actor.sendMessage(Component.text("Rank not found: $name", NamedTextColor.YELLOW))
            }
        } catch (e: Exception) {
            actor.sendMessage(Component.text("Failed to set weight: ${e.message}", NamedTextColor.RED))
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
                actor.sendMessage(Component.text("Usage: /rank permission add <name> <permission> [<duration>]", NamedTextColor.YELLOW))
                return
            }


            val success = rankManager.addPermissionToRank(name, permission)
            if (success) {
                actor.sendMessage(Component.text()
                    .append(Component.text("Successfully added permission ", NamedTextColor.GREEN))
                    .append(Component.text(permission, NamedTextColor.GOLD))
                    .append(Component.text(" to rank ", NamedTextColor.GREEN))
                    .append(Component.text(name, NamedTextColor.GOLD))
                    .build()
                )
            } else {
                actor.sendMessage(Component.text("Rank not found: $name", NamedTextColor.YELLOW))
            }
        } catch (e: Exception) {
            actor.sendMessage(Component.text("Failed to add permission: ${e.message}", NamedTextColor.RED))
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
            actor.sendMessage(Component.text("Usage: /rank permission remove <name> <permission>", NamedTextColor.YELLOW))
            return
        }

        try {
            val success = rankManager.removePermissionFromRank(name, permission)
            if (success) {
                actor.sendMessage(Component.text()
                    .append(Component.text("Successfully removed permission ", NamedTextColor.GREEN))
                    .append(Component.text(permission, NamedTextColor.GOLD))
                    .append(Component.text(" from rank ", NamedTextColor.GREEN))
                    .append(Component.text(name, NamedTextColor.GOLD))
                    .build()
                )
            } else {
                actor.sendMessage(Component.text("Rank not found or permission not assigned to this rank", NamedTextColor.YELLOW))
            }
        } catch (e: Exception) {
            actor.sendMessage(Component.text("Failed to remove permission: ${e.message}", NamedTextColor.RED))
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
                actor.sendMessage(Component.text("Usage: /rank inherit <name> <inherit>", NamedTextColor.YELLOW))
                return
            }


            // Get the rank to check if it already inherits from the specified rank
            val rank = rankManager.getRank(name)
            if (rank == null) {
                actor.sendMessage(Component.text("Rank not found: $name", NamedTextColor.YELLOW))
                return
            }

            val alreadyInherits = rank.inherits.contains(inherit)

            // Toggle inheritance based on current state
            val success = if (alreadyInherits) {
                // Already inherits, so remove it
                val removed = rankManager.removeInheritedRank(name, inherit)
                if (removed) {
                    actor.sendMessage(Component.text()
                        .append(Component.text("Successfully removed inheritance: ", NamedTextColor.GREEN))
                        .append(Component.text(name, NamedTextColor.GOLD))
                        .append(Component.text(" no longer inherits from ", NamedTextColor.GREEN))
                        .append(Component.text(inherit, NamedTextColor.GOLD))
                        .build()
                    )
                } else {
                    actor.sendMessage(Component.text("Failed to remove inheritance. Rank not found or no inheritance relationship exists.", NamedTextColor.YELLOW))
                }
                removed
            } else {
                // Doesn't inherit yet, so add it
                val added = rankManager.addInheritedRank(name, inherit)
                if (added) {
                    actor.sendMessage(Component.text()
                        .append(Component.text("Successfully added inheritance: ", NamedTextColor.GREEN))
                        .append(Component.text(name, NamedTextColor.GOLD))
                        .append(Component.text(" now inherits from ", NamedTextColor.GREEN))
                        .append(Component.text(inherit, NamedTextColor.GOLD))
                        .build()
                    )
                } else {
                    actor.sendMessage(Component.text("Failed to add inheritance. One of the ranks was not found.", NamedTextColor.YELLOW))
                }
                added
            }
        } catch (e: Exception) {
            actor.sendMessage(Component.text("Failed to toggle inheritance: ${e.message}", NamedTextColor.RED))
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
                actor.sendMessage(Component.text("Usage: /rank info <name>", NamedTextColor.YELLOW))
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
                    .append(Component.text("=== Rank Info: ", NamedTextColor.GOLD, TextDecoration.BOLD))
                    .append(Component.text(rank.name, NamedTextColor.WHITE, TextDecoration.BOLD))
                    .append(Component.text(" ===", NamedTextColor.GOLD, TextDecoration.BOLD))
                    .appendNewline()
                    .append(Component.text("Prefix: ", NamedTextColor.YELLOW))
                    .append(Component.text(rank.prefix, NamedTextColor.WHITE))
                    .appendNewline()
                    .append(Component.text("Weight: ", NamedTextColor.YELLOW))
                    .append(Component.text(rank.weight.toString(), NamedTextColor.WHITE))
                    .appendNewline()
                    .append(Component.text("Inherits: ", NamedTextColor.YELLOW))
                    .append(Component.text(if (directInherits.isEmpty()) "None" else directInherits.joinToString(", "), NamedTextColor.WHITE))
                    .appendNewline()
                    .append(Component.text("Permissions:", NamedTextColor.YELLOW))
                    .appendNewline()

                // Display permissions in requested format
                if (permissionMap.isEmpty()) {
                    component.append(Component.text("  None", NamedTextColor.GRAY))
                } else {
                    // First show own permissions
                    permissionMap.entries.filter { it.value == null }.forEach { (perm, _) ->
                        component.append(Component.text("  ", NamedTextColor.GRAY))
                            .append(Component.text(perm, NamedTextColor.WHITE))
                            .appendNewline()
                    }

                    // Then show inherited permissions with source
                    permissionMap.entries.filter { it.value != null }.forEach { (perm, source) ->
                        component.append(Component.text("  ", NamedTextColor.GRAY))
                            .append(Component.text(perm, NamedTextColor.WHITE))
                            .append(Component.text(" (from ", NamedTextColor.GRAY))
                            .append(Component.text(source!!, NamedTextColor.GOLD))
                            .append(Component.text(")", NamedTextColor.GRAY))
                            .appendNewline()
                    }
                }

                actor.sendMessage(component.build())
            } else {
                actor.sendMessage(Component.text("Rank not found: $name", NamedTextColor.YELLOW))
            }
        } catch (e: Exception) {
            actor.sendMessage(Component.text("Failed to get rank info: ${e.message}", NamedTextColor.RED))
        }
    }

    @Subcommand("list")
    @CommandPermission("command.rank")
    suspend fun listRanks(actor: Player
    ) {
        try {
            val ranks = rankManager.listRanksByWeight()
            if (ranks.isEmpty()) {
                actor.sendMessage(Component.text("No ranks found.", NamedTextColor.YELLOW))
                return
            }

            val component = Component.text()
                .append(Component.text("=== Ranks List ===", NamedTextColor.GOLD, TextDecoration.BOLD))
                .appendNewline()

            ranks.forEach { rank ->
                component.append(Component.text("• ", NamedTextColor.GRAY))
                    .append(Component.text(rank.name, NamedTextColor.WHITE))
                    .append(Component.text(" (", NamedTextColor.GRAY))
                    .append(Component.text("Weight: ${rank.weight}", NamedTextColor.YELLOW))
                    .append(Component.text(", ", NamedTextColor.GRAY))
                    .append(Component.text("Prefix: ", NamedTextColor.YELLOW))
                    .append(Component.text(rank.prefix, NamedTextColor.WHITE))
                    .append(Component.text(")", NamedTextColor.GRAY))
                    .appendNewline()
            }

            actor.sendMessage(component.build())
        } catch (e: Exception) {
            actor.sendMessage(Component.text("Failed to list ranks: ${e.message}", NamedTextColor.RED))
        }
    }
}