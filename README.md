# AuthGatewayX

Plugin uwierzytelniający umożliwiający obsługę graczy premium/online przez oficjalny
system Microsoft/Mojang oraz graczy non-premium/offline przez AuthGatewayX.

**[WIKI dla graczy i administratorów](docs/wiki/Home.md)** — instalacja, komendy,
uprawnienia, pełna konfiguracja i pomoc prostym językiem.

Projekt główny jest agregatorem Gradle. Implementacje platformowe znajdują się w:

- `authgatewayx-paper` — standalone Paper/Purpur/Folia,
- `authgatewayx-velocity` — odseparowany moduł proxy WIP.

Pozostałe moduły zawierają domenę, API, bezpieczeństwo, uwierzytelnianie, integracje i
storage bez mieszania ich z kodem platformowym.

Budowa artefaktu standalone:

```bash
./gradlew :authgatewayx-paper:build
```

Wynik: `authgatewayx-paper/build/libs/AuthGatewayX-1.0.0-WIP.jar`.
