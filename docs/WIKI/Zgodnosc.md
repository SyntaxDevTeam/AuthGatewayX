# Zgodność i dostępność

**Język / Language:** Polski · [English](EN/Compatibility.md)

[Home](Home.md) · [Instalacja](Instalacja.md)

WIKI opisuje **AuthGatewayX 1.0.0-R0.1-SNAPSHOT**. SNAPSHOT oznacza wersję w rozwoju. Zachęcamy do sprawdzenia jej na serwerze testowym i poznania sposobu logowania przed stabilnym wydaniem.

## Platformy

| Platforma | Stan obecnej wersji |
| --- | --- |
| Paper | Deklarowane minimum API 26.1; udana kompilacja źródeł na 26.1.2, 26.2 i 26.3. Test uruchomienia każdej wersji NMS pozostaje otwarty |
| Purpur | Ten sam wariant pluginu co Paper; testuj na pasującej wersji serwera |
| Folia | Zgodność jest rozwijana; nie jest jeszcze zatwierdzona do użycia |
| Velocity | Osobny wariant testowy; pełna weryfikacja sieci pozostaje otwarta |
| Spigot / Bukkit | Brak osobnego wspieranego wariantu obecnego wydania |
| Bedrock przez Geyser/Floodgate | Brak potwierdzonej obsługi |

Wersja Paper wymaga Javy 25. Używa okien Minecrafta, dlatego nie zakładaj obsługi starszego klienta tylko dlatego, że inny plugin pozwala mu połączyć się z serwerem. Tak samo nie zakładaj zgodności z każdą przyszłą aktualizacją Paper.

## Planowana zgodność 26.1+ i autoryzacja bez wejścia do świata

Celem rozwoju jest Paper/Purpur **26.1+**, Java **25** i Velocity **4.0+**. Obecny build domyślny Paper jest kompilowany przeciwko 26.3 i deklaruje `api-version: '26.1'`. Macierz CI potwierdziła kompilację także z 26.1.2 i 26.2. Dla beta przygotowano trzy warianty JAR kompilowane osobno przeciwko zgodnemu dev bundle. To nadal nie potwierdza zgodności uruchomieniowej. Velocity jest kompilowany z API 4.3.0; inne wersje 4.x muszą przejść osobne testy uruchomieniowe. Na Velocity 4.0–4.2.0 nie ma `getSessionId()` (dostępnego od 4.2.1). Wprowadzono bezpieczny wariant kompatybilności: ponowny lookup Mojang i ocena ryzyka w `LoginEvent`, bez powiązania poprzedniej decyzji po IP, porcie lub nicku. Testy JVM przeszły, lecz należy jeszcze przetestować go na rzeczywistych wydaniach 4.0–4.2.

Dotychczasowy mechanizm PRE_AUTH izoluje gracza dopiero po `PlayerJoinEvent`. Dodano eksperymentalny wariant **Configuration Phase**, który ma zatrzymywać gracza przed utworzeniem encji. Jest on **domyślnie wyłączony** (`authentication.prejoin.experimental-enabled: false`) i nieprzetestowany jeszcze z prawdziwym klientem. Nie włączaj go na serwerze produkcyjnym; konta wymagające migracji OFFLINE -> MOJANG otrzymują formularz z hasłem starego konta, po którym zostają rozłączone na czas bezpiecznej migracji danych (procedura nieprzetestowana jeszcze na rzeczywistym kliencie). Podstawowa ścieżka logowania pozostała bez zmian. Ryzykiem pozostaje zależność od wewnętrznego NMS Paper, w szczególności weryfikacja premium — dlatego beta dostarczy warianty JAR dla poszczególnych wersji Minecraft, a testy runtime są obowiązkowe przed uznaniem kompatybilności za potwierdzoną.

[Zgodność i wymagania](Zgodnosc.md) · [Pobieranie i instalacja](Instalacja.md).

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
[Zgodność i wymagania](Zgodnosc.md).
