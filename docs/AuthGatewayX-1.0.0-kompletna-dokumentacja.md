# AuthGatewayX 1.0.0 — kompletna dokumentacja koncepcyjna

Dokument zbiorczy obejmujący koncepcję ogólną, założenia praktyczne, techniczne,
bezpieczeństwo, wydajność, integracje, standard wdrożeniowy SyntaxDevTeam oraz zakres 1.0.0.

---

# AuthGatewayX 1.0.0 — ogólna koncepcja

## 1. Czym ma być AuthGatewayX

AuthGatewayX 1.0.0 ma być publicznym pluginem uwierzytelniającym dla serwerów Minecraft, który umożliwia jednoczesną obsługę:

- graczy premium/online — uwierzytelnionych przez oficjalny system Microsoft/Mojang,
- graczy non-premium/offline — uwierzytelnianych przez AuthGatewayX przy pomocy `/register` i `/login`.

Plugin nie powinien być tylko „AuthMe z dodatkiem premium”, lecz pełną warstwą zarządzania tożsamością gracza na etapie wejścia do serwera.

## 2. Główna zasada

Docelowy przepływ:

```text
                         POŁĄCZENIE GRACZA
                                |
                                v
                         AuthGatewayX
                                |
                 +--------------+--------------+
                 |                             |
                 v                             v
           KONTO PREMIUM                 KONTO OFFLINE
                 |                             |
                 v                             v
      oficjalne uwierzytelnienie         /register lub /login
      Microsoft/Mojang                         |
                 |                             |
                 +--------------+--------------+
                                |
                                v
                       GRACZ UWIERZYTELNIONY
                                |
                                v
                           ŚWIAT SERWERA
```

Gracz premium:

- nie wpisuje hasła,
- musi przejść prawdziwe uwierzytelnienie online,
- otrzymuje prawidłowy oficjalny UUID,
- powinien otrzymać prawidłowy profil i skórkę.

Gracz non-premium:

- otrzymuje tożsamość offline,
- musi zarejestrować konto lub się zalogować,
- do czasu zakończenia uwierzytelniania nie może normalnie korzystać z serwera.

## 3. Velocity

Velocity posiada natywne narzędzia do wyboru trybu uwierzytelniania per połączenie.

AuthGatewayX może w zależności od polityki konta wybrać:

```text
forceOnlineMode()
```

lub:

```text
forceOfflineMode()
```

Dzięki temu implementacja Velocity może korzystać z natywnej warstwy proxy i nie musi samodzielnie rekonstruować pełnego protokołu uwierzytelniania.

## 4. Paper / Purpur / Folia

Standalone Paper/Purpur/Folia powinien pracować w:

```properties
online-mode=false
```

AuthGatewayX przejmuje kontrolę nad etapem LOGIN i dla kont premium wykonuje własną wersję procesu online-mode:

```text
LOGIN_START
    |
    v
ENCRYPTION_REQUEST
    |
    v
ENCRYPTION_RESPONSE
    |
    v
Mojang Session Server verification
    |
    v
authenticated GameProfile
    |
    v
Paper login pipeline
```

Dla kont non-premium plugin przepuszcza gracza jako offline i nakłada własną warstwę uwierzytelniania.

## 5. Rozdzielenie tożsamości od sesji

AuthGatewayX powinien rozróżniać:

### Tożsamość

```text
MOJANG
OFFLINE
```

### Stan konta

```text
UNREGISTERED
REGISTERED
AUTHENTICATING
AUTHENTICATED
LOCKED
```

### Stan połączenia

```text
CONNECTING
PRE_AUTH
ACTIVE
DISCONNECTED
```

Nie należy traktować „premium” i „non-premium” jako jedynego stanu gracza.

## 6. Ochrona nazw premium

Konto premium musi mieć pierwszeństwo przed graczem non-premium używającym tego samego nicku.

Przykład:

```text
Mojang:
username = ExampleUser
uuid = official-uuid
```

Jeżeli gracz non-premium spróbuje wejść jako `ExampleUser`, AuthGatewayX może — zależnie od konfiguracji — wymusić online authentication lub odrzucić połączenie.

Nigdy nie należy stosować bezpiecznościowo niepoprawnego mechanizmu:

```text
premium authentication failed
        |
        v
fallback to offline
```

dla nicku, który jest chroniony jako premium.

## 7. Tryby polityki premium

Przykładowa konfiguracja:

```yaml
premium:
  mode: AUTO

  protect-premium-usernames: true

  authentication-failure:
    action: DENY
```

Możliwe tryby:

```text
DISABLED
AUTO
FORCE_FOR_KNOWN
FORCE_FOR_ALL_MATCHING
```

## 8. Migracja konta offline do premium

Projekt powinien od początku zakładać sytuację:

```text
gracz dzisiaj:
OFFLINE UUID

gracz później:
kupuje Minecraft
otrzymuje MOJANG UUID
```

AuthGatewayX powinien posiadać wewnętrzny identyfikator konta niezależny od Minecraft UUID:

```text
account_id
```

Dzięki temu migracja:

```text
OFFLINE -> MOJANG
```

nie wymaga tworzenia nowego logicznego konta AuthGatewayX.

## 9. Pozycjonowanie projektu

AuthGatewayX ma być:

- systemem authentication-first,
- platform-agnostic w warstwie biznesowej,
- bezpiecznym domyślnie,
- gotowym do integracji z innymi pluginami,
- projektowanym pod duże sieci i małe serwery standalone,
- odpornym na boty, flood i próby przejęcia kont.

## 10. Standard ekosystemu SyntaxDevTeam

AuthGatewayX ma być częścią wspólnego ekosystemu bibliotek SyntaxDevTeam.

Obowiązkowo:

```text
MessageHandler
    -> komunikaty, MiniMessage, locale, lang files

SyntaxCore
    -> logger, ServerEnvironment, update/stats, wspólna infrastruktura

PunisherX
    -> referencja strukturalna i wzorzec wieloplatformowego projektu
```

