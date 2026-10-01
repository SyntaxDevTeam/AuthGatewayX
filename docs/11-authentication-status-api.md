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

CraftConnectBridge odczytuje usługę, a następnie wysyła potwierdzenie wyłącznie na
połączenie danego gracza. Klient zaczyna komendy ukrycia dopiero po tym potwierdzeniu.
Stan ACTIVE dotyczy zarówno poprawnego logowania hasłem/dialogiem, jak i weryfikacji
Mojang lub przywróconej zaufanej sesji. Wysyłanie komend nie oznacza ochrony ekwipunku.

Velocity nie rejestruje Bukkit ServicesManager. Bridge instalowany jest na backendzie
Paper z AuthGatewayX; nie dodano niezależnego API proxy ani obejścia premium handoff.

Testy sprawdzają stan runtime, CONNECTING, PRE_AUTH, ACTIVE, inny UUID, wygaśnięcie,
disconnect i brak sesji. Walidacja na rzeczywistych serwerach Paper/Folia z bridge'em
pozostaje wymagana; nie zamyka to checklisty całego API ani publicznych eventów.
