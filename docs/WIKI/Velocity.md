# Velocity — sieć serwerów

**Język / Language:** Polski · [English](EN/Velocity.md)

[Home](Home.md) · [Instalacja](Instalacja.md) · [Zgodność](Zgodnosc.md)

Velocity to wejście do sieci serwerów: gracz łączy się z jednym adresem, a proxy przekazuje go do serwera gry. LoginCoreX ma osobną wersję na Velocity oraz wersję Paper na serwer gry.

Poniższa instrukcja opisuje ustawienia proxy i backendu. Oba moduły są potrzebne do obsługi logowania w sieci.

## Co zainstalować?

Plik LoginCoreX dla Velocity umieść w `plugins` proxy. Plik Paper umieść w `plugins` serwera gry. Sam plugin na proxy nie zapewnia formularzy i ochrony świata na serwerze Paper.

## Ustawienia Velocity

Przy wyłączonym proxy w `velocity.toml` ustaw:

```toml
online-mode = false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
```

LoginCoreX wybiera sposób sprawdzenia konta osobno dla każdego połączenia. Użyj prawdziwego sekretu z pliku wskazanego przez `forwarding-secret-file`.

## Ustawienia serwera Paper

W `server.properties`:

```properties
online-mode=false
```

W istniejącej sekcji `proxies` pliku `config/paper-global.yml`:

```yaml
proxies:
  velocity:
    enabled: true
    online-mode: false
    secret: "TU_WKLEJ_PRAWDZIWY_SEKRET_VELOCITY"
```

Oba miejsca muszą korzystać z tego samego sekretu. Nie zostawiaj tekstu przykładowego i nie publikuj wartości. `online-mode` w tej sekcji ma odpowiadać ogólnemu ustawieniu Velocity.

Włączona obsługa Velocity na Paper pozwala LoginCoreX rozpoznać, że działa za proxy. Na samodzielnym serwerze pozostaje wyłączona.

## Zamknij bezpośrednie wejście na serwer gry

W panelu hostingu lub zaporze sieciowej dopuść do portu Paper tylko połączenia z zaufanego proxy. Gracze mają wchodzić adresem Velocity. Sam sekret nie zastępuje ograniczenia dostępu do tego portu.

## Config pluginu na Velocity

W folderze danych LoginCoreX na proxy znajduje się `logincorex.yml`. To osobny plik od `config.yml` na Paper.

| Opcja | Domyślnie | Znaczenie |
| --- | --- | --- |
| `premium.lookup.timeout-millis` | `3000` | Łączny czas kolejki i sprawdzenia nicku |
| `premium.lookup.positive-ttl-seconds` | `21600` | Czas pamiętania oficjalnego profilu Minecraft |
| `premium.lookup.negative-ttl-seconds` | `300` | Czas pamiętania braku oficjalnego profilu Minecraft |
| `premium.lookup.maximum-cache-size` | `50000` | Limit zapamiętanych wyników |
| `connections.admission-timeout-millis` | `20000` | Maksymalny czas asynchronicznej weryfikacji przed logowaniem (mniej niż limit Velocity) |
| `connections.pending-ttl-seconds` | `30` | Jak długo czeka decyzja dotycząca rozpoczętego połączenia |
| `connections.maximum-pending` | `10000` | Maksymalna liczba oczekujących decyzji |
| `executors.mojang-threads` | `4` | Liczba jednoczesnych zadań sprawdzania nicków |
| `executors.mojang-queue` | `128` | Maksymalna liczba zadań czekających |

Przy `storage.enabled: true` schemat URL JDBC musi odpowiadać rzeczywistemu silnikowi:
`jdbc:mysql://...` dla MySQL, `jdbc:mariadb://...` dla MariaDB,
`jdbc:postgresql://...` dla PostgreSQL oraz `jdbc:sqlite:...` dla SQLite.
LoginCoreX jawnie wybiera klasę sterownika na podstawie tego schematu zamiast
polegać wyłącznie na automatycznym ServiceLoaderze classloadera Velocity.

Po zmianach wykonaj restart odpowiedniego serwera lub proxy.

## Próba działania

Sprawdź rejestrację i logowanie obu rodzajów tożsamości przez Velocity, ponowne połączenie oraz odmowę wejścia bezpośrednio na Paper spoza proxy. Przetestuj również zmianę serwera, jeśli Twoja sieć z niej korzysta. Nie zakładaj, że konto raz zalogowane będzie automatycznie zalogowane na każdym serwerze — wspólne sesje całej sieci nie są gotową funkcją tego wydania.