Moduł Paper ma wykorzystywać nowy lifecycle Paper (`PluginLoader` + `PluginBootstrap`), a moduł Velocity odpowiednie proxy API bibliotek SyntaxDevTeam.

---

# AuthGatewayX 1.0.0 — założenia praktyczne

## 1. Zachowanie z punktu widzenia gracza premium

Gracz premium:

1. łączy się z serwerem,
2. AuthGatewayX rozpoznaje, że konto powinno zostać uwierzytelnione online,
3. klient przechodzi prawidłowy proces Microsoft/Mojang,
4. AuthGatewayX uzyskuje zweryfikowany profil,
5. gracz trafia bezpośrednio na serwer,
6. nie wykonuje `/login`,
7. jego konto jest traktowane jako uwierzytelnione od początku sesji.

W przypadku nieudanego uwierzytelnienia dla chronionego konta premium:

```text
DENY
```

bez fallbacku do offline.

## 2. Zachowanie z punktu widzenia gracza non-premium

Pierwsze wejście:

```text
/connect
   |
   v
pre-auth session
   |
   v
/register <hasło> <hasło>
   |
   v
AUTHENTICATED
```

Kolejne wejścia:

```text
/connect
   |
   v
pre-auth session
   |
   v
/login <hasło>
   |
   v
AUTHENTICATED
```

Opcjonalnie:

- remembered session,
- trusted IP/session,
- ograniczony czas ważności sesji,
- możliwość wyłączenia tych mechanizmów.

## 3. Ochrona świata przed zalogowaniem

Do chwili poprawnego uwierzytelnienia gracz non-premium nie może być traktowany jak normalny gracz.

Minimalne blokady:

- ruch,
- teleportowanie,
- interakcje,
- niszczenie i stawianie bloków,
- otwieranie kontenerów,
- podnoszenie przedmiotów,
- wyrzucanie przedmiotów,
- handel,
- atakowanie,
- otrzymywanie obrażeń od świata/graczy,
- używanie pojazdów,
- używanie portali,
- komendy inne niż allowlista auth,
- chat,
- plugin messaging, jeśli może prowadzić do obejścia ochrony,
- interakcje z entity,
- inventory click/drag,
- command suggestions, jeśli ujawniają niepotrzebne informacje.

Domyślnie dozwolone:

```text
/login
/register
/logout
/changepassword
/quit
```

Ostateczna allowlista powinna być konfigurowalna.

## 4. Pre-auth isolation

Preferowany model:

```text
PRE-AUTH ZONE
```

lub logiczna izolacja bez wpuszczania gracza do normalnego świata.

Możliwe mechanizmy:

- zamrożenie pozycji,
- niewidoczność dla innych graczy,
- ukrycie innych graczy przed niezalogowanym,
- invulnerability,
- brak kolizji,
- opcjonalny dedicated login location,
- wyłączenie interakcji z otoczeniem,
- timeout logowania.

Przykład:

```yaml
authentication:
  timeout: 60s
  freeze-player: true
  hide-until-authenticated: true
  invulnerable-before-login: true
```

## 5. Blokada nicków premium

Administrator musi móc wymusić politykę:

```yaml
premium:
  protect-premium-usernames: true
```

Przykład:

```text
nick istnieje jako oficjalne konto premium
        |
        v
gracz nie przechodzi Mojang auth
        |
        v
DENY
```

Opcjonalne wyjątki:

```yaml
premium:
  username-protection:
    enabled: true
    exceptions:
      - "TestAccount"
```

Wyjątki powinny być wyraźnie oznaczone jako potencjalnie niebezpieczne.

## 6. CleanerX

AuthGatewayX powinien posiadać integrację z CleanerX dla walidacji nicku.

Przykładowy przepływ:

```text
LOGIN_START
    |
    v
AuthGatewayX
    |
    v
CleanerX username validation
    |
 +--+--+
 |     |
OK   DENY
```

Zastosowania:

- cenzura wulgarnych nicków,
- blokowanie nazw obraźliwych,
- blokowanie niedozwolonych wzorców,
- centralna polityka nazw kont.

Integracja powinna być opcjonalna.

W przypadku braku CleanerX AuthGatewayX musi działać samodzielnie.

## 7. PunisherX

PunisherX powinien móc pełnić rolę systemu wykonawczego dla blokad kont/IP.

Przykład:

```text
AuthGatewayX login attempt
        |
        v
PunisherX check
        |
 +------+------+
 |             |
ALLOWED       BANNED
 |             |
 v             v
continue      DENY
```

Integracja może obejmować:

- ban UUID,
- ban nazwy,
- ban IP,
- network ban,
- czasowe bany,
- sprawdzanie aktywnych kar przed wejściem do świata.

AuthGatewayX nie powinien duplikować całej logiki kar, jeżeli PunisherX jest dostępny.

Jednocześnie AuthGatewayX powinien posiadać minimalny fallback bezpieczeństwa, np. lokalny connection denylist dla anti-bot/anti-flood.

## 8. Anti-bot

AuthGatewayX musi traktować ochronę anti-bot jako część warstwy logowania.

Podstawowe mechanizmy:

- limit nowych połączeń na IP,
- limit prób logowania na IP,
- limit rejestracji na IP,
- limit liczby kont z jednego IP,
- cooldown po błędnym haśle,
- escalating penalty,
- tymczasowe IP quarantine,
- cache negatywnych decyzji,
- statystyki podejrzanych prób.

Przykład:

```yaml
anti-bot:
  enabled: true
  max-connections-per-ip-per-minute: 20
  max-login-attempts-per-minute: 10
  max-registrations-per-ip: 3
  temporary-block: 10m
```

## 9. Connection anti-flood

Anti-flood powinien działać możliwie wcześnie, jeszcze przed drogimi operacjami.

Kolejność:

