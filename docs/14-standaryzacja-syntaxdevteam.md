## Standaryzacja SyntaxDevTeam — 2026-10-10

Zakres tego etapu obejmuje istniejące moduły Paper/Purpur/Folia i Velocity.
Spigot i BungeeCord są odłożone zgodnie z decyzją autora. Wspólny kod pozostaje
w domain/API/auth/security/integrations/storage; nie tworzymy pustego common.

Wersja AuthGatewayX jest jawnie zadeklarowana w głównym build.gradle.kts.
Katalog gradle/libs.versions.toml jest jedynym źródłem wersji pluginów Gradle,
API, bibliotek runtime, integracji i testów. Zachowano lokalny Kotlin 2.4.20,
dynamiczny dev bundle 26.3.build.+ oraz wrapper 9.8.1. Dynamiczny bundle jest
ustawieniem roboczym WIP; opis wcześniejszego 169-beta jest historią walidacji,
nie aktualnym przypięciem. Przed publikacją wymagana jest walidacja konkretnego
rozwiązanego buildu. Java 25 pozostaje celowym wyjątkiem od bazowego Java 21
ekosystemu, ponieważ adapter Paper 26.3 używa NMS. Ta zmiana nie obniża API serwera.

Root deklaruje wspólne pluginy apply false, dziedziczenie group/version,
toolchain, UTF-8, JUnit Platform i jawne DuplicatesStrategy dla JAR.
buildAll buduje wszystkie istniejące moduły i uruchamia verifyDistributions.
Artefakty dystrybucyjne: AuthGatewayX-Paper-1.0.0-WIP.jar i
AuthGatewayX-Velocity-1.0.0-WIP.jar w build/libs modułów platformowych.

SyntaxLoader zastępuje AuthGatewayXLoader w Paper i odczytuje katalogiem generowane
koordynaty oraz repozytoria z paper-libraries.yml. Rozdziela resolver snapshots,
releases i central oraz odrzuca pusty/niepoprawny manifest. Zewnętrzne biblioteki
runtime Paper pozostają dostarczane przez MavenLibraryResolver; Kotlin jest
osadzony dla bootstrapu. Velocity zachowuje samowystarczalny shaded JAR z
SyntaxCore, MessageHandler-Velocity i sterownikami JDBC. Nie przenosimy mechanizmu
Paper PluginLoader na proxy. Merge service files zachowuje wpisy wszystkich driverów.

Główne klasy delegują lifecycle do platformowych loader/PluginInitializer.kt.
Paper inicjalizuje SyntaxCore z versionType=paper, pobiera logger i przypisuje
plugin.messageHandler po SyntaxMessages.initialize. Velocity używa
ProxySyntaxCore.initVelocity z versionType=velocity i istniejącego
SyntaxMessages.configure z VelocityMessageResources, następnie przypisuje handler
pluginowi. Ten adapter konfiguracji zachowuje authgatewayx.yml bez tworzenia
równoległego config.yml. Nie zmieniono treści wiadomości ani UI.

Celowy wyjątek od prostej kolejności startu: fail-closed readiness listener i tanie
guardy muszą być instalowane przed bibliotekami infrastrukturalnymi. Błąd konfiguracji,
SyntaxCore, wiadomości albo storage nie otwiera logowania. Składanie usług i callbacki
zachowują dotychczasowy bounded async JDBC/HTTP/Argon2, ownership schedulerów,
premium DENY bez fallbacku, PRE_AUTH oraz ochronę późnej aktywacji po shutdownie.
Nie dodano nowych zapytań, telemetryki, zmian schematu ani nowej funkcji auth.

verifyDistributions sprawdza duplikaty, nazwy klas/descriptorów, obecność shared code,
dokładną zgodność runtime Paper z katalogiem, brak ponownego shadingu bibliotek
Paper oraz komplet sterowników i połączonych usług JDBC Velocity.
Pełne testy start/restart/disconnect na Paper/Purpur/Folia/Velocity i zdalnych bazach
pozostają osobnymi bramkami. Sama kompilacja nie zatwierdza folia-supported: true;
deskryptor zachowuje obecną politykę do rzeczywistego testu Folia.

