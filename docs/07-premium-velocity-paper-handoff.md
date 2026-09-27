# AuthGatewayX 1.0.0 — premium handoff Velocity -> Paper

## 1. Problem

Sam lookup nazwy w Minecraft Services odpowiada wyłącznie na pytanie, czy dana nazwa
jest aktualnie nazwą konta premium. Nie jest dowodem, że bieżące połączenie należy do
właściciela tego konta.

Poprzednia implementacja Paper wykonywała lookup po `PlayerJoinEvent` i dla każdego
wyniku `PREMIUM` odrzucała gracza komunikatem o wymaganym gatewayu premium. Nie
sprawdzała, czy Velocity właśnie wykonało dla tego samego połączenia prawidłowe Mojang
online authentication. W efekcie także prawidłowo uwierzytelniony gracz premium był
odrzucany.

## 2. Inwariant bezpieczeństwa

AuthGatewayX nie może uznać gracza za premium tylko dlatego, że jego nick istnieje w
Minecraft Services.

Dla ścieżki Velocity -> Paper obowiązuje:

```text
lookup nazwy -> official Mojang UUID
                    |
                    v
UUID przekazany do Paper przez zaufany Velocity forwarding
                    |
             +------+------+
             |             |
          zgodny         różny
             |             |
             v             v
   MOJANG VERIFIED        DENY
```

Nie istnieje fallback `PREMIUM -> OFFLINE` po niezgodności UUID.

## 3. Velocity

`VelocityLoginListener` wybiera tryb przed uwierzytelnieniem:

```text
PREMIUM     -> forceOnlineMode()
NOT_PREMIUM -> forceOfflineMode()
```

`forceOnlineMode()` na proxy działającym globalnie w offline mode uruchamia normalny
proces szyfrowania i Mojang Session Server. Po sukcesie Velocity posiada oficjalny
`GameProfile`, w tym oficjalny Minecraft UUID.

Do backendu ten profil musi zostać przekazany przez Velocity modern forwarding. Modern
forwarding podpisuje dane współdzielonym sekretem i przekazuje m.in. IP, UUID, nazwę i
properties profilu.

## 4. Paper

Paper nadal rozpoczyna od krótkiego `PRE_AUTH`, aby do czasu rozwiązania tożsamości nie
udostępnić świata. `MojangProfileLookup` zwraca teraz nie tylko status `PREMIUM`, ale
również oficjalny UUID z odpowiedzi Minecraft Services.

Dla nazwy premium Paper porównuje:

```text
player.uniqueId == officialMojangUuid
```

Znaczenie tego porównania jest następujące:

- zgodność — backend otrzymał ten sam profil, który Minecraft Services przypisuje do
  nazwy; połączenie może przejść ścieżką `MOJANG`,
- niezgodność — backend otrzymał offline UUID albo inną tożsamość; połączenie jest
  odrzucane,
- niedostępność Minecraft Services lub niepoprawna odpowiedź — fail closed.

Samo porównanie UUID jest traktowane jako dowód wyłącznie w konfiguracji, w której
backend przyjmuje informacje gracza przez zaufany Velocity modern forwarding. Nie jest
to zamiennik poprawnego forwarding secret ani ochrony portu backendu.

## 5. Wymagana konfiguracja proxy/backend

Dla hybrydowego AuthGatewayX, gdzie Velocity jest globalnie w offline mode i plugin
wybiera online/offline per połączenie, konfiguracja powinna mieć co najmniej:

### Velocity

```toml
online-mode = false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
```

### Paper — `server.properties`

```properties
online-mode=false
```

### Paper — `config/paper-global.yml`

```yaml
proxies:
  velocity:
    enabled: true
    online-mode: false
    secret: "TEN_SAM_SEKRET_CO_VELOCITY"
```

Wartość `proxies.velocity.online-mode` musi odpowiadać globalnemu `online-mode` proxy,
a nie per-player decyzji `forceOnlineMode()`.

Port backendu Paper powinien być dodatkowo odcięty firewallem od bezpośrednich
połączeń spoza zaufanego proxy. Forwarding secret nie powinien być publikowany ani
commitowany do repozytorium.