```text
TCP/connection attempt
        |
        v
cheap connection checks
        |
        v
rate limiter
        |
        v
anti-bot classification
        |
        v
account lookup
        |
        v
Mojang/storage operations
```

Nigdy odwrotnie.

Drogie zapytanie do bazy lub zewnętrznego API nie powinno być pierwszą reakcją na niezweryfikowane połączenie.

## 10. Błędy logowania

Kategorie błędów powinny być rozróżniane:

```text
INVALID_PASSWORD
ACCOUNT_LOCKED
RATE_LIMITED
PREMIUM_REQUIRED
MOJANG_AUTH_FAILED
BANNED
USERNAME_REJECTED
CONNECTION_FLOOD
INTERNAL_ERROR
```

Nie wszystkie szczegóły powinny być pokazywane graczowi.

W szczególności komunikat nie powinien ułatwiać:

- enumeracji kont,
- ustalania, czy dany nick posiada konto,
- ustalania, czy hasło było „prawie poprawne”,
- testowania polityki anti-bot.

## 11. Publiczny config

Przykładowy kierunek:

```yaml
authentication:
  login-timeout: 60s
  allowed-commands:
    - login
    - register
    - changepassword

premium:
  enabled: true
  auto-detect: true
  protect-premium-usernames: true
  use-official-uuid: true
  cache-verification: true

offline:
  enabled: true
  registration:
    enabled: true
    max-accounts-per-ip: 3

security:
  password:
    algorithm: ARGON2ID

anti-bot:
  enabled: true

anti-flood:
  enabled: true

integrations:
  cleanerx:
    enabled: auto

  punisherx:
    enabled: auto
```

## 12. Domyślne ustawienia

Publiczna wersja powinna być:

```text
secure-by-default
```

czyli bezpieczne mechanizmy są włączone domyślnie.

Jeżeli administrator chce osłabić ochronę, powinien wykonać świadomą zmianę konfiguracji.

## 13. Spójność administracyjna SyntaxDevTeam

Administrator powinien otrzymać ten sam styl konfiguracji i komunikatów znany z innych pluginów SyntaxDevTeam.

- komunikaty przez MessageHandler,
- MiniMessage,
- pliki `lang/messages_*.yml`,
- locale-aware messages, gdy klient udostępni locale,
- logowanie i debug przez SyntaxCore,
- wspólne źródła aktualizacji,
- czytelne logi startu/wyłączenia,
- kompatybilne integracje CleanerX/PunisherX.

Awaria stats/update check nie może wpływać na możliwość uwierzytelnienia.

---

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

Pierwszy etap implementacji wydziela niezależny od platformy moduł
`authgatewayx-domain`. Moduł zawiera modele konta i sesji, walidację nazw oraz testowane
przejścia stanów połączenia. Reguły domenowe zabraniają aktywowania tożsamości `MOJANG`
hasłem offline, wymagają kompletu zweryfikowanych danych dla stanu `ACTIVE` i traktują
`DISCONNECTED` jako stan terminalny z idempotentnym rozłączeniem.

Nie oznacza to jeszcze ukończenia pozycji `session management` ani publicznego API z
checklisty 1.0.0: cache, storage, ochrona współbieżnych logowań i adaptery platformowe
pozostają do wdrożenia.

---

# AuthGatewayX 1.0.0 — bezpieczeństwo, wydajność i integracje

## 1. Priorytet bezpieczeństwa

Kolejność priorytetów projektu:

```text
1. bezpieczeństwo kont
2. integralność świata i serwera
3. odporność warstwy połączeń
4. poprawność uwierzytelniania
5. wydajność
6. wygoda administratora
```

W przypadku konfliktu wygody z bezpieczeństwem domyślna konfiguracja powinna wybrać bezpieczeństwo.

---

# 2. Ochrona przed kradzieżą kont

## MUST

- brak fallbacku premium -> offline dla chronionej nazwy,
- ochrona nazw premium,
- bezpieczny KDF dla haseł,
- rate limiting logowania,
- tymczasowa blokada po wielu błędnych próbach,
- brak informacji ułatwiających enumerację kont,
- sesje z kontrolowanym czasem życia,
- audit trail,
- jednoznaczne rozróżnienie MOJANG/OFFLINE,
- bezpieczna migracja OFFLINE -> MOJANG,
- ochrona przed race condition dwóch jednoczesnych logowań.

## Dodatkowe zabezpieczenia

- opcjonalne blokowanie równoczesnych sesji tego samego konta,
- invalidacja starej sesji po ponownym logowaniu,
- wykrywanie gwałtownej zmiany IP,
- configurable trusted sessions,
- opcjonalne 2FA w przyszłości.

---

# 3. Ochrona świata przed niezalogowanym graczem

Niezalogowany gracz non-premium powinien znajdować się w stanie:

```text
PRE_AUTH_QUARANTINE
```

Dozwolone tylko:

```text
/login
/register
/changepassword
/quit
```

Reszta funkcjonalności świata ma być zablokowana.

## MUST block

- movement,
- block break/place,
- use/interact,
- inventory,
- drop/pickup,
- damage,
- combat,
- portal,
- vehicle,
- entity interaction,
- chat,
- nieautoryzowane commands,
- plugin message abuse,
- command execution przez aliasy,
- teleporty inicjowane przez inne pluginy, jeśli pozwalają ominąć izolację.

Stan izolacji musi być egzekwowany niezależnie od klienta.

---

# 4. Premium username protection

Konfiguracja:

```yaml
premium:
  username-protection:
    enabled: true
    deny-offline-claim: true
    deny-on-auth-failure: true
```

Algorytm:

```text
username candidate
    |
    v
known premium?
    |
 +--+--+
 |     |
NO    YES
 |     |
 v     v
offline policy      require Mojang auth
                        |
                 +------+------+
                 |             |
              SUCCESS        FAILURE
                 |             |
                 v             v
               ALLOW          DENY
```

Wyjątki powinny być możliwe, ale oznaczone jako:

```text
SECURITY RISK
```

