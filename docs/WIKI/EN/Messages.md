# Messages and appearance

**Language / Język:** English · [Polski](../Wiadomosci.md)

[Home](Home.md) · [Configuration](Configuration.md)

Paper messages live in `plugins/AuthGatewayX/lang/messages_pl.yml`. Select their language with `language: "PL"` in `config.yml`. The distribution includes Polish messages. This English WIKI does not install an English runtime translation; selecting another language requires a matching language file.

## What can you customize?

| Section | Contents |
| --- | --- |
| `prefix` | AuthGatewayX message prefix |
| `auth.login_title`, `auth.registration_title` | Form titles |
| `auth.login_prompt`, `auth.registration_prompt` | Form instructions |
| `auth.password_label`, `auth.repeat_password_label` | Password field labels |
| `auth.submit_label`, `auth.cancel_label` | Buttons |
| Other `auth` entries | Authentication outcomes, restrictions and errors |
| `password` | Password changes and administrative resets |
| `risk_alert` | Staff alerts and permission-filtered identity/network data |
| `privacy` | Policy summary, full-policy link and decision buttons |

## Example text

Edit these entries in the existing `auth` section. This is a fragment, not a replacement for the whole language file.

```yaml
auth:
  login_title: "<aqua>Welcome, <white><username></white>!</aqua>"
  login_prompt: "<gray>Enter your password to join the game.</gray>"
  registration_prompt: "<gray>Choose a unique password and repeat it below.</gray>"
  offline_authentication_success: "<green>You are signed in. Enjoy the game!</green>"
```

MessageHandler handles MiniMessage formatting. `<green>Text</green>`, `<red>`, `<aqua>` and `<gray>` set colors; `<b>` makes text bold and `<newline>` inserts a line break. Keep input labels simple. `<username>` is supported in login and registration titles; do not assume it works in arbitrary messages or invent placeholders.

## Privacy policy

Customize `privacy.summary` to describe your server. Replace `https://example.com/privacy/` in `privacy.link` with your actual HTTPS policy address, retaining the `<click:open_url:'https://your-server.example/privacy/'>…</click>` structure. A nonempty `privacy.policy-url` overrides the message's link; an empty setting preserves it.

Chat decision buttons use `<click:run_command:'/privacy accept'>…</click>` and `<click:run_command:'/privacy reject'>…</click>`. You may change text and colors; native dialogs use their own decision buttons. Increment `privacy.policy-version` when changing the policy. Revocation ends the session without automatically deleting account data.

## Apply and verify

Back up the file, edit it while the server is stopped, and restart. Preserve keys and placeholders. Test registration, login, an incorrect password, password changes and privacy choices. Keep error messages accurate, especially Minecraft identity verification failures.

Velocity has its own `lang/messages_pl.yml`; Paper edits do not automatically change proxy text. Proxy risk alerts are private staff messages with a heading, status, fields and next action. Preserve the distinction between denied connections and attempts awaiting authentication.

IP, GeoIP and security details require their corresponding staff permissions. Do not turn staff alerts into public broadcasts of player data.
