# CraftConnect integration — instrukcje dla agentów

Ten dokument jest obowiązujący dla wszystkich zmian dotyczących integracji `SyntaxDevTeam/CraftConnect` z AuthGatewayX.

Przed zmianą kodu należy przeczytać:

```text
docs/CraftConnect-integration.md
AGENTS.md
```

## Nienegocjowalne założenia

1. CraftConnect musi działać jako zwykły klient Minecraft bez AuthGatewayX.
2. Nie tworzyć obowiązkowego `CraftConnectBridge` jako warunku działania aplikacji.
3. AuthGatewayX dostarcza wyłącznie opcjonalny `Enhanced Mode`.
4. RCON jest niezależnym fallbackiem implementowanym przez CraftConnect; AGX nie przechowuje ani nie pośredniczy w haśle RCON.
5. Pairing urządzenia nie oznacza przyznania wszystkich uprawnień.
6. Każda rozszerzona capability jest wyliczana na podstawie aktualnych server-side permissions gracza.
7. Nigdy nie ufać capabilities, UUID ani permissions zadeklarowanym przez klienta bez weryfikacji w serwerowym kontekście sesji.
8. `CONSOLE_VIEW` i `CONSOLE_EXECUTE` muszą pozostać rozdzielone.
9. Integracja CraftConnect nie może osłabić PRE_AUTH, anti-bot, anti-flood ani login pipeline.
10. Awaria integracji ma degradować wyłącznie funkcje Enhanced Mode, nigdy podstawowe logowanie AuthGatewayX.

## Kontrakt publiczny

Typy wspólne znajdują się w:

```text
authgatewayx-api/.../api/craftconnect/
```

Nie dodawać tam klas Paper, Velocity, NMS, loggerów platformowych ani storage implementation.

Stałe początkowe:

```text
channel = authgatewayx:craftconnect
protocolVersion = 1
```

Zmiana tych wartości jest zmianą protokołu i wymaga aktualizacji dokumentacji oraz kompatybilności wstecznej albo jawnego bumpu wersji.

## Permissions

Punktem wyjścia są:

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

Nie kodować ról typu `ADMIN`, `MODERATOR`, `OWNER` jako źródła autoryzacji. Role/rangi są mapowane przez system permissions serwera.

## Pairing security

Każda implementacja pairingu musi uwzględniać:

- poprawnie uwierzytelnioną tożsamość gracza,
- brak możliwości pairingu w PRE_AUTH,
- jednorazowy challenge,
- krótki TTL,
- replay protection,
- rate limit,
- limit liczby aktywnych challenge,
- public key urządzenia,
- możliwość revoke,
- audyt zdarzeń,
- brak sekretów w logach.

Nie implementować `deviceId + UUID = trusted` bez kryptograficznego potwierdzenia urządzenia.

## Console security

Live console i command execution to funkcje wysokiego ryzyka.

Wymagane:

- osobne permissions,
- bounded buffer,
- backpressure,
- rate limits,
- audyt wykonanych komend,
- filtrowanie/maskowanie sekretów,
- brak nieograniczonego kolejkowania logów,
- brak blokującego I/O na game thread.

## Folia / Velocity

Każda funkcja musi jawnie określić, czy działa na Paper/Purpur/Folia/Velocity. Nie deklarować capability, jeśli dana platforma nie potrafi jej poprawnie dostarczyć.

Folia threading rules z głównego `AGENTS.md` pozostają nadrzędne.

## Definition of Done dla funkcji CraftConnect

Funkcja nie jest ukończona, jeśli brakuje któregokolwiek adekwatnego elementu:

```text
[ ] kontrakt/API
[ ] implementacja platformowa
[ ] permissions
[ ] security review
[ ] rate limits / bounds
[ ] testy happy path
[ ] testy failure/replay/flood path
[ ] dokumentacja
[ ] kompatybilność z CraftConnect protocolVersion
```
