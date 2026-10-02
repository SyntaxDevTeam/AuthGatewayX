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

Docelowe permission nodes:

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
    ├── jawne zatwierdzenie urządzenia
    └── rejestracja device public key
```

Rekord sparowania powinien docelowo zawierać co najmniej:

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

### Wymagania bezpieczeństwa

- pairing wolno rozpocząć dopiero po bezpiecznej identyfikacji gracza,
- dla kont wymagających `/login` lub `/register` pairing nie może omijać PRE_AUTH,
- challenge musi być jednorazowy, mieć TTL i być odporny na replay,
- liczba aktywnych challenge musi być ograniczona,
- pairing/revoke wymagają audytu,
- token/sesja urządzenia nie może zawierać trwałego snapshotu permissions,
- odebranie permission powinno skutkować utratą capability bez ponownego pairingu,
- nie logować sekretów, pełnych tokenów ani materiału klucza prywatnego.

## Capability model

Początkowy zestaw logiczny:

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

Nie wszystkie capabilities muszą być dostępne na każdej platformie. Warstwa wspólna definiuje nazwę i permission, a adapter Paper/Velocity dostarcza implementację tylko tam, gdzie dane są faktycznie dostępne.

## Protokół

Stałe początkowe:

```text
channel = authgatewayx:craftconnect
protocolVersion = 1
```

Przykładowy logiczny handshake:

```text
C -> S  HELLO(protocolVersion, appVersion, deviceId)
S -> C  HELLO(protocolVersion, serverId, supportedFeatures)
S -> C  CAPABILITIES(grantedCapabilities)
```

Format transportowy nie jest jeszcze zamrożony. Przed implementacją należy zdefiniować:

- framing,
- maksymalny rozmiar wiadomości,
- limity częstotliwości,
- request/response correlation ID,
- wersjonowanie komunikatów,
- obsługę nieznanych typów,
- timeouty,
- replay protection dla operacji uprzywilejowanych.

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

AGX może udostępniać dane, które są dostępne lokalnie bez kosztownego lub blokującego I/O, m.in.:

- uptime,
- liczba graczy,
- TPS / MSPT, jeżeli platforma/API udostępnia wiarygodne dane,
- użycie pamięci JVM,
- wersja platformy,
- wersja Minecraft,
- podstawowy health serwera.

CPU/RAM hosta, filesystem i podobne dane systemowe powinny mieć jawnie zdefiniowane providery i nie mogą blokować game thread.

## Branding

Capability `BRANDING` może udostępniać m.in.:

- display name serwera,
- krótki opis,
- logo/ikonę,
- opcjonalne metadane wizualne.

Branding nie jest mechanizmem autoryzacji.

## Relacja do RCON

RCON jest implementowany po stronie CraftConnect i nie wymaga AuthGatewayX.

AuthGatewayX:

- nie przechowuje hasła RCON aplikacji,
- nie przekazuje haseł RCON,
- nie proxy'uje RCON jako domyślnej ścieżki,
- może oferować własne `CONSOLE_EXECUTE`, które ma pierwszeństwo w aplikacji, gdy capability jest przyznana.

## Platformy

### Paper / Purpur / Folia

Docelowo pełny provider funkcji administracyjnych.

Wymagane:

- prawidłowe schedulery Folia,
- brak blokującego I/O na threadach gry,
- limitowany bufor konsoli,
- ograniczenia rate-limit dla custom payload / requestów.

### Velocity

Może dostarczać capabilities dotyczące proxy, tożsamości, sesji i routingu. Nie należy deklarować danych backendu, których Velocity sam nie posiada, chyba że istnieje jawny i bezpieczny kanał backend ↔ proxy.

## Zasada awarii izolowanej

Awaria integracji CraftConnect:

- nie może blokować logowania,
- nie może przerwać poprawnej sesji Minecraft,
- nie może wyłączyć AuthGatewayX,
- nie może powodować nieograniczonej kolejki logów/metryk,
- powinna degradować wyłącznie rozszerzone capabilities.

## Etapy implementacji

### Etap 0 — kontrakt

- [x] udokumentowany model integracji,
- [x] podstawowe typy capability i permission w publicznym API,
- [ ] format transportowy v1,
- [ ] testy kontraktu capability/permission.

### Etap 1 — discovery + pairing

- [ ] rejestracja kanału `authgatewayx:craftconnect`,
- [ ] HELLO v1,
- [ ] jednorazowy challenge,
- [ ] device public key,
- [ ] zapis/revoke sparowanych urządzeń,
- [ ] permission-based capabilities,
- [ ] security/replay/rate-limit tests.

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
