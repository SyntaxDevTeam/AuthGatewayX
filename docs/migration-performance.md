# Migration performance isolation

AuthGatewayX treats identity migration as background maintenance, not tick-thread work.

## Execution model

- Filesystem inspection, UUID-content scanning, file copying and rollback work run on the dedicated bounded `authgatewayx-migration` executor.
- The executor is intentionally single-threaded by default so multiple migrations cannot create parallel disk scans.
- Bukkit/Paper state checks and UI delivery stay on the appropriate server/entity scheduler; they must remain short and must not perform migration file I/O.
- JDBC operations keep their separate bounded storage executor.

## Unmanaged plugin scan

The universal local-data provider is the most I/O-intensive migration stage because it may search many plugin files for the legacy UUID. Merely moving this scan to another thread is insufficient: an unrestricted background scan can still saturate the same disk used by world saves and plugin databases.

The scan therefore:

- reads files in 64 KiB chunks;
- applies an 8 MiB/s background I/O budget by default;
- keeps the existing total-byte and file-count fail-closed bounds;
- reuses the plan produced by the mandatory pre-migration inspection for the immediately following migration step, avoiding a second full content scan in the same pipeline;
- keeps a bounded, short-lived plan cache and removes entries after migration/rollback;
- continues to preserve target backups and rollback semantics.

The trade-off is deliberate: `/agx migrate inspect`, recovery and migration may take longer on installations with large unmanaged plugin datasets, but they should not monopolize storage bandwidth needed by the live server.

## Operational guidance

Keep `executors.migration-threads` at `1` unless the storage subsystem has been benchmarked under concurrent migration and world-save load. Increasing migration worker count improves throughput at the cost of disk contention and is normally counterproductive on a live Minecraft server.
