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
└── authgatewayx-folia
└── authgatewayx-spigot
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

## 19. Stan implementacji — fundament domenowy i bezpieczeństwa

Pierwszy etap implementacji wydziela moduł `authgatewayx-domain`, który nie zależy od
API Paper, Bukkit ani Velocity. Zawiera on:

- `AccountId`, `AccountUsername`, `AuthAccount`, `IdentityType` i `AccountState`,
- `ConnectionId`, `AuthSession`, `ConnectionState` i `AuthenticationMethod`,
- walidację formatu nazwy przed uruchomieniem kosztownych elementów pipeline'u,
- jawne i testowane przejścia `CONNECTING -> PRE_AUTH -> ACTIVE -> DISCONNECTED`,
- bezpośrednie `CONNECTING -> ACTIVE` wyłącznie jako ścieżkę zweryfikowanej tożsamości,
- inwariant zabraniający aktywacji tożsamości `MOJANG` metodą hasłową,
- idempotentne przejście do `DISCONNECTED`.

Pierwsza część etapu nie implementowała jeszcze session cache, storage ani
współbieżnej koordynacji logowań. Dostarczyła niezmienne modele i reguły domenowe, na
których następnie zbudowano serwisy auth oraz adaptery platformowe.

Kolejny etap dodał moduły `authgatewayx-api`, `authgatewayx-auth`,
`authgatewayx-security` i `authgatewayx-integrations`. Aktualnie dostępne są:

- `AuthGatewayApi` jako kontrakt publiczny bez zależności od platformy,
- `InMemorySessionRegistry` z atomowym przejściem sesji oraz blokadą dwóch aktywnych
  sesji tego samego `account_id`,
- `PremiumLoginPolicy`, która dla chronionej nazwy zawsze kończy nieudane Mojang auth
  decyzją `DENY_PREMIUM_AUTHENTICATION_FAILED`, bez ścieżki offline,
- zgodne z serwerem offline UUID generowane przez `OfflineIdentity`,
- per-IP i globalny token bucket, ograniczenie liczby śledzonych adresów oraz limit
  równoczesnych sesji PRE_AUTH,
- `BoundedTaskExecutor` z ograniczoną kolejką i jawnym odrzuceniem po przeciążeniu,
- kontrakty `UsernamePolicyProvider`, `PunishmentProvider` i `SecurityAuditSink`,
- adapter schedulerów Paper/Folia rozróżniający async, global, entity i region.

Elementy security są na tym etapie prymitywami używanymi przez przyszły login
pipeline. Nie należy uznawać anti-flood, API ani session management za ukończone dla
1.0.0, dopóki nie zostaną spięte z rzeczywistym wejściem połączenia, storage i
adapterami platformowymi.

## 20. Stan implementacji — SQLite i Argon2id

Dodano `authgatewayx-storage-api` oraz `authgatewayx-storage-jdbc`. Pierwszym
zaimplementowanym backendem jest SQLite:

- `AccountStorage` udostępnia wyłącznie operacje asynchroniczne jako `CompletionStage`,
- `SqliteAccountStorage` korzysta z ograniczonego HikariCP i `BoundedTaskExecutor`,
- migracja v1 tworzy `accounts` oraz `schema_history` i jest idempotentna,
- unikalne indeksy `canonical_username` i `minecraft_uuid` stanowią ostateczną ochronę
  atomowej rejestracji,
- test dwóch równoczesnych rejestracji potwierdza utworzenie dokładnie jednego konta,
- `RegistrationService` wykonuje Argon2id poza threadem gry, a następnie wywołuje
  asynchroniczne storage,
- wejściowa tablica hasła jest zerowana również po błędzie lub odrzuceniu zadania.

Kontrolowane wersje pierwszego backendu to HikariCP 7.1.0, sqlite-jdbc 3.53.2.1 oraz
argon2-jvm 2.12. Argon2 i sterownik SQLite korzystają z bibliotek natywnych. Nie są
shadowowane do głównego JAR-a; docelowo dostarczy je `PluginLoader`. Backendy MySQL,
MariaDB i PostgreSQL oraz runtime composition root nadal pozostają do wykonania, więc
checkboxy całej warstwy storage nie są jeszcze zamykane.

## 21. Stan implementacji — logowanie offline i lockout

`LoginService` realizuje domenowy przepływ logowania offline:

```text
per-IP LoginAttemptGate
    -> async credentials lookup
    -> Argon2id na bounded executorze
    -> atomowy success/failure update
    -> SecurityAuditSink
```

