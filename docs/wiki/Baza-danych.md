# Baza danych, kopie i aktualizacje

[Home](Home.md) · [Konfiguracja](Konfiguracja.md) · [Premium](Premium.md)

## Najprostszy start: SQLite

Domyślnie konta są zapisywane w `plugins/AuthGatewayX/authgatewayx.db`. Nie trzeba zakładać osobnego serwera bazy ani wpisywać do niego hasła. Zachowaj ten plik między restartami i aktualizacjami.

Nie używaj tego samego pliku SQLite jednocześnie w kilku niezależnych serwerach.

## MySQL, MariaDB i PostgreSQL

Obecna wersja ma ustawienia tych baz, ale pełne testy na każdym z tych silników są jeszcze do wykonania. Najpierw sprawdź wybrany wariant na kopii serwera.

Utwórz bazę i osobnego użytkownika, a następnie wpisz dane otrzymane od hostingu. Przykładowa sekcja dla MySQL:

```yaml
storage:
  type: MYSQL
  sqlite-file: authgatewayx.db
  pool-size: 2
  remote:
    host: "127.0.0.1"
    port: 3306
    database: "authgatewayx"
    username: "authgatewayx"
    password: "WPISZ_WLASNE_HASLO"
    parameters: ""
```

Zastąp dane przykładowe własnymi. Dla MariaDB wybierz `MARIADB`, a dla PostgreSQL `POSTGRESQL` i właściwy port — zwykle `5432`. Wymagania szyfrowania połączenia i pole `parameters` uzgodnij z hostingiem.

Możesz ustawić hasło w panelu hostingu jako zmienną `AUTHGATEWAYX_DB_PASSWORD`. Wtedy ma ono pierwszeństwo przed wartością z configu. Nie udostępniaj nikomu publicznie hasła ani całego pliku z danymi dostępu.

## Kopia zapasowa

1. Wyłącz serwer, aby dane nie zmieniały się podczas kopiowania.
2. Przy SQLite skopiuj cały folder `plugins/AuthGatewayX`, razem z plikami bazy, jeśli obok głównego pliku znajdują się dodatkowe pliki SQLite.
3. Przy osobnej bazie wykonaj jej pełny backup narzędziem hostingu i zachowaj również folder pluginu.
4. Zapisz wersję pluginu i serwera, z którymi wykonano kopię.
5. Przechowuj kopię poza folderem działającego serwera, z dostępem tylko dla zaufanych osób.

Sprawdź odtworzenie na prywatnym serwerze testowym. Sama obecność pliku kopii nie daje pewności, że można go poprawnie przywrócić.

## Aktualizacja pluginu

Zrób kopię, zatrzymaj serwer, wymień plik pluginu i uruchom najpierw kopię testową. Porównaj ustawienia z nową wersją i sprawdź logowanie istniejącego konta, rejestrację oraz restart. Nie usuwaj bazy, żeby „odświeżyć” instalację.

Jeśli aktualizacja zmieniła zapis danych, cofnięcie samego pliku pluginu może nie wystarczyć. Przywróć pasujące do siebie kopie pluginu, bazy i konfiguracji przy wyłączonym serwerze.

## Przenoszenie kont

Zmiana `storage.type` nie przenosi automatycznie kont. Podłączenie pustej bazy może wyglądać dla graczy jak nowa instalacja. Nie udostępniaj jej publicznie, zanim dane zostaną poprawnie przeniesione i sprawdzone.

Obecna wersja nie ma gotowego importera kont z innych pluginów logowania ani komendy do takiej migracji. Nie kopiuj ich tabel w ciemno. Przejście offline → premium opisuje strona [Premium](Premium.md).
