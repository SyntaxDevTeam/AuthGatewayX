# API stanu uwierzytelnienia — integracja CraftConnect

Paper/Purpur/Folia rejestruje publiczny kontrakt
`pl.syntaxdevteam.authgatewayx.api.AuthenticationStatusProvider` w Bukkit
`ServicesManager` po zainstalowaniu runtime. Provider jest usuwany przy shutdownie.

`isAuthenticated(UUID)` wykonuje wyłącznie odczyt niezmiennego snapshotu z
thread-safe rejestru sesji. Nie wykonuje JDBC, HTTP, Argon2 ani operacji na graczu.
Wymaga runtime READY, sesji ACTIVE, zgodności Minecraft UUID i niewygasłej sesji.
CONNECTING, PRE_AUTH, brak sesji, disconnect, logout, niedostępny runtime oraz
wygasła sesja dają `false`. Zapytanie niczego nie aktywuje i nie zdejmuje izolacji.

Decyzja: dodano wąski kontrakt odczytu zamiast ogłaszać cały istniejący
`AuthGatewayApi` jako wdrożony. Pełny account/session API nadal pozostaje w roadmapie.
Provider nie ujawnia haseł, IP ani danych konta. Nie dodano eventu Bukkit; tani odczyt
stanu pozwala uniknąć zgubienia zdarzenia przy późnej subskrypcji klienta.

AuthGatewayX natywnie odczytuje usługę i wysyła potwierdzenie wyłącznie na połączenie
danego gracza. Osobny CraftConnectBridge nie jest wymagany. Klient zaczyna komendy
ukrycia dopiero po tym potwierdzeniu. ACTIVE obejmuje poprawne logowanie,
weryfikację Mojang i przywróconą zaufaną sesję. Komendy wymagają uprawnień serwera.

Velocity przekazuje subskrypcję i odpowiedź bieżącego backendu Paper; nie rejestruje
Bukkit ServicesManager i nie uznaje samego połączenia premium za sesję ACTIVE.
Protokół, ograniczenia i walidację opisuje [kanał klienta](12-client-authentication-channel.md).

Testy providera obejmują runtime, CONNECTING, PRE_AUTH, ACTIVE, UUID, wygaśnięcie
i disconnect. Testy kanału obejmują błędne pakiety, podszywanie się, powtórzenia,
rozłączenie i zmianę backendu. Walidacja na rzeczywistych Paper/Folia/Velocity
pozostaje wymagana.
