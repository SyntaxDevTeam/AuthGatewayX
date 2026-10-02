# CraftConnect API agent instructions

Before modifying files in this package, read repository-root:

```text
CRAFTCONNECT_AGENTS.md
docs/CraftConnect-integration.md
AGENTS.md
```

This package is the platform-independent public contract for CraftConnect Enhanced Mode.

Do not introduce Paper, Velocity, NMS, Bukkit, logger implementation, database implementation or transport framing classes here.

Keep authorization server-side and permission-driven. Device pairing proves a trusted device relationship; it does not grant administrative capabilities by itself.

`CONSOLE_VIEW` and `CONSOLE_EXECUTE` must remain separate capabilities.

Any protocol-breaking change requires an explicit protocol version decision and matching CraftConnect-side documentation/update.
