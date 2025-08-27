# Radium Vanish System Documentation

## Overview
The Radium vanish system provides a comprehensive solution for making staff members invisible to appropriate players based on rank weights and permissions. The system operates across both the Velocity proxy and backend servers.

## Architecture

### Proxy-Side (Velocity)
The proxy handles:
- **Tab List Management**: Removing/adding vanished players from tab lists based on visibility rules
- **Vanish State Tracking**: Maintaining the vanished status of players
- **Permission/Rank Checking**: Determining who can see vanished players
- **Redis Messaging**: Broadcasting vanish events to backend servers
- **Auto-Vanish**: Automatically vanishing players on join if configured

### Backend Server-Side 
The backend servers handle:
- **Player Entity Visibility**: Actually hiding/showing the player model and entity
- **Collision**: Preventing interaction with vanished players
- **Chat Integration**: Ensuring vanished players don't appear in certain contexts

## Visibility Rules

Players can see vanished staff members if:
1. They have the `radium.vanish.see` permission, OR
2. Their rank weight is greater than or equal to the vanished player's rank weight

### Examples:
- Owner (weight 100) vanishes → Only other owners or players with `radium.vanish.see` can see them
- Moderator (weight 50) vanishes → Admins (weight 75), Owners (weight 100), and players with permission can see them
- Helper (weight 25) vanishes → Moderators+, Admins+, Owners+, and players with permission can see them

## Backend Server Requirements

For the vanish system to work properly, backend servers MUST have a vanish plugin that:

1. **Supports the `/vanish` command** - The proxy spoofs this command to toggle vanish on the backend
2. **Integrates with Redis** - Listens for vanish events from `radium:player:vanish` channel
3. **Respects rank-based visibility** - Uses the same rank weight logic as the proxy

### Recommended Plugins:
- **EssentialsX** - Built-in vanish with customization
- **SuperVanish/PremiumVanish** - Advanced vanish features
- **CMI** - Comprehensive management with vanish
- **Custom Plugin** - Implementing the Redis integration

### Redis Integration Example:
```java
// Listen for vanish events
jedis.subscribe(new JedisPubSub() {
    @Override
    public void onMessage(String channel, String message) {
        if (channel.equals("radium:player:vanish")) {
            // Parse message: "uuid=...,username=...,vanished=true,timestamp=..."
            // Update local vanish state
            // Apply visibility rules to all online players
        }
    }
}, "radium:player:vanish");
```

## Configuration

### Proxy Configuration (radium-config.yml)
```yaml
vanish:
  # Whether to enable vanish functionality
  enabled: true
  
  # Whether to show (Vanished) indicator in tab for staff who can see vanished players
  show_vanished_indicator: true
  
  # Redis channel for broadcasting vanish events
  redis_channel: "radium:player:vanish"
```

### Player Auto-Vanish Setting
Players can enable auto-vanish in their profile settings:
```yaml
# In player's profile
settings:
  autoVanish: "true"  # Automatically vanish on join
```

## Commands

### `/vanish [player]`
- **Usage**: `/vanish` or `/vanish <player>`
- **Permission**: `radium.vanish` (self), `radium.vanish.others` (others)
- **Description**: Toggles vanish status for self or specified player

## Permissions

- `radium.vanish` - Allow vanishing/unvanishing self
- `radium.vanish.others` - Allow vanishing/unvanishing other players
- `radium.vanish.see` - Override to see ALL vanished players regardless of rank weight

## Technical Details

### Tab List Behavior
1. When a player vanishes:
   - Their entry is removed from tab lists of players who shouldn't see them
   - Players who CAN see them see a "(Vanished)" indicator
   - Tab list is refreshed for all online players

2. When a player unvanishes:
   - Their entry is re-added to all tab lists
   - Tab lists are refreshed for all online players

### Message Flow
1. Player executes `/vanish`
2. Proxy updates vanish state and spoofs `/vanish` command to backend
3. Proxy publishes vanish event to Redis
4. Proxy refreshes all tab lists
5. Backend server receives Redis event and updates local vanish state
6. Backend server applies visibility rules to all players

### Error Handling
- If Redis is unavailable, vanish still works but backend servers won't be notified
- If backend vanish command fails, tab list changes still apply
- Comprehensive logging for debugging vanish issues

## Troubleshooting

### Common Issues:

1. **Player is hidden in tab but visible in-game**
   - Backend server doesn't have vanish plugin
   - Vanish plugin not responding to `/vanish` command
   - Redis integration not working

2. **Player is visible in tab but hidden in-game**
   - Tab list refresh failed
   - Permission/rank weight calculation error

3. **Vanish not working at all**
   - Check `radium.vanish` permission
   - Verify vanish is enabled in config
   - Check Redis connectivity

### Debug Commands:
- Check vanish status: Monitor proxy logs for vanish debug messages
- Verify Redis: Check Redis logs for published messages
- Test rank weights: Use rank weight comparison methods

## Integration with Lobby Server

The lobby server receives vanish events through the Radium API integration:
- `isPlayerVanished(uuid)` - Check if a player is vanished
- `canPlayerSeeVanished(viewer, vanished)` - Check visibility permissions
- Tab list and visibility managers respect vanish status
- Join/leave messages are suppressed for vanished players to unauthorized viewers
