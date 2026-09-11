# VPN, multi-konta i alerty na proxy Velocity

## Stan i podział odpowiedzialności

Decyzja z 2026-09-11 rozszerza wcześniejszy priorytet Paper na wymagane admission
Velocity. `VelocityLoginListener` wykonuje kontrolę w `PreLoginEvent`, zanim proxy
ustanowi połączenie z backendem. `DENY` nie jest kickiem wykonywanym dopiero na Paper.

- Paper standalone: dotychczasowe raporty i alerty po poprawnym auth offline.
- Velocity: VPN/proxy/Tor, kraj/ASN, kontrola historii IP, komenda i alerty sieciowe.
- Paper za Velocity: nadal odpowiada za hasło, PRE_AUTH, zapis historii po sukcesie
  i opcjonalne potwierdzenie uprawnień administracyjnych proxy.

Proxy nie twierdzi, że niezweryfikowany nick jest tożsamością właściciela. Alert brzmi
„połączenie pod nickiem”; dopiero backend weryfikuje hasło. Samo wejście nie zapisuje
historii IP. Nie da się powiązać całkiem nowego nicku i nowego VPN bez wspólnego śladu.

## Konfiguracja proxy — `plugins/authgatewayx/authgatewayx.yml`

Przykład włączenia (uzupełnij połączenie do tej samej bazy co Paper):

```yaml
storage:
  enabled: true
  jdbc-url: "jdbc:mysql://127.0.0.1:3306/authgatewayx"
  username: "authgatewayx_reader"
  password: ""
multi-account:
  action: ALERT
ip-intelligence:
  enabled: true
  api-key: ""
  action: DENY
  show-geo: true
  timeout-millis: 2000
  cache-ttl-seconds: 3600
  failure-ttl-seconds: 60
  maximum-cache-size: 10000
  maximum-concurrent: 2
  requests-per-minute: 30
risk:
  failure-strategy: FAIL_CLOSED
  maximum-concurrent: 16
alerts:
  enabled: true
  console: true
  cooldown-seconds: 300
staff-proof:
  secret: ""
```

`DISABLED` pomija dany mechanizm, `ALERT` informuje, `DENY` odrzuca. Multi-konta
sprawdzane są tylko dla nazw wybranych jako OFFLINE; VPN dla obu trybów. Multi-account
DENY dotyczy dowolnego innego konta offline znanego z bieżącego IP w ostatnich 30 dniach,
nie tylko kont o zbliżonych nazwach. Może odrzucić rodzeństwo lub wspólną sieć NAT/VPN.
Własny deklarowany nick jest pomijany w wynikach; nie daje to zwolnienia z hasła.

`FAIL_CLOSED` odrzuca połączenie także przy nieznanym wyniku, timeoutach, przeciążeniu,
wyczerpaniu budżetu API i brakujących polach klasyfikacji. `FAIL_OPEN` dopuszcza takie
połączenie i może wysłać komunikat niedostępności, nie traktując wyniku jako czystego.
Wyłączenie alertów nie wyłącza kontroli DENY. Kraj/ASN nigdy samodzielnie nie blokuje.
Adresy lokalne/prywatne pomijane przez dostawcę dają wynik nieznany — uwzględnij
`failure-strategy` przy lokalnych testach. Listy wyjątków IP nie są częścią tej zmiany.

Nowe instalacje oraz aktualizowane stare konfiguracje mają domyślnie
`storage.enabled=false` i `ip-intelligence.enabled=false`. Nie da się automatycznie
odgadnąć bazy backendu ani zgody na udostępnienie IP dostawcy.

## Wspólna baza

Proxy używa `ConnectionAccountLookup`: wyłącznie odczytuje istniejące tabele `accounts`
i `offline_account_addresses`. Najpierw uruchom zaktualizowany Paper, aby wykonał
migrację v5. Proxy sprawdza obecność historii, ale samo nie wykonuje migracji.
Włączony storage z niedostępną/brakującą historią pozostawia proxy niegotowe fail-closed
niezależnie od runtime `failure-strategy` — to błąd instalacji.

Preferuj wspólny MySQL/MariaDB/PostgreSQL oraz użytkownika z prawem SELECT. SQLite
jest możliwe tylko przez ten sam lokalny plik na tym samym hoście; nie twórz drugiej,
niezależnej bazy na proxy ani nie udostępniaj pliku SQLite przez sieciowy filesystem.
Backend musi nadal zapisywać historię udanych logowań. Zawartość bazy przekłada się
na zakres wykrywania; proxy nie zbiera historii nieudanych prób.

