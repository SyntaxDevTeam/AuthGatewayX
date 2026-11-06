# AuthGatewayX — pre-join authentication oraz zgodność Paper 26.1+ / Java 25 / Velocity 4+

Status: **eksperymentalna ścieżka Configuration Phase za flagą, domyślnie wyłączona; niezweryfikowana w grze i niedopuszczona do wydania**.  
Data audytu: 2026-11-06.  
Gałąź robocza: `feature/prejoin-auth-26x-compatibility`.

## 1. Cel i kryterium bezpieczeństwa

Gracz wymagający uwierzytelnienia nie może otrzymać aktywnej encji w świecie Paper/Purpur/Folia przed poprawnym uwierzytelnieniem (oraz weryfikacją polityk wymaganych do wpuszczenia na serwer). Niedopuszczalny jest wariant, w którym gracz wchodzi do świata, a plugin tylko ukrywa go, teleportuje do VOID albo anuluje eventy. Połączenie ma pozostać w **Minecraft Configuration Phase**.

Oczekiwane platformy docelowe:
- Paper/Purpur: **26.1+** (co najmniej 26.1.2, 26.2, 26.3) — **docelowo, niezweryfikowane**.
- Folia: 26.x — po osobnej weryfikacji i testach właścicieli wątków; brak deklarowania wsparcia przed testami.
- Java: **25** (już ustawiona w katalogu wersji Gradle i toolchain).
- Velocity: **4.0+** (aktualnie kompilacja z API 4.3.0; wsparcie 4.0–4.3 wymaga testów).
- Spigot: poza zakresem tego rozwiązania (brak Paper Configuration Phase API).

Powyższe są **celami zgodności**, nie deklaracją już dostarczonej i przetestowanej funkcji.

## 2. Audyt bieżącego repozytorium

1. `PreAuthEntryListener` tworzy `AuthSession` i przełącza na `PRE_AUTH` podczas `PlayerJoinEvent`. `PreAuthIsolationManager` ukrywa i ogranicza gracza **po** wejściu do świata. To nie spełnia nowego wymagania.
2. `AuthenticationDialogController` akceptuje `PlayerGameConnection` i korzysta z `Player`. Dla Configuration Phase potrzebuje wariantu opartego o `PlayerConfigurationConnection` oraz `connection.audience`, bez `Player`.
3. `AuthenticationFormCoordinator`, `LoginService`, `RegistrationService`, `SessionRegistry` i ograniczenia dostępu są w dużej części niezależne od API platformowego — należy je **współdzielić**, nie duplikować autoryzacji.
4. `AuthenticationDialogRouter` opiera routing offline/premium, lookup Mojang i login admission na `Player`. Trzeba wydzielić principal / kontekst połączenia przedświatowego.
5. `StandalonePremiumProtocolInterceptor` oraz `PaperConnectionMaintenanceGate` używają NMS i elementów wewnętrznych Paper. Są główną przeszkodą dla jednego artefaktu uruchamianego na całej linii 26.1–26.3. **Nie zmieniać** `api-version: '26.3'` na `26.1` bez zapewnienia kompatybilności kodu wykonywanego.
6. `libs.versions.toml`: Java 25, Paper dev bundle 26.3.build.+, Velocity API 4.3.0. Wersje te oznaczają punkt kompilacji, nie minimalną wersję testowaną.
7. Obecne procesy premium, migracje UUID, ConsentChecker, anti-bot, cheap guard, ponowne włączenie pluginu i plugin messaging wymagają zachowania obecnych gwarancji fail-closed.

## 3. Architektura docelowa