### Wynik weryfikacji tego etapu

- [x] Wspólny root build i jeden katalog wersji wszystkich aktywnych zależności.
- [x] Wersja wydania wyłącznie w root build.gradle.kts.
- [x] Platformowe PluginInitializer z SyntaxCore/MessageHandler i cienkie entrypointy.
- [x] SyntaxLoader, generowany manifest i jawne repozytoria runtime Paper.
- [x] Pakowanie Paper/Velocity, merge service files i kontrola dystrybucji.
- [x] Pełny build wszystkich dziewięciu modułów i testy lokalne: 214 zaliczonych,
  4 jawnie pominięte z powodu braku zdalnych baz, 0 błędów.
- [x] Configuration cache zapisuje się przy buildAll; nie został wyłączony.
- [x] Kontrola źródeł: wspólne moduły nie importują Bukkit/Paper/Velocity.
- [x] Build Explorer używa buildAll i weryfikuje nowy loader/nazwy artefaktów.
- [ ] Start, shutdown i restart nowego artefaktu na Paper/Purpur.
- [ ] Rzeczywiste testy Folia i zatwierdzenie folia-supported.
- [ ] Testy runtime proxy/backend Velocity oraz zdalnych baz danych.

Robocze 26.3.build.+ rozwiązało się podczas tego buildu do 26.3.build.169-beta.
Nie uruchamiano serwerów produkcyjnych ani lokalnych serwerów z katalogu run.

### Jawny dostęp natywny JVM

Proces wykonujący build Gradle otrzymuje tę samą flagę przez
`org.gradle.jvmargs` w `gradle.properties`. Dotyczy to także buildu uruchamianego
z IDE oraz jednorazowego daemona przy `--no-daemon`. Samo ustawienie flagi na
zadaniach Test/JavaExec nie nadawało uprawnienia JVM Gradle.

Zadania Test (również securityBenchmark) i JavaExec (w tym runServer) otrzymują
`--enable-native-access=ALL-UNNAMED`. JLine FFM, SQLite JNI oraz Argon2/JNA wymagają
dostępu natywnego z classpath. To jawne uprawnienie JVM, a nie wyciszenie logów.
Flaga procesu Gradle nie jest automatycznie dziedziczona przez testowe JVM,
dlatego jest ustawiona bezpośrednio na zadaniach uruchamiających te procesy.
Przy uruchamianiu Paper/Velocity osobnym skryptem administrator dodaje tę flagę
przed `-jar` w poleceniu Java serwera/proxy; plugin nie może zmienić jej po starcie JVM.


## PlugManX i powtarzany lifecycle Paper — 2026-10-14

Paper ma teraz osobny initializer/readiness dla każdej generacji enable i wspólne,
odłączalne gateways komend rejestrowanych raz przez Lifecycle API. Przed bootstrapem
instalowany jest `PaperConnectionMaintenanceGate`; STARTING/FAILED/STOPPING odrzuca
połączenia, a unload pozostawia zamknięty guard do kolejnego load. Disable zamyka
transport wszystkich klientów. Nowy runtime czeka na async cleanup poprzedniego;
JDBC, executory i UUIDManager są zamykane poza threadem gry, a spóźniona inicjalizacja
nie publikuje zasobów. SyntaxCore/MessageHandler inicjalizują się na bounded executorze
bootstrap. Koordynator migracji odrzuca nową pracę po stopie i udostępnia drain
trwających operacji. Błąd cleanup blokuje kolejne enable. Premium interceptor usuwa
wyłącznie własne handlery. Komendy i feedback nie korzystają ze starego runtime.

Celowy wyjątek lifecycle: minimalny zamknięty guard Netty i jego classloader pozostają
po unloadzie dla ochrony serwera offline. Nie oznacza to pełnego zwolnienia
classloadera; stan połączeń guardu jest czyszczony. Velocity nie zmienia lifecycle.
Testy JVM i artefaktów przechodzą; rzeczywista macierz PlugManX/Paper/Purpur/Folia
pozostaje otwarta. Wymagany PlugManX z obsługą natywnego Paper i konkretnego buildu.
[Szczegóły decyzji, ograniczenia i checklista](15-plugmanx-lifecycle.md).
