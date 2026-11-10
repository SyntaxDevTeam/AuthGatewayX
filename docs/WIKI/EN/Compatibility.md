# Compatibility and requirements

**Language / Język:** English · [Polski](../Zgodnosc.md)

[Home](Home.md) · [Installation](Installation.md)

## Choose your server distribution

AuthGatewayX requires **Java 25**. Choose the JAR for your server platform and Minecraft version.

| Platform | Installation guidance |
| --- | --- |
| Paper / Purpur | Choose the matching Minecraft variant: 26.1.2, 26.2 or 26.3; do not assume that one JAR works across all versions |
| Folia | Use only a distribution explicitly marked for your Folia version |
| Velocity | Install the separate proxy JAR and the Paper module on the backend; follow the [network guide](Velocity.md) |
| Spigot / Bukkit | No separate supported distribution |
| Bedrock through Geyser/Floodgate | No confirmed support |

Players need clients that can display authentication dialogs. Allowing an older client to connect through another plugin does not guarantee that login forms will work.

## Authentication before world entry

By default, the player is isolated from gameplay while waiting to sign in. The optional `authentication.prejoin.experimental-enabled` mode moves authentication before world entry. It is off by default; keep it disabled on a public server. Accounts needing account UUID migration must confirm the old server password and disconnect while data is moved.

## PlugManX and restarts

Use PlugManX only if it supports native Paper plugins and your exact server build. Older versions without native Paper support are incompatible. A full process restart is the preferred way to apply configuration changes.

`/plugman restart AuthGatewayX` and `/plugman reload AuthGatewayX` disconnect all players. Connections resume after the console reports READY; everyone signs in again. After disable/unload, connections and status pings remain blocked until enable/load or a process restart. Do not manually remove the connection protection.

If cleanup fails or times out, restart the server process before trying again.

## Unavailable features

A web panel, 2FA, Discord account linking, email recovery and multi-proxy session synchronization are not available.

## Version messages at startup

At startup, the plugin reports whether the Minecraft number matches its target list (26.1, 26.1.1, 26.1.2, 26.2, 26.3), or warns about an unlisted version. Choose the JAR matching your server version; the version message alone does not guarantee that every feature will work. An unrecognized version or missing required modern login events stops initialization with the connection gate closed.
