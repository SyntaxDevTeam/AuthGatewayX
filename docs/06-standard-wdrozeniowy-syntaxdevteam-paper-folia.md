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

# 21. Bieżący stan implementacji lifecycle

Implementacja platformowa znajduje się fizycznie w module `authgatewayx-paper` wraz z
własnym `build.gradle.kts`, źródłami, zasobami i testami. Projekt główny jest wyłącznie
agregatorem zadań `clean`, `check` i `build`; nie zawiera już platformowego katalogu
`src`. Artefakt Paper powstaje w `authgatewayx-paper/build/libs`, a `runServer` zachowuje
wspólny katalog testowy `${rootProject.projectDir}/run`.

Klasy modułu Paper używają docelowej przestrzeni nazw:

```text
pl.syntaxdevteam.authgatewayx.paper.AuthGatewayXPaper
pl.syntaxdevteam.authgatewayx.paper.bootstrap.AuthGatewayXBootstrap
pl.syntaxdevteam.authgatewayx.paper.loader.AuthGatewayXLoader
```

`PaperPlatformScheduler` posiada osobne operacje async, global, entity i region.
Nie oznacza to jeszcze potwierdzonej zgodności platformowej; checkboxy schedulerów
pozostają niezaznaczone do czasu testów Paper/Folia.

Do chwili ukończenia adapterów uwierzytelniania `AuthenticationReadinessListener`
utrzymuje start w stanie `STARTING` i odrzuca nowe logowania. Jest to świadoma polityka
fail-closed: nie wolno oznaczyć runtime jako `READY`, zanim storage, premium/offline
decision pipeline, PRE_AUTH isolation oraz wymagane integracje startowe nie zostaną
zainicjalizowane.

Loader jest jawnie podłączony w `paper-plugin.yml` i ładuje kontrolowaną listę bibliotek
runtime z `paper-libraries.yml`. Wersje SNAPSHOT SyntaxCore i MessageHandler są
tymczasowo dopuszczone tylko dla testów wydania WIP; przed stabilnym 1.0.0 należy
ponownie sprawdzić i preferować dostępne wersje release.

# 22. Natywne dialogi uwierzytelniania

`AuthenticationDialogController` implementuje formularze Paper Dialog API dla loginu
i rejestracji. Obsługuje wyłącznie własne namespaced action keys, utrzymuje oczekiwany
typ formularza per gracz i ignoruje nieoczekiwane custom clicki. Rejestracja porównuje
dwa hasła bez wczesnego wyjścia zależnego od pierwszej różnicy.

Callback domenowy wraca na `EntityScheduler` przed zamknięciem lub ponownym pokazaniem
okna. Teksty są wstrzykiwane jako `AuthenticationDialogText` z MessageHandler; nie
istnieje równoległy system wiadomości.

Controller jest rejestrowany dopiero razem z izolacją PRE_AUTH po poprawnej migracji
storage; błąd inicjalizacji przełącza stan na `FAILED`.

Prompt oraz feedback wymagający ponownej próby pozostają wewnątrz Paper Dialog API.
Wynik poprzedniej próby jest budowany jako `DialogBody.plainMessage`, a pola hasła jako
`DialogInput.text`. Auth flow nie wysyła instrukcji ani błędów przez chat, komendy,
action bar lub inventory GUI. Nagłówek rejestracji ma postać `Rejestracja konta dla
<nazwa_użytkownika>` i dwa pola hasła, natomiast kolejne wejście otrzymuje nagłówek
logowania oraz jedno pole. Nick zastępuje placeholder jako zwykły komponent tekstowy.
Po aktywacji sesji gracz otrzymuje na czacie wyłącznie informację o sukcesie — osobną
dla uwierzytelnienia offline i premium — wysłaną w kontekście `EntityScheduler`.

Aktualny pionowy wycinek Paper przechodzi do `READY` dopiero po asynchronicznym
utworzeniu storage, migracjach SQLite i przygotowaniu dummy hash Argon2id. Następnie
rejestruje izolację PRE_AUTH, MessageHandler-backed Paper Dialogs i router wyboru
logowania lub rejestracji. Zewnętrzne biblioteki są dostarczane przez `PluginLoader` z
`paper-libraries.yml`; snapshoty SyntaxCore/MessageHandler są tymczasowo dopuszczone
wyłącznie dla wersji WIP i wymagają zastąpienia wydaniami release przed stabilnym 1.0.0.

