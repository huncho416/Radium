# Radium Permission System

This document outlines the permission structure implemented for Radium staff commands.

## Staff Permission Structure

All staff commands now require the base `radium.staff` permission plus specific permissions for each subcommand.

### Gamemode Command (`/gamemode`, `/gm`)
- **Base Permission**: `radium.staff`
- **Subcommands**:
  - `radium.gamemode.use` - Basic gamemode command usage
  - `radium.gamemode.others` - Set gamemode for other players
  - `radium.gamemode.survival` - Use survival gamemode shortcuts (`/gms`, `/gm0`)
  - `radium.gamemode.creative` - Use creative gamemode shortcuts (`/gmc`, `/gm1`)
  - `radium.gamemode.adventure` - Use adventure gamemode shortcuts (`/gm2`)
  - `radium.gamemode.spectator` - Use spectator gamemode shortcuts (`/gm3`)

### Permission Command (`/permission`, `/perms`, `/perm`)
- **Base Permission**: `radium.staff`
- **Subcommands**:
  - `radium.permission.use` - Basic permission command usage/help
  - `radium.permission.add` - Add permissions to players
  - `radium.permission.remove` - Remove permissions from players
  - `radium.permission.list` - List player permissions
  - `radium.permission.clear` - Clear all permissions from a player

### Rank Command (`/rank`, `/ranks`)
- **Base Permission**: `radium.staff`
- **Subcommands**:
  - `radium.rank.use` - Basic rank command usage/help
  - `radium.rank.create` - Create new ranks
  - `radium.rank.delete` - Delete existing ranks
  - `radium.rank.setprefix` - Set rank prefix
  - `radium.rank.setcolor` - Set rank color
  - `radium.rank.setweight` - Set rank weight/priority
  - `radium.rank.permission.add` - Add permissions to ranks
  - `radium.rank.permission.remove` - Remove permissions from ranks
  - `radium.rank.inherit` - Set rank inheritance
  - `radium.rank.info` - View rank information
  - `radium.rank.list` - List all ranks

### Grant Command (`/grant`)
- **Base Permission**: `radium.staff`
- **Subcommands**:
  - `radium.grant.use` - Grant ranks to players
  - `radium.grants.view` - View player's grants

### Revoke Command (`/revoke`)
- **Base Permission**: `radium.staff`
- **Subcommands**:
  - `radium.revoke.use` - Revoke ranks from players

### StaffChat Command (`/staffchat`, `/sc`)
- **Base Permission**: `radium.staff`
- **Subcommands**:
  - `radium.staffchat.use` - Toggle staff chat mode
  - `radium.staffchat.hide` - Hide staff chat messages
  - `radium.staffchat.send` - Send messages to staff chat

### Vanish Command (`/vanish`, `/v`)
- **Base Permission**: `radium.staff`
- **Subcommands**:
  - `radium.vanish.use` - Toggle vanish mode
  - `radium.vanish.auto` - Toggle auto-vanish on join
  - `radium.vanish.list` - List vanished staff members (filtered by rank weight)
  - `radium.vanish.see` - See all vanished players regardless of rank weight (admin permission)

## Weight-Based Vanish System

The vanish system now respects staff hierarchy based on rank weights:

### How It Works
1. **Rank Weight Comparison**: Staff members can only see vanished players whose rank weight is equal to or lower than their own rank weight.
2. **Admin Override**: Players with the `radium.vanish.see` permission can see all vanished players regardless of rank weight.
3. **Self-Visibility**: Players can always see themselves when vanished.

### Examples
- **Admin** (weight: 100) can see vanished **Moderators** (weight: 50) and **Helpers** (weight: 25)
- **Moderator** (weight: 50) can see vanished **Helpers** (weight: 25) but NOT vanished **Admins** (weight: 100)
- **Helper** (weight: 25) can only see other vanished **Helpers** (weight: 25) and lower

### Usage
- Use `/vanish list` to see which staff members are currently vanished (filtered by your rank weight)
- Grant `radium.vanish.see` to trusted admins who need to see all vanished staff

## Testing the Weight-Based Vanish System

### Test Scenarios

#### Setup
1. Create ranks with different weights:
   - **Owner**: weight 1000, prefix "&4[Owner]"
   - **Admin**: weight 100, prefix "&c[Admin]"
   - **Moderator**: weight 50, prefix "&6[Mod]"
   - **Helper**: weight 25, prefix "&9[Helper]"

#### Test Cases

1. **Admin can see Moderator's vanish**:
   - Give player1 the Admin rank (weight 100)
   - Give player2 the Moderator rank (weight 50)
   - player2 uses `/vanish` to become invisible
   - player1 uses `/vanish list` → Should see player2 in the list

2. **Moderator CANNOT see Admin's vanish**:
   - Give player1 the Moderator rank (weight 50)
   - Give player2 the Admin rank (weight 100)
   - player2 uses `/vanish` to become invisible
   - player1 uses `/vanish list` → Should NOT see player2 in the list

3. **Override permission works**:
   - Give player1 the Helper rank (weight 25)
   - Give player1 the permission `radium.vanish.see`
   - Any higher rank player vanishes
   - player1 uses `/vanish list` → Should see ALL vanished players

#### Commands to Test
```
/rank create Owner &4 1000
/rank create Admin &c 100
/rank create Moderator &6 50
/rank create Helper &9 25

/grant <player> <rank>
/permission <player> add radium.vanish.see

/vanish
/vanish list
/vanish auto
```

## Implementation Notes

1. **Double Permission Check**: Each command requires both the base `radium.staff` permission (at the class level) AND the specific subcommand permission.

2. **No More "Requesting"**: The gamemode command no longer has requesting logic - only users with proper permissions can execute the commands.

3. **Consistent Pattern**: All permissions follow the `radium.<command>.<subcommand>` pattern for easy management.

4. **Granular Control**: Server administrators can grant specific command permissions without giving access to all staff commands.

## Permission Assignment Examples

### Full Staff Access
```yaml
permissions:
  - radium.staff
  - radium.*
```

### Limited Staff Access (Only Gamemode and Vanish)
```yaml
permissions:
  - radium.staff
  - radium.gamemode.*
  - radium.vanish.*
```

### Specific Feature Access
```yaml
permissions:
  - radium.staff
  - radium.gamemode.use
  - radium.gamemode.creative
  - radium.gamemode.survival
```
