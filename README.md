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

Wynik: `authgatewayx-paper/build/libs/AuthGatewayX-Paper-1.0.0-WIP.jar`.

Pełny build obu platform, testy i kontrola zawartości JAR-ów:

```bash
./gradlew clean buildAll
```

Velocity: `authgatewayx-velocity/build/libs/AuthGatewayX-Velocity-1.0.0-WIP.jar`.
Wersja pluginu jest w głównym `build.gradle.kts`; wszystkie wersje zależności są
w `gradle/libs.versions.toml`. Lifecycle składają platformowe `PluginInitializer.kt`.
Zależności Paper ładuje `SyntaxLoader` z generowanego `paper-libraries.yml`.
Szczegóły i granice walidacji: [standard SyntaxDevTeam](docs/14-standaryzacja-syntaxdevteam.md).
