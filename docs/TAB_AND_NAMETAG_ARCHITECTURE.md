# Tab List and Nametag System Architecture

## Overview

Radium implements two completely separate visual systems for player identification:

1. **Tab List System** - Handles player names in the TAB overlay
2. **Nametag System** - Handles floating names above player heads in-world

These systems are architecturally separated to provide maximum flexibility and prevent conflicts.

## Tab List System (`TabListManager.kt`)

### Purpose
- Manages player display names in the TAB list overlay
- Shows rank prefixes and colors in the tab menu
- Handles vanish status visibility in tab

### Configuration
- Uses `lang.yml` for formatting templates:
  ```yaml
  tab:
    player_format: "<color>{color}>{prefix}{player}</color>"
    default_format: "<gray>{player}</gray>"
  ```

### Features
- Respects staff vanish status (vanished staff hidden from non-staff)
- Shows "(Vanished)" suffix for staff who can see vanished players
- Updates all players' tab lists when players join/leave/change ranks
- Re-adds players to tab when they unvanish

### Key Methods
- `updatePlayerTabList(player)` - Updates a single player's tab entry
- `updateAllPlayersTabList()` - Refreshes entire tab list
- Automatic updates on player connect events

## Nametag System (`NameTagService.kt`)

### Purpose  
- Manages floating nametags above player heads in-world
- Applies rank-based visual templates and colors
- Handles temporary nametag overrides and animations

### Configuration
- Uses `nametags.yml` for templates and settings:
  ```yaml
  enabled: true
  default_template: "<gray><username></gray>"
  respect_vanish: true
  weight_gating_enabled: true
  templates:
    admin: "<red>⚡</red> <red><username></red>"
    mod: "<blue>🔨</blue> <blue><username></blue>"
  ```

### Features
- Rank-based nametag templates with custom colors/symbols
- Weight-based visibility (higher rank staff see lower rank staff nametags)
- Temporary template overrides with expiry
- Batch update system to prevent spam
- Fallback to tab list updates when MSNameTags backend unavailable

### Key Methods
- `updatePlayerNameTag(player)` - Updates a single player's nametag
- `updateAllNameTags()` - Refreshes all nametags
- `setTemporaryTemplate()` - Applies temporary nametag override
- Background cleanup of expired temporary templates

## Architectural Benefits

### 1. **Independence**
- Tab formatting and nametag formatting can be configured separately
- Changes to one system don't affect the other
- Different visual themes can be applied to each

### 2. **Flexibility**
- Tab list can show detailed rank information
- Nametags can focus on visual appeal and symbols
- Each system can have different vanish behavior if needed

### 3. **Performance**
- Updates are batched and optimized per system
- No redundant operations between systems
- Efficient memory usage with separate caches

### 4. **Maintainability**
- Clear separation of concerns
- Easy to debug issues in specific systems
- Simple to add features to one without affecting the other

## Integration Points

### Vanish System
Both systems respect vanish status but handle it independently:
- **Tab**: Vanished players removed from non-staff tab lists
- **Nametags**: Vanished players get special templates or are hidden

### Rank Changes
When a player's rank changes:
1. `TabListManager.updatePlayerTabList()` updates tab display
2. `NameTagService.updatePlayerNameTag()` updates nametag template
3. Both systems operate independently with their own formatting

### Redis Communication
- Tab system: Direct Velocity tab list API
- Nametag system: Redis messages to MSNameTags backends
- No cross-system dependencies

## Future Customization

### Adding New Tab Formats
1. Add new keys to `lang.yml` under `tab:` section
2. Modify `TabListManager.updatePlayerTabList()` logic
3. No changes needed to nametag system

### Adding New Nametag Templates
1. Add templates to `nametags.yml` under `templates:` section
2. Templates automatically available via rank assignment
3. No changes needed to tab system

### Custom Behaviors
Each system can implement unique features:
- Tab: Custom sorting, server-specific displays
- Nametags: Animations, conditional visibility, special effects

## Configuration Examples

### Professional Tab + Fancy Nametags
```yaml
# lang.yml - Simple professional tab
tab:
  player_format: "<color:{color}>[{prefix}] {player}</color>"
  
# nametags.yml - Fancy visual nametags  
templates:
  owner: "<gradient:red:gold>👑 <username></gradient>"
  admin: "<red>⚡</red> <red><username></red>"
```

### Minimalist Tab + Detailed Nametags
```yaml
# lang.yml - Clean minimal tab
tab:
  player_format: "<gray>{player}</gray>"
  
# nametags.yml - Detailed rank information
templates:
  admin: "<red>[ADMIN]</red> <white><username></white>"
  mod: "<blue>[MODERATOR]</blue> <white><username></white>"
```

This separation ensures that visual preferences for tab lists and in-world nametags can be configured independently while maintaining system stability and performance.
