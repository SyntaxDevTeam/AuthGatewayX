# Weryfikacja wydania i benchmarki — 2026-10-10

Aktualizacja build/lifecycle 2026-10-10: obowiązuje [standard SyntaxDevTeam](14-standaryzacja-syntaxdevteam.md).
Obecny katalog zachowuje robocze `26.3.build.+`; wcześniejsze przypięcie 169-beta i jego
wyniki testów opisują poprzednią walidację. Composition root jest w platformowych
`PluginInitializer.kt`, a loader Paper nazywa się `SyntaxLoader`.

Ten dokument opisuje wykonane poprawki, automatyczne testy i procedury pomiaru.
Kanoniczne bramki pełnego wydania pozostają w `08-roadmap-do-1.0.0.md`.
Wynik jednostkowy lub lokalny benchmark nie zamyka testu serwerowego Folia/Velocity.

## Wdrożone zmiany i decyzje

- Paper dev bundle i `runServer` przeniesiono do 26.3. Zależność jest przypięta do
  `26.3.build.169-beta`, a descriptor wymaga API 26.3. Java pozostaje 25. To wersja beta
  według metadanych repozytorium Paper; nie oznacza certyfikacji stabilnego release 1.0.0.
  Kompilacja NMS i testy JVM przechodzą. Test klientem i testy platformowe 26.3 pozostają
  otwarte; wcześniejsze wyniki Paper 26.2 build 121 są historyczne.
- `BoundedTaskExecutor` kończy wyjątkowo futures z kolejki usuniętej przez przymusowy
  shutdown. Zadanie anulowane przed startem nie wykonuje drogiej operacji. Testy sprawdzają
  shutdown, odrzucenie dalszej pracy i brak wykonania zadania z kolejki.
- `LoginService` zeruje hasło także po wyjątkowym wyniku odczytu storage i synchronicznym
  wyjątku dostawcy. Nie zmienia parametrów Argon2id ani zasad premium/offline.
- Modyfikacje `InMemorySessionRegistry` są serializowane krótkim monitorem rejestru,
  aby `clear` i równoległa aktywacja nie pozostawiały osieroconych indeksów. Sekcja
  krytyczna obejmuje tylko przejścia w pamięci, bez JDBC/HTTP/Argon2 ani platformowego API.
- `AccountActivationGuard` ponawia kontrolę CleanerX/PunisherX po kosztownej weryfikacji
  offline lub zapisie zweryfikowanej tożsamości premium, przed `sessions.activate`.
  Paper używa tej samej konfiguracji admission i strategii awarii co przy wejściu.
  Odrzucenie jest audytowane, pozostawia PRE_AUTH i nie emituje aktywacji/sukcesu.
  Offline pokazuje istniejący komunikat MessageHandler `auth.integration_denied` w dialogu;
  premium zostaje rozłączony tym komunikatem. Konto utworzone przed odmową nie jest usuwane.
  Guard nie zapewnia transakcji obejmującej bazę obcego systemu kar: decyzja odnosi się
  do wyniku ostatniej kontroli, a późniejsze kary pozostają odpowiedzialnością PunisherX.
- `ConnectionFloodGate` wygasza nieaktywne adresy przy alokacji nowego wpisu. Retencja
  jest nie krótsza niż minuta i pełne odtworzenie per-IP token bucket:
  `max(60s, ceil(capacity/refillTokens) * refillPeriod)`. Aktywne wpisy nie są eksmitowane,
  globalny bucket nie jest resetowany. Mapa z kolejnością ostatniego użycia usuwa
  najstarsze wygasłe wpisy, bez pełnego skanowania na każdą nową próbę. Operacje są
  synchronizowane; rozmiar pozostaje ograniczony. Testy chronią retencję i brak resetu
  limiterów przez aktywne adresy. Nie oznacza to weryfikacji wszystkich cache projektu.

Kod wspólny pozostaje bez importów Paper/Velocity. Guard działa asynchronicznie przez
istniejące kontrakty providerów; UI i kick pozostają na EntityScheduler. Rzeczywiste
testy ownership na Folia pozostają otwarte.

## Testy auth i storage

`AuthenticationLifecycleRaceTest` obejmuje dwie równoległe poprawne weryfikacje tego
samego konta, disconnect/shutdown przed callbackiem, późną rejestrację, ban pojawiający
się podczas oczekiwania na hasło i błąd admission po rejestracji.
`VerifiedMojangAuthenticationLifecycleTest` obejmuje odmowę, awarię i disconnect
podczas końcowej kontroli premium. Żaden taki przypadek nie aktywuje sesji.