```text
Minecraft login/handshake
  -> cheap guard + weryfikacja tożsamości (Mojang / offline, bez downgrade premium)
  -> AsyncPlayerConnectionConfigureEvent
     -> bounded pre-join admission (capacity + timeout + session handle)
     -> AuthSession(CONNECTING -> PRE_AUTH)
     -> LoginAdmission / punishments / username policy
     -> routing premium/offline + migracja UUID
     -> Dialog API na PlayerConfigurationConnection.audience
     -> PlayerCustomClickEvent dla połączenia konfiguracyjnego
     -> AuthenticationFormCoordinator (async I/O, Argon2, DB)
     -> AuthSession(ACTIVE) / odmowa / timeout
  -> zakończenie Configuration Phase WYŁĄCZNIE przy ACTIVE
  -> PlayerJoinEvent: sprawdzenie ACTIVE, bez ponownego create()/disconnect()
  -> ConsentChecker zgodnie z polityką (wymagana zgoda przed wpuszczeniem, jeśli włączona)
  -> rozgrywka
```

Stan `ACTIVE` musi być powiązany z bieżącym uchwytem fizycznego połączenia, by opóźniona odpowiedź po disconnect/reconnect nie autoryzowała nowej sesji o tym samym UUID. Przedświatowe odpowiedzi formularza muszą sprawdzać identyfikator połączenia i generację sesji; NIE wystarczy mapowanie tylko po nicku.

Pierwszy techniczny element: `PreJoinConnectionGate` w module `authgatewayx-auth`, wspólna ograniczona pojemnością bramka zakończenia autoryzacji. Jest już podłączona do **eksperymentalnego** Paper adaptera, włączanego wyłącznie przez `authentication.prejoin.experimental-enabled: true` (domyślnie `false`). Ścieżka pozostaje niezweryfikowana w prawdziwym kliencie. Migracja OFFLINE -> MOJANG ma osobny dialog na połączeniu konfiguracyjnym: po zweryfikowaniu starego hasła sesja jest rozłączana, a migrator uruchamia się po zwolnieniu źródłowego i docelowego profilu; konta z kolizją lub błędem są odrzucane (fail-closed).

## 4. Etapy implementacji

### A. Projekt i bezpieczeństwo
- [x] Audyt ścieżki Paper `PlayerJoinEvent`, formularzy, sesji i premium.
- [x] Potwierdzenie Configuration Phase + Dialog API w dokumentacji Paper.
- [x] Spisanie planu, ryzyk i kryteriów akceptacji.
- [ ] Udokumentować granicę zaufania dla identyfikatorów premium w konfiguracji (w tym forwarding Velocity).
- [ ] Ustalić i przetestować zasady ConsentChecker przed wejściem do świata.

### B. Mechanizm sesji przedświatowej

Stan roboczy (nie odhaczać jako zakończone bez testów runtime):
- `PreJoinConnectionGate` z jednorazowym biletem, limitem, shutdown i walidacją generacji połączenia; testy JVM.
- `ConfigurationAuthenticationDialogController`: formularz i odpowiedzi `PlayerCustomClickEvent` w fazie CONFIG, bez `Player`.
- `ConfigurationAuthenticationRouter`: admission, premium/online UUID, logowanie/rejestracja offline, przekazanie żądania migracji premium do formularza przedświatowego.
- `PreJoinConfigurationListener`: `AsyncPlayerConnectionConfigureEvent`, timeout, fail-closed, tracking reconnect i `PlayerJoinEvent` bez ponownego tworzenia sesji.
- `PluginInitializer`: flagowane rozłączenie starego `PreAuthEntryListener`, rejestracja nowych eventów i shutdown; consent ponawiany po join.
- `ConfigurationPremiumMigrationDialogController`: hasło starego konta + `PremiumMigrationService.authorize`, rozłączenie po PREPARED; migracja tylko po disconnect i stwierdzeniu braku aktywnych sesji źródła/celu (krótkie ponowienia); w razie blokady pozostaje trwały ticket do recovery.
- Obsługa `PlayerConnectionReconfigureEvent`: aktywna sesja weryfikowana przed `completeReconfiguration()`; nie otwiera się ponownej sesji PRE_AUTH.
- **Brakuje** testów prawdziwym klientem oraz gwarancji kompatybilności NMS 26.1–26.3.
- [ ] Dokończyć i zweryfikować testami jednostkowymi bounded gate z uchwytami jednorazowymi, timeout, disconnect, shutdown i ochroną przed reconnect.
- [ ] Wydzielić niezależny od `Player` `ConnectingPrincipal` i backend wspólny dla auth.
- [ ] Dodać listener `AsyncPlayerConnectionConfigureEvent`: fail-closed, brak niezabezpieczonego przejścia do gry, poprawna obsługa wyjątków oraz zamykania połączeń.
- [ ] Dodać obsługę `PlayerCustomClickEvent` dla `PlayerConfigurationConnection`, walidację formularza i obsługę błędów/ponowienia.
- [ ] Zastosować automatyczne zakończenie sesji przy timeout/disconnect/reload, bez wycieku zasobów.
- [ ] Zmienić `PlayerJoinEvent` na weryfikację aktywnego pre-join auth (nigdy nie tworzyć nowej sesji dla już autoryzowanego połączenia).
- [ ] Zapewnić fallback fail-closed, bez cichego uruchamiania starej izolacji po wejściu do świata.

