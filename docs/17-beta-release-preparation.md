# AuthGatewayX — przygotowanie wydania 1.0.0-beta

Ostatni przegląd: 2026-11-06. Status: **kod beta-candidate w przygotowaniu; wydanie nieopublikowane**.

## Co zawiera beta

- AuthGatewayX Paper: premium (Mojang) i offline, rejestracja/logowanie, blokada PRE_AUTH w fazie Configuration Phase, migracja OFFLINE -> MOJANG w osobnym dialogu, kontrola RODO po auth, anti-bot/admission.
- AuthGatewayX Velocity: klasyfikacja premium/offline w PreLogin, bezpieczna weryfikacja w Login. Velocity 4.2.1+ ma natywny `getSessionId`; dla wcześniejszych 4.x ponawiane są lookup Mojang i kontrole risk, a ID aktywnego połączenia jest lokalne i usuwane przy rozłączeniu.
- Java 25.
- Paper API: minimalna deklaracja `api-version: '26.1'`. Testy kompilacji źródeł przeszły przeciwko `26.1.2.build.74-stable`, `26.2.build.133-stable` i `26.3.build.241-stable`. **Nie jest to jeszcze dowód zgodności runtime**, bo plugin używa NMS.
- Domyślnie `authentication.prejoin.experimental-enabled: false`. Do testów beta zmień na `true` i **zrestartuj** serwer; nie włączaj na środowisku produkcyjnym przed odbiorem.

## Przed pierwszym tagiem beta — kryteria

- [ ] `clean buildAll` na Java 25 / pinned bundle 26.3 zielone dla ostatniego commita.
- [ ] Macierz budowania JAR Paper 26.1.2, 26.2, 26.3 zielona dla ostatniego commita.
- [ ] Weryfikacja pakowania i testy podstawowego uruchomienia każdej dystrybucji (Paper 26.1.2 / 26.2 / 26.3 / Velocity 4.0–4.3).
- [ ] Zakończenie ścieżek login/register/premium/migracja/consent oraz bezpiecznego sprzątania po błędach; code review security.
- [ ] Testy klientem Minecraft: brak postaci i `PlayerJoinEvent` przed zalogowaniem; rozłączenie przy niepowodzeniu; powrót po poprawnym loginie.
- [ ] Test migracji przedświatowej i rollbacku, w tym natychmiastowy reconnect i inne pluginy zależne od UUID.
- [ ] Test timeout, shutdown, reconnect, zawieszenia zewnętrznego lookup i flood w granicach limitu `maximum-pending`.
- [ ] Test Velocity forwarding/online-mode, old 4.x session adapter, multi-backend.
- [ ] Folia dopiero po osobnym teście scheduler ownership; brak deklaracji wsparcia przed odbiorem.

## Zestaw artefaktów

Do wydania beta przygotowano **cztery** osobne pliki, ponieważ nie przyjmujemy bez testu założenia, że jeden JAR skompilowany z NMS 26.3 będzie wstecznie binarnie zgodny z 26.1:

| Dystrybucja | Środowisko |
|---|---|
| `AuthGatewayX-Paper-<version>-MC26.1.2.jar` | Paper/Purpur 26.1.2 |
| `AuthGatewayX-Paper-<version>-MC26.2.jar` | Paper/Purpur 26.2 |
| `AuthGatewayX-Paper-<version>-MC26.3.jar` | Paper/Purpur 26.3 |
| `AuthGatewayX-Velocity-<version>-Velocity4.jar` | Velocity 4.x, po testach |

**Nie instaluj jednocześnie kilku wersji Paper JAR**. Dobierz artefakt do konkretnej wersji Minecraft uruchomionej na backendzie.

Każdy artefakt będzie kompilowany na Java 25 i posiada osobny plik z SHA-256. Workflow do tworzenia prerelease: `.github/workflows/beta-release.yml`; uruchamia się dopiero po wypchnięciu tagu odpowiadającego `v*-beta.*`. Samo utworzenie workflow **nie publikuje wydania**.