Nieistniejące konto przechodzi weryfikację względem dostarczonego dummy Argon2id hash,
aby ograniczyć różnicę kosztu względem błędnego hasła istniejącego konta. Wynik
`InvalidCredentials` jest wspólny. Limiter działa przed storage i Argon2. Po osiągnięciu
progu SQLite wykonuje pojedynczy atomowy `UPDATE ... RETURNING`, ustawiając
`locked_until`; udane logowanie zeruje licznik i blokadę.

Migracja v2 dodaje `failed_login_count`, a v3 tabelę `security_events`. Backend SQLite
implementuje `SecurityAuditSink`; zapis obejmuje wyłącznie identyfikatory, nick, IP,
typ zdarzenia i reason code. Nie zapisuje hasła ani hasha. Komendy Paper i aktywacja
sesji pozostają do integracji, dlatego `/login`, account lockout i security audit log
nie są jeszcze oznaczone jako kompletne w checkliście wydania.

## 22. Stan implementacji — lookup premium i wejście Paper

`MojangProfileLookup` używa Java HttpClient wyłącznie na osobnym bounded executorze.
Rozróżnia `PREMIUM`, `NOT_PREMIUM` i `UNAVAILABLE`; tylko potwierdzony brak profilu
otwiera ścieżkę hasłową offline. Cache ma limit rozmiaru, osobne TTL positive/negative,
invalidację i współdzielenie jednego requestu dla równoległych prób tego samego nicku.

Paper standalone przechwytuje `ServerboundHelloPacket` przed standardowym listenerem
LOGIN i zastępuje listener wariantem per-połączeniowym. Potwierdzony profil premium
otrzymuje natywny Paper encryption request, po czym istniejący kod Paper weryfikuje
odpowiedź klienta i sesję w Mojang Session Server. Potwierdzony brak profilu wraca do
standardowej ścieżki offline; awaria lookupu, brak miejsca w ograniczonym limicie
handshake albo nieudana sesja kończy się DENY bez fallbacku.

Instalacja handlera potomnego kanału odbywa się synchronicznie w `channelActive`, po
utworzeniu kodeków Paper i przed odczytem LOGIN; eliminuje to wyścig, w którym szybki
klient mógł wcześniej ominąć interceptor. Lokalny test protokołu dla `WieszczY`
potwierdził odpowiedź `0x01 Encryption Request` zamiast wejścia do ścieżki offline.

Liczbę jednoczesnych handshake'ów premium ogranicza
`premium.authentication.maximum-concurrent-handshakes`. Adapter kompiluje się i startuje
na Paper 26.2 build 121; test wejścia rzeczywistym klientem premium pozostaje wymagany,
więc checklista pełnego premium loginu nie jest jeszcze zamknięta. `ConnectionFloodGate`
działa w `AsyncPlayerPreLoginEvent`; protocol-level cheap guard przed lookupem LOGIN
pozostaje kolejnym etapem hardeningu.

## 23. Stan implementacji — selektor uwierzytelnienia Velocity

Powstał osobny moduł `authgatewayx-velocity`. W `PreLoginEvent` wykonuje tani
connection flood guard, walidację nazwy i asynchroniczny `MojangProfileLookup`, a
następnie używa natywnego `forceOnlineMode()` dla profilu premium lub
`forceOfflineMode()` dla potwierdzonego braku profilu. `UNAVAILABLE` zawsze oznacza
DENY.

Wybrany tryb jest zapisywany w ograniczonym rozmiarem i TTL rejestrze oczekujących
połączeń. `LoginEvent` odbiera wpis dokładnie raz i sprawdza `Player.isOnlineMode`;
brak wpisu, wygaśnięcie lub rozbieżność trybu kończą połączenie. Moduł inicjalizuje
`ProxySyntaxCore` oraz MessageHandler-Velocity i ma niezależny fail-closed readiness.
Przekazanie zweryfikowanej tożsamości premium do backendu Paper pozostaje następnym
etapem i wymaga uwierzytelnionego kanału, nie zwykłej ufności do plugin message.

## 24. Stan implementacji — username burst anti-bot

Moduł security zawiera ograniczony `UsernameBurstGate`. Dla każdego IP przechowuje
wyłącznie zbiór kanonicznych nazw w konfigurowalnym oknie, czas kwarantanny i czas
ostatniej aktywności. Limit kardynalności zapobiega nieograniczonemu wzrostowi pamięci,
a wygasłe wpisy są usuwane przy osiągnięciu limitu.

