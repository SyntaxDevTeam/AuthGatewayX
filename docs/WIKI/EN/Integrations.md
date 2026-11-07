# Integrations

**Language / Język:** English · [Polski](../Integracje.md)

[Home](Home.md) · [Configuration](Configuration.md)

## CleanerX and PunisherX

CleanerX applies additional username rules, such as prohibited words. An explicitly rejected name is denied admission.

PunisherX remains the punishment system. AuthGatewayX checks active punishments exposed for the player's UUID; do not treat this as complete name, IP and network-wide ban coverage.

Neither plugin is required for basic authentication in the default `AUTO` mode.

| `mode` | Behavior |
| --- | --- |
| `AUTO` | Use a compatible service when available; skip the check when absent |
| `REQUIRED` | Treat absence as unavailability and apply the failure strategy |
| `DISABLED` | Do not use the integration |

| `failure-strategy` | On an unavailable or failed check |
| --- | --- |
| `FAIL_CLOSED` | Deny login if the check cannot complete |
| `FAIL_OPEN` | Continue without a result from this check |

`FAIL_OPEN` does not override a confirmed ban, an explicit username rejection or premium verification.

To require PunisherX, edit the existing section and restart:

```yaml
integrations:
  cleanerx:
    mode: AUTO
    failure-strategy: FAIL_CLOSED
  punisherx:
    mode: REQUIRED
    failure-strategy: FAIL_CLOSED
```

Test with both punished and unpunished accounts. `REQUIRED` combined with `FAIL_OPEN` permits continuation when the service is missing or fails.

## UUID migration providers

Plugins storing player data by UUID can register `pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationProvider` through Bukkit/Paper ServicesManager. Context includes the stable account ID, username, old/new UUIDs and migration ID; it never includes a password or IP.

1. `inspect(context)` returns `READY`, `NO_DATA` or `BLOCKED`.
2. `migrate(context)` performs idempotent migration with its own backups.
3. `rollback(context)` restores the previous state.

Providers should declare `managedDataOwners`, for example `setOf("PlotsX")`, so the local scanner recognizes explicitly managed directories. Providers run sequentially; failures roll back completed providers in reverse order. The account UUID changes only after success.

## EssentialsX and LuckPerms

EssentialsX copies `userdata/<old-uuid>.yml`, updates embedded UUIDs and retains the source. Existing destination data is backed up before replacement and restored on rollback. See [Premium accounts](Premium.md).

LuckPerms uses its public API independently of storage type. It copies persistent user nodes: direct permissions, group inheritance, prefixes, suffixes, metadata, contexts, negations and expiry times. Global groups do not need copying. A conflicting nonempty target is not overwritten. Rollback clears the target only while it remains an exact copy; later edits fail closed.

## HorseManagerX

The built-in adapter uses HorseManagerX's migration service for horse ownership, trust relationships, active market offers and log actor entries. HorseManagerX maintains its journal and rollback and invalidates caches. If installed without an available migration bridge, finalization is blocked.

## PunisherX UUID migration

The transactional bridge covers active punishments, punishment history, reports, bridge queue entries, encrypted player cache and jail cache. PunisherX maintains its own journal and backups. An active PunisherX installation without an available bridge blocks identity finalization instead of leaving punishment data behind.

## Authentication status and clients

The public authentication-status API allows nonblocking checks of an active authenticated session. Client channels `authgatewayx:auth` and `craftconnect:auth` report authentication state; they do not grant administrative access or replace server-side checks. See [Integrations](Integrations.md) and [Integrations](Integrations.md).