`StorageAtomicityContractTest` uruchamia ten sam kontrakt na SQLite oraz skonfigurowanych
MySQL/MariaDB/PostgreSQL: idempotentne migracje, równoczesna rejestracja tej samej nazwy,
konkurencja o ostatni slot IP z rollbackiem odrzuconego konta, atomowy lockout, concurrent
password CAS, reset lockoutu i wyjątkowe zakończenie odczytu po zamknięciu puli.
To ostatnie jest testem zamkniętej puli, nie zerwania połączenia w połowie transakcji migracji.

Lokalnie niezdefiniowane zdalne bazy są jawnie oznaczane jako pominięte przez JUnit.
CI ustawia `AGX_REQUIRE_REMOTE_DATABASES=true`; brak dowolnego z trzech URL-i powoduje
błąd zamiast pozornego sukcesu. Workflow uruchamia te testy z istniejącymi usługami
MySQL 8.4, MariaDB 11.4 i PostgreSQL 17. Odczyt logów przebiegu 38077964789 potwierdził
sukces `clean check` z obowiązkowymi zdalnymi bazami przed błędem harnessu. Kontrakt
atomowości wykonano więc także w CI; nie jest to dowód testu zerwania połączenia w migracji.

Zmienne: `AGX_TEST_MYSQL_URL/USER/PASSWORD`, `AGX_TEST_MARIADB_URL/USER/PASSWORD`,
`AGX_TEST_POSTGRESQL_URL/USER/PASSWORD`. Używać wyłącznie osobnych baz testowych;
testy tworzą rekordy kont i migracje schematu. Nie podawać produkcyjnego JDBC URL.

```bash
./gradlew check --console=plain
python3 -m unittest discover -s tools -p 'test_*.py'
```

## Benchmark rzeczywistych implementacji w procesie

```bash
./gradlew :authgatewayx-security:securityBenchmark -PbenchmarkSamples=20 --console=plain
```

Raport: `authgatewayx-security/build/reports/benchmarks/security.json`.
Task wymaga natywnych bibliotek Argon2 z zależności testowych. Nie tworzy socketów,
nie wykonuje DNS/HTTP i nie używa kont ani haseł graczy. Benchmark jest osobnym taskiem
z tagiem `benchmark`, wyłączonym z zwykłego `test`; CI uruchamia go osobno i drukuje raport.

Pomiary obejmują realne hash/verify Argon2id (także błędne hasło), pipeline cheap guards,
username/reconnect scoring, sygnały nieudanego auth/disconnect, limiter rejestracji
oraz rzeczywiste zadania Argon2 w bounded executorze. Limity harnessu:

- 3–64 próbki dla każdej operacji Argon2; domyślnie 8, dwa rozgrzewające verify;
- 100 000 operacji na wariant guarda, 256 syntetycznych adresów, limit stanu 128;
- 32 submitowania Argon2, dwa workery, kolejka czterech zadań;
- JVM heap 512 MiB, timeout testu 60 sekund;
- raport bez haseł, hashy, rzeczywistych IP i nazwy hosta.

Percentyle używają nearest rank. Dla małej liczby próbek p99 jest bliski maksimum;
nie należy interpretować go jako stabilnego p99 produkcyjnego ruchu. Przepustowość
guardów dotyczy mieszanego workloadu, zdominowanego przez tanie odmowy po wyczerpaniu
limitu, a nie przepustowości pełnego serwera. Licznik drogiej pracy w pipeline jest
syntetyczny: sprawdza, że odmowa nie przechodzi do kolejnego etapu, bez zapytań DB/HTTP.
Limit 128 MiB dla dwóch workerów to wyliczony koszt pamięci Argon2, nie pomiar RSS.

Wykonany pomiar 20 próbek na Linux amd64, Java 25.0.4.1, 16 dostępnych procesorach:

| Operacja | p50 | p95 |
|---|---:|---:|
| Argon2id hash: 3 iteracje / 64 MiB / parallelism 1 | 108,01 ms | 124,00 ms |
| Verify poprawnego hasła | 99,20 ms | 103,11 ms |
| Verify błędnego hasła | 99,49 ms | 103,68 ms |
| Argon2 w kolejce, od submit do końca | 204,75 ms | 314,36 ms |

