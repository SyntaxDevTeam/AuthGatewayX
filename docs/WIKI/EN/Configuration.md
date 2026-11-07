# Configuration

**Language / Język:** English · [Polski](../Konfiguracja.md)

[Home](Home.md) · [Server protection](Server-protection.md) · [Database](Database.md)

Paper settings live in `plugins/AuthGatewayX/config.yml`; Velocity uses its own [configuration](Velocity.md). Start with defaults. Stop the server, back up the file, edit existing sections and restart. Use spaces, preserve indentation and never duplicate keys.

Dotted names in the tables represent nested YAML sections. For example:

```yaml
authentication:
  timeout-seconds: 120
```

Examples are fragments, not whole configuration files. `seconds` means seconds; `millis` means milliseconds (1000 ms = 1 s). Do not use `0` as a universal off switch: `anti-bot.login-timeouts.window-seconds` is a documented exception.

## Language and authentication

| Setting | Default | Meaning |
| --- | --- | --- |
| `language` | `PL` | Message language; requires a matching language file |
| `authentication.timeout-seconds` | `120` | Time to register or sign in |
| `authentication.maximum-pre-auth-players` | `500` | Maximum players awaiting authentication |
| `authentication.password.minimum-length` | `8` | Minimum password length |
| `authentication.password.maximum-length` | `128` | Maximum password length |
| `authentication.lockout.attempts` | `5` | Failed attempts before account lockout |
| `authentication.lockout.duration-seconds` | `600` | Account lockout duration |

Minimum length must not exceed maximum length. See [Messages](Messages.md) for text customization.

## Privacy policy (Paper)

| Setting | Default | Meaning |
| --- | --- | --- |
| `privacy.enabled` | `true` | Require a decision after premium or offline authentication |
| `privacy.policy-version` | `1` | Policy version; changing it requires acceptance again |
| `privacy.policy-url` | empty | HTTPS URL overriding `privacy.link`; empty retains the message link |
| `privacy.timeout-seconds` | `120` | Time to decide after authentication |
| `privacy.dialogs.enabled` | `true` | Native dialog on server Minecraft 1.21.7+; otherwise chat. `false` forces chat |

Policy interface selection uses the server Minecraft version, independently of the client version. It does not extend platform compatibility: the current JAR declares minimum Paper API 26.1. Switching the policy interface to chat does not make this JAR run on older Paper releases.

## Premium verification

| Setting | Default | Meaning |
| --- | --- | --- |
| `premium.authentication.maximum-concurrent-handshakes` | `32` | Concurrent premium verifications on standalone servers |
| `premium.lookup.timeout-millis` | `3000` | Total username lookup deadline, including queue and HTTP |
| `premium.lookup.positive-ttl-seconds` | `21600` | Premium profile cache lifetime: six hours |
| `premium.lookup.negative-ttl-seconds` | `300` | Missing profile cache lifetime: five minutes |
| `premium.lookup.maximum-cache-size` | `50000` | Maximum cached results |

Caching reduces requests; it does not prove that the connecting player owns the account. Longer lifetimes delay detection of name changes. Expired lookups are removed and unverified names are denied.

HTTP 429 respects `Retry-After`, capped at 24 hours and defaulting to 60 seconds. Other service/connection failures pause new uncached lookups for two seconds. Valid cached results remain usable. See [Help](Help.md).

Standalone premium session verification retries only temporary service unavailability: at most three attempts with 1 s and 2 s delays, within one 12 s deadline. Invalid sessions are rejected immediately without offline fallback. This automatic behavior does not apply to Velocity and has no separate switch. Waiting retries occupy handshake slots. The username lookup timeout is a separate limit.

## Behavior scoring

Keys below are relative to `anti-bot.behavior-score.`.

| Setting | Default | Meaning |
| --- | --- | --- |
| `threshold` | `40` | Score triggering address quarantine |
| `connection-weight` | `1` | Points per connection attempt |
| `distinct-username-weight` | `5` | Points associated with different names |
| `authentication-failure-weight` | `8` | Points per failed authentication |
| `pre-auth-disconnect-weight` | `8` | Points for leaving before authentication |
| `window-seconds` | `60` | Scoring window |
| `quarantine-seconds` | `600` | Quarantine duration |

Lower thresholds and higher weights block faster. Consider legitimate users sharing an address.

## Names, reconnects and registration

| Setting | Default | Meaning |
| --- | --- | --- |
| `anti-bot.maximum-tracked-addresses` | `50000` | Maximum tracked addresses |
| `anti-bot.username-burst.maximum-distinct-usernames` | `5` | Different names allowed per IP within the window |
| `anti-bot.username-burst.window-seconds` | `30` | Name burst window |
| `anti-bot.username-burst.quarantine-seconds` | `600` | Quarantine after exceeding the limit |
| `anti-bot.reconnect-loop.maximum-pre-auth-disconnects` | `5` | Pre-auth disconnect limit, using the name burst window and quarantine |
| `anti-bot.registration-attempts.maximum-accounts-per-address` | `3` | Persistent offline accounts per IP limit |
| `anti-bot.registration-attempts.capacity` | `3` | Available registration attempts per address |
| `anti-bot.registration-attempts.refill-tokens` | `1` | Attempts replenished per refill interval |
| `anti-bot.registration-attempts.refill-seconds` | `60` | Refill interval |
| `anti-bot.registration-attempts.state-ttl-seconds` | `600` | Inactive attempt-state lifetime |

Each attempt consumes a token; tokens gradually return. Refilling attempts does not remove the persistent account limit. New attempts may be denied when safe address tracking capacity is exhausted.

## Native LOGIN timeouts