`AUTHGATEWAYX_DB_PASSWORD` ma pierwszeństwo nad YAML. HikariCP ma dwa połączenia,
executor dwa wątki i kolejkę 64, zapytania timeout 5 s. Wyniki ograniczone są do 20 kont.
Nie dodano skanowania całej historii security_events ani rekurencyjnego grafu kont.

## Dostawca VPN/GeoIP

Używany jest ten sam `ProxycheckIpLookup` co na Paper: HTTPS, v3 przypięte do
`24-June-2026`, `tag=0`, tylko IP bez nicku/UUID/hasła. Klucz z
`AUTHGATEWAYX_PROXYCHECK_API_KEY` ma pierwszeństwo nad YAML. Pusty klucz korzysta
z limitu anonimowego dostawcy. Szczegóły usługi: https://proxycheck.io/api/.

Cache ma TTL, limit rozmiaru, deduplikację i invalidację; stan nieznany ma krótszy TTL
oraz globalny backoff. Limit 64 KiB obejmuje odpowiedź HTTP. Budżet i cache resetują
się po restarcie, limit planu dostawcy nie. Nie ma retry. `risk.maximum-concurrent`
ogranicza cały pipeline przed lookupem Mojang i dodatkowymi kontrolami. `DENY` zawsze
kończy połączenie na proxy; blokady nie są zapisywane jako trwałe bany PunisherX.

## Administracja

`/authgatewayx alts <nick>` wymaga `authgatewayx.admin.alts` przyznanego na proxy.
Pozostałe subkomendy, np. `setpassword`, nadal są przekazywane do Paper. Raport wymaga
włączonego storage. `authgatewayx.admin.alerts` pozwala odbierać alerty z całego proxy.
Alert pokazuje do 5 nicków, raport do 20. Surowe IP nie są wysyłane administratorom.
Cooldown jest per nick, limit stanu wynosi 10000; globalnie najwyżej jeden alert/s.
Komenda ma jedną operację w toku, a alerty mogą być pomijane przy przeciążeniu.

Konsola oraz uwierzytelniony gracz Mojang mogą korzystać z tych uprawnień bez dodatkowej
konfiguracji backendu. Administrator offline potrzebuje niezależnego losowego sekretu
(minimum 32 bajty) identycznego w `staff-proof.secret` na obu platformach. Zmienna
`AUTHGATEWAYX_STAFF_PROOF_SECRET` ma pierwszeństwo. Nie używaj forwarding secret.
Pusty sekret nie pozwala kontom offline odczytywać danych administracyjnych na proxy.

Proxy wysyła challenge dla konkretnego gracza i aktualnego backendu, a Paper odpowiada
wyłącznie w stanie ACTIVE przez EntityScheduler. Challenge i odpowiedź są podpisane
HMAC-SHA256, obejmują UUID, losowy nonce i ważność 5 s. Każda odpowiedź jest konsumowana
raz; nie powstaje długotrwały cache uprawnień. Raport ponownie sprawdza sesję i permisję
przed ujawnieniem danych. Synchronizacja zegarów proxy/backendu jest wymagana.

Kanał `authgatewayx:staff_proof` zawsze jest konsumowany na proxy jako handled;
wiadomość od klienta nie jest przekazywana. Sprawdzane są źródło ServerConnection,
bieżący backend, podpis, nonce i termin. Limit: 1000 oczekujących potwierdzeń i jedno
na gracza; timeout, disconnect, zmiana backendu i shutdown anulują oczekiwanie.
Ten dowód nie loguje gracza, nie zdejmuje PRE_AUTH i nie przyznaje permisji.

## Wdrożenie

1. Zaktualizuj oba JAR-y. Paper wykonuje migrację historii.
2. Wskaż wspólną bazę na proxy i włącz wybrane kontrole.
3. Dla administracji offline skonfiguruj sekret na proxy i wszystkich zaufanych backendach.
4. Przyznaj permisje na proxy. Wykonaj pełny restart.
5. Jeśli alerty mają pochodzić tylko z proxy, wyłącz na Paper
   `multi-account.alerts.enabled` oraz `ip-intelligence.enabled`; zapis historii nadal działa.
6. Zachowaj modern forwarding i firewall backendu. Proxy admission nie chroni
   niezabezpieczonego portu, na który ktoś połączy się bezpośrednio.

## Weryfikacja

Testy automatyczne obejmują polityki DENY/ALERT/DISABLED, awarie, brak fallbacku premium,
limity przed lookupem, późne callbacki po shutdownie, rozdzielenie gniazd z tym samym
IP/nickiem, podpisy/UUID/nonce/expiry oraz spoof/replay kanału administracyjnego.
Zdalne bazy, rzeczywisty dostawca i end-to-end Velocity + Paper/Purpur/Folia wymagają
osobnego testu serwerowego. Nie oznacza się tych pozycji jako ukończonych samym buildem.