---

# 5. CleanerX

CleanerX może odpowiadać za:

```text
nickname policy
```

Przykład API:

```kotlin
interface UsernamePolicyProvider {
    fun validate(username: String): UsernameVerdict
}
```

Verdict:

```text
ALLOW
DENY_PROFANITY
DENY_PATTERN
DENY_RESERVED
```

AuthGatewayX powinien:

- wykonać tę walidację wcześnie,
- cache'ować wynik przez rozsądny TTL,
- nie wykonywać jej wielokrotnie dla tego samego login attempt,
- mieć configurable fail-open/fail-closed.

Dla bezpieczeństwa nazw zalecane:

```text
fail-closed
```

jeżeli CleanerX jest wymagany przez konfigurację.

---

# 6. PunisherX

PunisherX może odpowiadać za:

```text
ban enforcement
```

Sprawdzane przed wejściem gracza do świata:

- UUID ban,
- username ban,
- IP ban,
- network/global ban,
- czasowe kary.

AuthGatewayX nie powinien samodzielnie kopiować pełnej domeny kar PunisherX.

Przykład adaptera:

```kotlin
interface PunishmentProvider {
    fun checkLogin(identity: LoginIdentity): CompletableFuture<LoginPunishmentResult>
}
```

AuthGatewayX na podstawie wyniku:

```text
ALLOW
DENY
```

Opcjonalne informacje:

- reason,
- expiry,
- punishment id.

---

# 7. Anti-bot

Anti-bot powinien być warstwowy.

## Layer 1 — cheap guards

- malformed connection rejection,
- invalid username format,
- impossible protocol state,
- connection burst rate limiting.

## Layer 2 — IP reputation lokalna

- liczba połączeń,
- liczba rozłączeń,
- nieudane logowania,
- liczba prób różnych nicków,
- liczba rejestracji.

## Layer 3 — behavioural scoring

Przykład:

```text
+10 10 połączeń w 2 sekundy
+20 5 różnych nicków z jednego IP
+30 10 błędnych haseł
+40 repeated reconnect loop
```

Próg:

```text
score >= threshold -> temporary quarantine/block
```

## Layer 4 — external integrations

Opcjonalnie później:

- proxy reputation,
- ASN policy,
- known datacenter networks,
- external bot mitigation provider.

Nie powinno to być wymagane w 1.0.0.

---

# 8. Connection anti-flood

Anti-flood różni się od klasycznego anti-bot.

Celem jest ochrona zasobów serwera przed zalewem samych połączeń.

Mechanizmy:

- token bucket per IP,
- token bucket globalny,
- per-subnet limits,
- max concurrent pre-auth connections,
- max login handshakes in progress,
- timeout na niedokończony handshake,
- szybkie zwalnianie zasobów,
- early disconnect.

Przykład:

```yaml
anti-flood:
  enabled: true

  per-ip:
    connections-per-second: 3
    burst: 8

  global:
    connections-per-second: 200
    burst: 400

  pre-auth:
    max-concurrent: 500
    handshake-timeout: 10s
```

---

# 9. Wydajność

## Zasada

Najtańsza decyzja powinna być wykonywana jako pierwsza.

Poprawnie:

```text
connection limit
    ->
IP limiter
    ->
cached account policy
    ->
cached punishment
    ->
storage lookup
    ->
external Mojang verification
```

Niepoprawnie:

```text
Mojang HTTP
    ->
database
    ->
dopiero anti-flood
```

## Async candidates

- DB,
- HTTP,
- Argon2,
- audit write,
- remote PunisherX,
- remote cache.

## Sync / scheduler dependent

- Bukkit/Paper/Folia entity/world operations,
- teleport,
- inventory,
- visibility,
- movement state.

---

# 10. Cache

Przykładowe TTL:

```text
account policy              5-15 min
premium positive lookup     kilka godzin
premium negative lookup     krótki TTL
punishment result           5-30 s
username CleanerX verdict   kilka minut
temporary IP block          wg kary
session                     do końca sesji
```

Nie należy ustawiać jednego globalnego TTL dla wszystkich danych.

---

# 11. Race conditions

AuthGatewayX musi być odporny m.in. na:

```text
2 jednoczesne logowania tego samego konta
2 jednoczesne /register
premium verification + offline registration race
logout + reconnect race
ban received during authentication
disconnect podczas Argon2
disconnect podczas DB lookup
```

Rozwiązania:

- per-account locking,
- atomic storage operations,
- compare-and-set state,
- idempotent session invalidation.

---

# 12. Fail-open vs fail-closed

Dla każdej integracji trzeba określić zachowanie awaryjne.

Przykłady:

### Mojang verification

```text
fail-closed
```

dla kont wymagających premium authentication.

### PunisherX

Konfigurowalne:

```text
fail-closed
```

dla sieci wymagającej bezwzględnej egzekucji banów,

lub:

```text
fail-open
```

dla serwerów preferujących dostępność.

### CleanerX

Konfigurowalne.

---

# 13. Audit i bezpieczeństwo operacyjne

Minimalne zdarzenia audytowe:

```text
REGISTER
LOGIN_SUCCESS
LOGIN_FAILURE
ACCOUNT_LOCK
ACCOUNT_UNLOCK
PREMIUM_VERIFIED
PREMIUM_AUTH_FAILURE
OFFLINE_TO_PREMIUM_MIGRATION
USERNAME_POLICY_DENY
PUNISHMENT_DENY
ANTI_BOT_DENY
ANTI_FLOOD_DENY
SESSION_INVALIDATED
```

Każdy wpis:

```text
timestamp
account_id?
uuid?
username
source_ip
event_type
reason_code
```

Bez:

```text
password
password_hash
token
shared secret
```

---

# 14. Checklist bezpieczeństwa przed 1.0.0

