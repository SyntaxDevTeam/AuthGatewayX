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

Implementacja Paper posiada osobne blokady eventów również dla wiader, książek i
lecternów, armor standów, strzyżenia, wędkowania, pocisków i hanging entities. Dla
`PlayerCommandSendEvent` usuwa wszystkie sugestie komend w stanie `PRE_AUTH`.
Test regresyjny powierzchni listenera weryfikuje komplet wymaganych handlerów, ich
priorytet `HIGHEST` oraz zachowanie wcześniejszego anulowania. Pełne checkboxy ochrony
świata pozostają otwarte do testu na rzeczywistym Paper/Purpur/Folia.

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

# 17. Zaimplementowana ochrona haseł i rejestracji

`Argon2PasswordHasher` jawnie wybiera wariant Argon2id. Domyślne parametry pierwszego
wydania implementacji to 3 iteracje, 65536 KiB pamięci i równoległość 1; przed wydaniem
1.0.0 wymagają benchmarku na wspieranych platformach. Hasła są przyjmowane jako
`CharArray` i zerowane po hash/verify, także na ścieżkach błędów.

`RegistrationService` przekazuje Argon2 do osobnego bounded executora. Atomowość
rejestracji nie opiera się na wcześniejszym `SELECT`: gwarantują ją ograniczenia UNIQUE
w storage, a konflikt jest mapowany na neutralny wynik domenowy. Szczegóły te nie mogą
być bezpośrednio ujawniane graczowi, aby nie ułatwiać enumeracji kont.

# 18. Zaimplementowany login limiter i lockout

`LoginAttemptGate` jest ograniczonym rozmiarem token bucket per IP i musi być wywołany
przed lookupem konta. `LoginService` używa dummy Argon2id hash dla nieistniejącej nazwy
i zwraca wspólny wynik `InvalidCredentials`. Szczegółowy stan istnieje wyłącznie dla
logiki wewnętrznej i audytu.

Licznik błędów jest zwiększany atomowo w storage. Domyślny próg domenowy to 5 prób, a
blokada trwa 10 minut. Pomyślne logowanie zeruje licznik oraz `locked_until`. Próby
odrzucone przez limiter nie wykonują JDBC ani Argon2 i również zerują wejściową tablicę
hasła.

Zmiana hasła używa Argon2id na bounded executorze. Ścieżka własna weryfikuje obecny
hash i zapisuje nowy przez compare-and-set, więc równoległa zmiana nie może nadpisać
nowszego hasła. Administracyjny reset wymaga osobnej permisji, jest audytowany jako
`ADMIN_PASSWORD_RESET` i rozłącza aktywną sesję celu. Hasła są przekazywane wyłącznie
przez Dialog API i zerowane na wszystkich ścieżkach zakończenia.

`LogoutService` dopuszcza `/logout` tylko dla aktywnej tożsamości `OFFLINE`. Najpierw
usuwa sesję i indeksy concurrent-login, następnie emituje audit
`SESSION_INVALIDATED/PLAYER_LOGOUT`, a adapter Paper rozłącza gracza przez
EntityScheduler. Konto premium nie jest degradowane ani przekształcane przez logout.

# 19. Zaimplementowana ochrona nicków na Paper standalone

Przed lookupem konta offline wykonywany jest ograniczony i deduplikowany lookup profilu
Minecraft Services. Odpowiedź potwierdzająca konto premium kieruje połączenie do
natywnego szyfrowanego handshake Paper i weryfikacji Mojang Session Server. Timeout,
przeciążenie kolejki, 429, 5xx i błąd transportu kończą się DENY. Wyłącznie jednoznaczny
brak profilu pozwala przejść do formularza offline.

Niezależny limit `premium.authentication.maximum-concurrent-handshakes` ogranicza liczbę
równoległych kryptograficznych weryfikacji sesji. Nieudane uwierzytelnienie premium nie
jest zastępowane fallbackiem offline. W trybie standalone adapter protokołu wykonuje
`ConnectionFloodGate`, walidację nazwy, `ConnectionBehaviorGate` i `UsernameBurstGate`
bezpośrednio po `ServerboundHelloPacket`, przed pierwszym `MojangProfileLookup`.
`AsyncPlayerPreLoginEvent` nie nalicza tych samych stanowych guardów drugi raz; po
aktywacji ownership protokołu wykonuje jedynie tanią walidację nazwy i kontrolę
readiness. W trybie Velocity-forwarded ownership pozostaje przy pre-login evencie.

