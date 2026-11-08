# Help and FAQ

**Language / Język:** English · [Polski](../Pomoc.md)

[Home](Home.md) · [Player guide](Player-guide.md) · [Configuration](Configuration.md)

## Why do `/login` and `/register` not work?

This version automatically opens a Minecraft form. Enter passwords there; commands are blocked before authentication.

## No login dialog appears

Verified premium accounts do not need a password dialog. For offline accounts, check client/server compatibility, plugin readiness and console errors. First test with a matching vanilla client without screen-changing mods.

## Passwords do not match or credentials are incorrect

Check capitalization and accidental spaces. Registration needs the same password twice; password changes have a separate current-password field. If you forget the password, contact staff instead of repeatedly guessing.

## My account is temporarily locked

The default lockout after failed passwords lasts ten minutes. Wait and try again; staff may configure another duration. Do not attempt to bypass it by changing names or addresses.

## Too many attempts or accounts

Wait before reconnecting. Rapid connections, leaving before login and many names from one shared IP can trigger protection. The default three-offline-accounts-per-IP limit is persistent: waiting does not remove it. Staff should review legitimate shared-network cases.

## Premium session verification failed

Close the game, sign in again to the Microsoft account owning the name in your launcher and restart Minecraft. See [Premium accounts](Premium.md). Never disable premium protection to work around an outage.

## Username status cannot be verified

Check server internet access and Minecraft service availability. This is different from verifying an already classified premium session. Find `[mojang-lookup]` diagnostics:

| Reason | Meaning |
| --- | --- |
| `HTTP_ERROR` | Service error; HTTP 429 means rate limiting |
| `DNS_ERROR` / `TLS_ERROR` / `TRANSPORT_ERROR` | Connection problem reaching Minecraft services |
| `QUEUE_TIMEOUT` | Deadline elapsed while waiting for a lookup worker |
| `HTTP_TIMEOUT` | Deadline elapsed after starting the request |
| `EXECUTOR_OVERLOADED` | Queue full or executor stopped |
| `INVALID_RESPONSE` | Missing or invalid profile identifier |
| `BACKOFF` | Pause following an earlier service error |
| `INTERRUPTED` | Request interrupted, for example during shutdown |

`retry_after_seconds` shows the remaining pause. HTTP 429 honors `Retry-After` as seconds or a date, capped at 24 hours; missing/invalid values use 60 seconds. Other service/transport failures pause for two seconds. Lookups do not retry automatically; another connection after the pause can try again. Valid cached results remain usable.

Each category logs at most once per 30 seconds. `suppressed` reports skipped repetitions. These lines contain no player data and do not require handshake diagnostics. A missing answer still denies admission; service failure is never treated as evidence of an offline account.

Native Paper console messages such as `Failed to verify username` or `invalid session` may remain English even when player messages use another language.

## Authentication capacity reached

Wait briefly. Staff should check for connection floods, database problems or unavailable external services before increasing limits. Larger queues can make overload worse.

## Authentication temporarily unavailable

Startup may still be preparing services. If the message persists, inspect the first AuthGatewayX error, database access, runtime-library downloads and configuration. For proxy-specific `AGX-STARTUP` diagnostics see [Velocity](Velocity.md).

## Password-change command opens nothing

Run it in-game after authenticating an offline account. Check [Permissions](Permissions.md). Console cannot open forms; premium accounts have no offline server password to change.

## Can I reload without restarting?

`/agx reload` reloads only the migration directory exclusion list. Other settings and messages need a full restart. PlugManX lifecycle behavior and its disconnect/maintenance implications are described in [Compatibility](Compatibility.md).

## Bedrock, 2FA or email recovery?

These are not confirmed/available capabilities of this version; see [Compatibility](Compatibility.md).

## How do I report a problem?

Provide plugin, server, client and Java versions, whether Velocity is involved, reproduction steps and the relevant error/log excerpt. Remove database passwords, forwarding/proof secrets, API keys, IPs and private player data. Do not upload the account database or screenshots showing a password.
