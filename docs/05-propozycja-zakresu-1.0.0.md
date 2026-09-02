# AuthGatewayX 1.0.0 — proponowany zakres pierwszego publicznego wydania

## Platformy

- [ ] Velocity
- [ ] Paper
- [ ] Purpur
- [ ] Folia

## Uwierzytelnianie

- [ ] Mojang/Microsoft session verification
- [ ] obsługa official UUID
- [ ] obsługa offline UUID
- [ ] `/register`
- [ ] `/login`
- [x] `/logout`
- [x] `/changepassword`
- [ ] Argon2id
- [ ] login timeout
- [ ] account lockout
- [ ] session management

## Premium protection

- [ ] auto-detection
- [ ] known premium cache
- [ ] premium username protection
- [ ] brak premium -> offline fallback
- [ ] migracja OFFLINE -> MOJANG
- [ ] audit migracji

## Ochrona świata

- [ ] movement lock
- [ ] interaction lock
- [ ] inventory lock
- [ ] block protection
- [ ] combat protection
- [ ] chat lock
- [ ] command allowlist
- [ ] plugin messaging policy
- [ ] hide/unhide player
- [ ] pre-auth quarantine

## Security

- [ ] Argon2id
- [ ] per-IP login rate limiting
- [ ] brute-force protection
- [ ] account lockout
- [ ] enumeration-resistant messages
- [ ] concurrent-login protection
- [ ] security audit log

## Anti-bot

- [ ] connection scoring
- [ ] username burst detection
- [ ] reconnect loop detection
- [ ] temporary IP quarantine
- [ ] registration abuse protection

## Anti-flood

- [ ] per-IP token bucket
- [ ] global token bucket
- [ ] max concurrent pre-auth sessions
- [ ] handshake timeout
- [ ] early cheap rejection
- [ ] flood metrics

## Storage

- [ ] SQLite
- [ ] MySQL
- [ ] MariaDB
- [ ] PostgreSQL
- [ ] HikariCP
- [ ] migrations
- [ ] async storage operations

## Cache

- [ ] Caffeine
- [ ] premium status cache
- [ ] account cache
- [ ] session cache
- [ ] punishment cache
- [ ] username-policy cache
- [ ] rate-limit state

## Integracje

### CleanerX

- [x] nickname validation provider
- [x] profanity/pattern deny
- [x] optional integration
- [x] configurable failure strategy

### PunisherX

- [x] UUID ban check
- [ ] name ban check
- [ ] IP ban check
- [ ] network ban support
- [x] async punishment provider
- [x] login denial before session activation

## API

- [ ] `AuthGatewayApi`
- [ ] authentication state
- [ ] identity type
- [ ] account lookup
- [ ] session lookup
- [ ] public events
- [ ] integration provider interfaces

## Observability

- [ ] structured logs
- [ ] security event log
- [ ] counters
- [ ] cache hit ratio
- [ ] DB latency
- [ ] Mojang verification latency
- [ ] anti-bot/anti-flood statistics

## Poza zakresem 1.0.0

Zalecane do późniejszych wersji:

- Redis jako distributed cache/session store,
- multi-proxy synchronization,
- web panel,
- REST API,
- Discord linking,
- email authentication,
- 2FA/TOTP,
- Geyser/Floodgate,
- external anti-bot providers,
- ASN/datacenter reputation,
- account recovery workflow.

## Standard SyntaxDevTeam / Paper lifecycle

- [ ] MessageHandler-Paper
- [ ] MessageHandler-Velocity
- [ ] SyntaxCore Paper init
- [ ] ProxySyntaxCore Velocity init
- [ ] `paper-plugin.yml`
- [ ] `PluginLoader`
- [ ] `paper-libraries.yml`
- [ ] `PluginBootstrap`
- [ ] Paper Lifecycle API
- [x] Brigadier/lifecycle command registration
- [ ] `folia-supported: true`
- [ ] EntityScheduler dla player/entity state
- [ ] RegionScheduler dla location/chunk state
- [ ] GlobalRegionScheduler tylko dla global state
- [ ] AsyncScheduler/bounded executor dla JDBC/HTTP/Argon2
- [ ] backpressure dla kosztownych operacji
- [ ] brak SNAPSHOT runtime dependencies w stabilnym 1.0.0, jeśli dostępne są release
