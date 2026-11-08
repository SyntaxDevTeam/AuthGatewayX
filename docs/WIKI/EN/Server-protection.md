# Server protection

**Language / Język:** English · [Polski](../Ochrona-serwera.md)

[Home](Home.md) · [Configuration](Configuration.md) · [Integrations](Integrations.md)

## Authentication before gameplay

Before authentication, players cannot move normally, break/place blocks, use items, fight, chat, run commands or alter inventory. They are separated from authenticated players and protected from damage. Protection also covers buckets, books, vehicles, portals and other less obvious interactions. Test your own plugin combination on a private server.

Offline passwords are stored as Argon2id hashes, not readable text. By default, five incorrect attempts lock the account for ten minutes; additional rate limits may reject attempts sooner.

## Bots and reconnect abuse

Connection guards apply before expensive database or external work. AuthGatewayX observes connections, rapidly changing names, failed authentication and leaving before login. Suspicious behavior can quarantine an address.

By default, more than five distinct usernames from one IP within 30 seconds trigger ten minutes of quarantine. Behavior scoring adds another signal. Timeouts and pending-player limits prevent incomplete authentication from occupying unlimited capacity.

The default registration limit is three offline accounts per IP. This is a persistent account limit; waiting or restarting does not reset it. Families, schools and carrier NAT may share an IP, so tune limits for your community one at a time.

## Recorded security information

Audit records include registrations, logins, denials, password changes and logout. They may contain names, account IDs and IPs, but not plaintext passwords. Restrict database and backup access. Application-level guards do not replace hosting protection against attacks saturating the network connection.

## Related-account reports

`/authgatewayx alts <name>` compares account address history, rather than trusting failed passwords or an unauthenticated attempt under someone else's name. The report does not automatically punish users or reveal raw addresses.

History keeps up to 16 distinct addresses per account; reports compare observations from the last 30 days and return at most 20 accounts. Expired observations are ignored and physically cleaned on a later write. History survives restarts and premium migration. These limits are independent of registration limits.

A shared address may mean one person, a household, carrier NAT or a VPN exit. A new name and completely new address with no shared history may go undetected. No matches do not prove that no alternate account exists.

## Automatic alerts and optional VPN/GeoIP

`multi-account.alerts.enabled: true` enables alerts following successful offline authentication. Recipients must be authenticated and have `authgatewayx.admin.alerts`. Console delivery is controlled separately. Default per-account cooldown is 300 s; alerts show up to five related names, with the fuller report available through `alts`.

`ip-intelligence.enabled: true` enables optional proxycheck.io v3 checks. It is off by default. Public player IPs are sent over HTTPS; names, UUIDs and passwords are not. `AUTHGATEWAYX_PROXYCHECK_API_KEY` overrides the YAML API key. An empty key uses the provider's anonymous quota.

`notify-on-vpn` permits VPN/proxy/Tor alerts without a related account. `show-geo` adds country and ASN to suspicious alerts; country, ASN or hosting alone does not trigger one. Network data describes an exit point, not a person's physical location. `?` means unavailable data; provider failure does not mean no VPN.

Checks have concurrency, cooldown, cache and request budgets. Overload may skip checks. Paper API timeouts do not block login or the local account report, and these alerts do not impose bans or country blocks. See [Configuration](Configuration.md).

## Proxy controls

Velocity separately supports shared-history and VPN/proxy/Tor checks before the backend, with `ALERT`, `DENY` or `DISABLED` policies where applicable. Both integrations are disabled by default. Proxy alerts concern connection attempts, not proof that the password was accepted. Offline staff require backend staff proof. See [Velocity](Velocity.md).

## Repeated “Took too long to log in”

Standalone Paper/Purpur/Folia tracks native LOGIN timeouts per IP. Defaults are `attempts: 3`, `window-seconds: 0` (attempts do not expire) and `ban-seconds: 86400` (24 hours). A positive window enables timed counting. Clients see remaining attempts; the third timeout produces a local “Suspicious connections.” block affecting everyone sharing that IP.

Counters and deadlines persist in `connection_timeout_state` in the configured database. Restarts preserve them; database backups include them. Read/write errors fail closed. The block belongs to AuthGatewayX, not PunisherX or Minecraft's ban list. One Paper instance owns this table per database; do not share it between concurrently running Paper servers.

Incorrect passwords, PRE_AUTH timeouts and Mojang outages do not increment this counter. Velocity timeouts are not counted. Configuration changes require restart.
