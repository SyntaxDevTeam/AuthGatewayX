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

## 13. Okno uwierzytelniania gracza

Na Paper/Purpur/Folia podstawowym interfejsem wpisywania hasła jest natywne Minecraft
Dialog API, a nie chat i nie inventory GUI. Logowanie pokazuje jedno pole tekstowe,
rejestracja dwa pola z potwierdzeniem. Zamknięcie klawiszem Escape jest wyłączone w
PRE_AUTH.

Dialog API nie udostępnia obecnie trybu maskowania znaków pola tekstowego. Wpisane hasło
jest więc widoczne lokalnie na ekranie gracza, ale nie trafia do czatu, historii komend
ani logów. Adapter natychmiast konwertuje odpowiedź do `CharArray`; dalszy pipeline
zeruje tablicę po każdej ścieżce. Ograniczenie klienta musi być opisane administratorowi.

Komendy z hasłem nie powinny być domyślnym fallbackiem, ponieważ trafiają do historii
klienta i mogą zostać przechwycone przez inne pluginy. Ewentualny fallback dla klientów
bez Dialog API wymaga osobnej, jawnej decyzji bezpieczeństwa i nie może być dodany
automatycznie.

## 14. Egzekwowanie PRE_AUTH na Paper/Folia

Wejście gracza offline tworzy sesję `CONNECTING`, natychmiast przełącza ją do
`PRE_AUTH` i dopiero wtedy uruchamia UI. Kwarantanna blokuje ruch, teleporty,
interakcje, bloki, inventory, drop/pickup, obrażenia zadawane i otrzymywane, chat,
portale, pojazdy, zmianę trzymanego przedmiotu, użycie przedmiotu i wszystkie komendy.

Dialog API usuwa potrzebę allowlisty `/login` i `/register`. Blokowanie wszystkich
komend eliminuje obejścia przez aliasy i namespace; `/quit` nie wymaga komendy, ponieważ
klient może rozłączyć się normalnie.

Gracz jest invulnerable, bez kolizji i pickupów oraz wzajemnie ukryty względem graczy
aktywnych. Zmiany player state i timeout korzystają z EntityScheduler. Oryginalne flagi
są przywracane dopiero po atomowym `PRE_AUTH -> ACTIVE`. Disconnect usuwa sesję i
snapshot kwarantanny.

## 15. Limit i feedback PRE_AUTH

Liczba jednoczesnych graczy oczekujących na uwierzytelnienie jest ograniczona przez
`authentication.maximum-pre-auth-players`. Miejsce jest rezerwowane przed utworzeniem
sesji i zwalniane dokładnie raz po aktywacji albo disconnect. Brak miejsca kończy
połączenie przed uruchomieniem storage, HTTP i Argon2.

Formularz nie przyjmuje równoległych submitów tego samego gracza. Wynik błędnego hasła,
blokady, rate-limitera, niezgodnego potwierdzenia lub konfliktu rejestracji jest
wyświetlany bezpośrednio w treści ponownie otwartego Minecraft Dialog. Plugin nie używa
do uwierzytelniania czatu, komend, action bara ani inventory GUI.
