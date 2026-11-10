<div align="center">

# AuthGatewayX

### Your account. Your name. Your game.

**Secure authentication for every player, convenient Minecraft forms, and protection from the very first connection.**
<p>
    <a href="#-download"><img src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3.2.0/assets/cozy/supported/paper_vector.svg" alt="Supports Paper" height="56"></a>
    <a href="#-download"><img src="https://github.com/SyntaxDevTeam/PunisherX/raw/main/assets/badges/folia.svg" alt="Supports Folia" height="56"></a>
    <a href="#-download"><img src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3.2.0/assets/cozy/supported/purpur_vector.svg" alt="Supports Purpur" height="56"></a>
</p>

[![Build](https://github.com/SyntaxDevTeam/AuthGatewayX/actions/workflows/buildexplorer.yml/badge.svg)](https://github.com/SyntaxDevTeam/AuthGatewayX/actions/workflows/buildexplorer.yml) [![Issues](https://img.shields.io/github/issues/SyntaxDevTeam/AuthGatewayX)](https://github.com/SyntaxDevTeam/AuthGatewayX/issues) [![Last commit](https://img.shields.io/github/last-commit/SyntaxDevTeam/AuthGatewayX)](https://github.com/SyntaxDevTeam/AuthGatewayX/commits) [![Java 25](https://img.shields.io/badge/Java-25-orange)](docs/WIKI/EN/Compatibility.md) [![License MIT](https://img.shields.io/badge/License-MIT-green)](LICENSE)

**[📦 Download](#-download) · [📚 WIKI EN](docs/WIKI/EN/Home.md) · [📚 WIKI PL](docs/WIKI/Home.md) · [⚙️ Installation](docs/WIKI/EN/Installation.md) · [💬 Support](https://github.com/SyntaxDevTeam/AuthGatewayX/issues)**

</div>

---

AuthGatewayX secures every Minecraft Java Edition player connection, including on servers configured with `online-mode=false`. New players register an account and returning players sign in through Minecraft dialogs. Official Minecraft session verification additionally protects verified identities, while administrators get account-security, connection-protection and diagnostic tools.

**Premium username impersonation protection: players cannot access an official Minecraft account owner's protected username without successfully verifying ownership of that account.**

Running a small server for friends or growing a community? Start with the default SQLite database, customize your messages, and manage authentication in one place.

## ✨ Why AuthGatewayX?

- **Registration and login for everyone.** Every player registers and uses an in-game password dialog by default, without placing passwords in commands or chat.
- **Verified Minecraft identity protection.** Officially owned usernames are checked against the Minecraft session; a verified account can skip the additional server password when explicitly authorized. Failed verification of a protected username denies access.
- **World protection before login.** PRE_AUTH isolation blocks movement, interactions, inventory actions, combat, chat, and unauthorized commands.
- **Abuse prevention.** Limits on connections, password attempts, registrations, and reconnects help control bots and brute-force attempts.
- **Passwords protected with Argon2id.** Password changes and administrative resets use forms, and important operations are recorded in the audit log.
- **Administrative tools.** Account reports, session status, security history, and account links through shared IP addresses, with separate permissions for sensitive data.
- **Messages in your style.** Language files, MiniMessage formatting, and message handling through MessageHandler.
- **Database choice.** SQLite for a simple start, plus MySQL, MariaDB, and PostgreSQL configuration.
- **Server ecosystem integrations.** CleanerX checks username policy, PunisherX provides punishment information, and controlled UUID migration helps keep player data associated with the correct identity.

[Explore all features →](docs/WIKI/EN/Features.md)

## 📦 Download

**[Browse AuthGatewayX builds →](https://github.com/SyntaxDevTeam/AuthGatewayX/actions)**

Select a successful packaging workflow run and download the appropriate artifact from its **Artifacts** section. GitHub Actions artifact downloads require signing in to GitHub; availability depends on the retention period.

| Distribution | Where to install it |
| --- | --- |
| **AuthGatewayX Paper** | The `plugins` folder on your Paper or Purpur server; choose the JAR matching your Minecraft version |
| **AuthGatewayX Velocity** | The `plugins` folder on your Velocity proxy, alongside the Paper module on the backend |

See the [installation guide](docs/WIKI/EN/Installation.md) for artifact selection and building from source. Java 25 and platform requirements are covered in [Compatibility](docs/WIKI/EN/Compatibility.md).

## ⚙️ Quick start

1. Prepare a test server with Java 25 and back up your data.
2. Stop the server and place the appropriate JAR in `plugins`.
3. For a standalone server, set `online-mode=false` in `server.properties` and leave Paper's Velocity support disabled.
4. Start the server and check the console for AuthGatewayX readiness. The plugin creates its configuration and SQLite database.
5. Customize `plugins/AuthGatewayX/config.yml`, messages, and your privacy policy URL. Restart the server.
6. Test registration and repeat login with different account identities, optional password exemption for verified players, and protection before authentication.

Only open an `online-mode=false` server to players when authentication protection is working. For proxy networks, follow the [separate Velocity guide](docs/WIKI/EN/Velocity.md), covering forwarding and backend protection.

**[Full installation walkthrough →](docs/WIKI/EN/Installation.md)**

## 🎮 Commands at a glance

| Command | Purpose |
| --- | --- |
| `/changepassword` | Change your own server account password |
| `/logout` | End your authenticated session and disconnect |
| `/privacy` | View the privacy policy and manage acceptance |
| `/agx help` | List available commands |
| `/agx info <name>` | Administrative account and session report |
| `/agx alts <name>` | Report account links through shared IPs |
| `/agx setpassword <name>` | Administrative server account password reset form |

Registration and login forms open automatically. `/agx` is an alias of `/authgatewayx`.

**[All commands](docs/WIKI/EN/Commands.md) · [Permissions](docs/WIKI/EN/Permissions.md)**

## 📚 Documentation

**[English](docs/WIKI/EN/Home.md) · [Polski](docs/WIKI/Home.md)**

| Want to… | Open |
| --- | --- |
| Set up the plugin and learn daily operations | [Administrator guide](docs/WIKI/EN/ADMIN-GUIDE.md) |
| Help players sign in for the first time | [Player guide](docs/WIKI/EN/Player-guide.md) |
| Customize settings and appearance | [Configuration](docs/WIKI/EN/Configuration.md) · [Messages](docs/WIKI/EN/Messages.md) |
| Understand verified identity protection | [Account verification and UUID migration](docs/WIKI/EN/Premium.md) |
| Configure protection | [Server protection](docs/WIKI/EN/Server-protection.md) |
| Prepare a backup or update | [Database and backups](docs/WIKI/EN/Database.md) |
| Connect the plugin to other services | [Integrations](docs/WIKI/EN/Integrations.md) · [Velocity](docs/WIKI/EN/Velocity.md) |
| Solve a problem | [Help and FAQ](docs/WIKI/EN/Help.md) |

## 🛠️ For developers

The project separates domain logic, API, authentication, security, integrations, and storage into independent modules, with platform adapters for Paper and Velocity. Usage guides are available in [English](docs/WIKI/EN/Home.md) and [Polish](docs/WIKI/Home.md).

Build the Paper module:

```bash
./gradlew :authgatewayx-paper:build
```

Run the full build, tests, and JAR content verification:

```bash
./gradlew clean buildAll
```

Distributions are written to `authgatewayx-paper/build/libs` and `authgatewayx-velocity/build/libs`. See the [installation guide](docs/WIKI/EN/Installation.md) for selecting your Minecraft variant.

## 💬 Support and development

Have an idea or a problem? [Open an issue](https://github.com/SyntaxDevTeam/AuthGatewayX/issues) with your plugin, server, and Java versions, plus steps to reproduce the problem. Remove passwords, secrets, API keys, and player data before sharing logs.

## 📄 License

AuthGatewayX is released under the [MIT License](LICENSE).

Created by [SyntaxDevTeam](https://github.com/SyntaxDevTeam).
