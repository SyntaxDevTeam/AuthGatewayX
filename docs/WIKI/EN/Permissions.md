# Permissions

**Language / Język:** English · [Polski](../Uprawnienia.md)

[Home](Home.md) · [Commands](Commands.md)

Ordinary registration and login dialogs need no extra permission. Assign exact permission names through your permissions plugin.

| Permission | Access | Default |
| --- | --- | --- |
| `authgatewayx.command.changepassword` | Change your own server password | Everyone |
| `authgatewayx.auth.skip-password` | Skip the server password after verified Minecraft session authentication | Disabled by default |
| `authgatewayx.command.logout` | Sign out | Everyone |
| `authgatewayx.command.privacy` | View the policy and manage acceptance | Everyone |
| `authgatewayx.admin.info` | Basic account report | Operators |
| `authgatewayx.admin.alerts` | Receive automatic alerts after authentication | Operators |
| `authgatewayx.admin.view-ip` | View IP addresses in reports and alerts | Operators |
| `authgatewayx.admin.view-geo` | View GeoIP, ASN, network provider and anonymizing operator | Operators |
| `authgatewayx.admin.view-security` | View lockouts, failed attempts and recent audit events | Operators |
| `authgatewayx.admin.alts` | Report related accounts and add the related-account section | Operators |
| `authgatewayx.admin.password` | Reset another server account's password | Operators |
| `authgatewayx.admin.reload` | Reload supported runtime settings | Operators |
| `authgatewayx.admin.migration.view` | Inspect migration tickets and providers | Operators |
| `authgatewayx.admin.migration.execute` | Retry an existing migration | Operators |
| `authgatewayx.admin.migration.recover` | Create recovery for previously upgraded accounts | Operators |

## Split staff access by responsibility

Players only need the default player permissions. Restrict password resets and migration recovery to staff responsible for account ownership and data. Moderators receiving alerts do not automatically need IP access.

`info` opens the basic report; `view-ip`, `view-geo`, `view-security` and `alts` independently add sensitive sections. Audit IPs require IP permission as well. The server console is trusted and receives all sections. Check inherited permissions and operator status when creating a restricted role.

Permissions never bypass Minecraft session verification or world protection. Password changes and logout apply to authenticated accounts. The optional `authgatewayx.auth.skip-password` permission affects only the additional password prompt for players whose official Minecraft identity has already been verified; it does not skip identity verification, admission checks or privacy requirements.

## Velocity

Assign `authgatewayx.admin.alerts` and `authgatewayx.admin.alts` on the proxy for alerts and related-account reports. The connected-session report uses `authgatewayx.admin.session`. Geo details require `authgatewayx.admin.view-geo`; IP visibility requires `authgatewayx.admin.view-ip`.

Administrators with a locally assigned identity need successful backend login and matching, separate `staff-proof.secret` values on Paper and Velocity. Console and Mojang-authenticated administrators do not require offline staff proof. See [Velocity setup](Velocity.md).

## Migration access

`migration.view` allows diagnosis without changing data. `migration.execute` retries a ticket; `migration.recover` creates recovery. The console may run all migration subcommands. In-game administrators still require an authenticated session.
