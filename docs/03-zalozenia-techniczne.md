# AuthGatewayX 1.0.0 — założenia techniczne

## 1. Proponowana architektura modułów

```text
AuthGatewayX
|
├── authgatewayx-api
├── authgatewayx-domain
├── authgatewayx-auth
├── authgatewayx-security
├── authgatewayx-storage-api
├── authgatewayx-storage-jdbc
├── authgatewayx-integrations
├── authgatewayx-platform-common
├── authgatewayx-velocity
└── authgatewayx-paper
```

Opcjonalnie później:

```text
authgatewayx-storage-redis
authgatewayx-backend
authgatewayx-web-api
```

## 2. Warstwa domenowa

Przykładowe modele:

```kotlin
enum class IdentityType {
    MOJANG,
    OFFLINE
}

enum class AccountState {
    UNREGISTERED,
    REGISTERED,
    AUTHENTICATED,
    LOCKED
}

enum class ConnectionState {
    CONNECTING,
    PRE_AUTH,
    ACTIVE,
    DISCONNECTED
}
```

Konto powinno posiadać wewnętrzny identyfikator:

```text
account_id
```

niezależny od Minecraft UUID.

## 3. Model danych

Przykład:

```text
accounts
────────────────────────────────
id
username
identity_type
minecraft_uuid
password_hash
created_at
updated_at
last_login_at
last_login_ip
locked_until
premium_verified_at
```

Dodatkowo:

```text
login_attempts
sessions
trusted_sessions
ip_restrictions
security_events
account_aliases
account_migrations
```

## 4. Storage

Wersja 1.0:

- SQLite,
- MySQL,
- MariaDB,
- PostgreSQL.

Warstwa JDBC:

```text
HikariCP
```

Operacje storage nie mogą blokować głównego wątku serwera.

## 5. Hasła

Wymagany algorytm:

```text
Argon2id
```

Hasła nie mogą być przechowywane jako:

- plaintext,
- MD5,
- SHA-1,
- SHA-256 bez KDF,
- SHA-512 bez KDF.

Hasz powinien zawierać indywidualny salt.

Możliwe wsparcie migracji z innych pluginów:

```text
bcrypt
PBKDF2
legacy hash import
```

ale po pierwszym poprawnym logowaniu hasło powinno zostać rehashowane do aktualnego formatu AuthGatewayX.

## 6. Velocity flow

```text
PreLoginEvent
    |
    v
connection security checks
    |
    v
account policy resolution
    |
    +------ MOJANG ------> forceOnlineMode()
    |
    +------ OFFLINE -----> forceOfflineMode()
```

Po zakończeniu procesu:

```text
verified identity
    |
    v
session creation
    |
    v
server selection
```

## 7. Paper/Purpur/Folia flow

Standalone wymaga niższego poziomu integracji niż zwykłe eventy Bukkit/Paper.

Serwer:

```properties
online-mode=false
```

Dla kont premium:

```text
LOGIN_START
    |
    v
AuthGatewayX protocol interceptor
    |
    v
ENCRYPTION_REQUEST
    |
    v
ENCRYPTION_RESPONSE
    |
    v
Mojang session verification
    |
    v
official GameProfile
    |
    v
Paper login pipeline
```

Dla kont offline:

```text
LOGIN_START
    |
    v
offline profile
    |
    v
Paper login
    |
    v
PRE_AUTH isolation
    |
    v
/register or /login
```

## 8. Folia

Folia wymaga zgodności z modelem region-threading.

Operacje dotyczące świata i encji:

- teleport,
- inventory,
- movement correction,
- visibility,
- entity interaction,
- message/state changes powiązane z graczem,

muszą korzystać z poprawnego schedulera Folii/Paper.

Operacje sieciowe, storage i kryptograficzne nie powinny być wykonywane w region thread.

## 9. Session Service

Sesja powinna reprezentować:

```text
connection id
account id
minecraft uuid
identity type
ip
created_at
authenticated_at
expires_at
authentication method
```

Przykładowe API:

```kotlin
interface SessionService {
    suspend fun createPreAuthSession(...)
    suspend fun authenticate(...)
    suspend fun invalidate(...)
    fun getCachedSession(uuid: UUID): Session?
}
```

Implementacja nie musi koniecznie używać Kotlin coroutines — może używać CompletableFuture — ale nie może wykonywać blokujących I/O na threadach gry.

## 10. PremiumAccountService

Odpowiedzialności:

- wykrywanie znanych kont premium,
- cache statusu konta,
- wymuszanie online auth,
- kontrola ochrony nicków,
- przechowywanie czasu ostatniej weryfikacji,
- migracja OFFLINE -> MOJANG.

Nie należy odpytywać Mojang bez potrzeby przy każdym logowaniu.

## 11. Cache

Potencjalne cache:

```text
username -> account policy
username -> premium status
uuid -> session
ip -> rate limit bucket
ip -> temporary block
account id -> current state
ban check -> short TTL
CleanerX nickname verdict -> TTL
```

Cache powinien mieć:

- TTL,
- ograniczenie rozmiaru,
- możliwość invalidacji,
- metryki hit/miss.

Preferowane:

```text
Caffeine
```

dla pamięci lokalnej.

Redis może zostać dodany później dla sieci wieloproxy.

## 12. Async

Asynchronicznie wykonywane:

- zapytania JDBC,
- Mojang HTTP/session lookup,
- haszowanie Argon2id,
- PunisherX network/storage lookup, jeśli API na to pozwala,
- CleanerX zewnętrzne źródła, jeśli występują,
- zapisy audit log,
- statystyki bezpieczeństwa.