### C. Tożsamość, migracje i integracje
- [ ] Zintegrować premium online i offline bez fallbacku premium -> offline po błędzie.
- [ ] Zachować weryfikację stanu premium, IP/risk, CleanerX, PunisherX i limity kont.
- [ ] Utrzymać migrację OFFLINE -> MOJANG i recovery przed zwolnieniem połączenia; przetestować zmiany UUID.
- [ ] Unikać plaintext password w logach, komunikatach i trwałej pamięci; wyzerować tablice char.
- [ ] Zabezpieczyć scenariusz, gdy inny plugin odrzuci połączenie w trakcie zdarzeń login.
- [ ] Uwzględnić brak natywnego maskowania pola `DialogInput.text` (decyzja UX/security i ostrzeżenie).

### D. Kompatybilność
- [x] Sprawdzenie aktualnego toolchain Java 25 i zależności Velocity 4.3.0.
- [ ] Przetestować bezpośrednie odwołania NMS oraz refleksję wobec 26.1.2, 26.2 i 26.3; zastąpić odpowiednimi adapterami/wariantami lub bezpiecznym API.
- [ ] Dopiero po audycie API/NMS zmienić `api-version` na `26.1` i dodać test deskriptora.
- [ ] Testy jednostkowe, kompilacja, uruchomienie, premium handshake i przeładowanie na **każdej** wersji Paper/Purpur.
- [ ] Upewnić się, że podstawowa funkcjonalność Velocity działa z API/runtime 4.0.x, 4.1.x, 4.2.x i 4.3.x.
- [ ] Zweryfikować Folia (schedulers, stop/reload, routing i połączenia); dopiero potem zatwierdzić wsparcie.
- [ ] Przepuścić i przeanalizować CI macierzy kompilacji dla Paper 26.1.2 / 26.2 / 26.3 z Java 25. Workflow `paper-26x-compatibility.yml` i `-PpaperDevBundleVersion` są dodane, wyniki wymagają odbioru; kompilacja nie oznacza zgodności jednego JAR z kilkoma wersjami NMS.
- [ ] Runtime test na Velocity 4.0–4.2.0: brak `InboundConnection.getSessionId()` (od 4.2.1) obchodzony przez `VelocitySessionIds`, a przy `LoginEvent` wykonywany jest ponowny lookup Mojang i kontrola risk zamiast korelowania PreLogin po IP/porcie/nicku. Testy JVM scenariusza fallback przeszły; testy realnego proxy pozostają otwarte.

### E. Testy akceptacyjne i wydanie
- [ ] `PlayerJoinEvent` nie występuje przed uwierzytelnieniem offline.
- [ ] Nieautoryzowany gracz nie pojawia się w `Bukkit.getOnlinePlayers()` i nie jest encją w świecie.
- [ ] Test login/register, błędne hasło, limit prób, anulowanie, timeout, disconnect i reconnect.
- [ ] Test premium z oficjalnym UUID, niedostępność Mojang (DENY), migracja i kolizja UUID.
- [ ] Równoczesne połączenia tego samego konta oraz zalew łączących się użytkowników nie powodują obejścia ani wycieków.
- [ ] Test shutdown, nieudanej inicjalizacji i PlugManX unload/reload w stanie oczekiwania.
- [ ] Testy platformowe Paper/Purpur/Folia/Velocity; aktualizacja wiki, README, release checklist i dziennika.
- [ ] Po potwierdzeniu wszystkich kryteriów uznać implementację za gotową.

