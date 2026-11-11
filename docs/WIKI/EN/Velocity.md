# Velocity networks

**Language / Język:** English · [Polski](../Velocity.md)

[Home](Home.md) · [Installation](Installation.md) · [Compatibility](Compatibility.md)

Install LoginCoreX Velocity in the proxy's `plugins` folder and LoginCoreX Paper on the game backend. The proxy module alone does not provide password forms or world isolation. Validate your complete network on a private environment.

## Forwarding setup

With the proxy stopped, configure `velocity.toml`:

```toml
online-mode = false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
```

LoginCoreX verifies official Minecraft session ownership when applicable and requires server authentication for every player by default. On Paper, set `online-mode=false` in `server.properties` and edit the existing `proxies` section in `config/paper-global.yml`:

```yaml
proxies:
  velocity:
    enabled: true
    online-mode: false
    secret: "REPLACE_WITH_THE_ACTUAL_VELOCITY_SECRET"
```

Use the actual secret from the proxy's forwarding secret file. Do not leave example text or publish the value. The Paper forwarding `online-mode` must match Velocity's overall mode. Standalone Paper must leave Velocity support disabled.

Restrict the backend port in your firewall/hosting panel to trusted proxy connections. Players must connect through Velocity. A forwarding secret does not replace network restrictions.

## Plugin settings

Velocity uses `logincorex.yml` in its LoginCoreX data folder, separately from Paper's `config.yml`.

| Setting | Default | Meaning |
| --- | --- | --- |
| `premium.lookup.timeout-millis` | `3000` | Total lookup queue and HTTP deadline |
| `premium.lookup.positive-ttl-seconds` | `21600` | Official Minecraft profile cache lifetime |
| `premium.lookup.negative-ttl-seconds` | `300` | Missing profile cache lifetime |
| `premium.lookup.maximum-cache-size` | `50000` | Cached-result limit |
| `connections.admission-timeout-millis` | `20000` | Overall async admission deadline, shorter than Velocity's login timeout |
| `connections.pending-ttl-seconds` | `30` | Pending connection-decision lifetime |
| `connections.maximum-pending` | `10000` | Pending decision capacity |
| `executors.mojang-threads` | `4` | Concurrent username lookup tasks |
| `executors.mojang-queue` | `128` | Queued lookup tasks |

Restart after changes. With `storage.enabled: true`, use the same Paper account/history database and the correct JDBC scheme: `jdbc:mysql:`, `jdbc:mariadb:`, `jdbc:postgresql:` or `jdbc:sqlite:`. LoginCoreX explicitly selects the driver by scheme. A separate empty proxy database is insufficient; the proxy reads existing schema and does not run migrations. Do not concurrently share a SQLite file between independent servers.

## VPN and related-account admission

`storage.enabled` enables shared history reads. `multi-account.action` selects `ALERT`, `DENY` or `DISABLED`. `ip-intelligence.enabled` enables VPN/proxy/Tor checks, whose default action is `DENY`; `show-geo` adds country and ASN. Both optional integrations are disabled by default.

`alerts.enabled`, `alerts.console` and `alerts.cooldown-seconds` control proxy notifications. Alerts concern attempted connections under a name, not confirmed password authentication. Without shared history, a changed IP cannot be linked to previous accounts. Paper alerts remain local and can be disabled to avoid duplicates.

The full setting reference, failure policies and rollout order are in [Velocity configuration](Velocity.md).

## Staff authentication and secrets

Assign `logincorex.admin.alerts` and `logincorex.admin.alts` on the proxy. Staff with locally assigned identities additionally need authenticated backend sessions and matching separate `staff-proof.secret` values on Paper and Velocity. Console and Mojang-authenticated staff do not require offline proof.

Generate your own cryptographically random secret of at least 32 bytes, preferably 32 random bytes encoded as hex/base64. Set it on both processes or supply `LOGINCOREX_STAFF_PROOF_SECRET` to both. Never reuse the old publicly known example or the Velocity forwarding secret. New installs default to an empty proof secret; staff proof for locally assigned identities stays unavailable until configured. Updating the JAR does not replace secrets in existing files.

## Session diagnostics

`/logincorex session <name>` requires `logincorex.admin.session` and verified staff identity. It reports a connected player's proxy session identifier, verification mode and backend. The identifier correlates events; it is not an authentication token.

Reconnect creates a new proxy session; switching backends within the same proxy retains it. `OFFLINE` in the proxy report does not prove successful password login on Paper. Never treat a session identifier as an access credential.

Keep `connections.admission-timeout-millis` below Velocity's login timeout, normally 30 seconds (`-Dvelocity.login-timeout`). LCX defaults to 20 seconds so it can finish futures and free slots first. Proxy packet/flood protection operates independently; LCX does not replace it.

## Startup diagnostics

`language: PL` belongs in `logincorex.yml`. MessageHandler does not create an additional `config.yml`. Existing legacy language values are still read when the main file lacks the key; after moving it, the old file can be removed.

`shared history is unavailable; proxy admission remains closed` indicates a database/schema/SELECT-permission problem. Check that Paper has initialized the same database, including account and history tables. On another machine, `127.0.0.1` means the proxy host, not the Paper host. Logs show the cause class, SQLState, driver code and a hint without the password or JDBC URL. `JDBC driver is unavailable` indicates a missing driver class or incorrect scheme, even when SQLState is `08001`.

`Authentication service is unavailable. [LCX-STARTUP]` means the proxy has not reached READY. It repeats the remembered STARTING/FAILED stage and safe cause at most once every ten seconds. Stages include DATABASE, CONFIGURATION, MESSAGES, STAFF_PROOF and LISTENERS. Check the first initialization error and the absolute configuration path logged at startup. Editing source resources does not modify an existing runtime file. If startup and denial markers are absent after restart, check which JAR and proxy instance are serving the connection. Do not force READY to bypass initialization.

For username lookup/backoff errors see [Help](Help.md). `Backend server is online-mode` suggests checking backend `server.properties`, forwarding enablement and secret agreement.

## Verify your network

Test registration and login for both officially verified and locally assigned identities, reconnects, backend switches and rejection of direct backend connections from outside the proxy. Do not assume authentication automatically follows players across every backend.

## Connection diagnostics

In the Velocity plugin's `logincorex.yml`, add `diagnostics: true` to the existing
`connections` section and restart the proxy. The default is `false`.
`[login-diag]` entries include the username, session ID (`null` on older Velocity),
`LOOKUP_PREMIUM` / `LOOKUP_NOT_PREMIUM` / `LOOKUP_UNAVAILABLE`, selected `MOJANG` / `OFFLINE`
mode, admission result and available LOGIN / DISCONNECT events. No IPs, passwords or tokens are logged.
Output is limited to 120 lines per minute across the proxy; excess lines and queue overflow are dropped.
A missing later entry does not prove an invalid session: clients may disconnect and native
rejections before LOGIN may not emit a plugin DISCONNECT event. `MOJANG` means verification
is required, not that it succeeded. Disable diagnostics and restart after testing.
