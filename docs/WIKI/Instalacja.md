# Pobieranie i instalacja

**Język / Language:** Polski · [English](EN/Installation.md)

[Home](Home.md) · [Konfiguracja](Konfiguracja.md) · [Pomoc](Pomoc.md)

## Zanim zaczniesz

Przygotuj serwer testowy Minecraft Java Edition, dostęp do jego plików i konsoli oraz Javę 25. Domyślny build jest przygotowany dla Paper 26.3; warianty dla innych wersji Minecraft dobieraj zgodnie z opisem artefaktu. Informacje o Purpur, Folia i Velocity znajdziesz w [zgodności](Zgodnosc.md).

Serwer potrzebuje dostępu do internetu, aby pobrać wymagane biblioteki i weryfikować oficjalne tożsamości Minecraft. Gracze potrzebują klienta obsługującego okna logowania Minecrafta. Na pierwszą próbę użyj wersji gry zgodnej z serwerem.

## Jaki plik wybrać?

Do serwera Paper wybierz plik `LoginCoreX-Paper-1.0.0-R0.1-SNAPSHOT.jar` udostępniony przez autorów. Wersja Velocity jest osobnym plikiem przeznaczonym na proxy. Nie zamieniaj ich miejscami.

### Pobierz gotowy artefakt

Otwórz [GitHub Actions LoginCoreX](https://github.com/SyntaxDevTeam/LoginCoreX/actions), wybierz udane uruchomienie workflow pakowania i sekcję **Artifacts**. Zaloguj się do GitHub, pobierz archiwum i rozpakuj JAR. Artefakty mają ograniczony czas przechowywania.

Workflow **Package snapshot distributions** udostępnia paczki Paper dla poszczególnych wersji Minecraft i osobną paczkę Velocity wraz z sumami SHA-256. Wybierz plik zgodny z platformą i wersją swojego serwera. Po instalacji sprawdź logowanie i komunikaty w konsoli.

Nie instaluj kilku wariantów Paper jednocześnie ani plików `sources`, `javadoc` czy `plain`. Nazwy plików mogą zawierać dodatkowy numer buildu lub oznaczenie wersji Minecraft.

### Zbuduj samodzielnie

Jeśli gotowy artefakt nie jest dostępny, sklonuj repozytorium LoginCoreX i z Javą 25 uruchom:

```bash
./gradlew clean buildAll
```

Wyniki znajdziesz w `logincorex-paper/build/libs` i `logincorex-velocity/build/libs`. Zbudowanie innego wariantu Minecraft opisuje [Pobieranie i instalacja](Instalacja.md).

## Instalacja na jednym serwerze

1. Wyłącz serwer i zrób kopię jego plików.
2. Umieść plik LoginCoreX w katalogu `plugins`.
3. W `server.properties` ustaw `online-mode=false`. To ustawienie pozwala LoginCoreX obsługiwać weryfikację tożsamości i logowanie wszystkich graczy.
4. Jeśli serwer działa samodzielnie, pozostaw obsługę Velocity wyłączoną w `config/paper-global.yml`.
5. Uruchom serwer testowy. Plugin przygotuje swoje pliki i bazę kont.
6. Sprawdź konsolę. Jeśli pojawi się błąd uruchomienia LoginCoreX, rozwiąż go przed otwarciem serwera dla graczy.
7. Wyłącz serwer, dopasuj `plugins/LoginCoreX/config.yml` i uruchom go ponownie.
8. Sprawdź rejestrację i ponowne logowanie każdego gracza, także z oficjalnym kontem Minecraft.

Nie zostawiaj publicznie dostępnego serwera z `online-mode=false`, jeśli ochrona logowania nie działa. Na czas instalacji i napraw ogranicz dostęp do serwera testowego.

Nie uruchamiaj dwóch pluginów logowania jednocześnie bez sprawdzonej zgodności. Przeniesienie kont ze starego pluginu wymaga osobnego planu — przeczytaj [bazę danych i kopie](Baza-danych.md).

## Szybka próba po instalacji

- [ ] Nowy gracz widzi rejestrację z dwoma polami hasła.
- [ ] Przy kolejnym wejściu pojawia się logowanie z jednym polem.
- [ ] Przed logowaniem nie można chodzić, używać skrzynek ani komend.
- [ ] Poprawne hasło otwiera dostęp do gry, a błędne go nie daje.
- [ ] Oficjalne konto Minecraft przechodzi weryfikację i domyślnie również wymaga hasła serwerowego.
- [ ] `/changepassword` i `/logout` działają po zalogowaniu każdego konta.
- [ ] Po restarcie konto nadal istnieje i można się zalogować.

Masz sieć serwerów? Przejdź do [Velocity](Velocity.md).

## Zmiana nazwy — LoginCoreX

LoginCoreX — System uwierzytelniania i ochrony graczy. Przed aktualizacją zatrzymaj
proxy i backendy oraz wykonaj pełną kopię katalogu pluginu i bazy danych.
Przenieś istniejący katalog `plugins/AuthGatewayX` do `plugins/LoginCoreX` (na Velocity
`plugins/authgatewayx` do `plugins/logincorex`). Nie uruchamiaj dwóch wersji jednocześnie.
Na proxy zmień nazwę pliku `authgatewayx.yml` na `logincorex.yml`.
Zachowaj istniejącą bazę: ustaw `storage.sqlite-file` na jej dotychczasową nazwę lub,
przy zatrzymanym serwerze, przenieś bazę SQLite wraz z plikami WAL/SHM na nazwę
`logincorex.db`. Dla zdalnej bazy zachowaj URL, nazwę bazy i dane dostępowe.
Nie twórz pustej bazy w miejsce istniejących kont.

Zaktualizuj permisje `authgatewayx.*` na `logincorex.*`, komendy `/authgatewayx` i `/agx`
na `/logincorex` i `/lcx`, zmienne środowiskowe `AUTHGATEWAYX_*` na `LOGINCOREX_*`
oraz zmienne narzędzi `AGX_*` na `LCX_*`. Integracje API wymagają ponownej kompilacji
z pakietem `pl.syntaxdevteam.logincorex`, typem `LoginCoreApi` i artefaktami
`logincorex-api`/`logincorex-domain`. Zaktualizuj również skrypty i zależności pluginów.
W sieci proxy wdrażaj zgodne wersje na obu końcach: kanały używają `logincorex:*`.
Klient CraftConnect wymaga dopasowania nazw kanałów i permisji; framing v1, magic
`0x41475843` i domena podpisu `AGX-CRAFTCONNECT-PAIR-V1` pozostają niezmienione.
Po aktualizacji sprawdź liczbę kont, logowanie oraz uprawnienia administracji na
prywatnym serwerze. Testy rzeczywistych klientów Paper/Purpur/Folia/Velocity pozostają
osobną bramką wdrożenia.
