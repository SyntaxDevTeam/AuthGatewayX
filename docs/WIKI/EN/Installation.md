# Download and installation

**Language / Język:** English · [Polski](../Instalacja.md)

[Home](Home.md) · [Configuration](Configuration.md) · [Help](Help.md)

## Before you start

Prepare a private Minecraft Java Edition test server, Java 25 and access to server files and the console. The default build targets Paper 26.3. Choose other Minecraft variants according to their artifact descriptions; see [Compatibility](Compatibility.md) for Purpur, Folia and Velocity.

Internet access is needed to download runtime libraries and verify premium accounts. Use a client matching the server version for your first test: authentication forms require Minecraft dialog support.

## Download the right file

Open [AuthGatewayX GitHub Actions](https://github.com/SyntaxDevTeam/AuthGatewayX/actions), select a successful packaging run and download its **Artifacts**. GitHub artifact downloads require signing in. Extract the archive to obtain the JAR; artifacts expire according to their retention period.

The **Package snapshot distributions** workflow provides Minecraft-specific Paper packages and a separate Velocity package with SHA-256 checksums. Choose the file matching your platform and server version.

Install the Paper JAR on the game server and the Velocity JAR on the proxy. Never install several Paper variants together. Do not install `sources`, `javadoc` or `plain` JARs. File names may include a build number or Minecraft version. For example, the basic Paper distribution is `AuthGatewayX-Paper-1.0.0-R0.1-SNAPSHOT.jar`.

## Build from source

Clone the repository and run with Java 25:

```bash
./gradlew clean buildAll
```

Distributions are written to `authgatewayx-paper/build/libs` and `authgatewayx-velocity/build/libs`. For version-specific packaging, see [Download and installation](Installation.md). After installation, check player login and console messages.

## Install on a standalone server

1. Stop the server and back up its files.
2. Put the matching Paper JAR in `plugins`.
3. Set `online-mode=false` in `server.properties` for AuthGatewayX mixed authentication.
4. Leave Velocity support disabled in `config/paper-global.yml` on standalone servers.
5. Start the test server and let the plugin create its configuration and account database.
6. Check the console for readiness and resolve startup errors before opening access.
7. Stop the server, edit `plugins/AuthGatewayX/config.yml` and start it again.
8. Test offline registration, repeat login and your own premium account.

Do not expose an `online-mode=false` server while authentication protection is unavailable. Keep access restricted during installation and repairs. Do not run two authentication plugins together without verified compatibility; moving existing accounts requires a separate [migration plan](Database.md).

## First-launch checklist

- [ ] A new offline account receives a registration form with two password fields.
- [ ] An existing offline account receives a login form.
- [ ] Movement, containers and commands are blocked before authentication.
- [ ] Correct passwords grant access; incorrect passwords do not.
- [ ] Your own premium account passes verification.
- [ ] `/changepassword` and `/logout` work for authenticated offline accounts.
- [ ] Accounts survive a restart and can log in again.

For a proxy network, follow [Velocity](Velocity.md).
