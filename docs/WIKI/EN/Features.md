# Features

**Language / Język:** English · [Polski](../Mozliwosci.md)

[Home](Home.md) · [Installation](Installation.md) · [Commands](Commands.md)

## Player authentication

Offline players register and sign in through Minecraft dialogs. Passwords are not command arguments or chat messages. Premium accounts use official Minecraft session verification. A protected premium name cannot fall back to offline login when verification fails.

AuthGatewayX manages one active account session, offers `/logout`, and supports both self-service offline password changes and authorized administrative resets through forms.

## Protected gameplay

PRE_AUTH blocks movement, combat, chat, commands, inventory operations, block use and world changes. Guards also cover buckets, books/lecterns, armor stands, shearing, fishing, projectiles and hanging entities. Command suggestions are hidden before authentication. Timeout and pending-player capacity bound incomplete logins.

## Password and connection protection

Argon2id protects offline passwords. Hashing and verification use bounded workers and queues. Failed passwords trigger attempt limits and lockout. Cheap connection guards precede database and external work; behavior scoring, name bursts and pre-auth reconnect loops can quarantine IPs. Registration has both short-term attempt and persistent account-per-address limits. See [Server protection](Server-protection.md).

## Premium identity migration

Buying Minecraft while keeping an existing offline name requires the old server password after official premium verification. Migration uses controlled providers and backups, never blind global UUID replacement. Providers cover vanilla data and integrations such as EssentialsX, LuckPerms, HorseManagerX, PunisherX and PlotsX when the required integration is available. Unmanaged data may block finalization.

Migration supports rollback and restart recovery. Staff can inspect, retry or recover previous upgrades. See [Premium accounts](Premium.md).

## Administrative tools

Account reports show identity, lifecycle dates, sessions and current Mojang classification. Separate permissions expose IP history, network reputation/GeoIP, lockout, recent audit and related accounts. Related-account reports use shared-address history as a clue, not proof of a person. Optional alerts notify authenticated staff and console; external IP checks are off by default.

## Storage and ecosystem

SQLite, MySQL, MariaDB and PostgreSQL are configurable; remote passwords can come from the environment. The separate Velocity module classifies connections before the backend and optionally applies shared-history and VPN/proxy/Tor admission checks.

CleanerX applies name policy, while PunisherX checks active punishments. You can configure what happens if an integration is unavailable. Compatible plugins and clients, including CraftConnect, can receive confirmation that a player is signed in; this does not grant administrative permissions.

MessageHandler provides language files and MiniMessage formatting. See [Configuration](Configuration.md), [Integrations](Integrations.md) and [Messages](Messages.md).

## Privacy policy on Paper

After successful offline or Mojang authentication, players remain isolated until they accept the required policy version. Native dialog versus clickable chat is selected from the server Minecraft version; `privacy.dialogs.enabled: false` forces chat. This does not lower the JAR's minimum Paper version.

Decisions are saved with the date and policy version and retained after upgrading to premium. Changing `privacy.policy-version` requires acceptance again. Decision timeout and read/write errors disconnect the player. Rejection/revocation does not automatically impose a PunisherX penalty.

Only `/privacy` is permitted while awaiting a decision. This behavior requires Paper; the proxy module alone does not enforce it. Set your real HTTPS policy link and server-specific information. Acceptance is not a substitute for determining the appropriate legal basis for processing. ConsentChecker data is not automatically imported.

For platform scope and functions outside the current release, see [Compatibility](Compatibility.md).
