# Compatibility and requirements

**Language / Język:** English · [Polski](../Zgodnosc.md)

[Home](Home.md) · [Installation](Installation.md)

This reference describes the current `1.0.0-R0.1-SNAPSHOT` implementation and separates build targets from completed runtime validation.

| Platform | Current scope |
| --- | --- |
| Paper | Minimum declared API 26.1; source builds for 26.1.2, 26.2 and 26.3. Runtime tests are required for each NMS variant |
| Purpur | Uses the matching Paper artifact; test the exact server version |
| Folia | Scheduler compatibility is being developed; separate runtime acceptance is required |
| Velocity | Separate proxy artifact; complete network acceptance remains open |
| Spigot / Bukkit | No separate supported distribution |
| Bedrock through Geyser/Floodgate | No confirmed support |

Paper requires Java 25. Dialog support cannot be assumed merely because another plugin lets an older client connect. Future Paper updates also need verification.

## Version-specific artifacts

The default Paper build targets 26.3 and declares `api-version: '26.1'`. Source compatibility has been checked against 26.1.2 and 26.2, and three Minecraft-specific Paper distributions are prepared. NMS use means a successful compilation is not proof of binary/runtime compatibility.

Velocity targets API 4.3.0, with compatibility work for 4.0+. `getSessionId()` is available from 4.2.1; older 4.x uses repeated Mojang lookup and risk checks at login, without matching an earlier decision by IP, port or username. Real version-specific network tests remain necessary.

## Pre-world authentication

The default PRE_AUTH path isolates players after `PlayerJoinEvent`. The separate experimental Configuration Phase path aims to authenticate before an entity exists. It is disabled by default (`authentication.prejoin.experimental-enabled: false`) and needs actual client acceptance testing before production use. Accounts needing offline-to-premium migration receive a dialog for the old server password and then disconnect for migration.

See the [Compatibility and requirements](Compatibility.md) and [Download and installation](Installation.md).

## Remaining acceptance scope

Release requirements include Folia, complete Velocity networks, high connection loads, remote database behavior, identity migration and conflict scenarios. Configuration availability alone does not certify every deployment.

A web panel, 2FA, Discord account linking, email recovery and multi-proxy synchronization are not available features. Bedrock support is not confirmed.

## PlugManX

Paper supports repeated enable/disable and load/unload with a PlugManX version that understands native `paper-plugin.yml`, Brigadier commands and the exact server build. Older versions lacking native Paper support are incompatible.

`/plugman restart AuthGatewayX` and `/plugman reload AuthGatewayX` disconnect all players. Admission resumes after READY and everyone authenticates again. After disable/unload, connections and status pings remain rejected until enable/load or a process restart. Do not manually remove the Netty guard.

Automated lifecycle checks do not replace actual PlugManX/Paper/Purpur/Folia tests. Cleanup failure or timeout blocks another enable; restart the process. See [Compatibility and requirements](Compatibility.md).
