# Hybrid Vanish System - Backend Server Requirements

## Overview
The hybrid vanish system has been successfully implemented in the Velocity proxy (Radium). This document outlines what needs to be implemented in the backend servers (Lobby/Game servers) to complete the hybrid vanish system.

## Backend Server Responsibilities

### 1. Plugin Message Handling
The backend servers need to listen for vanish state changes sent from the Velocity proxy:

**Channel:** `radium:vanish`

**Message Format:**
```json
{
    "action": "set_vanish" | "remove_vanish" | "batch_update",
    "player": "player_uuid",
    "vanished": true | false,
    "level": "HELPER" | "MODERATOR" | "ADMIN" | "OWNER",
    "vanishedBy": "vanisher_uuid" (optional),
    "reason": "reason text" (optional),
    "updates": [...] (for batch_update only)
}
```

### 2. Entity Visibility Management
Based on the received vanish state, the backend servers should:

- **Hide vanished players** from other players (using appropriate bukkit/spigot APIs)
- **Show vanished players** to staff members who have sufficient permissions
- **Handle player interactions** (prevent non-staff from targeting vanished players)

### 3. Permission Levels
The backend servers should respect these permission levels for vanish visibility:

```yaml
permissions:
  radium.vanish.helper: true     # Can see HELPER level vanished players
  radium.vanish.moderator: true  # Can see MODERATOR+ level vanished players  
  radium.vanish.admin: true      # Can see ADMIN+ level vanished players
  radium.vanish.owner: true      # Can see all vanished players
```

### 4. Server Switch Handling
When a player switches to your server:
- Request current vanish states from Velocity (or wait for batch update)
- Apply entity visibility rules for all currently vanished players
- Ensure the new player's vanish state is maintained if they were vanished

### 5. Example Implementation (Bukkit/Spigot)

```java
// Plugin message listener
@EventHandler
public void onPluginMessage(PluginMessageEvent event) {
    if (!event.getTag().equals("radium:vanish")) return;
    
    // Parse JSON message
    JsonObject data = JsonParser.parseString(new String(event.getData())).getAsJsonObject();
    String action = data.get("action").getAsString();
    
    if ("set_vanish".equals(action)) {
        UUID playerId = UUID.fromString(data.get("player").getAsString());
        boolean vanished = data.get("vanished").getAsBoolean();
        String level = data.get("level").getAsString();
        
        updatePlayerVisibility(playerId, vanished, level);
    }
    // Handle other actions...
}

private void updatePlayerVisibility(UUID playerId, boolean vanished, String level) {
    Player player = Bukkit.getPlayer(playerId);
    if (player == null) return;
    
    for (Player viewer : Bukkit.getOnlinePlayers()) {
        if (vanished) {
            if (canSeeVanished(viewer, level)) {
                viewer.showPlayer(plugin, player);
            } else {
                viewer.hidePlayer(plugin, player);
            }
        } else {
            viewer.showPlayer(plugin, player);
        }
    }
}

private boolean canSeeVanished(Player viewer, String level) {
    switch (level) {
        case "HELPER": return viewer.hasPermission("radium.vanish.helper");
        case "MODERATOR": return viewer.hasPermission("radium.vanish.moderator");
        case "ADMIN": return viewer.hasPermission("radium.vanish.admin");
        case "OWNER": return viewer.hasPermission("radium.vanish.owner");
        default: return false;
    }
}
```

## What's Already Implemented in Velocity

### ✅ Vanish State Management
- `NetworkVanishManager` handles all vanish state tracking
- Permission-based vanish levels (HELPER, MODERATOR, ADMIN, OWNER)
- Cross-server synchronization via plugin messages

### ✅ Tab List Control
- Staff can see vanished players with `[V]` indicator in tab list
- Non-staff cannot see vanished players in tab list
- Automatic updates when vanish state changes

### ✅ Commands
- `/vanish [player] [level]` - Toggle or set vanish state
- `/vanish list` - List all vanished players (staff only)
- `/vanish staff` - Quick staff vanish toggle

### ✅ API Integration
- HTTP API endpoint for vanish control from external systems
- Plugin message broadcasting to backend servers

### ✅ Events & Listeners
- Auto-vanish for staff on join (configurable)
- Server switch vanish state preservation
- Batch updates for performance

## Testing Checklist

When implementing on backend servers, test:

1. **Basic Vanish/Unvanish**
   - Player disappears/reappears for appropriate viewers
   - Tab list updates correctly

2. **Permission Levels**
   - HELPER level staff can see HELPER vanished players
   - MODERATOR level staff can see HELPER+MODERATOR vanished players
   - etc.

3. **Server Switching**
   - Vanish state persists when switching servers
   - Entity visibility updates correctly on new server

4. **Batch Updates**
   - Multiple vanish changes process efficiently
   - No entity visibility glitches during batch updates

## Notes

- The Velocity proxy handles all tab list management - backend servers should NOT modify tab lists
- Plugin messages are sent reliably with error handling and retries
- The system supports both individual and batch vanish updates for performance
- All vanish state is managed centrally in Velocity for consistency across the network