## 6. Konto i sesja po poprawnym handoff

Po zgodności UUID istnieją teraz dwa różne przypadki.

### Nowe lub już zmigrowane konto premium

Jeżeli nazwa nie posiada konta `OFFLINE` albo istniejący rekord jest już `MOJANG`,
`VerifiedMojangAuthenticationService` może związać/odświeżyć oficjalną tożsamość,
utworzyć sesję `MOJANG` i zwolnić PRE_AUTH.

### Istniejące konto OFFLINE o tej samej nazwie

Samo poprawne Mojang authentication **nie wykonuje już automatycznej podmiany UUID**.
Storage zwraca `MigrationRequired`, a Paper otwiera osobny dialog migracyjny.

Gracz musi potwierdzić własność poprzedniego konta hasłem AuthGatewayX. Weryfikacja
korzysta z tego samego `LoginService`, Argon2id, rate limitera i lockoutu co zwykłe
logowanie offline. Po sukcesie tworzony jest trwały rekord `identity_migrations`
ze stanem `PREPARED`, zawierający:

- niezmienny `account_id`,
- poprzedni UUID offline,
- docelowy oficjalny UUID Mojang,
- nazwę konta,
- adres źródłowy i czas przygotowania.

Na tym etapie rekord `accounts` **pozostaje OFFLINE**: stary UUID i hash hasła nie są
usuwane. Jest to celowe. Finalizacja może nastąpić dopiero po bezpiecznym przeniesieniu
danych zależnych od UUID (vanilla i pluginy) przez kontrolowane migratory.

Storage udostępnia osobne przejścia:

```text
PREPARED
    -> MIGRATING
    -> COMPLETED

PREPARED/MIGRATING
    -> FAILED
```

`completePremiumMigration(...)` wykonuje compare-and-set względem `account_id`,
starego UUID i typu `OFFLINE`. Dopiero wtedy atomowo zmienia konto na `MOJANG`,
ustawia docelowy UUID, usuwa `password_hash`, resetuje lockout i zwalnia slot
rejestracyjny. Konflikt docelowego UUID lub zmiana stanu konta kończy się fail-closed.

Migracja v7 dodaje również `account_identities`, aby przyszłe zmiany tożsamości nie
nadpisywały jedynego śladu poprzedniego UUID.

Po potwierdzeniu hasła gracz jest rozłączany, a migracja wykonuje się poza aktywną sesją.
Wbudowany provider vanilla robi backup i przenosi `playerdata`, statystyki i advancementy.
Pozostałe pluginy mogą rejestrować `IdentityMigrationProvider` przez ServicesManager.

AuthGatewayX nie wykonuje ogólnego search/replace UUID. Lokalny skaner niezarządzanych
katalogów pluginów wyszukuje stare UUID (także w postaci surowych 16 bajtów) i blokuje
finalizację, jeżeli znajdzie dane bez providera. Limit plików i bajtów jest ograniczony
konfiguracją; przekroczenie limitu również kończy się fail-closed. Zdalnych baz innych
pluginów skaner nie może poznać — takie integracje wymagają jawnego providera.

Providery są idempotentne i mają rollback. Jeżeli którykolwiek krok lub końcowy CAS
AuthGatewayX zawiedzie, wykonane providery są wycofywane w odwrotnej kolejności, a konto
pozostaje OFFLINE.
## 7. Konflikty

Fail closed obowiązuje między innymi gdy:

- oficjalny UUID różni się od UUID widzianego przez Paper,
- ten sam nick jest już związany z innym kontem `MOJANG`,
- oficjalny UUID jest związany z innym, kolidującym kontem,
- lookup Minecraft Services nie jest dostępny,
- persistence/session activation kończy się błędem.

Zmiana nazwy konta premium może zostać automatycznie odzwierciedlona tylko wtedy, gdy
oficjalny UUID wskazuje już istniejące konto `MOJANG`, a nowa nazwa nie koliduje z innym
rekordem.

## 8. Diagnostyka offline UUID

