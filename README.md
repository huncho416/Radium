# Radium - Advanced Velocity Staff Plugin

[![Minecraft Version](https://img.shields.io/badge/Minecraft-1.21.8-brightgreen.svg)](https://minecraft.net)
[![Velocity](https://img.shields.io/badge/Velocity-3.4.0--SNAPSHOT-blue.svg)](https://papermc.io/software/velocity)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A comprehensive staff management plugin for Velocity proxy servers with advanced permission systems, weight-based vanish functionality, and seamless database integration.

## 🌟 Features

### 🔐 Advanced Permission System
- **Granular Permissions**: Fine-grained control over staff command access
- **Hierarchical Structure**: `radium.<command>.<subcommand>` pattern
- **Double Permission Checks**: Base staff permission + specific command permissions
- **No Console Spam**: Clean permission checking without excessive logging

### 👻 Weight-Based Vanish System
- **Rank Hierarchy**: Higher-ranked staff can see lower-ranked vanished staff
- **Admin Override**: `radium.vanish.see` permission bypasses rank restrictions
- **Auto-Vanish**: Configurable auto-vanish on staff join
- **Tab List Integration**: Smart tab list management based on permissions

### 🎮 Staff Commands
- **Gamemode Management**: `/gmc`, `/gms`, `/gm1`, `/gm2`, etc.
- **Permission Control**: `/permission add/remove/list/clear`
- **Rank Management**: `/rank create/delete/setweight/info`
- **Grant System**: `/grant` and `/revoke` with expiration support
- **Staff Chat**: `/staffchat` with toggle and hide features
- **Vanish Control**: `/vanish`, `/vanish auto`, `/vanish list`

### 💬 Player Commands
- **Cross-Server Messaging**: `/msg`, `/tell`, `/whisper` for private messages
- **Reply System**: `/reply`, `/r` to respond to messages
- **Friend System**: `/friend add/remove/list` for friend management
- **Server Information**: `/lastseen` to check player activity

### 🗄️ Database Integration
- **MongoDB**: Primary data storage with reactive streams
- **Redis**: High-performance caching layer
- **Dual Sync**: Memory → Redis → MongoDB synchronization
- **Profile Caching**: Fast permission lookups and profile access

## 🚀 Quick Start

### Prerequisites
- **Minecraft**: 1.21.8+
- **Velocity**: 3.4.0-SNAPSHOT or higher
- **Java**: 21+
- **MongoDB**: 4.4+
- **Redis**: 6.0+

### Installation

1. **Download** the latest release from the [releases page](https://github.com/huncho416/Radium/releases)
2. **Place** `Radium.jar` in your Velocity `plugins/` directory
3. **Configure** your database connections in `plugins/radium/database.yml`
4. **Start** your Velocity server
5. **Configure** server connections in `velocity.toml`

### Database Setup

#### Using Docker (Recommended)
```bash
# Clone the repository
git clone https://github.com/huncho416/Radium.git
cd Radium

# Start MongoDB and Redis
docker-compose up -d
```

#### Manual Setup
Configure your database connections in `plugins/radium/database.yml`:
```yaml
mongodb:
  host: localhost
  port: 27017

redis:
  host: localhost
  port: 6379
  username: "default"
  password: "your_password"
```

### Server Configuration

Add your servers to `velocity.toml`:
```toml
[servers]
lobby = "127.0.0.1:25566"
survival = "127.0.0.1:25567"
creative = "127.0.0.1:25568"

try = ["lobby"]
```

**For Minestom Backend Servers (MythicHub Integration):**
- Use `player-info-forwarding-mode = "none"` in `velocity.toml`
- Radium provides Redis-based communication for MythicHub integration
- **Chat Formatting**: Enabled on hub server, disabled on backend servers for Minecraft 1.19.1+ signed chat compatibility
- Available Redis channels for RadiumClient:
  - `radium:proxy:request` - Server list, player count requests
  - `radium:server:transfer` - Player server transfers
  - `radium:player:permission:check` - Permission checks
  - `radium:player:profile:request` - Player profile data
  - `radium:player:vanish` - Vanish status notifications
  - `radium:command:execute` - Forward staff commands to proxy
  - `radium:player:message` - Cross-server private messaging
- Configure your Minestom server to disable forwarding:
  ```kotlin
  // In your Minestom server setup
  val server = MinecraftServer.init()
  server.setBungeeCord(false) // Disable forwarding
  ```
- Ensure Velocity has `online-mode = true` for authentication

### ⚠️ Important Notes

**Chat Formatting**: Chat formatting is enabled on the hub server using rank-based prefixes and colors from `lang.yml`. It's disabled on backend servers to prevent protocol errors with Minecraft 1.19.1+ signed chat messages. Staff chat functionality remains available on all servers.

## 📖 Documentation

### Permission Structure
All commands follow the pattern: `radium.<command>.<subcommand>`

**Base Permission**: `radium.staff` (required for all staff commands)

**Command Permissions**:

**Staff Commands** (require `radium.staff` base permission):
- `radium.gamemode.use` - Basic gamemode command
- `radium.gamemode.creative` - `/gmc` shortcut
- `radium.vanish.use` - Toggle vanish mode
- `radium.vanish.see` - See all vanished players (admin override)
- `radium.permission.add` - Add permissions to players
- `radium.rank.create` - Create new ranks

**Player Commands** (no special permissions required):
- `/msg`, `/tell`, `/whisper` - Cross-server private messaging
- `/reply`, `/r` - Reply to messages
- `/friend` - Friend system commands
- `/lastseen` - Check player activity

See [PERMISSIONS.md](PERMISSIONS.md) for complete documentation.

### Weight-Based Vanish Examples

**Rank Setup**:
```
Owner   (weight: 1000) → Can see all vanished staff
Admin   (weight: 100)  → Can see Moderator + Helper vanished
Moderator (weight: 50) → Can see Helper vanished only
Helper  (weight: 25)   → Can see same-rank vanished only
```

**Usage**:
- `/vanish` - Toggle your vanish status
- `/vanish auto` - Toggle auto-vanish on join
- `/vanish list` - See vanished staff (filtered by your rank)

### Staff Commands Quick Reference

| Command | Permission | Description |
|---------|------------|-------------|
| `/gmc` | `radium.gamemode.creative` | Set creative mode |
| `/gms` | `radium.gamemode.survival` | Set survival mode |
| `/vanish` | `radium.vanish.use` | Toggle vanish mode |
| `/staffchat` | `radium.staffchat.use` | Toggle staff chat |
| `/grant <player> <rank>` | `radium.grant.use` | Grant rank to player |
| `/permission <player> add <perm>` | `radium.permission.add` | Add permission |

## 🔧 Development

### Building from Source

```bash
# Clone the repository
git clone https://github.com/huncho416/Radium.git
cd Radium

# Build the plugin
./gradlew shadowJar

# Run with Velocity for testing
./gradlew runVelocity
```

### Project Structure
```
src/main/kotlin/radium/backend/
├── commands/          # Command implementations
├── player/           # Player management & profiles
├── util/            # Database utilities
└── Radium.kt       # Main plugin class
```

## 🤝 Contributing

1. **Fork** the repository
2. **Create** a feature branch (`git checkout -b feature/amazing-feature`)
3. **Commit** your changes (`git commit -m 'Add amazing feature'`)
4. **Push** to the branch (`git push origin feature/amazing-feature`)
5. **Open** a Pull Request

## 📝 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- **PaperMC Team** for Velocity
- **MongoDB** for reliable database solutions
- **Redis** for high-performance caching
- **JetBrains** for Kotlin

## 📞 Support

- **Discord**: [Join our community](https://discord.gg/your-server)
- **Issues**: [GitHub Issues](https://github.com/huncho416/Radium/issues)
- **Wiki**: [Documentation Wiki](https://github.com/huncho416/Radium/wiki)

---

Made with ❤️ for the Minecraft community
