# AuthGatewayX ↔ CraftConnect

## Status

Dokument definiuje obowiązujący kierunek integracji AuthGatewayX z aplikacją `SyntaxDevTeam/CraftConnect`.

AuthGatewayX NIE jest wymagany do podstawowego działania CraftConnect. Aplikacja pozostaje zwykłym headless klientem Minecraft i bez pluginu ma dokładnie taki zakres możliwości jak standardowy gracz.

## Model trójwarstwowy

```text
Minecraft Protocol
      ↓
chat / commands / players / MOTD / status

RCON (opcjonalnie)
      ↓
Console Lite / command-response

AuthGatewayX (opcjonalnie, po pairing)
      ↓
pełne funkcje administracyjne i telemetryczne
```

AuthGatewayX jest preferowanym providerem funkcji rozszerzonych. RCON jest fallbackiem i nie może być traktowany jako odpowiednik pełnej integracji AGX.

## Zasada autoryzacji

Sparowanie urządzenia potwierdza zaufanie do urządzenia, ale samo w sobie NIE przyznaje capabilities administracyjnych.

Każda capability musi być przyznawana na podstawie aktualnych permissions gracza/rangi na serwerze.

Permission nodes:

```text
authgatewayx.craftconnect.pair
authgatewayx.craftconnect.status
authgatewayx.craftconnect.stats
authgatewayx.craftconnect.console.view
authgatewayx.craftconnect.console.execute
authgatewayx.craftconnect.server-info
authgatewayx.craftconnect.branding
authgatewayx.craftconnect.diagnostics
```

Permissions są sprawdzane po stronie AGX. Nie należy ufać deklarowanym permissions z aplikacji.

`PaperCraftConnectCapabilityProvider` wylicza capabilities z aktualnego `Player#hasPermission(...)`. Dla niesparowanego urządzenia może zwrócić wyłącznie `PAIRING`; pozostałe uprawnienia Enhanced Mode nie są ujawniane przed potwierdzonym pairingiem.

## Pairing

Minimalny przepływ:

```text
CraftConnect
    │
    │ aktywna, uwierzytelniona sesja Minecraft
    ▼
AuthGatewayX
    │
    ├── identyfikacja UUID gracza
    ├── jednorazowy challenge
    ├── proof-of-possession klucza urządzenia
    ├── jawne zatwierdzenie urządzenia przez gracza
    └── zapis rekordu pairingu
```

Rekord sparowania:

```text
serverId
playerUuid
deviceId
devicePublicKey
createdAt
lastUsedAt
revokedAt?
```

Klucz prywatny pozostaje wyłącznie po stronie urządzenia.

### Aktualny proof-of-possession

Pierwszy etap kryptograficzny jest zaimplementowany jako EC P-256 + `SHA256withECDSA`.

Kanoniczny podpisywany payload jest rozdzielony domeną:

```text
AGX-CRAFTCONNECT-PAIR-V1
```

i zawiera:

```text
serverId
challengeId
playerUuid
deviceId
nonce
expiresAt
```

`CraftConnectPairingChallengeRegistry`:

- utrzymuje ograniczoną liczbę oczekujących challenge,
- domyślnie wygasza je po 2 minutach,
- utrzymuje najwyżej jeden aktywny challenge per gracz,
- generuje 32-bajtowy losowy nonce,
- sprawdza klucz P-256,
- wymaga, aby `deviceId` był deterministycznym fingerprintem przesłanego klucza publicznego,
- usuwa challenge PRZED weryfikacją podpisu, więc każdy challenge jest jednorazowy również przy błędnym podpisie,
- wiąże podpis z serwerem, graczem, urządzeniem i czasem wygaśnięcia.

Poprawny podpis NIE tworzy automatycznie nowego pairingu.

`CraftConnectPairingCoordinator` rozdziela dwa przypadki:

1. aktywny zapis istnieje i public key jest identyczny — nowy challenge potwierdza possession, aktualizowany jest `lastUsedAt` i sesja może odzyskać Enhanced Mode bez ponownej zgody człowieka,
2. aktywnego zapisu brak — wynik to `ApprovalRequired`, a zapis może powstać dopiero po jawnej zgodzie gracza.

Jeżeli zapis istnieje dla tego samego `deviceId`, ale public key jest inny, wynik to `StoredKeyMismatch`.

### Jawna zgoda gracza

Powstał `CraftConnectPairingDialogController` oparty o Paper Dialog API. Controller:

- pokazuje identyfikator urządzenia,
- posiada osobne akcje APPROVE/REJECT,
- nie pozwala ESC zastąpić decyzji,
- posiada timeout,
- czyści oczekiwanie po wyjściu gracza,
- sam NIE zapisuje pairingu — dopiero callback APPROVE może uruchomić trwały zapis.