Paper standalone wykonuje ograniczony bounded executorem lookup Minecraft Services
w fazie LOGIN. Potwierdzony brak profilu dopuszcza ścieżkę offline, a istniejący profil
premium uruchamia natywny encryption request Paper. Odpowiedź klienta, szyfrowanie i
weryfikację Mojang Session Server wykonuje kod Paper. Timeout lub awaria usługi kończy
się DENY. Lookup ma bounded cache, oddzielne positive/negative TTL i deduplikację per
nick, a osobny limit ogranicza równoległe handshake'i. Adapter zweryfikowano kompilacją
i startem Paper 26.2 build 121. Test pakietowy LOGIN dla nazwy premium potwierdził
odpowiedź `0x01 Encryption Request`; test pełnej sesji rzeczywistym klientem premium
i Folia pozostają otwarte.

`ConnectionFloodGate` działa w `AsyncPlayerPreLoginEvent` przed readiness, storage i
Argon2. Pierwszy lookup premium standalone występuje wcześniej na poziomie LOGIN, więc
protocol-level cheap guard przed tym HTTP nadal pozostaje otwarty.

Za limiterem połączeń działa ograniczony `UsernameBurstGate`. Wykrywa wiele różnych
kanonicznych nazw z jednego IP, nakłada czasową kwarantannę i wygasza nieaktywny stan.
Jest wykonywany przed readiness, JDBC i Argon2 oraz czyszczony przy shutdownie.
Listener PRE_AUTH rejestruje w tym samym ograniczonym stanie szybkie disconnecty przed
uwierzytelnieniem; przekroczenie limitu nakłada kwarantannę na kolejny reconnect.
Ważony `ConnectionBehaviorGate` łączy te sygnały z próbami połączeń i nieudanymi
wynikami formularza. Ma bounded state, okno wygaszania, kwarantannę i fail-closed
capacity. Callback formularza oraz quit zapisują wyłącznie sygnały związane z IP, bez
sekretów; shutdown czyści stan. Test obciążeniowy pozostaje otwarty.

Próby rejestracji przechodzą dodatkowo przez `RegistrationAttemptGate` zanim zostanie
zaplanowany Argon2 lub JDBC. Gate ma własny token bucket, TTL i limit śledzonych IP;
odrzucenie zeruje hasło oraz zapisuje `ANTI_BOT_DENY`. Runtime czyści ten rejestr przy
shutdownie. Trwały limit liczby kont na IP pozostaje otwarty.

# 24. Bieżący stan adaptera Velocity

Moduł `authgatewayx-velocity` używa konstrukcji przez Guice oraz
`ProxyInitializeEvent`. Inicjalizuje `ProxySyntaxCore.initVelocity(...)` i
`SyntaxMessages.initialize(container, dataDirectory, logger)`, po czym rejestruje
fail-closed listener. `PreLoginEvent` wybiera natywnie `forceOnlineMode()` lub
`forceOfflineMode()` dopiero po limiterze i asynchronicznym lookupie polityki premium.

Decyzja ma ograniczony rejestr z TTL i jest ponownie weryfikowana w `LoginEvent` wobec
rzeczywistego `Player.isOnlineMode`. Shutdown zamyka executor oraz czyści rejestry.
Artefakt kompiluje wspólne moduły domain/security/integrations, SyntaxCore i
MessageHandler-Velocity. Nie oznaczono jeszcze pełnej integracji Velocity jako
ukończonej, ponieważ brakuje zabezpieczonego przekazania principal do Paper i testu na
uruchomionym proxy.

# 23. Implementacja PRE_AUTH isolation

`PreAuthEntryListener` tworzy i przełącza sesję do PRE_AUTH przy wejściu.
`PreAuthIsolationListener` egzekwuje blokady eventów niezależnie od klienta, a
`PreAuthIsolationManager` odpowiada za invulnerability, collision, pickup, visibility
i timeout. Operacje gracza oraz hide/show są kierowane przez EntityScheduler.

`AuthenticationFormCoordinator` wywołuje hook aktywacji dopiero po udanym auth i
atomowym `SessionRegistry.activate`. Hook przywraca zapisane flagi i widoczność. Quit
usuwa snapshot oraz sesję, więc reconnect nie dziedziczy starego stanu.

Listener blokuje wszystkie komendy PRE_AUTH. Natywne dialogi nie wymagają komend z
hasłem, a pełna blokada eliminuje obejścia przez aliasy i namespace.

Klasy kwarantanny kompilują się przeciw Paper 26.2, ale nie są jeszcze rejestrowane w
runtime. Checkboxy ochrony świata i Folia pozostają otwarte do testów serwerowych oraz
audytu plugin messaging na granicy proxy/backend.