Przy natychmiastowym submitowaniu 32 zadań przyjęto 6 i odrzucono 26. Wynik potwierdza
backpressure, nie rekomenduje limitów dla każdego hosta. Kopia surowego pomiaru znajduje
się w `benchmarks/2026-10-10-security.json`. Parametrów produkcyjnych nie obniżono.
Przed wydaniem powtórzyć pomiary na wspieranym sprzęcie pod zwykłym obciążeniem gry,
obserwując TPS/MSPT, RSS, użycie CPU, liczbę odrzuceń i latencję pełnego auth.

## Lokalny benchmark protokołu LOGIN

Uruchomić osobny lokalny serwer testowy Paper/Purpur/Folia lub lokalne Velocity z
testowym backendem i testową bazą. Następnie:

```bash
python3 tools/benchmark_local_login.py --port 25565 --connections 40 --concurrency 2 --rate 2 --pattern reconnect
python3 tools/benchmark_local_login.py --port 25565 --connections 40 --concurrency 2 --rate 2 --pattern username-burst
```

Program łączy się wyłącznie z `127.0.0.1`, bez opcji celu zewnętrznego. Odczytuje numer
protokołu jednym lokalnym status query; przy wyłączonym statusie podać jawnie
`--protocol NUMER`. Obsługuje format LOGIN_START od 1.20.2 (protocol 764).
Używa losowych syntetycznych nicków/UUID, nie cudzych kont. Nie odpowiada na encryption
request, nie wysyła komend, nie wchodzi w PLAY. Kończy po pierwszej odpowiedzi LOGIN,
także po negocjacji kompresji. Nic nie potwierdza jeszcze pełnego loginu premium,
PRE_AUTH ani wykonania `/login` lub `/register`.

Limity: do 200 prób, 8 równoległych zadań/socketów, 20 nowych połączeń/s; domyślnie
20 prób, concurrency 2 i rate 2/s. Okno submitowania trwa najwyżej 45 sekund, timeout
jednego socketu wynosi 2 sekundy. Nie ma nadrabiania zaległych prób burstem ani
nieograniczonej kolejki futures. Raport zawiera rozkład pierwszych odpowiedzi i latencje,
bez treści komunikatów serwera. Całkowity brak odpowiedzi protokołu daje kod wyjścia 1.

Raport domyślny: `build/reports/benchmarks/local-login.json`; dla kolejnych platform
użyć osobnych nazw przez `--output`. Pole `disconnect` oznacza tylko odpowiedź protokołu:
powód odmowy i udział konkretnych guardów trzeba skorelować z lokalnym audytem serwera.
Nick syntetyczny także może wymagać sprawdzenia Mojang; serwer powinien mieć testowy
limit requestów i bounded executory. Nie jest to generator internetowego floodu ani
pełny stress test. Testy harnessu wykonano na lokalnym socketowym responderze, nie
na działającym Minecraft. Bramka testów serwerowych pozostaje otwarta.

## Ręczna macierz konieczna przed release

Na każdej platformie zapisać wersję Java/platformy/JAR-a, konfigurację bez sekretów,
wyniki i audyt. Testy zdalnych baz wykonywać na osobnym schemacie.

| Scenariusz | Oczekiwany wynik |
|---|---|
| Paper/Purpur 26.3 (Paper build 169-beta), rzeczywisty premium i niepoprawna sesja | Official UUID/profile przy sukcesie; DENY bez fallbacku przy błędzie |
| Folia: login/register, timeout, logout, zmiana hasła i shutdown | Brak ownership exceptions; I/O/Argon2 poza entity/region/global |
| Dwa klienty tego samego konta | Maksymalnie jedna ACTIVE; druga próba nie zwalnia izolacji |
| Disconnect podczas DB/HTTP/Argon2 i końcowego admission | Brak reaktywacji, brak utraconych leases i nieskończonych futures |
| Ban dodany podczas auth | Końcowy admission odmawia ACTIVE i zapisuje audit |
| Plugin messages i pluginy teleport/inventory/combat w PRE_AUTH | Brak wpływu na świat ani ominięcia auth |
| Velocity: modern forwarding, reconnect i backend switch | Zgodny zweryfikowany principal; brak zaufania do wiadomości klienta |
| Backend bez proxy, błędny sekret, replay/staff proof spoof | DENY; brak aktywacji lub uprawnień administracyjnych |
| OFFLINE → MOJANG, konflikt UUID/nazwy, awaria migratora | Kontrolowany rollback/recovery, brak utraty danych |
| Awaria połączenia DB w połowie migracji | Jednoznaczny ticket i możliwość restart/retry; brak połowicznej aktywacji |
| Bounded local reconnect/name burst + zwykłe granie | Stabilne pamięć/CPU/MSPT; limity dobierać na podstawie pomiaru |
| Czysta instalacja, upgrade, restart, graceful shutdown | READY tylko po sukcesie zależności; fail-closed przy awarii |