- [ ] Premium user cannot be downgraded to offline after failed Mojang auth.
- [ ] Premium username protection działa przed rejestracją offline.
- [ ] `/register` jest atomiczne.
- [ ] Hasła są Argon2id.
- [ ] Brak blokującego JDBC na main/region thread.
- [ ] Anti-flood działa przed storage/API calls.
- [ ] Anti-bot posiada per-IP i global limiter.
- [ ] Pre-auth player nie może wpływać na świat.
- [ ] Pre-auth player nie może użyć aliasu do obejścia command allowlist.
- [ ] Plugin messaging nie pozwala ominąć auth.
- [ ] PunisherX może odrzucić login przed aktywacją sesji.
- [ ] CleanerX może odrzucić nick przed rejestracją.
- [ ] Disconnect podczas auth czyści wszystkie zasoby.
- [ ] Nie ma race condition przy równoczesnym logowaniu.
- [ ] Logi nie ujawniają sekretów.
- [ ] Cache posiada limity i invalidację.
- [ ] Folia scheduler compliance jest przetestowane.
- [ ] Velocity forwarding/backends są zabezpieczone.
- [ ] Testy obciążeniowe obejmują reconnect flood.
- [ ] Testy bezpieczeństwa obejmują próbę przejęcia premium nicku.

# 15. Thread ownership jako wymaganie bezpieczeństwa

W AuthGatewayX poprawne użycie schedulerów jest częścią bezpieczeństwa, a nie jedynie optymalizacji.

Błąd thread ownership może doprowadzić do:

- race condition sesji,
- częściowego zdjęcia PRE_AUTH isolation,
- równoczesnej aktywacji dwóch sesji,
- błędnego teleportu/unfreeze,
- niespójności cache i storage.

Dlatego:

```text
JDBC / HTTP / Argon2 -> AsyncScheduler / bounded executor
Player state         -> EntityScheduler
Location state       -> RegionScheduler
Global server state  -> GlobalRegionScheduler
```

Każde przejście:

```text
PRE_AUTH -> AUTHENTICATED -> ACTIVE
```

musi być atomowe logicznie i wykonane w poprawnym execution context.

# 16. Biblioteki infrastrukturalne

- MessageHandler odpowiada za messages/lang/locale.
- SyntaxCore odpowiada za wspólny logging/platform utilities.
- Telemetria SyntaxCore nie może być elementem krytycznej ścieżki auth.
- Loader Paper powinien dostarczać biblioteki runtime bez niepotrzebnego shadingu.
- Krytyczne biblioteki muszą mieć kontrolowane wersje i repozytoria.

---

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
- [ ] `/logout`
- [ ] `/changepassword`
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

- [ ] nickname validation provider
- [ ] profanity/pattern deny
- [ ] optional integration
- [ ] configurable failure strategy

### PunisherX

- [ ] UUID ban check
- [ ] name ban check
- [ ] IP ban check
- [ ] network ban support
- [ ] async punishment provider
- [ ] login denial before session activation

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
- [ ] Brigadier/lifecycle command registration
- [ ] `folia-supported: true`
- [ ] EntityScheduler dla player/entity state
- [ ] RegionScheduler dla location/chunk state
- [ ] GlobalRegionScheduler tylko dla global state
- [ ] AsyncScheduler/bounded executor dla JDBC/HTTP/Argon2
- [ ] backpressure dla kosztownych operacji
- [ ] brak SNAPSHOT runtime dependencies w stabilnym 1.0.0, jeśli dostępne są release

---

# AuthGatewayX 1.0.0 — standard wdrożeniowy SyntaxDevTeam, Paper i Folia

## 1. Cel

Implementacja AuthGatewayX 1.0.0 powinna wzorować się na strukturze i dobrych praktykach rozwijanych w `SyntaxDevTeam/PunisherX`, jednocześnie eliminując rozwiązania, które nie są optymalne dla krytycznego systemu uwierzytelniania.

Obowiązkowe biblioteki SyntaxDevTeam:

- `SyntaxDevTeam/MessageHandler`
- `SyntaxDevTeam/SyntaxCore`

Referencyjny projekt strukturalny:

- `SyntaxDevTeam/PunisherX`

AuthGatewayX nie powinien kopiować PunisherX 1:1. Ma przejąć jego model:

- wielomodułowość,
- osobną warstwę platformową,
- runtime dependency loading,
- centralną inicjalizację,
- adaptery platformowe,
- API,
- cache,
- hooki/integracje,
- oddzielenie domeny od platformy,

ale powinien zostać zaprojektowany od początku pod wymagania login protocol, security-first i pełną zgodność z Folia.

---

# 2. Docelowa struktura projektu

```text
AuthGatewayX/
|
├── authgatewayx-api
├── authgatewayx-domain
├── authgatewayx-auth
├── authgatewayx-security
├── authgatewayx-storage-api
├── authgatewayx-storage-jdbc
├── authgatewayx-integrations
├── authgatewayx-platform-common
|
├── authgatewayx-paper
|   ├── bootstrap
|   ├── loader
|   ├── lifecycle
|   ├── scheduler
|   ├── protocol
|   └── listener
|
└── authgatewayx-velocity
    ├── lifecycle
    ├── scheduler
    ├── listener
    └── protocol
```

Moduły `domain`, `auth`, `security` i `storage-api` nie powinny posiadać zależności od Bukkit/Paper/Velocity.

---

# 3. MessageHandler — obowiązkowa warstwa wiadomości

AuthGatewayX nie powinien implementować własnego systemu:

- plików językowych,
- MiniMessage,
- placeholderów,
- prefixów,
- locale,
- konwersji legacy,
- cache wiadomości.

Do tego należy używać `SyntaxDevTeam/MessageHandler`.

## Paper/Purpur/Folia

Artefakt:

```kotlin
compileOnly("pl.syntaxdevteam:messageHandler-paper:<version>")
```

Biblioteka ma zostać dostarczona w runtime przez `PluginLoader`, analogicznie do `PunisherX`.

Inicjalizacja:

```kotlin
SyntaxMessages.initialize(plugin)
messageHandler = SyntaxMessages.messages
```

