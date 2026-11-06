# Zgodność i dostępność

[Home](Home.md) · [Instalacja](Instalacja.md)

WIKI opisuje **AuthGatewayX 1.0.0-WIP**. WIP oznacza wersję w rozwoju. Zachęcamy do sprawdzenia jej na serwerze testowym i poznania sposobu logowania przed stabilnym wydaniem.

## Platformy

| Platforma | Stan obecnej wersji |
| --- | --- |
| Paper | Główna wersja pluginu; kompilacja i testy JVM dla 26.3 build 169-beta; test serwerowy tej wersji pozostaje otwarty |
| Purpur | Ten sam wariant pluginu co Paper; testuj na pasującej wersji serwera |
| Folia | Zgodność jest rozwijana; nie jest jeszcze zatwierdzona do użycia |
| Velocity | Osobny wariant testowy; pełna weryfikacja sieci pozostaje otwarta |
| Spigot / Bukkit | Brak osobnego wspieranego wariantu obecnego wydania |
| Bedrock przez Geyser/Floodgate | Brak potwierdzonej obsługi |

Wersja Paper wymaga Javy 25. Używa okien Minecrafta, dlatego nie zakładaj obsługi starszego klienta tylko dlatego, że inny plugin pozwala mu połączyć się z serwerem. Tak samo nie zakładaj zgodności z każdą przyszłą aktualizacją Paper.

## Planowana zgodność 26.1+ i autoryzacja bez wejścia do świata

Celem rozwoju jest Paper/Purpur **26.1+**, Java **25** i Velocity **4.0+**. Obecny build Paper jest jednak kompilowany przeciwko 26.3 i deklaruje `api-version: '26.3'`; nie wolno traktować tego celu jako bieżącej gwarancji pracy na 26.1/26.2. Velocity jest kompilowany z API 4.3.0; inne wersje 4.x muszą przejść osobne testy uruchomieniowe. Istotny blocker dla Velocity 4.0–4.2.0: identyfikator połączenia `getSessionId()` istnieje dopiero od 4.2.1, a bieżący kod wymaga go do bezpiecznej korelacji logowania.

Dotychczasowy mechanizm PRE_AUTH izoluje gracza dopiero po `PlayerJoinEvent`. Dodano eksperymentalny wariant **Configuration Phase**, który ma zatrzymywać gracza przed utworzeniem encji. Jest on **domyślnie wyłączony** (`authentication.prejoin.experimental-enabled: false`) i nieprzetestowany jeszcze z prawdziwym klientem. Nie włączaj go na serwerze produkcyjnym; konta wymagające migracji OFFLINE -> MOJANG są w tym wariancie rozłączane. Podstawowa ścieżka logowania pozostała bez zmian. Ryzykiem pozostaje zależność od wewnętrznego NMS Paper, w szczególności weryfikacja premium — obniżenie pola `api-version` bez dopasowania NMS byłoby niebezpieczne.

[Plan i kryteria akceptacji](../16-prejoin-authentication-and-26x-compatibility.md).

## Co możesz wypróbować?

Rejestrację i logowanie offline przez okno, weryfikację premium, zmianę hasła, reset hasła przez administratora, wylogowanie, ochronę przed działaniem bez logowania oraz limity nadużyć. Dostępne są też ustawienia baz danych i integracji.

## Co jeszcze wymaga sprawdzenia?

Przed stabilnym 1.0.0 pozostają między innymi testy Folia i całej sieci Velocity, testy dużej liczby połączeń, pełna weryfikacja osobnych baz danych oraz scenariuszy przenoszenia kont i konfliktów. Obecność ustawienia nie jest gwarancją gotowości do każdego zastosowania.

Panel WWW, 2FA, łączenie z Discordem, odzyskiwanie przez e-mail i synchronizacja wielu proxy nie są dostępnymi funkcjami tej wersji.

## PlugManX

Wariant Paper ma obsługę powtarzanego enable/disable i load/unload. Wymaga PlugManX
obsługującego `paper-plugin.yml`, komendy Brigadier i używany build serwera. Starsze
wersje bez obsługi natywnego Paper nie są zgodne.

`/plugman restart AuthGatewayX` i `/plugman reload AuthGatewayX` rozłączają wszystkich
graczy. Plugin przyjmuje nowe połączenia po logu READY; każdy gracz przechodzi auth
ponownie. Po disable/unload połączenia i ping statusu pozostają odrzucane do
ponownego enable/load lub restartu procesu. Nie usuwaj ręcznie ochrony Netty.

Testy automatyczne przechodzą. Testy rzeczywistym PlugManX na Paper/Purpur/Folia
pozostają do wykonania; pełna zgodność tych konfiguracji nie jest jeszcze potwierdzona.
Błąd sprzątania lub timeout blokuje kolejne przeładowania — wtedy zrestartuj proces.
[Procedura i zakres weryfikacji](../15-plugmanx-lifecycle.md).