## Otwarte ograniczenie dystrybucji

Workflow publikacji został podniesiony do limitu 45 MiB. Portable Velocity zachowuje
biblioteki natywne dla obsługiwanych systemów; nie odchudzano go przez usuwanie platform.
Ostatni przebieg nie doszedł do publikacji, więc skuteczność uploadu i zgodność limitu
z serwerem BuildExplorer pozostają do potwierdzenia. Build/test nie oznacza publikacji.
Pełne `AuthGatewayApi`, eventy, metryki produkcyjne, rozszerzenia PunisherX oraz końcowy
security review pozostają otwarte. W tym etapie nie zmieniono wersji `1.0.0-WIP` ani
nie zadeklarowano `folia-supported: true`.

## Diagnostyka handshake standalone i migracja do 26.3

Decyzja: aktualny cel Paper to 26.3 build 169-beta, po publikacji
[Paper 26.3](https://papermc.io/news/26-3/). Przypięta wersja pochodzi z
[metadanych dev bundle](https://repo.papermc.io/repository/maven-public/io/papermc/paper/dev-bundle/maven-metadata.xml).
Adapter używa NMS, dlatego ta binarka wymaga 26.3; nie deklarujemy jej zgodności z 26.2.
Purpur/Folia wymagają osobnych prób na zgodnych buildach. Zmiana nie dotyczy API Velocity.

W `config.yml` serwera testowego ustaw i zrestartuj plugin/serwer:

```yaml
premium:
  authentication:
    diagnostics:
      enabled: true
      maximum-lines-per-minute: 120
```

`PremiumHandshakeDiagnostics` zapisuje przez `SyntaxCore.logger` linie `[premium-diag]`
w osobnym daemon threadzie. Netty i tick produkują wyłącznie liczniki oraz nieblokujące
`offer` do kolejki 128 wpisów. Ograniczenie obejmuje 120 szczegółowych linii na minutę
(domyślnie); podsumowanie co 30 s oraz końcowe po opróżnieniu kolejki nie zużywają limitu.
Shutdown nie czeka na logger. Awaria zapisu jest liczona jako `dropped`, nie propaguje się
w stronę logowania. Diagnostyka domyślnie jest wyłączona; nie zmienia globalnego DebugLevel.

Przykładowy format (wartości ilustracyjne, nie pomiar serwera):

```text
[premium-diag] trace=1 event=LOOKUP_PREMIUM elapsed_ms=25 stage_ms=25 active=0/32 lookup_ms=25
[premium-diag] trace=1 event=SLOT_RELEASED elapsed_ms=100 stage_ms=50 active=0/32 handshake_ms=75
```

`trace` jest lokalnym kolejnym identyfikatorem próby, resetowanym po restarcie.
Nie zawiera nicku, IP, UUID, profilu, hasła, tokena, challenge ani treści wyjątków/pakietów.
`elapsed_ms` liczy czas od utworzenia listenera, `stage_ms` od poprzedniego zdarzenia,
`lookup_ms` czas klasyfikacji (łącznie z kolejką/cache), `handshake_ms` czas od zajęcia slotu.
`NATIVE_STATE` zawiera enum `state`, np. KEY lub AUTHENTICATING. Nie jest rejestrowany
każdy tick. Końcowe zdarzenie wycisza dalsze wpisy tej próby.

Guard denial zawiera enum przyczyny (flood, invalid username, behavior, username burst);
podsumowania zawierają osobne liczniki tych przyczyn. Liczniki obejmują guard denial, lookup offline/premium/unavailable, capacity denial,
acquire/release, timeout vanilla, odmowę sesji vanilla i disconnect. Podsumowanie
zachowuje liczniki także przy ograniczeniu tempa (`suppressed`) lub pełnej kolejce
(`dropped`). Nie mierzy TPS, RSS, liczby faktycznych zapytań HTTP ani aktywacji backendu.
`NATIVE_VERIFIED` oznacza opuszczenie KEY/AUTHENTICATING po natywnej weryfikacji,
nie dowód sesji ACTIVE ani zgodności UUID. Po shutdown podsumowanie może nadal obejmować
żywe połączenia, których callback disconnect jeszcze nie nastąpił.

Procedura zebrania danych:

1. Uruchomić świeże środowisko 26.3, zapisać build, Javę, konfigurację limitów i czas próby.
2. Włączyć diagnostykę i wykonać pojedynczy login klientem premium. Zachować komplet
   linii danego trace oraz podsumowanie. Osobno sprawdzić official UUID, ACTIVE i brak
   formularza offline. Negatywna próba musi kończyć się DENY, bez fallbacku.
3. Wykonać timeout/disconnect podczas KEY i AUTHENTICATING. Porównać SLOT_ACQUIRED oraz
   SLOT_RELEASED i sprawdzić `active=0` po zakończeniu wszystkich prób na działającym serwerze.
4. Wykonać bounded harness LOGIN z wcześniejszej sekcji, zebrać podsumowania i raport
   JSON. Przy `suppressed`/`dropped` nie wyliczać percentyli z niekompletnego zbioru linii.
5. Powtórzyć przypadki na Folia/Purpur. Dla Velocity standalone interceptor i te logi
   nie są instalowane; trzeba wykonać osobne testy forwarding/admission.
6. Wyłączyć diagnostykę po pomiarze i zrestartować. Do raportu dołączyć wyłącznie linie
   `[premium-diag]`; inne logi platformy mogą zawierać dane graczy.

Testy JVM obejmują limit i reset tempa, czasy z kontrolowanym zegarem, terminal trace,
pełną kolejkę przy zablokowanym loggerze, wyjątek loggera i tryb wyłączony.
Testy capacity sprawdzają współbieżność oraz brak ujemnego stanu po nadmiarowym release.
Nie zastępują próby protokołowej rzeczywistym klientem.

Końcowa weryfikacja po zmianach diagnostyki i wersji: `./gradlew check build --console=plain`
— sukces; raporty JUnit: 218 przypadków, 214 przeszło, 4 pominięte (zdalne bazy),
0 failures/errors. Nowe testy diagnostyki: 5; dodatkowe testy capacity: 2.
Artefakt Paper: `authgatewayx-paper/build/libs/AuthGatewayX-Paper-1.0.0-WIP.jar`.
Nie uruchamiano serwera 26.3 ani klienta; potwierdzono kompilację i testy JVM.

### Naprawa wyścigu w teście limitu diagnostyki — 2026-10-13

`PremiumHandshakeDiagnosticsTest` oczekiwał na dowolne podsumowanie loggera.
Przesunięcie kontrolowanego zegara o minutę uruchamia również podsumowanie okresowe,
które może zostać zapisane przed opróżnieniem kolejki. Wtedy asercja liczby trace
widziała tylko jeden z dwóch dozwolonych wpisów i losowo zatrzymywała CI.

Test czeka teraz osobno na zapis obu trace i na podsumowanie zawierające końcowe
liczniki `START=1`, `CAPACITY_DENIED=20`, `suppressed=19`. Zachowano asercję dokładnie
dwóch wpisów oraz bounded timeout, bez sleep ani uzależnienia od kolejności threadów.
Zmiana dotyczy wyłącznie testu; auth, limity runtime, kolejka i shutdown są niezmienione.
Nie dodano I/O na threadach gry ani zależności platformowych; brak wpływu na ochronę
premium/PRE_AUTH oraz runtime Paper/Purpur/Folia/Velocity.

- [x] `./gradlew clean buildAll --console=plain` — sukces; raporty JUnit: 221 przypadków,
  0 failures/errors, 4 pominięte kontrakty zdalnych baz bez lokalnej konfiguracji.
- [x] Pięć testów diagnostyki w 10 wymuszonych przebiegach — 50 wykonań, wszystkie
  przeszły (task `test` z `outputs.upToDateWhen { false }` przez tymczasowy init script).


## Naprawa brakujących plików harnessu w CI

Przebieg [38077964789](https://github.com/SyntaxDevTeam/AuthGatewayX/actions/runs/38077964789)
zakończył się po poprawnym `clean check`/shadowJar obu platform: Python nie znalazł
`tools`, ponieważ pliki nie weszły do commita. Do pakietu Git dodano harness, cztery testy,
ten dokument oraz raport wcześniejszego benchmarku. CI sprawdza pliki śledzone po checkout
i odmawia sukcesu przy zerowej liczbie testów. Naprawę weryfikujemy na kopii indeksu Git.
Ponowne uruchomienie starego SHA nie naprawi braku plików: potrzebny jest nowy commit.
W ostatnim przebiegu wykonano także kontrakty zdalnych baz; późniejsze kroki benchmarku,
kontroli zawartości JAR oraz publikacji były pominięte po błędzie Python.

Weryfikacja poprawki lokalnie: cztery testy Python z kopii plików indeksu Git — sukces;
pusta suite — oczekiwana odmowa; `securityBenchmark` — sukces; dokładne kroki kontroli
zawartości JAR-ów Paper i Velocity z workflow — sukces. Nie uruchamiano uploadu ani
zdalnego przebiegu dla poprawki; wymaga on nowego commita i push.


## Stan początkowy migracji — 2026-10-11

Przygotowano generator świeżej bazy SQLite z kontem OFFLINE, offline UUID i prawdziwym
hashem Argon2id, bez wyłączania ochrony nicków premium. Narzędzie działa na test classpath,
poza dystrybucją pluginów. Pełna próba wymaga własnego klienta premium oraz danych
świata/pluginów pod source UUID. [Instrukcja](10-premium-uuid-migration.md#przygotowanie-istniejacego-konta-offline-do-testu-klientem).

Weryfikacja tego etapu: trzy nowe testy generatora przeszły, `buildAll` zakończył się
sukcesem (217 zaliczonych, 4 pominięte zdalne bazy, 0 błędów). Task generatora uruchomiono
na syntetycznym nicku FixtureUser; utworzył nowy katalog i osobną bazę. Sprawdzono brak
klas fixture w obu JAR-ach dystrybucyjnych. Nie wykonywano logowania klientem premium
ani nie modyfikowano serwerów/katalogu run; pełny test migracji nadal pozostaje otwarty.

## Lookup Mojang: kolejka, błędy i cooldown — 2026-10-24

Poprawka obejmuje wspólny `MojangProfileLookup`, usuwanie zakończonych zadań z kolejki
`BoundedTaskExecutor`, diagnostykę async i zamykanie transportu w composition roots
Paper/Folia oraz Velocity. Zasady sesji premium, brak fallbacku, UUID i izolacja
PRE_AUTH pozostają bez zmian. Nie dodano zależności ani operacji HTTP/logowania na
threadzie gry. Wymagany jest restart po podmianie JAR; istniejący config jest zgodny.

- [x] Testy `MojangProfileLookupResilienceTest`: 429, Retry-After w sekundach i dacie,
  wadliwy/nadmierny nagłówek, 503/5xx, DNS/TLS/transport/HTTP timeout, nieprawidłowy
  profil, globalny cooldown i wznowienie po nim, ważny/wygasły cache, TTL/LRU/invalidation.
- [x] Deadline z kolejką, pozostały budżet przekazany do HTTP i pomijanie wygasłej pracy.
- [x] Ponowne sprawdzenie cooldownu po kolejce oraz odrzucenie po przepełnieniu.
- [x] Deduplikacja i niezależne futures: anulowanie lub podmiana wyniku konsumenta nie
  zmienia współdzielonego lookupu ani cache.
- [x] Spóźniony sukces, shutdown podczas HTTP, brak odtworzenia cache po zamknięciu.
- [x] Ograniczanie diagnostyki, licznik pominięć i odporność na wyjątek loggera.
- [x] Test executora: anulowanie natychmiast zwalnia slot dla kolejnego zadania.
- [x] `./gradlew buildAll --offline --console=plain`: sukces; 265 przypadków,
  261 zaliczonych, 0 failures/errors, 4 pominięte kontrakty zdalnych baz bez konfiguracji.
  Oba JAR-y zbudowane; `verifyDistributions` zakończone sukcesem.
- [ ] Rzeczywisty login klientem premium/offline na Paper/Purpur/Folia/Velocity
  oraz sprawdzenie przyczyn dotychczasowych błędów na serwerze administratora.

Symulowane odpowiedzi pochodzą z testowego HttpClient; testy nie wywołują Mojang i nie
potwierdzają bieżącej dostępności zewnętrznej usługi ani poprawności sesji konkretnego
gracza. Konsolowe `invalid session` nadal pochodzi z natywnej weryfikacji sesji Paper.
