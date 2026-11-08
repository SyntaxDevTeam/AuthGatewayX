# Premium accounts

**Language / Język:** English · [Polski](../Premium.md)

[Home](Home.md) · [Player guide](Player-guide.md) · [Help](Help.md)

## Sign in and play

AuthGatewayX checks whether a username belongs to a premium account, then requires Minecraft to verify ownership of that account's session. Successful premium authentication needs no extra server password. Sign in to Microsoft in your launcher; never enter your Microsoft password in a server form.

Offline users cannot access a protected premium name by choosing it in a launcher. Failed verification means denial, never an uncontrolled switch to offline authentication. Offline players using a name owned by a premium account must choose a different, unclaimed name.

If session verification fails, close Minecraft, sign in again to the correct account in your launcher and restart the game. If many players are affected, staff should check connectivity to Minecraft services. Outages can also prevent classification of a new offline username.

## Keep your name when buying Minecraft

If an offline account already exists under your name, the first verified premium connection does not immediately overwrite its UUID. A separate migration dialog asks for the **old AuthGatewayX server password**, proving access to both the verified premium identity and the previous offline account.

After confirmation, a persistent migration ticket is created. Until dependent data has been migrated, the AuthGatewayX record retains its OFFLINE type, old UUID and password hash. Finalization changes it to `MOJANG` only after controlled migration providers succeed. Players disconnect before data is copied.

Never delete player files or manually edit account UUIDs without backups. Contact staff when migration is unfinished.

## What is migrated?

The plugin transfers these vanilla player files:

- `playerdata/<uuid>.dat` and `.dat_old`;
- `stats/<uuid>.json`;
- `advancements/<uuid>.json`.

Backups are stored in `plugins/AuthGatewayX/migration-backups/<migration-id>/`. If a transfer step fails, the plugin attempts to restore the previous state.

AuthGatewayX does not globally search and replace UUIDs. It scans local plugin directories without a dedicated migration integration. Old identity data or exhausted scan budgets block finalization rather than silently leaving data behind. File-count and total-byte limits apply; files are streamed, so a large single file alone is not a reason for rejection. `/agx migrate inspect` reports the path and consumed budget.

## Local recipes and integrations

Built-in and custom recipes handle unambiguous local UUID-indexed files. A conservative fallback can copy a file named exactly `<old-uuid>.yml/.yaml/.json/.toml/.properties` when its content does not also contain the old UUID. The source is retained, the destination is backed up and rollback is supported.

AdvancedPortals has a recipe for `playerData/<uuid>.yaml`. Put custom recipes in `plugins/AuthGatewayX/migration-recipes/*.yml`. A basic recipe example is included below.

EssentialsX has a dedicated `userdata/<uuid>.yml` migrator. If different destination data already exists, it is durably backed up before replacement and can be restored on rollback. Its existence alone does not block recovery. Other providers may reject conflicting destination data. LuckPerms, HorseManagerX and PunisherX adapters are described in [Integrations](Integrations.md).

Local scans cannot inspect another plugin's remote MySQL/PostgreSQL database. Such plugins need a dedicated migration integration.

## Deliberately exclude a plugin's data

Add the exact directory name to `migration.unmanaged-plugin-scan.ignored-plugin-directories` only if you deliberately choose not to migrate that data. Names are matched case-insensitively. AuthGatewayX checks for old UUID evidence but does not modify those directories; `migrate inspect` reports the exclusion. Data remains accessible only under its old UUID. The default list is empty.

After editing this list, run `/agx reload`. This one setting does not require a full restart.

## Staff recovery tools

Use `migrate status` and `inspect` to diagnose, `retry` to resume an existing ticket and `recover` for an identity already switched by an older version. An optional old UUID handles historical username capitalization differences. Recovery requires actual evidence of old data, not merely a name. Targets must be offline for retry and recovery. Unfinished recovery blocks premium login until safe completion or successful rollback.

See [Commands](Commands.md), [Permissions](Permissions.md) and [Premium account migration](Premium.md).

## Custom local recipe example

Create `plugins/AuthGatewayX/migration-recipes/my-plugin.yml` only after confirming that the file format uses UUID solely in its filename:

```yaml
id: my-plugin-users
plugin-directory: MyPlugin
rules:
  - type: UUID_FILE
    source: "users/{source_uuid}.yml"
    target: "users/{target_uuid}.yml"
    rewrite-uuid-in-content: false
    require-source-uuid-absent: true
```

Paths must remain relative to the named plugin directory. Absolute paths, `..`, backslashes and symlinks are rejected. Available placeholders are `{source_uuid}`, `{target_uuid}`, `{source_uuid_compact}` and `{target_uuid_compact}`. Keep backups and test migration plus rollback on a copy. Shared files, embedded UUIDs or remote storage require a provider that understands the data format.