AuthGatewayX powinien przechowywać otrzymany `MessageHandler` jako zależność serwisów UI/command/auth feedback zamiast wywoływać globalny singleton w każdej klasie.

Przykład:

```kotlin
class LoginMessageService(
    private val messages: MessageHandler
)
```

## Velocity

Artefakt:

```kotlin
compileOnly("pl.syntaxdevteam:messageHandler-velocity:<version>")
```

Velocity posiada osobny initializer:

```kotlin
SyntaxMessages.initialize(
    pluginContainer = container,
    dataDirectory = dataDirectory,
    logger = logger
)
```

Nie wolno próbować inicjalizować Velocity wariantem Bukkit:

```kotlin
SyntaxMessages.initialize(plugin)
```

ponieważ proxy nie posiada `JavaPlugin`.

## Locale

MessageHandler posiada locale-aware API. AuthGatewayX powinien używać go wszędzie, gdzie locale klienta jest już dostępne.

Na bardzo wczesnym etapie pre-login locale może jeszcze nie istnieć. Wtedy należy użyć globalnego fallbacku.

Przykład przepływu:

```text
pre-login:
    locale unavailable
    -> global configured language

post-client-settings:
    locale available
    -> messages_<locale>.yml
```

## Cache wiadomości

MessageHandler posiada własny mechanizm cache. AuthGatewayX nie powinien dokładać drugiego cache na gotowe wiadomości bez wykazanego problemu wydajnościowego.

---

# 4. SyntaxCore — obowiązkowy fundament infrastrukturalny

AuthGatewayX powinien używać `SyntaxDevTeam/SyntaxCore` w zakresie, w którym biblioteka dostarcza wspólną infrastrukturę SyntaxDevTeam.

## Paper/Purpur/Folia

```kotlin
SyntaxCore.registerUpdateSources(
    GitHubSource("SyntaxDevTeam/AuthGatewayX")
)

SyntaxCore.init(
    plugin,
    versionType = "paper"
)

logger = SyntaxCore.logger
pluginManager = SyntaxCore.pluginManagerx
statsCollector = SyntaxCore.statsCollector
```

W razie publikacji na Modrinth/Hangar należy dodać odpowiednie źródła aktualizacji.

## Velocity

Należy użyć proxy API SyntaxCore:

```kotlin
ProxySyntaxCore.initVelocity(
    proxy = proxyServer,
    container = pluginContainer,
    logger = slf4jLogger,
    dataDirectory = dataDirectory.toFile(),
    debugLevel = DebugLevel.OFF,
    versionType = "velocity"
)
```

## Zakres użycia SyntaxCore

Preferowane elementy:

- `Logger`
- `DebugLevel`
- `PluginManagerX`
- `StatsCollector`
- update checker
- `ServerEnvironment`
- wspólna infrastruktura baz danych, jeśli jej API spełnia wymagania AuthGatewayX
- wspólne utility utrzymywane przez SyntaxDevTeam

## Zasada

AuthGatewayX nie powinien duplikować funkcji już utrzymywanych w SyntaxCore.

Jednocześnie system uwierzytelniania nie może uzależniać krytycznej ścieżki loginu od funkcji telemetrycznych, update-checkera ani zewnętrznego endpointu SyntaxCore.

```text
authentication path
        |
        +-- MUST work without stats endpoint
        +-- MUST work without update endpoint
        +-- MUST work without telemetry
```

Awaria elementów niekrytycznych nie może blokować logowania.

---

# 5. Wzorzec zależności z PunisherX

Aktualny moduł Paper PunisherX używa:

```text
compileOnly SyntaxCore
compileOnly MessageHandler-Paper
```

a właściwe biblioteki są dostarczane podczas uruchamiania przez:

```text
paper-plugin.yml
    |
    v
PluginLoader
    |
    v
paper-libraries.yml
    |
    v
MavenLibraryResolver
```

AuthGatewayX powinien zachować ten wzorzec.

Przykładowe zasoby:

```text
authgatewayx-paper/src/main/resources/
├── paper-plugin.yml
├── paper-libraries.yml
├── config.yml
└── lang/
```

---

# 6. PluginLoader — obowiązkowy dla modułu Paper

Należy utworzyć klasę np.:

```text
pl.syntaxdevteam.authgatewayx.paper.loader.AuthGatewayXLoader
```

implementującą:

```java
io.papermc.paper.plugin.loader.PluginLoader
```

Cel:

- zbudowanie runtime classpath,
- pobieranie zależności z Nexus SyntaxDevTeam,
- pobieranie bibliotek z Maven Central/Paper,
- ograniczenie rozmiaru shadow JAR,
- niezależne wersjonowanie SyntaxCore/MessageHandler,
- czytelne zarządzanie bibliotekami.

Schemat:

```text
Paper starts
   |
   v
AuthGatewayXLoader
   |
   v
read paper-libraries.yml
   |
   v
MavenLibraryResolver
   |
   +--> SyntaxCore
   +--> MessageHandler-Paper
   +--> Caffeine
   +--> JDBC drivers / storage dependencies
   +--> Argon2 implementation
   +--> other runtime libraries
```

## Zasady loadera

1. Loader nie może zawierać logiki biznesowej.
2. Loader nie może przechowywać runtime state AuthGatewayX w `static`.
3. Należy pamiętać, że `PluginLoader` może być wywołany z innego classloadera.
4. Błąd pobrania krytycznej biblioteki musi przerwać start pluginu z jednoznacznym komunikatem.
5. Repozytoria powinny być jawnie zdefiniowane.
6. Snapshot repository nie powinno być używane w finalnym `1.0.0`, jeżeli dostępne są stabilne wydania bibliotek.

Przykład wpisu:

```yaml
loader: pl.syntaxdevteam.authgatewayx.paper.loader.AuthGatewayXLoader
```

---

# 7. PluginBootstrap — obowiązkowy dla modułu Paper