Applies to standalone Paper/Purpur/Folia before world entry, separately from password attempts and PRE_AUTH form timeouts.

| Setting | Default | Meaning |
| --- | --- | --- |
| `anti-bot.login-timeouts.attempts` | `3` | Native LOGIN timeouts before an IP block |
| `anti-bot.login-timeouts.window-seconds` | `0` | Counting window; zero retains attempts without expiry until the block expires |
| `anti-bot.login-timeouts.ban-seconds` | `86400` | Block duration: 24 hours |

State persists in the database through restarts. This table belongs to one Paper instance per database. Password failures, PRE_AUTH timeouts and Mojang outages do not increment it. See [Server protection](Server-protection.md).

## Database

| Setting | Default | Meaning |
| --- | --- | --- |
| `storage.type` | `SQLITE` | `SQLITE`, `MYSQL`, `MARIADB` or `POSTGRESQL` |
| `storage.sqlite-file` | `authgatewayx.db` | Database filename in the plugin folder |
| `storage.pool-size` | `2` | Maximum concurrent database connections |
| `storage.remote.host` | `127.0.0.1` | Remote database host |
| `storage.remote.port` | `3306` | Database port; usually `5432` for PostgreSQL |
| `storage.remote.database` | `authgatewayx` | Database name |
| `storage.remote.username` | `authgatewayx` | Database user |
| `storage.remote.password` | empty | Database password |
| `storage.remote.parameters` | empty | Additional connection parameters required by your host |

Remote settings apply outside SQLite. `AUTHGATEWAYX_DB_PASSWORD` takes precedence over YAML. See [Database and backups](Database.md).

## Work limits and Argon2id

| Setting | Default | Meaning |
| --- | --- | --- |
| `executors.storage-threads` | `2` | Concurrent database tasks |
| `executors.storage-queue` | `256` | Queued database tasks |
| `executors.password-threads` | `2` | Concurrent password hashing/verification tasks |
| `executors.password-queue` | `64` | Queued password tasks |
| `executors.mojang-threads` | `4` | Concurrent username lookups |
| `executors.mojang-queue` | `128` | Queued username lookups |
| `authentication.password.argon2.iterations` | `3` | Hashing iterations |
| `authentication.password.argon2.memory-kib` | `65536` | Memory per hash: 64 MiB |
| `authentication.password.argon2.parallelism` | `1` | Hashing parallelism |

Two concurrent password jobs can consume approximately 128 MiB for hashing alone. The server needs additional memory. Increasing a queue does not make processing faster. Keep defaults until you have measurements.

## Integrations

| Setting | Default | Meaning |
| --- | --- | --- |
| `integrations.cleanerx.mode` | `AUTO` | CleanerX integration mode |
| `integrations.cleanerx.failure-strategy` | `FAIL_CLOSED` | Policy if its check cannot complete |
| `integrations.punisherx.mode` | `AUTO` | PunisherX integration mode |
| `integrations.punisherx.failure-strategy` | `FAIL_CLOSED` | Policy if its check cannot complete |

Modes: `AUTO`, `REQUIRED`, `DISABLED`. Failure strategies: `FAIL_CLOSED`, `FAIL_OPEN`. See [Integrations](Integrations.md).

## Staff alerts, VPN and GeoIP

Merge missing sections into the existing configuration without duplicating keys:

```yaml
multi-account:
  alerts:
    enabled: true
    console: true
    cooldown-seconds: 300
    maximum-tracked-accounts: 10000
    maximum-concurrent: 2
ip-intelligence:
  enabled: false
  api-key: ""
  notify-on-vpn: true
  show-geo: true
  timeout-millis: 2000
  cache-ttl-seconds: 3600
  failure-ttl-seconds: 60
  maximum-cache-size: 10000
  maximum-concurrent: 2
  requests-per-minute: 30
```

Enabling IP intelligence sends public IP addresses to proxycheck.io. `AUTHGATEWAYX_PROXYCHECK_API_KEY` overrides YAML. Paper checks run after successful offline authentication and produce informational alerts. The two `enabled` switches are independent; disable both to turn off both kinds of checks.

`show-geo` adds country and ASN to suspicious alerts rather than reporting every connection. `notify-on-vpn` controls separate VPN/proxy/Tor alerts. Multi-account cooldown, concurrency and console settings also cover network alerts.

Successful-result TTL is 3600 s; error TTL and global error backoff are 60 s. Exceeding request or concurrency budgets skips checks without blocking authentication. Limits: state 1–100000, concurrency 1–32, requests/minute 1–1000, timeout 100–10000 ms; cooldowns and TTLs must be positive. Restart clears in-memory cache and budgets, but not the provider's daily quota.

Proxy risk controls are configured separately in [Velocity](Velocity.md). They can deny admission; Paper alerts do not.

## Migration reload and diagnostics

`/agx reload` reloads only `migration.unmanaged-plugin-scan.ignored-plugin-directories`. The list is empty by default; excluding a directory means deliberately leaving its data under the old UUID. See [Premium accounts](Premium.md).

Set `premium.authentication.diagnostics.enabled: true` and restart to enable standalone traces. `maximum-lines-per-minute` defaults to `120`. `[premium-diag]` lines show traces, timings, NMS state, slots and denials without player names, IPs, UUIDs or secrets. Summaries every 30 s include suppressed/dropped counts. `SESSION_RETRY` marks a scheduled retry. See [Help and diagnostics](Help.md).

Do not invent settings such as `anti-flood`, a pre-login command allowlist or `premium.enabled`: adding those keys does not enable functionality. Pre-world authentication is a separate experimental path described in [Compatibility](Compatibility.md).