Controller nie jest jeszcze podpięty do produkcyjnego `PaperCraftConnectChannel`, ponieważ trwała implementacja JDBC musi powstać wcześniej. Dzięki temu nie istnieje przejściowa ścieżka „zaakceptowano, ale tylko w RAM”.

### Persistent storage

W `authgatewayx-storage-api` istnieje `CraftConnectPairingStorage` obejmujący:

- `findActiveCraftConnectPairing`,
- `saveApprovedCraftConnectPairing`,
- `touchCraftConnectPairing`,
- `revokeCraftConnectPairing`,
- `listCraftConnectPairings`.

Kontrakt używa `CompletionStage`, tak jak pozostała asynchroniczna warstwa storage AGX. Produkcyjna implementacja JDBC i migracja schematu są jeszcze wymagane. Nie należy tworzyć dla CraftConnect osobnego ad-hoc pliku ani drugiej niezależnej bazy.

### Wymagania bezpieczeństwa

- pairing wolno rozpocząć dopiero po bezpiecznej identyfikacji gracza,
- dla kont wymagających `/login` lub `/register` pairing nie może omijać PRE_AUTH,
- challenge musi być jednorazowy, mieć TTL i być odporny na replay,
- liczba aktywnych challenge musi być ograniczona,
- proof-of-possession nie może samodzielnie oznaczać nowego urządzenia jako zaakceptowane,
- `deviceId` musi być związany z public key,
- pairing/revoke wymagają audytu,
- token/sesja urządzenia nie może zawierać trwałego snapshotu permissions,
- odebranie permission powinno skutkować utratą capability bez ponownego pairingu,
- nie logować sekretów, pełnych tokenów ani materiału klucza prywatnego.

## Capability model

Aktualny zestaw logiczny:

```text
PAIRING
STATUS
STATS
CONSOLE_VIEW
CONSOLE_EXECUTE
SERVER_INFO
BRANDING
DIAGNOSTICS
```

Wire IDs są stabilne i niezależne od nazw enumów:

```text
pairing
status
stats
console.view
console.execute
server.info
branding
diagnostics
```

Nie wszystkie capabilities muszą być dostępne na każdej platformie. Warstwa wspólna definiuje wire ID i permission, a adapter Paper/Velocity dostarcza implementację tylko tam, gdzie dane są faktycznie dostępne.

## Protokół v1

Stałe:

```text
channel = authgatewayx:craftconnect
protocolVersion = 1
maxFrameBytes = 65536
magic = AGXC (0x41475843)
```

Framing binarny v1:

```text
magic:int32
version:uint8
type:uint8
requestId:UUID/128-bit
payload zależny od typu
```

Typy pierwszego etapu:

```text
1   ClientHello
2   ServerHello
3   Capabilities
10  PairingBegin
11  PairingChallenge
12  PairingConfirm
13  PairingResult
127 Error
```

Pola tekstowe i binarne są length-prefixed i ograniczone rozmiarem. Nieznane capability są ignorowane przy dekodowaniu, aby umożliwić kompatybilne rozszerzanie protokołu. Nieznany typ wiadomości lub niezgodna wersja powodują odrzucenie ramki.

Oba repozytoria posiadają ten sam wektor kompatybilności `ClientHello`, dzięki czemu przypadkowa zmiana framingu, endianowości albo type ID jest wykrywana przez testy.

Logiczny handshake:

```text
C -> S  ClientHello(appVersion, deviceId?)
S -> C  ServerHello(serverId, serverName, supportedCapabilities)
S -> C  Capabilities(grantedCapabilities)
```

## Paper/Folia — aktualny adapter

`PaperCraftConnectChannel` rejestruje `authgatewayx:craftconnect` jako native plugin messaging channel i obsługuje:

- `ClientHello`,
- permission-derived pre-pair `Capabilities`,
- `PairingBegin`,
- generowanie challenge,
- asynchroniczną weryfikację podpisu na ograniczonym executorze,
- powrót do kontekstu gracza przez EntityScheduler przed wysłaniem odpowiedzi.

Weryfikacja ECDSA nie jest wykonywana na threadzie gracza.

### Konfiguracja tożsamości serwera

Pairing wymaga stabilnego `serverId`. Adapter Paper odczytuje:

```yaml
craftconnect:
  server-id: "unikalny-stabilny-id-serwera"
  server-name: "Opcjonalna nazwa wyświetlana"
```

Jeżeli `server-id` nie jest skonfigurowany, discovery może odpowiedzieć jako `unconfigured`, ale `PAIRING` nie jest reklamowany i rozpoczęcie pairingu kończy się `server_id_not_configured`. Nie wolno automatycznie generować nowego serverId przy każdym starcie.

Domyślne wpisy configu powinny zostać dodane przed uznaniem Etapu 1 za gotowy produkcyjnie.

