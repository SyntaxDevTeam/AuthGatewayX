# Velocity networks

**Language / Język:** English · [Polski](../Velocity.md)

[Home](Home.md) · [Installation](Installation.md) · [Compatibility](Compatibility.md)

Install AuthGatewayX Velocity in the proxy's `plugins` folder and AuthGatewayX Paper on the game backend. The proxy module alone does not provide password forms or world isolation. Validate your complete network on a private environment.

## Forwarding setup

With the proxy stopped, configure `velocity.toml`:

```toml
online-mode = false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
```

AuthGatewayX selects premium or offline verification separately for each connection. On Paper, set `online-mode=false` in `server.properties` and edit the existing `proxies` section in `config/paper-global.yml`:

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

Velocity uses `authgatewayx.yml` in its AuthGatewayX data folder, separately from Paper's `config.yml`.

| Setting | Default | Meaning |
| --- | --- | --- |
| `premium.lookup.timeout-millis` | `3000` | Total lookup queue and HTTP deadline |
| `premium.lookup.positive-ttl-seconds` | `21600` | Premium profile cache lifetime |
| `premium.lookup.negative-ttl-seconds` | `300` | Missing profile cache lifetime |
| `premium.lookup.maximum-cache-size` | `50000` | Cached-result limit |
| `connections.admission-timeout-millis` | `20000` | Overall async admission deadline, shorter than Velocity's login timeout |
| `connections.pending-ttl-seconds` | `30` | Pending connection-decision lifetime |
| `connections.maximum-pending` | `10000` | Pending decision capacity |
| `executors.mojang-threads` | `4` | Concurrent username lookup tasks |
| `executors.mojang-queue` | `128` | Queued lookup tasks |

Restart after changes. With `storage.enabled: true`, use the same Paper account/history database and the correct JDBC scheme: `jdbc:mysql:`, `jdbc:mariadb:`, `jdbc:postgresql:` or `jdbc:sqlite:`. AuthGatewayX explicitly selects the driver by scheme. A separate empty proxy database is insufficient; the proxy reads existing schema and does not run migrations. Do not concurrently share a SQLite file between independent servers.

## VPN and related-account admission

`storage.enabled` enables shared history reads. `multi-account.action` selects `ALERT`, `DENY` or `DISABLED`. `ip-intelligence.enabled` enables VPN/proxy/Tor checks, whose default action is `DENY`; `show-geo` adds country and ASN. Both optional integrations are disabled by default.

`alerts.enabled`, `alerts.console` and `alerts.cooldown-seconds` control proxy notifications. Alerts concern attempted connections under a name, not confirmed password authentication. Without shared history, a changed IP cannot be linked to previous accounts. Paper alerts remain local and can be disabled to avoid duplicates.

The full setting reference, failure policies and rollout order are in [Velocity configuration](Velocity.md).

## Staff authentication and secrets

Assign `authgatewayx.admin.alerts` and `authgatewayx.admin.alts` on the proxy. Offline staff additionally need authenticated backend sessions and matching separate `staff-proof.secret` values on Paper and Velocity. Console and Mojang-authenticated staff do not require offline proof.

Generate your own cryptographically random secret of at least 32 bytes, preferably 32 random bytes encoded as hex/base64. Set it on both processes or supply `AUTHGATEWAYX_STAFF_PROOF_SECRET` to both. Never reuse the old publicly known example or the Velocity forwarding secret. New installs default to an empty proof secret; offline staff proof stays unavailable until configured. Updating the JAR does not replace secrets in existing files.

## Session diagnostics

`/authgatewayx session <name>` requires `authgatewayx.admin.session` and verified staff identity. It reports a connected player's proxy session identifier, verification mode and backend. The identifier correlates events; it is not an authentication token.

On newer Velocity, decisions are bound to the native connection session UUID, not IP, port or name. Reconnect creates a new session; backend changes within the same proxy retain it. Earlier 4.x compatibility repeats lookup and risk checks at login instead of trusting an IP/name match.

`AuthGatewayXVelocity.session(player)` exposes a bounded session snapshot: session/player UUID, username, `MOJANG/OFFLINE` mode, connection time and backend. Entries update on backend connection and disappear on disconnect/shutdown. `OFFLINE` does not prove successful password login on Paper. Network-wide shared authenticated sessions are not a completed feature.

Keep `connections.admission-timeout-millis` below Velocity's login timeout, normally 30 seconds (`-Dvelocity.login-timeout`). AGX defaults to 20 seconds so it can finish futures and free slots first. Proxy packet/flood protection operates independently; AGX does not replace it.

## Startup diagnostics

`language: PL` belongs in `authgatewayx.yml`. MessageHandler does not create an additional `config.yml`. Existing legacy language values are still read when the main file lacks the key; after moving it, the old file can be removed.

`shared history is unavailable; proxy admission remains closed` indicates a database/schema/SELECT-permission problem. Check that Paper has initialized the same database, including account and history tables. On another machine, `127.0.0.1` means the proxy host, not the Paper host. Logs show the cause class, SQLState, driver code and a hint without the password or JDBC URL. `JDBC driver is unavailable` indicates a missing driver class or incorrect scheme, even when SQLState is `08001`.

`Authentication service is unavailable. [AGX-STARTUP]` means the proxy has not reached READY. It repeats the remembered STARTING/FAILED stage and safe cause at most once every ten seconds. Stages include DATABASE, CONFIGURATION, MESSAGES, STAFF_PROOF and LISTENERS. Check the first initialization error and the absolute configuration path logged at startup. Editing source resources does not modify an existing runtime file. If startup and denial markers are absent after restart, check which JAR and proxy instance are serving the connection. Do not force READY to bypass initialization.

For username lookup/backoff errors see [Help](Help.md). `Backend server is online-mode` suggests checking backend `server.properties`, forwarding enablement and secret agreement.

## Verify your network

Test premium and offline login, reconnects, backend switches and rejection of direct backend connections from outside the proxy. Do not assume authentication automatically follows players across every backend.