## 5. Wymagania niefunkcjonalne i granice

- Zatrzymanie `AsyncPlayerConnectionConfigureEvent` odbywa się na wątku asynchronicznym, ale jest nadal kosztem zasobów serwera: liczba blokowanych połączeń musi być ograniczona, z twardym timeoutem i niezależną od callbacków ścieżką czyszczenia.
- Żadnego blokującego JDBC, HTTP, Argon2 lub plików na wątku tick/entity.
- Brak automatycznej zgody/fallbacku przy błędzie, przekroczeniu limitu, reloadzie, wyjątku i stale response.
- Żaden niezalogowany klient nie uzyskuje standardowego gameplay packet stream.
- Nie zmieniać semantyki `SessionRegistry` i `ConnectionId` bez testów zgodności i anti-replay.
- Wersja build dependency nie jest deklaracją minimalnej kompatybilnej wersji runtime.

## 6. Źródła techniczne

- https://docs.papermc.io/paper/dev/dialogs/#requiring-the-player-to-agree-before-allowing-them-to-join
- https://docs.papermc.io/paper/getting-started/
- https://docs.papermc.io/paper/dev/project-setup/
- https://docs.papermc.io/paper/dev/plugin-yml/
- https://docs.papermc.io/velocity/faq/

## 7. Dziennik decyzji

- **2026-11-06:** wybór natywnej Configuration Phase zamiast świata VOID i symulowania pustki pakietami; wymaganie braku encji w świecie przed auth.
- **2026-11-06:** Java 25 i Velocity API 4.3.0 już skonfigurowane. Minimalne Velocity 4.0+ i Paper 26.1+ to cele weryfikacyjne.
- **2026-11-06:** nie obniżać `paper-plugin.yml api-version` bez usunięcia problemów NMS i testów uruchomieniowych; w trakcie prac stary PRE_AUTH pozostaje istniejącą ścieżką.
- **2026-11-06:** dodano bramkę pre-join, adapter formularzy bez `Player`, routing premium/offline, listener konfiguracji, wpięcie przez flagę domyślnie `false`; brak testów runtime i obsługi migracji premium. Tryb nie jest gotowy produkcyjnie.
- **2026-11-06:** `PlayerConnection.getAddress()` jest `SocketAddress`, wymagany bezpieczny rzut na `InetSocketAddress`; wykryto w CI i poprawiono.
- **2026-11-06:** Velocity `getSessionId()` wymaga API >= 4.2.1; plan 4.0+ jest otwarty i wymaga odrębnego rozwiązania identyfikacji połączeń, a nie tylko zmiany Maven dependency.

## 8. Przegląd wykonania pod beta (2026-11-06)

- [x] Kompilacja Java 25 + `clean buildAll` dla dotychczasowej konfiguracji Paper 26.3 / Velocity 4.3.0 (CI `42270854772`).
- [x] Transport formularza konfiguracji, routing offline/premium, migracja po rozłączeniu, obsługa reconfiguration i odbiór po join — za wyłączoną domyślnie flagą.
- [x] Testy JVM fallback Velocity bez natywnego session UUID i brak bezpośrednich wywołań `getSessionId()` w adapterach runtime.
- [ ] CI macierz Paper 26.1.2/26.2/26.3 oraz test źródeł z różnymi NMS, a potem produkcja właściwych adapterów / artefaktów.
- [ ] Testy autoryzacji na rzeczywistym kliencie Minecraft, zarówno w połączeniu bezpośrednim, jak i przez Velocity, przed włączeniem domyślnego pre-join auth.
- [ ] Kontrola parametrów ochrony (120s timeout, 64 concurrent CONFIG), konfliktów UUID, przychodzącego plugin messaging i odtwarzania po shutdown.
- [ ] Wersja `1.0.0-beta` dopiero po domknięciu blokad kodowych; beta wymaga testów odbiorowych, nie jest oznaczona jako produkcyjna.