Jeżeli Mojang Session Server nie potwierdzi sesji chronionego nicku, adapter zastępuje
wyłącznie vanilla `multiplayer.disconnect.unverified_username` komunikatem
`auth.premium_session_invalid` z MessageHandler. Gracz widzi, że decyzję egzekwuje
AuthGatewayX, dlaczego nick wymaga premium oraz jak odnowić sesję. Inne klasy błędów nie
są maskowane tym tekstem, a ścieżka nadal kończy się DENY bez fallbacku offline.

# 20. Admission control PRE_AUTH

`PreAuthAdmission` opiera się na `PreAuthCapacity` i przechowuje pojedynczy lease per
UUID połączenia. Limit jest sprawdzany przed utworzeniem sesji i uruchomieniem UI.
Lease jest zwalniany przy `PRE_AUTH -> ACTIVE`, disconnect lub błędzie tworzenia sesji.
Kontroler dialogów dodatkowo dopuszcza najwyżej jedną kosztowną operację hasłową per
gracz, co ogranicza spam custom-clickami i tworzenie równoległych zadań Argon2.

# 21. Username burst i reconnect-loop guard

`UsernameBurstGate` wykrywa próby wielu różnych nicków z jednego IP przed Minecraft
Services, JDBC i Argon2. Po przekroczeniu limitu nakłada czasową kwarantannę. Rejestr
jest ograniczony rozmiarem, wpisy mają politykę wygaszania, a stan jest czyszczony przy
shutdownie. Gate nie przechowuje haseł ani trwałych danych kont.

Disconnect jest zliczany tylko dla gracza pozostającego w PRE_AUTH. Seria takich
rozłączeń w jednym oknie kończy się tą samą czasową kwarantanną, zanim kolejna próba
dotrze do HTTP, JDBC lub Argon2. Zwykłe rozłączenie sesji ACTIVE nie wpływa na licznik.

Jest to nadal część warstwowego anti-bot. Ważony scoring jest już zaimplementowany i
spięty z tym gate'em, natomiast checkbox reconnect loop pozostaje otwarty do testów
obciążeniowych na rzeczywistym serwerze.

# 22. Registration attempt rate limiting

Próby rejestracji posiadają osobny bounded token bucket per IP. Guard jest wykonywany
przed Argon2id i JDBC, więc spam formularzem nie tworzy kosztownych zadań. Stan ma TTL,
limit adresów i jest czyszczony podczas shutdownu. Zarówno rate limit, jak i brak miejsca
na śledzenie są obsługiwane fail-closed oraz audytowane bez hasła i hasha.

SQLite egzekwuje dodatkowo trwały limit liczby kont na IP. Konto i rekord slotu IP są
tworzone w jednej transakcji; brak wolnego slotu powoduje rollback. Unikalny klucz
`(source_ip, slot)` serializuje decyzję także dla równoległych prób, a migracja v4
zachowuje historię istniejących kont. Odmowa jest audytowana jako
`ANTI_BOT_DENY/REGISTRATION_ADDRESS_LIMIT` bez sekretów. Checkbox całej ochrony
pozostaje otwarty do testu obciążeniowego oraz implementacji równoważnej polityki w
przyszłych backendach MySQL/MariaDB/PostgreSQL.

# 23. Ważony behavioral scoring połączeń

`ConnectionBehaviorGate` łączy kilka tanich sygnałów per IP zamiast podejmować decyzję
na podstawie pojedynczego licznika: każde połączenie, kolejne różne nazwy w oknie,
nieudane wyniki logowania/rejestracji i disconnect w PRE_AUTH. Domyślne wagi wynoszą
odpowiednio `1`, `5`, `8` i `8`, a próg `40` nakłada dziesięciominutową kwarantannę.

Stan nie zawiera haseł ani trwałych danych kont, ma limit adresów, resetowane okno,
wygaszanie i jawne zachowanie fail-closed po wyczerpaniu pojemności. Sygnał z późnego
callbacku nie alokuje nowego stanu. Scoring jest wykonywany przed storage i Argon2 oraz
współdziała z ostrzejszym `UsernameBurstGate`, reconnect guardem i token bucketami.

W Paper standalone pierwsze naliczenie `CONNECTION` i `DISTINCT_USERNAME` odbywa się w
LOGIN adapterze przed `MojangProfileLookup`. `AsyncPlayerPreLoginEvent` nie wykonuje
ponownego naliczenia tego samego połączenia. W Paper za Velocity pre-login event nadal
jest właścicielem tych guardów. Pozycja `connection scoring` pozostaje niezaznaczona
wyłącznie do wymaganego testu obciążeniowego/reconnect-flood na serwerze.
