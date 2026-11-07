# Administrator guide

**Language / Język:** English · [Polski](../ADMIN-GUIDE.md)

[Home](Home.md) · [Installation](Installation.md) · [Configuration](Configuration.md) · [Help](Help.md)

Follow this guide from initial setup to daily operations. Detailed setting names and defaults are in [Configuration](Configuration.md).

## 1. Choose your deployment

A standalone Paper/Purpur server needs the matching Paper JAR, Java 25 and, initially, local SQLite storage. A Velocity network needs the proxy module, Paper on the backend, modern forwarding and firewall restrictions on direct backend access.

Choose the artifact for your Minecraft version using [Compatibility](Compatibility.md), then follow [Installation](Installation.md). Do not run competing authentication plugins together.

## 2. Prepare configuration

Paper uses `plugins/AuthGatewayX/config.yml`. Velocity uses `authgatewayx.yml` in its own plugin folder. Stop the server and back up files before editing existing sections. This example is a fragment, not a replacement for the complete file:

```yaml
language: "PL"
authentication:
  timeout-seconds: 120
  maximum-pre-auth-players: 500
  password:
    minimum-length: 8
    maximum-length: 128
  lockout:
    attempts: 5
    duration-seconds: 600
storage:
  type: SQLITE
  sqlite-file: authgatewayx.db
  pool-size: 2
```

Keep other fields, especially Argon2id parameters. Bounded default queues and limits are a starting point; change them based on measurements. Lowering hashing cost is not a remedy for connection abuse. `PL` selects the bundled runtime messages; the documentation language does not change the plugin's message language.

For a remote engine, create a dedicated database/user using [Database and backups](Database.md). Changing `storage.type` alone does not transfer accounts.

## 3. Customize messages and privacy

Edit titles, instructions, prefixes and colors in `lang/messages_pl.yml`, retaining keys and placeholders. MessageHandler handles formatting. Set the real HTTPS policy address through `privacy.policy-url` or `privacy.link`, and increment `privacy.policy-version` when the policy changes.

Acceptance is required after authentication by default. Rejection or revocation disconnects the player without automatically deleting the account. See [Messages](Messages.md).

## 4. Assign staff access

Use specific permissions through your permissions plugin. Basic diagnosis uses `authgatewayx.admin.info`; alerts use `authgatewayx.admin.alerts`; shared-address investigations use `authgatewayx.admin.alts`. IP, network and audit details require separate `view-ip`, `view-geo` and `view-security` permissions. Password resets require `authgatewayx.admin.password`; migration inspection requires `authgatewayx.admin.migration.view`.

Operators have administrative defaults. Check inherited permissions and operator status when restricting a role. A permission never replaces authentication. See the complete [Permissions](Permissions.md) list.

## 5. Verify first login

Complete these checks on your private test environment before opening access; mark them only after actually verifying the result.

- [ ] New offline players receive registration; existing players receive login.
- [ ] Incorrect passwords deny access and correct passwords proceed.
- [ ] Players cannot affect the world before authentication and required acceptance.
- [ ] Your own premium account passes official verification.
- [ ] Password changes, logout and repeat offline login work.
- [ ] Acceptance persists and rejection ends the connection.
- [ ] Accounts remain usable after a restart.
- [ ] Staff see only the sensitive data they are authorized to view.
- [ ] Database/configuration backups restore successfully on a separate environment.

Minecraft dialog inputs display password characters. Ask players to hide the game while streaming or screen sharing.

## 6. Daily account support

**Forgotten offline password:** confirm ownership, then use `/agx setpassword <name>` in-game and enter the new password in the form. The target is disconnected. Deliver the temporary password privately and ask the owner to use `/changepassword`. This does not reset Microsoft credentials.

**Cannot connect:** inspect the message, `/agx info <name>`, service availability and console errors. For an invalid premium session, sign in again in the official launcher; do not bypass premium verification.

**Suspected alternate accounts:** inspect `/agx alts <name>`. Shared IP is a clue; households, schools and carrier NAT may join unrelated people. The report does not automatically punish users.

**Offline-to-premium upgrade:** after Mojang verification the player confirms the old server password. Data migrates after disconnect. Diagnose with `migrate status` and `inspect`; never manually replace account UUIDs. See [Premium accounts](Premium.md).

## 7. Protection and integrations

Start with default limiters. Distinguish password lockout, reconnect protection and native LOGIN timeouts: the standalone timeout counter and IP block persist across restarts. Consider legitimate shared-IP users when tuning protection.

CleanerX/PunisherX `AUTO` mode uses available compatible services. For mandatory checks, review `REQUIRED` and `FAIL_CLOSED` in [Integrations](Integrations.md).

External IP reputation is disabled by default and sends public IPs to the provider when enabled. Paper produces alerts; proxy admission decisions are configured separately. See [Server protection](Server-protection.md) and [Velocity](Velocity.md).

## 8. Restart, backup and update

Most settings require a full restart. `/agx reload` only refreshes `migration.unmanaged-plugin-scan.ignored-plugin-directories`; it does not reload all settings, messages or database connections.

Before upgrading, stop the server, back up the plugin folder and database, and test the new JAR on a copy. When reverting, restore matching plugin, configuration and database versions. See [Database and backups](Database.md).

For unresolved issues, follow [Help and FAQ](Help.md) and provide versions plus reproduction steps with player data and secrets removed.