Komunikat `Backend server is online-mode` może oznaczać, że serwer gry używa niewłaściwego trybu. Sprawdź `online-mode=false` w `server.properties`, włączenie Velocity w `paper-global.yml` i zgodność sekretu.

## VPN i multi-konta na proxy

Velocity ma własne ustawienia w `logincorex.yml`: `storage.enabled` włącza odczyt
wspólnej bazy Paper, `multi-account.action` wybiera `ALERT`, `DENY` lub `DISABLED`.
`ip-intelligence.enabled` włącza VPN/proxy/Tor (domyślna akcja `DENY`);
`show-geo` dodaje kraj i ASN. Kontrole odrzucają połączenie przed serwerem gry.
Obie integracje są domyślnie wyłączone; brak wspólnej historii nie wykryje zmiany IP.

`alerts.enabled`, `alerts.console` i `alerts.cooldown-seconds` sterują powiadomieniami
proxy. Alert dotyczy próby wejścia pod nickiem, nie potwierdzonego logowania hasłem.
Nadaj na proxy `logincorex.admin.alerts` oraz `logincorex.admin.alts` dla raportu
`/logincorex alts <nick>`. Pozostałe podkomendy trafiają do Paper.
Administrator z lokalnie przypisanym UUID wymaga poprawnego zalogowania na backendzie i wspólnego,
osobnego `staff-proof.secret` (co najmniej 32 bajty) na Paper i proxy.
Konsola i administrator uwierzytelniony przez Mojang nie potrzebują tego sekretu.

Pełne ustawienia, zachowanie przy awarii i kolejność wdrożenia:
[Konfiguracja Velocity](Velocity.md). Opcje alertów opisane
powyżej dla Paper pozostają lokalne; można je wyłączyć, aby nie dublować powiadomień.

## Diagnostyka startu i plik języka

`language: PL` należy do `logincorex.yml`. Adapter MessageHandler odczytuje ten
plik i nie tworzy dodatkowego `config.yml`. Istniejący stary plik pozostaje bez zmian;
gdy w głównym pliku brakuje `language`, jego dotychczasowa wartość jest nadal odczytywana.
Po przeniesieniu `language` do `logincorex.yml` można usunąć stary `config.yml`.

`shared history is unavailable; proxy admission remains closed` oznacza błąd
otwarcia wspólnej bazy lub sprawdzenia wymaganych tabel/kolumn i uprawnień SELECT.
Log zawiera teraz klasę przyczyny, SQLState, kod sterownika i wskazówkę bez ujawniania
hasła czy URL połączenia. Diagnostyka `JDBC driver is unavailable` oznacza brak klasy
sterownika w dystrybucji albo niezgodny schemat JDBC; nie jest klasyfikowana jako awaria
sieci tylko dlatego, że sterownik zwrócił SQLState `08001`. Najpierw sprawdź, czy Paper uruchomił migrację v5
w tej samej bazie. Oddzielna pusta baza proxy nie wystarczy. Przy zdalnym proxy
`127.0.0.1` wskazuje maszynę proxy, nie serwer Paper. Po poprawieniu ustawień wykonaj restart.
Sprawdzenie startowe obejmuje zarówno historię, jak i tabelę kont; proxy nie wykonuje migracji.

### Powtarzana odmowa LCX-STARTUP

Odmowa `Authentication service is unavailable. [LCX-STARTUP]` oznacza brak READY,
przed kontrolą VPN i Mojang. Przy próbie wejścia konsola powtarza zapamiętany etap
STARTING lub FAILED i bezpieczny opis przyczyny, najwyżej raz na 10 sekund dla całego
proxy. Etap DATABASE oznacza oczekiwanie na wspólną bazę albo jej błąd; CONFIGURATION,
MESSAGES, STAFF_PROOF i LISTENERS wskazują inne części startu. Pełny wyjątek nadal
znajduje się w logu inicjalizacji (dla JDBC stosowana jest diagnostyka bez sekretów).
Startup podaje bezwzględną ścieżkę używanego `logincorex.yml` i stan włączenia
integracji. Edycja pliku `src/main/resources` nie aktualizuje istniejącego pliku proxy.
Jeżeli po pełnym restarcie brak znacznika zarówno przy starcie, jak i w odmowie,
należy sprawdzić, jaki JAR i która instancja proxy rzeczywiście obsługują połączenie.
Nie omijamy wymaganej inicjalizacji przez wymuszenie READY.

## Sesja proxy i czas logowania