AuthGatewayX powinien dodatkowo wykorzystywać:

```java
io.papermc.paper.plugin.bootstrap.PluginBootstrap
```

Klasa np.:

```text
pl.syntaxdevteam.authgatewayx.paper.bootstrap.AuthGatewayXBootstrap
```

i:

```yaml
bootstrapper: pl.syntaxdevteam.authgatewayx.paper.bootstrap.AuthGatewayXBootstrap
```

## Odpowiedzialność bootstrappera

Bootstrap nie zastępuje `onEnable()`.

Ma odpowiadać wyłącznie za operacje, które rzeczywiście należą do wczesnego lifecycle Paper, np.:

- rejestracja Paper Lifecycle API,
- rejestracja natywnych Brigadier commands przez `LifecycleEvents.COMMANDS`,
- przygotowanie bootstrap-safe metadanych,
- walidacja krytycznych parametrów startowych, jeśli nie wymaga Bukkit runtime,
- przekazanie immutable bootstrap state do instancji pluginu przez `createPlugin()` tam, gdzie ma to uzasadnienie.

## Niedozwolone w bootstrap

Nie wolno zakładać pełnej dostępności Bukkit API.

W szczególności bootstrap nie jest miejscem na:

- odczyty świata,
- operacje graczami,
- listener registration wymagający działającego serwera,
- połączenia z bazą tylko dlatego, że „jest wcześniej”,
- uruchamianie normalnej logiki auth.

## Paper lifecycle

Docelowo rejestracja komend Paper powinna odbywać się przez:

```text
PluginBootstrap
    |
    v
LifecycleEventManager
    |
    v
LifecycleEvents.COMMANDS
```

zamiast opierać nowy plugin Paper wyłącznie na starym `getCommand()`/`plugin.yml` command executor.

---

# 8. paper-plugin.yml

Docelowy kierunek:

```yaml
name: AuthGatewayX
version: ${version}
main: pl.syntaxdevteam.authgatewayx.paper.AuthGatewayXPaper
api-version: '26.2'
folia-supported: true

bootstrapper: pl.syntaxdevteam.authgatewayx.paper.bootstrap.AuthGatewayXBootstrap
loader: pl.syntaxdevteam.authgatewayx.paper.loader.AuthGatewayXLoader
has-open-classloader: false
```

`api-version` powinno odpowiadać rzeczywistej minimalnej wersji wspieranej przez wydanie, a nie być kopiowane z PunisherX.

## Dependencies

Integracje:

```yaml
dependencies:
  server:
    CleanerX:
      load: BEFORE
      required: false
      join-classpath: false

    PunisherX:
      load: BEFORE
      required: false
      join-classpath: false
```

Preferowane jest korzystanie z publicznego API/ServicesManager zamiast `join-classpath: true`.

---

# 9. Pełna optymalizacja Paper/Folia

`folia-supported: true` jest wyłącznie deklaracją. Nie oznacza zgodności.

AuthGatewayX musi być projektowany tak, aby każda operacja miała jawnie określone execution context.

## 9.1 I/O i CPU-heavy

Następujące operacje NIE mogą odbywać się na main thread, Global Region ani Entity Scheduler:

- JDBC,
- HTTP Mojang,
- DNS,
- odczyty dużych plików,
- Argon2id,
- kosztowne serializacje,
- audit persistence,
- zewnętrzne API.

W Folia:

```text
AsyncScheduler
```

lub dedykowany bounded executor.

W Paper:

```text
async scheduler
```

lub dedykowany executor.

## 9.2 Gracz / entity

Operacje na konkretnym graczu powinny korzystać z:

```text
player.getScheduler()
```

czyli `EntityScheduler`.

Dotyczy m.in.:

- freeze/unfreeze,
- stan gracza,
- visibility,
- część inventory operations,
- akcje wymagające ownership entity,
- bezpieczne przejście PRE_AUTH -> ACTIVE.

## 9.3 Lokacja / chunk / block

Operacje związane z konkretną lokacją:

```text
RegionScheduler
```

## 9.4 Global server state

Operacje naprawdę globalne:

```text
GlobalRegionScheduler
```

Nie należy traktować `GlobalRegionScheduler` jako odpowiednika `runTaskAsynchronously`.

## 9.5 Async

```text
AsyncScheduler
```

dla pracy niezależnej od ticków i world state.

---

# 10. Scheduler abstraction

AuthGatewayX powinien posiadać własny adapter, wzorowany na `PunisherX/SchedulerAdapter`, ale bardziej precyzyjny.

Nie wystarczy:

```kotlin
runSync()
runAsync()
```

ponieważ na Folii „sync” jest niejednoznaczne.

Preferowane API:

```kotlin
interface PlatformScheduler {
    fun async(task: Runnable)
    fun global(task: Runnable)
    fun entity(player: Player, task: Runnable)
    fun region(location: Location, task: Runnable)
    fun delayedEntity(player: Player, delayTicks: Long, task: Runnable)
    fun delayedGlobal(delayTicks: Long, task: Runnable)
}
```

Nazwy mają wymuszać prawidłowe myślenie o ownership.

---

# 11. Async auth pipeline

Krytyczna ścieżka logowania powinna być pipeline'em asynchronicznym:

```text
connection
   |
   v
cheap anti-flood
   |
   v
anti-bot cache
   |
   v
cached account policy
   |
   v
async DB if required
   |
   v
async Mojang verification if required
   |
   v
async PunisherX query if required
   |
   v
auth decision
   |
   v
platform-safe state transition
```

Nie wolno:

```text
event thread
   |
   v
blocking JDBC
   |
   v
blocking HTTP
   |
   v
Argon2
```

---

# 12. Virtual threads / executors

Jeżeli docelowa wersja Java i platforma pozwalają na bezpieczne użycie virtual threads, można je rozważyć dla blokującego I/O.

Nie zwalnia to jednak z:

- connection pool limits,
- HTTP concurrency limits,
- rate limitów,
- bounded queues,
- timeoutów.