Adapter Paper wywołuje gate po token bucketach connection flood i przed wszystkimi
kosztownymi etapami. Testy pokrywają powtarzanie tej samej nazwy, burst różnych nazw,
wygaśnięcie kwarantanny, reset okna oraz odzyskanie miejsca w ograniczonym rejestrze.
`PreAuthIsolationListener` przekazuje dodatkowo sygnał disconnect tylko wtedy, gdy
sesja nadal jest w PRE_AUTH. Gate zlicza takie rozłączenia w tym samym oknie i po
przekroczeniu konfigurowalnego maksimum nakłada kwarantannę na następny reconnect.
Testy obejmują próg oraz wygaśnięcie historii disconnectów. Nie zamyka to całego
zakresu anti-bot 1.0.0.

## 25. Stan implementacji — registration attempt gate

`RegistrationAttemptGate` jest osobnym token bucketiem per IP używanym wyłącznie przez
`RegistrationService`. Działa przed Argon2 i `AccountStorage`, ma size limit, TTL oraz
jawny wynik fail-closed po wyczerpaniu kardynalności. `RegistrationOutcome.RateLimited`
jest mapowany przez koordynator formularza na neutralny feedback bez aktywacji sesji.

Odrzucenie zeruje `CharArray` i emituje `ANTI_BOT_DENY`; utworzenie konta emituje
`REGISTER`. Testy potwierdzają brak wywołania storage na ścieżce limitera, zerowanie
hasła, audit, refill tokenów, TTL rejestru oraz pozostanie sesji w PRE_AUTH.
Migracja SQLite v4 tworzy `registration_ip_slots`. `registerOffline` zapisuje konto i
przydziela pierwszy wolny slot `(source_ip, slot)` w jednej transakcji. Klucz główny
uniemożliwia przekroczenie limitu przez równoległe rejestracje, a rollback usuwa konto
po odmowie. Migracja przypisuje istniejącym kontom offline sloty na podstawie
`last_login_ip`, więc restart nie zeruje limitu. Pozostałe backendy JDBC muszą zapewnić
równoważną atomowość przed oznaczeniem całej warstwy jako ukończonej.

## 26. Stan implementacji — rozróżnienie formularzy i feedback sukcesu

`AuthenticationDialogRouter` rozpoznaje brak konta jako pierwsze wejście i otwiera
rejestrację z dwoma polami hasła; istniejące konto offline otrzymuje formularz logowania
z jednym polem. Oba nagłówki zawierają nazwę gracza, wstawianą do komponentu Adventure
jako zwykły tekst. Zapobiega to interpretowaniu nazwy jako formatowania MiniMessage.

Po udanej rejestracji lub logowaniu offline komunikat sukcesu jest planowany dopiero po
atomowej aktywacji sesji. Osobny komunikat premium jest wysyłany po pomyślnym
`VerifiedMojangAuthenticationService`. Wysyłka do gracza odbywa się przez
`EntityScheduler`; komunikaty pochodzą z MessageHandler i nie zawierają sekretów.

## 27. Stan implementacji — ważony connection behavior scoring

`ConnectionBehaviorGate` utrzymuje per IP ważony wynik prób połączeń, kolejnych różnych
nicków, nieudanych wyników formularza oraz rozłączeń w PRE_AUTH. Próg uruchamia lokalną
kwarantannę. Rejestr ma limit kardynalności, osobne okno obserwacji i czas kwarantanny,
usuwa wygasłe wpisy przy presji pojemności i odrzuca nowy adres fail-closed, gdy nie może
go bezpiecznie śledzić. Późne callbacki nie mogą utworzyć nowego wpisu.

Paper wywołuje scoring po tanim `ConnectionFloodGate` i walidacji nazwy, przed JDBC i
Argon2. Wyniki formularza wracają na `EntityScheduler`, gdzie nieudane auth zwiększa
wynik; quit w PRE_AUTH rejestruje sygnał rozłączenia. Shutdown czyści cały stan.
Pierwszy lookup premium standalone nadal poprzedza `AsyncPlayerPreLoginEvent`, więc
protocol-level cheap guard i test obciążeniowy pozostają otwarte.

## 28. Stan implementacji — czytelny błąd sesji premium standalone

Adapter Netty przechwytuje wychodzący `ClientboundLoginDisconnectPacket` tylko dla
połączenia, które `StandalonePremiumLoginListener` skierował do oficjalnego premium
handshake. Jeżeli powodem jest dokładnie vanilla
`multiplayer.disconnect.unverified_username`, zastępuje go komponentem MessageHandler
`auth.premium_session_invalid`. Pozostałe powody — w tym niedostępność usług,
przeciążenie i błędy protokołu — nie są przepisywane.

Komunikat identyfikuje AuthGatewayX, wyjaśnia brak potwierdzonej sesji dla chronionego
nicku oraz podaje kroki naprawcze. Nie wprowadza fallbacku offline i nie twierdzi, że
serwer zna dokładną lokalną przyczynę, której Minecraft Services nie ujawnia.