Ponowne połączenie gracza tworzy nową sesję proxy; zmiana backendu za tym samym Velocity zachowuje sesję. Jej identyfikator służy diagnostyce i nie daje uprawnień. Tryb `OFFLINE` w raporcie proxy nie oznacza, że gracz podał już poprawne hasło na Paper.

Velocity domyślnie rozłącza logowania trwające ponad 30 sekund
(`-Dvelocity.login-timeout`). LCX ma odrębny, krótszy domyślny deadline
20 sekund (`connections.admission-timeout-millis`), aby zakończyć własne
futures i zwolnić slot przed limitem proxy. Nie ustawiaj go większego niż
limit login-timeout. Powolne zewnętrzne usługi powinny kończyć się odmową,
a nie bezterminowo blokować przyjęcia kolejnych graczy.

Ochrona Velocity 4.3.0 przed nadmiernymi pakietami i floodem działa **w samym
proxy**, niezależnie od filtrów LoginCoreX. LCX utrzymuje swoje limity
weryfikacji i ryzyka, nie próbuje zastępować mechanizmów Netty ani modyfikować
limitów pakietów Velocity. Przy aktualizacji zrób test rejestracji i logowania każdego gracza,
ponownego połączenia oraz przełączenia backendu.

### Komenda diagnostyczna sesji proxy

`/logincorex session <nick>` (uprawnienie Velocity `logincorex.admin.session`)
wyświetla identyfikator bieżącej sesji, tryb weryfikacji proxy oraz nazwę
backendu. Tak jak raport `alts`, wymaga potwierdzonej tożsamości
administratora: dla lokalnie przypisanego konta weryfikacji `staff-proof` przez Paper.
Nie przekazuje hasła ani sekretów; identyfikator sesji nie jest kluczem
autoryzacyjnym. Działa tylko dla graczy aktualnie połączonych z proxy.

### Ważne: wymiana sekretu staff-proof

Starsze przykładowe pliki konfiguracyjne zawierały ten sam, publicznie
znany sekret `staff-proof`. **Nie używaj go w produkcji.** Wygeneruj
własną losową wartość (co najmniej 32 bajty, najlepiej 32 bajty z CSPRNG
zakodowane hex/base64), ustaw identyczną na Velocity i Paper lub przekaż
ją obu procesom przez `LOGINCOREX_STAFF_PROOF_SECRET`.
W nowych instalacjach ustawienie jest domyślnie puste: pracownicy z lokalnie przypisaną tożsamością
nie przejdą proof, dopóki administrator świadomie nie włączy bezpiecznej
integracji. Zmiana szablonu nie modyfikuje istniejącego pliku na serwerze:
sekret użyty wcześniej należy wymienić ręcznie na każdej instancji.
Nie utożsamiaj tego sekretu z Velocity forwarding secret.

## Problemy ze sprawdzaniem nicków

Proxy korzysta z tej samej ochrony zapytań co Paper: respektuje `Retry-After`, robi
krótką przerwę po błędach usługi i nadal używa ważnego cache. Czas
`premium.lookup.timeout-millis` obejmuje kolejkę i HTTP, niezależnie od dłuższego
limitu całego przyjęcia połączenia. Wpisy `[mojang-lookup]` opisano w [Pomocy](Pomoc.md).

## Diagnostyka połączeń

W pliku `logincorex.yml` pluginu na Velocity można włączyć:

```yaml
connections:
  diagnostics: true
```

Dodaj klucz do istniejącej sekcji `connections` i uruchom ponownie proxy.
Wpisy `[login-diag]` zawierają nick i identyfikator sesji (na starszym Velocity może być `null`),
wynik `LOOKUP_PREMIUM` / `LOOKUP_NOT_PREMIUM` / `LOOKUP_UNAVAILABLE`,
wybrany tryb `MOJANG` / `OFFLINE`, wynik admission oraz dostępne zdarzenia LOGIN i DISCONNECT.
Nie zawierają IP, haseł ani tokenów. Domyślnie diagnostyka jest wyłączona.
Limit wynosi 120 wpisów na minutę dla całego proxy; nadmiar i przepełniona kolejka są pomijane.
Brak późniejszego wpisu nie dowodzi błędnej sesji: klient może przerwać połączenie,
a natywne odrzucenie przed LOGIN nie musi wywołać zdarzenia DISCONNECT pluginu.
`MOJANG` oznacza wymaganą weryfikację, a nie jej sukces.
Po teście ustaw `diagnostics: false` i zrestartuj proxy.