## Console

### CONSOLE_VIEW

Docelowo zapewnia strumień konsoli/logów serwera z ograniczonym buforem historii oraz backpressure.

### CONSOLE_EXECUTE

Jest oddzielną capability od odczytu. Użytkownik posiadający `console.view` nie może automatycznie wykonywać komend jako konsola.

Każde wykonanie komendy musi być audytowalne co najmniej przez:

```text
playerUuid
deviceId
command
timestamp
result/status
```

Należy rozważyć maskowanie komend zawierających sekrety przed zapisem audytu.

## Metrics / status

AGX może udostępniać dane, które są dostępne lokalnie bez kosztownego lub blokującego I/O, m.in. uptime, liczbę graczy, TPS/MSPT, pamięć JVM, wersję platformy/Minecraft oraz health serwera. Dane hosta i filesystemu muszą mieć jawne providery i nie mogą blokować game thread.

## Branding

Capability `BRANDING` może udostępniać display name serwera, opis, logo/ikonę i opcjonalne metadane wizualne. Branding nie jest mechanizmem autoryzacji.

## Relacja do RCON

RCON jest implementowany po stronie CraftConnect i nie wymaga AuthGatewayX. AuthGatewayX nie przechowuje ani nie proxy'uje hasła RCON. Własne `CONSOLE_EXECUTE` AGX ma pierwszeństwo w aplikacji, gdy capability jest przyznana.

## Platformy

Paper/Purpur/Folia docelowo dostarczają pełny provider funkcji administracyjnych z poprawnymi schedulerami, bounded I/O, rate limitingiem i backpressure. Velocity może dostarczać wyłącznie dane proxy/tożsamości/routingu, które faktycznie posiada, chyba że istnieje jawny bezpieczny kanał backend ↔ proxy.

## Zasada awarii izolowanej

Awaria integracji CraftConnect:

- nie może blokować logowania,
- nie może przerwać poprawnej sesji Minecraft,
- nie może wyłączyć AuthGatewayX,
- nie może powodować nieograniczonej kolejki logów/metryk,
- powinna degradować wyłącznie rozszerzone capabilities.

## Walidacja 2026-10-02

GitHub Actions `Verify repository` dla commita `93515b80dcf0ff8eeda2e4a28d3ad32734f250ef` zakończył się sukcesem: testy oraz build i weryfikacja dystrybucyjnych JAR-ów Paper/Velocity przeszły. Joby publikacyjne Paper/Velocity później nie powiodły się na kroku `Publish ... artifact to BuildExplorer v2`; sam build pluginów w tych jobach zakończył się sukcesem.

## Etapy implementacji

Checkbox `[x]` oznacza element wdrożony i zweryfikowany w zakresie wskazanym przez dokumentację. End-to-end/live nadal ma osobne pozycje.

### Etap 0 — kontrakt

- [x] udokumentowany model integracji,
- [x] podstawowe typy capability i permission w publicznym API,
- [x] format transportowy v1 zaimplementowany, zamrożony i zweryfikowany buildem/testami,
- [x] testy kontraktu capability/protocol.

### Etap 1 — discovery + pairing

- [x] rejestracja kanału `authgatewayx:craftconnect` w adapterze Paper — build zweryfikowany,
- [ ] HELLO v1 — obie strony zaimplementowane, brak testu live CraftConnect ↔ Paper,
- [x] jednorazowy challenge + testy replay/expiry,
- [x] device public key / proof-of-possession + binding `deviceId` ↔ public key,
- [ ] jawne zatwierdzenie gracza — controller Dialog istnieje, wymaga podpięcia po implementacji JDBC,
- [ ] zapis/revoke/list — kontrakt i coordinator istnieją, brak JDBC/migracji,
- [ ] permission-based capabilities — provider Paper zaimplementowany, pełny paired flow jeszcze nie istnieje,
- [ ] rate-limit/audit całego endpointu,
- [ ] test end-to-end CraftConnect ↔ Paper/Folia.

### Etap 2 — status / branding / metrics

- [ ] STATUS,
- [ ] SERVER_INFO,
- [ ] BRANDING,
- [ ] STATS,
- [ ] platform-specific providers.

### Etap 3 — console

- [ ] ograniczony bufor live console,
- [ ] CONSOLE_VIEW,
- [ ] CONSOLE_EXECUTE,
- [ ] audyt komend,
- [ ] filtrowanie sekretów,
- [ ] backpressure i flood protection.

## Zasada dla przyszłych zmian

Nie należy tworzyć osobnego obowiązkowego `CraftConnectBridge` tylko po to, aby aplikacja działała. Podstawowa aplikacja ma pozostawać niezależna od pluginów, a AuthGatewayX jest opcjonalnym providerem Enhanced Mode.