Nie należy bezrefleksyjnie robić „wszystkiego async”.

Operacje wymagające API świata/encjom muszą wrócić na właściwy scheduler platformy.

## 13. API publiczne

Przykład:

```kotlin
interface AuthGatewayApi {
    fun isAuthenticated(uuid: UUID): Boolean
    fun getIdentityType(uuid: UUID): IdentityType?
    fun getAccount(uuid: UUID): AuthAccount?
    fun getSession(uuid: UUID): AuthSession?
}
```

Dodatkowe operacje async:

```kotlin
fun findAccount(uuid: UUID): CompletableFuture<AuthAccount?>
fun invalidateSession(uuid: UUID): CompletableFuture<Void>
```

## 14. Eventy

Przykładowe eventy:

```text
PreAuthenticationEvent
AuthenticationSuccessEvent
AuthenticationFailedEvent
PlayerRegisteredEvent
PlayerLoggedOutEvent
PremiumIdentityVerifiedEvent
AccountIdentityChangedEvent
SecurityPolicyDeniedEvent
BotConnectionDeniedEvent
FloodConnectionDeniedEvent
```

## 15. Integracje

Interfejsy integracyjne:

```kotlin
interface UsernamePolicyProvider
interface PunishmentProvider
interface SecurityDecisionProvider
```

CleanerX może implementować:

```text
UsernamePolicyProvider
```

PunisherX:

```text
PunishmentProvider
```

Dzięki temu core AuthGatewayX nie zależy bezpośrednio od implementacji innych pluginów.

## 16. Logowanie i audyt

AuthGatewayX powinien logować:

- rejestrację,
- udane logowanie,
- nieudane logowanie,
- zmianę typu konta,
- premium verification,
- odrzucone połączenia,
- anti-bot,
- anti-flood,
- blokadę konta,
- integrację PunisherX,
- odrzucenie przez CleanerX.

Logi nie mogą zawierać:

- haseł,
- hashy haseł,
- pełnych sekretów,
- tokenów,
- poufnych danych sesji.

## 17. Observability

Przydatne liczniki:

```text
active_pre_auth_sessions
active_authenticated_sessions
login_success_total
login_failure_total
premium_auth_success_total
premium_auth_failure_total
connection_denied_total
anti_bot_denied_total
anti_flood_denied_total
cache_hit_ratio
db_latency
mojang_latency
argon2_latency
```

Mogą później zasilać SentinelX lub zewnętrzne systemy telemetryczne.

## 18. Obowiązkowy standard Paper lifecycle

Moduł `authgatewayx-paper` ma być natywnym Paper pluginem wykorzystującym:

```text
paper-plugin.yml
PluginLoader
PluginBootstrap
Lifecycle API
```

`PluginLoader` odpowiada za runtime classpath i biblioteki, w szczególności SyntaxCore oraz MessageHandler-Paper.

`PluginBootstrap` odpowiada za bootstrap-safe lifecycle i rejestrację elementów dostępnych przed `onEnable()`, m.in. natywnych komend Paper przez Lifecycle API.

Przykład:

```yaml
bootstrapper: pl.syntaxdevteam.authgatewayx.paper.bootstrap.AuthGatewayXBootstrap
loader: pl.syntaxdevteam.authgatewayx.paper.loader.AuthGatewayXLoader
folia-supported: true
```

### MessageHandler

Paper/Purpur/Folia:

```kotlin
SyntaxMessages.initialize(plugin)
val messageHandler = SyntaxMessages.messages
```

Velocity:

```kotlin
SyntaxMessages.initialize(pluginContainer, dataDirectory, logger)
```

### SyntaxCore

Paper:

```kotlin
SyntaxCore.init(plugin, versionType = "paper")
```

Velocity:

```kotlin
ProxySyntaxCore.initVelocity(...)
```

### Scheduler semantics

Na Folii nie wolno sprowadzać schedulerów do prostego podziału `sync/async`.

```text
EntityScheduler       -> konkretny gracz/entity
RegionScheduler       -> konkretna lokacja/chunk
GlobalRegionScheduler -> prawdziwy stan globalny
AsyncScheduler        -> I/O i praca poza tickiem
```

JDBC, Mojang HTTP i Argon2id nie mogą wykonywać się na Global Region Scheduler.

Pełny opis znajduje się w `06-standard-wdrozeniowy-syntaxdevteam-paper-folia.md`.

## 19. Stan implementacji — fundament domenowy

Pierwszy etap implementacji wydziela moduł `authgatewayx-domain`, który nie zależy od
API Paper, Bukkit ani Velocity. Zawiera on:

- `AccountId`, `AccountUsername`, `AuthAccount`, `IdentityType` i `AccountState`,
- `ConnectionId`, `AuthSession`, `ConnectionState` i `AuthenticationMethod`,
- walidację formatu nazwy przed uruchomieniem kosztownych elementów pipeline'u,
- jawne i testowane przejścia `CONNECTING -> PRE_AUTH -> ACTIVE -> DISCONNECTED`,
- bezpośrednie `CONNECTING -> ACTIVE` wyłącznie jako ścieżkę zweryfikowanej tożsamości,
- inwariant zabraniający aktywacji tożsamości `MOJANG` metodą hasłową,
- idempotentne przejście do `DISCONNECTED`.

Ten etap nie implementuje jeszcze session cache, storage ani współbieżnej koordynacji
logowań. Odpowiada wyłącznie za niezmienne modele i reguły domenowe, na których będą
budowane serwisy auth oraz adaptery Paper/Velocity.