Tysiąc tanich wątków nie oznacza, że baza danych powinna dostać tysiąc równoległych zapytań.

---

# 13. Backpressure

AuthGatewayX musi posiadać mechanizm backpressure.

Przykład:

```text
max concurrent Mojang checks
max concurrent Argon2 verifications
max DB auth operations
max PRE_AUTH sessions
```

Po przekroczeniu limitu:

```text
QUEUE WITH SHORT TIMEOUT
```

lub:

```text
DENY / TRY AGAIN
```

a nie nieograniczone tworzenie futures/tasks.

---

# 14. Cache

Preferowany cache lokalny:

```text
Caffeine
```

Może być runtime dependency dostarczaną przez loader.

Cache powinny być rozdzielone:

```text
PremiumIdentityCache
AccountPolicyCache
SessionCache
PunishmentCache
UsernamePolicyCache
IpSecurityCache
RateLimitCache
```

Każdy posiada własny TTL/size/eviction policy.

---

# 15. Dependency injection / composition root

Główna klasa pluginu nie powinna tworzyć losowo serwisów w listenerach.

Należy użyć centralnego composition root:

```text
AuthGatewayXPaper
      |
      v
PluginInitializer / ServiceContainer
      |
      +--> AccountService
      +--> AuthenticationService
      +--> SessionService
      +--> SecurityService
      +--> PremiumIdentityService
      +--> Storage
      +--> MessageHandler
      +--> SyntaxCore Logger
      +--> CleanerX adapter
      +--> PunisherX adapter
```

Może to być własny lekki container; framework DI nie jest wymagany.

---

# 16. Wzorzec inicjalizacji

## Paper

```text
PluginLoader
    |
    v
runtime libraries
    |
    v
PluginBootstrap
    |
    v
Paper Lifecycle registration
    |
    v
JavaPlugin instance
    |
    v
SyntaxCore.init()
    |
    v
SyntaxMessages.initialize()
    |
    v
config validation
    |
    v
storage init ASYNC
    |
    v
services
    |
    v
listeners/hooks
    |
    v
READY
```

Plugin nie powinien akceptować graczy, dopóki krytyczny auth subsystem nie osiągnie:

```text
READY
```

W czasie:

```text
STARTING
DEGRADED
FAILED
```

nowe połączenia muszą zostać obsłużone zgodnie z bezpieczną polityką, domyślnie fail-closed.

## Velocity

```text
Velocity construction/injection
    |
    v
ProxyInitializeEvent
    |
    v
ProxySyntaxCore.initVelocity()
    |
    v
SyntaxMessages.initialize(container, dataDir, logger)
    |
    v
storage/security init
    |
    v
register listeners
    |
    v
READY
```

---

# 17. Krytyczny lifecycle shutdown

Wyłączenie:

```text
STOP_ACCEPTING_AUTH
    |
    v
cancel pending verification
    |
    v
invalidate pre-auth sessions
    |
    v
flush audit queue
    |
    v
close storage pools
    |
    v
shutdown owned executors
    |
    v
clear caches
```

Nie wolno pozostawiać:

- własnych executorów,
- scheduler tasks,
- DB pools,
- HTTP resources,
- references do Player/Connection.

---

# 18. Integracje CleanerX i PunisherX

Integracje powinny być adapterami, nie bezpośrednimi zależnościami domeny.

```text
authgatewayx-domain
        |
        v
interfaces
        |
   +----+----+
   |         |
CleanerX  PunisherX
adapter    adapter
```

Preferowane wykrywanie na Paper:

```text
ServicesManager / public API
```

a nie dostęp do prywatnych klas pluginów.

---

# 19. Testy platformowe

Minimalna macierz:

```text
Paper latest
Paper minimum supported
Purpur latest
Folia latest
Velocity latest
```

Testy muszą objąć:

- premium login,
- offline register/login,
- collision nicku premium,
- failed Mojang auth,
- DB latency,
- Mojang timeout,
- CleanerX unavailable,
- PunisherX unavailable,
- PunisherX ban,
- reconnect flood,
- bot burst,
- simultaneous login,
- disconnect during Argon2,
- disconnect during Mojang request,
- plugin disable with active sessions.

---

# 20. Checklista wdrożeniowa

- [ ] `MessageHandler-Paper` używany na Paper/Purpur/Folia.
- [ ] `MessageHandler-Velocity` używany na Velocity.
- [ ] `SyntaxCore.init(..., versionType = "paper")` używany w module Paper.
- [ ] `ProxySyntaxCore.initVelocity(...)` używany na Velocity.
- [ ] `PluginLoader` dostarcza runtime dependencies.
- [ ] `paper-libraries.yml` posiada kontrolowane repozytoria.
- [ ] Stabilne 1.0.0 nie zależy bez potrzeby od SNAPSHOT bibliotek.
- [ ] `PluginBootstrap` jest wpisany w `paper-plugin.yml`.
- [ ] Paper commands korzystają z Lifecycle API tam, gdzie ma to sens.
- [ ] `folia-supported: true` jest ustawione dopiero przy faktycznej zgodności.
- [ ] JDBC nigdy nie działa na main/global/entity/region thread.
- [ ] HTTP Mojang nigdy nie działa na main/global/entity/region thread.
- [ ] Argon2id nigdy nie blokuje threadu gry.
- [ ] Entity operations korzystają z `EntityScheduler`.
- [ ] Location/chunk operations korzystają z `RegionScheduler`.
- [ ] Global state korzysta z `GlobalRegionScheduler`.
- [ ] I/O korzysta z `AsyncScheduler` lub bounded executora.
- [ ] Istnieje backpressure dla DB/Mojang/Argon2.
- [ ] Wszystkie cache mają size limit i TTL.
- [ ] Shutdown zamyka wszystkie zasoby należące do AuthGatewayX.
- [ ] Security path działa niezależnie od StatsCollector/update checker.
