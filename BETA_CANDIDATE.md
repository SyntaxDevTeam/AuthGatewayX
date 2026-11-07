# AuthGatewayX 1.0.0-beta.1 — release candidate

This branch records the exact candidate commit for the first pre-world authentication beta.
It is deliberately separate from `main`.

## Included
- Paper Configuration Phase authentication with a bounded gate, dialog login/register and
  permission checks before world join.
- Pre-world premium verification and OFFLINE -> MOJANG migration preparation; migration
  runs only after disconnect when source and target sessions are absent.
- Generation-safe session lifecycle and conditional cleanup on reconnect/quit.
- Java 25; per-version Paper 26.1.2, 26.2, 26.3 artifacts; Velocity 4.x artifact.
- Automatic checksum manifests.

## Beta use

`authentication.prejoin.experimental-enabled: true` must be set explicitly on a
**test server** and requires restart. This feature is disabled by default.

This is a **beta for acceptance testing**, not a production certification.
The Minecraft client and multi-server runtime matrix has not yet been executed;
the test checklist is in [docs/17-beta-release-preparation.md](docs/17-beta-release-preparation.md).
Password inputs in vanilla Minecraft Dialog API are visibly rendered (not masked).

The GitHub workflow publishes prerelease binaries only after all four artifacts
have been successfully assembled. Failure of any build leaves the release unpublished.