Jeżeli po `forceOnlineMode()` Paper nadal loguje UUID odpowiadający formule
`OfflinePlayer:<nick>`, oznacza to, że zweryfikowany profil premium nie został poprawnie
przekazany do backendu. W takiej sytuacji AuthGatewayX ma odrzucić połączenie, a nie
obchodzić ochronę nicku.

Należy wtedy sprawdzić przede wszystkim:

- `player-info-forwarding-mode = "modern"` na Velocity,
- zgodność forwarding secret,
- `proxies.velocity.enabled: true` na Paper,
- zgodność `proxies.velocity.online-mode` z globalnym `online-mode` Velocity,
- czy gracz faktycznie wchodzi przez Velocity, a nie bezpośrednio na port Paper.

## 9. Zakres tej decyzji

Ten handoff rozwiązuje ścieżkę sieciową Velocity -> Paper/Purpur/Folia. Samodzielny
backend nie korzysta z handoffu: posiada osobny interceptor LOGIN, który dla profilu
premium uruchamia natywne szyfrowanie Paper i weryfikację Mojang Session Server.
Obie ścieżki kończą się tym samym domenowym wiązaniem zweryfikowanego oficjalnego UUID,
ale mają odrębne granice zaufania. Adapter standalone wymaga jeszcze testu rzeczywistym
klientem premium przed oznaczeniem pełnej funkcji jako zweryfikowanej.

Jeżeli standalone handshake dojdzie do weryfikacji sesji, lecz Minecraft Services jej
nie potwierdzi, AuthGatewayX zastępuje ogólny angielski kick vanilla własnym komunikatem
`auth.premium_session_invalid`. Informuje on, że nick jest chroniony jako premium i że
klient powinien zalogować się na właściwe konto Microsoft/Mojang w launcherze oraz
uruchomić grę ponownie. Sam brak potwierdzenia nie pozwala serwerowi rozstrzygnąć, czy
przyczyną była wygasła sesja, złe konto, wylogowanie czy launcher offline.

## 10. Automatyczny wybór trybu Paper

Paper nie może jednocześnie wykonywać standalone premium encryption handshake i być
backendem Velocity modern forwarding. Gdy backend za proxy wysyła `Encryption Request`,
Velocity interpretuje go jako próbę pracy backendu w online mode i zamyka połączenie
błędem `Backend server is online-mode`.

AuthGatewayX wybiera więc tryb Paper na podstawie efektywnej konfiguracji Paper:

```text
proxies.velocity.enabled = true
    -> VELOCITY_FORWARDED
    -> NIE instaluj StandalonePremiumProtocolInterceptor
    -> premium identity przez modern forwarding + zgodność UUID

proxies.velocity.enabled = false
    -> STANDALONE_PROTOCOL
    -> instaluj StandalonePremiumProtocolInterceptor
    -> natywny Paper encryption + Mojang Session Server
```

Sprawdzana jest efektywna wartość `GlobalConfiguration.get().proxies.velocity.enabled`,
czyli stan po walidacji konfiguracji Paper. Samo `online-mode=false` w
`server.properties` nie wystarcza do rozpoznania backendu proxy, ponieważ jest ono
wymagane również przez tryb standalone AuthGatewayX.

Dla konfiguracji Velocity + Paper poprawny startup backendu powinien logować tryb
`Velocity modern forwarding; standalone Paper encryption interceptor disabled`. Jeśli
Paper wybiera tryb standalone mimo pracy za Velocity, należy traktować to jako błąd
konfiguracji forwarding i sprawdzić `paper-global.yml` oraz forwarding secret.

## Kontrole ryzyka i administracyjne potwierdzenie sesji

Opcjonalna odmowa VPN/multi-konta następuje już w PreLoginEvent proxy. Nie zmienia
reguły Mojang ani integralności UUID opisanej powyżej. Oddzielny kanał podpisanego
potwierdzenia ACTIVE służy wyłącznie dostępowi administratora offline do raportów
i alertów; nie uwierzytelnia gracza i nie zwalnia PRE_AUTH. Wymaga osobnego sekretu.
Zobacz [konfigurację i granice zaufania](09-proxy-risk-admission.md).
