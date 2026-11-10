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

`FAIL_OPEN` does not override a confirmed ban, an explicit username rejection or Minecraft session verification.

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

## Account UUID migration

Before upgrading an account, back up player data and check `/agx migrate inspect <name>`. Plugins storing data under a player's UUID need a compatible migration integration. If a required integration is missing, resolve the reported block before retrying; do not manually replace UUIDs. See [Account identity verification](Premium.md).

## EssentialsX and LuckPerms

EssentialsX copies `userdata/<old-uuid>.yml`, updates embedded UUIDs and retains the source. Existing destination data is backed up before replacement and restored on rollback. See [Account identity verification](Premium.md).

LuckPerms uses its public API independently of storage type. It copies persistent user nodes: direct permissions, group inheritance, prefixes, suffixes, metadata, contexts, negations and expiry times. Global groups do not need copying. A conflicting nonempty target is not overwritten. Rollback clears the target only while it remains an exact copy; later edits fail closed.

## HorseManagerX

The built-in adapter uses HorseManagerX's migration service for horse ownership, trust relationships, active market offers and log actor entries. HorseManagerX maintains its journal and rollback and invalidates caches. If installed without an available migration bridge, finalization is blocked.

## PunisherX UUID migration

The transactional bridge covers active punishments, punishment history, reports, bridge queue entries, encrypted player cache and jail cache. PunisherX maintains its own journal and backups. An active PunisherX installation without an available bridge blocks identity finalization instead of leaving punishment data behind.

## Client integration

Compatible clients can receive confirmation that the player is signed in. No separate authentication-status bridge is required. This confirmation does not grant administrative permissions.
