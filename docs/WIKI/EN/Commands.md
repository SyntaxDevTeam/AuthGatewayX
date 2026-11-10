# Commands

**Language / Język:** English · [Polski](../Komendy.md)

[Home](Home.md) · [Permissions](Permissions.md) · [Player guide](Player-guide.md)

Run player commands in Minecraft after signing in. The console supports help, reports, reload and migration operations, but cannot open password dialogs. `/agx` is an alias of `/authgatewayx` with identical arguments and permissions.

| Command | Purpose |
| --- | --- |
| `/changepassword` | Open a password-change dialog for your authenticated account |
| `/logout` | End an active session and disconnect |
| `/privacy [show\|accept\|reject\|revoke\|status]` | View the policy or manage acceptance after account authentication |
| `/agx`, `/agx help` | Show commands available to the sender |
| `/agx reload` | Reload supported runtime settings: currently the migration directory exclusion list |
| `/agx info <name>` | Account, session and security report, filtered by staff permissions |
| `/agx alts <name>` | Find accounts sharing historical IP addresses |
| `/agx setpassword <name>` | Open an administrative server-password reset dialog |
| `/agx migrate status <name>` | Show the most recent persistent migration ticket |
| `/agx migrate inspect <name> [old-uuid]` | Inspect providers and old identity data without migrating |
| `/agx migrate retry <name>` | Retry the existing `FAILED/PREPARED/MIGRATING` ticket and retain its backups |
| `/agx migrate recover <name> [old-uuid]` | Prepare recovery for a previously upgraded account when old UUID data is found |

Replace `<name>` with the player's name without brackets. In-game help entries suggest commands when clicked; they do not execute them automatically. Help is permission-filtered. `alts` supports suggestions for connected names, while offline names can still be typed manually; PRE_AUTH users do not receive those suggestions.

## Account reports

`info` shows the account ID, Minecraft UUID, identity type and status, dates, current session and authentication method. It also checks the current Mojang username classification, including names without an AuthGatewayX record.

Additional permissions reveal address history, GeoIP/network reputation, lockout, failed-login count, up to ten recent audit events and related accounts. The console receives the complete report. Password hashes and secrets are never displayed. In-game reports use a dialog with separate lines for long UUIDs and UTC timestamps to seconds; console output is text.

`alts` examines the last 30 days, returns at most 20 related accounts and reports truncation. Shared IP is an investigative clue, not proof of one person. See [Server protection](Server-protection.md).

## Reset a server password

Confirm account ownership first. An authenticated administrator runs `/agx setpassword Alex` and enters the new password in the dialog. The action is audited and an online target is disconnected. Deliver the temporary password privately and ask the owner to change it. This does not reset Microsoft credentials.

## Login and reload

Registration and login open automatically. There are no `/login`, `/register`, `/premium` or `/unregister` commands. All commands are blocked before authentication; while waiting for privacy acceptance, only `/privacy` is allowed.

`reload` updates only `migration.unmanaged-plugin-scan.ignored-plugin-directories`. Invalid YAML or directory names leave the last valid list active. Other settings and messages require a full restart.

## Migration operations

A UUID upgrade starts after successful Minecraft session verification and confirmation of the old server password. The player disconnects before data is copied. Administrative `inspect` accepts an optional historical UUID to handle old capitalization differences. `recover` requires actual evidence of old identity data, rather than trusting a name alone.

Targets must be offline for `retry` and `recover`. Unfinished recovery blocks account login until safe completion or rollback. See [Account identity verification](Premium.md).

## Proxy commands

Velocity provides `/authgatewayx alts <name>` and `/authgatewayx session <name>`; other subcommands go to Paper. The session report requires `authgatewayx.admin.session` and works only for connected players. Staff with locally assigned identities require backend authentication and a separate staff proof. See [Velocity](Velocity.md).