## Odtworzenie builda lokalnie

```bash
./gradlew clean buildAll --console=plain --no-daemon
./gradlew :authgatewayx-paper:shadowJar -PpaperDevBundleVersion=26.1.2.build.74-stable -PreleaseVersion=1.0.0-beta.1 --console=plain --no-daemon
./gradlew :authgatewayx-paper:shadowJar -PpaperDevBundleVersion=26.2.build.133-stable -PreleaseVersion=1.0.0-beta.1 --console=plain --no-daemon
./gradlew :authgatewayx-paper:shadowJar -PpaperDevBundleVersion=26.3.build.241-stable -PreleaseVersion=1.0.0-beta.1 --console=plain --no-daemon
./gradlew :authgatewayx-velocity:shadowJar -PreleaseVersion=1.0.0-beta.1 --console=plain --no-daemon
```

Uwaga: powyższe polecenia zapisują kolejne warianty Paper pod **tą samą nazwą JAR** w katalogu build/libs; należy skopiować każdy wynik pod odrębną nazwę przed kolejnym buildem. Workflow robi to automatycznie w osobnych zadaniach.

## Testy akceptacyjne — do wykonania na gotowym kandydacie

1. Offline nowy gracz: w fazie CONFIG wyświetlany jest dialog rejestracji; `PlayerJoinEvent` / encja nie pojawiają się przed poprawną rejestracją.
2. Offline istniejący gracz: niewłaściwe hasło, blokada konta, limit prób, późna odpowiedź po disconnect, poprawne hasło; wejście tylko po ACTIVE.
3. Premium: poprawny oficjalny UUID, brak downgrade do OFFLINE po problemie usług Mojang, odrzucenie nieprawidłowej sesji.
4. Konflikt kont i migracja OFFLINE -> MOJANG: potwierdzenie hasłem starego konta, rozłączenie, migracja danych poza aktywną sesją, wynik i następne logowanie.
5. RODO: zgoda po uwierzytelnieniu, brak interakcji do decyzji, prawidłowy zapis i ponowne wejście.
6. Timeouts, rapid reconnect, różne nicki i ten sam adres NAT, duplicate UUID, stop/reload i awaria DB/Mojang.
7. Velocity modern forwarding: zarówno wersje 4.0–4.2.0 bez natywnego session ID, jak i 4.2.1–4.3 z natywnym ID; deny po zmianie wyniku admission.
8. Paper 26.1.2/26.2/26.3 oraz Folia oddzielnie; uruchomienie, handshake premium i przeładowanie.

## Znane ograniczenia

- `DialogInput.text` nie maskuje wprowadzanego hasła; przed betą wymagane jest świadome zaakceptowanie ryzyka UI albo zmiana interfejsu, jeśli Paper doda bezpieczny typ pola.
- Faza konfiguracji trzyma wątek zdarzenia asynchronicznego do odpowiedzi lub timeout; maksymalna liczba oczekujących połączeń jest ograniczona przez `maximum-pending: 64` (wartość do strojenia po pomiarach).
- Przy niepewności tożsamości, awarii bazy, niezgodności API/NMS albo błędzie migracji: **odmowa**, nigdy niekontrolowane wpuszczenie do świata.
- Zgodność runtime Paper, Folia i Velocity 4.x do potwierdzenia w testach odbiorowych — kompilacja i deklaracja API nie są gwarancją działania.

## Wydanie

Po spełnieniu kryteriów:
1. Uaktualnić changelog i dokumentację, ustalić wersję beta, oznaczyć komit.
2. Wypchnąć tag `v1.0.0-beta.1` — workflow zbuduje cztery dystrybucje i utworzy GitHub prerelease.
3. Po testach odbiorowych poprawić stwierdzone problemy i wydać kolejną betę, zamiast nazywać WIP stabilnym `1.0.0`.
