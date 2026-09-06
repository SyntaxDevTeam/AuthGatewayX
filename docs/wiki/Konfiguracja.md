# Konfiguracja

[Home](Home.md) · [Ochrona serwera](Ochrona-serwera.md) · [Baza danych](Baza-danych.md)

Ustawienia serwera gry znajdziesz w `plugins/AuthGatewayX/config.yml`. Na początek możesz zostawić wartości domyślne. Poniżej opisano wszystkie opcje dostarczane w tym pliku.

## Jak zmieniać ustawienia?

Wyłącz serwer, zrób kopię pliku, zmień wybrane wartości i uruchom serwer ponownie. Używaj spacji zamiast tabulatorów i zachowaj wcięcia. Nie dodawaj drugi raz tej samej sekcji.

Kropka w tabeli oznacza kolejne poziomy pliku. Na przykład `authentication.timeout-seconds` to:

```yaml
authentication:
  timeout-seconds: 120
```

To fragment do odnalezienia i edycji, a nie cały config. Końcówka `seconds` oznacza sekundy, a `millis` milisekundy: `1000` milisekund to sekunda. Liczbowe limity w konfiguracji Paper muszą być dodatnie; nie używaj `0` jako sposobu wyłączania ochrony.

## Język i logowanie

| Opcja | Domyślnie | Znaczenie |
| --- | --- | --- |
| `language` | `PL` | Język wiadomości; wymaga pasującego pliku językowego |
| `authentication.timeout-seconds` | `120` | Czas na logowanie lub rejestrację |
| `authentication.maximum-pre-auth-players` | `500` | Maksymalna liczba graczy czekających na zalogowanie |
| `authentication.password.minimum-length` | `8` | Najkrótsze dozwolone hasło |
| `authentication.password.maximum-length` | `128` | Najdłuższe dozwolone hasło |
| `authentication.lockout.attempts` | `5` | Liczba błędnych prób prowadząca do blokady konta |
| `authentication.lockout.duration-seconds` | `600` | Czas blokady konta |

Minimum długości hasła nie może być większe od maksimum. Wiadomości opisano na stronie [Wiadomości](Wiadomosci.md).

## Sprawdzanie premium

| Opcja | Domyślnie | Znaczenie |
| --- | --- | --- |
| `premium.authentication.maximum-concurrent-handshakes` | `32` | Ile kont premium może być jednocześnie weryfikowanych na samodzielnym serwerze |
| `premium.lookup.timeout-millis` | `3000` | Jak długo czekać na odpowiedź o nicku |
| `premium.lookup.positive-ttl-seconds` | `21600` | Jak długo pamiętać, że nick należy do premium: 6 godzin |
| `premium.lookup.negative-ttl-seconds` | `300` | Jak długo pamiętać brak profilu premium: 5 minut |
| `premium.lookup.maximum-cache-size` | `50000` | Maksymalna liczba zapamiętanych wyników |

Zapamiętywanie wyniku ogranicza powtarzanie zapytań. Nie zastępuje potwierdzenia, że łączący się gracz jest właścicielem konta. Dłuższy czas pamiętania oznacza wolniejsze zauważanie zmian nazw.

## Podejrzane zachowanie

Opcje tej tabeli zaczynają się od `anti-bot.behavior-score.`. Przykładowo pełna nazwa progu to `anti-bot.behavior-score.threshold`.

| Opcja | Domyślnie | Znaczenie |
| --- | --- | --- |
| `threshold` | `40` | Wynik, przy którym adres otrzymuje czasową blokadę |
| `connection-weight` | `1` | Punkty za próbę połączenia |
| `distinct-username-weight` | `5` | Punkty związane z różnymi nickami |
| `authentication-failure-weight` | `8` | Punkty za nieudane uwierzytelnienie |
| `pre-auth-disconnect-weight` | `8` | Punkty za wyjście przed zalogowaniem |
| `window-seconds` | `60` | Okres zbierania punktów |
| `quarantine-seconds` | `600` | Czas blokady adresu |

Niższy próg lub większe punkty oznaczają szybsze blokowanie. Weź pod uwagę osoby korzystające ze wspólnego internetu.

## Nicki, ponowne wejścia i rejestracja

| Opcja | Domyślnie | Znaczenie |
| --- | --- | --- |
| `anti-bot.maximum-tracked-addresses` | `50000` | Limit adresów pamiętanych przez ochronę |
| `anti-bot.username-burst.maximum-distinct-usernames` | `5` | Limit różnych nicków z jednego IP w oknie czasu |
| `anti-bot.username-burst.window-seconds` | `30` | Długość tego okna |
| `anti-bot.username-burst.quarantine-seconds` | `600` | Blokada za przekroczenie limitu |
| `anti-bot.reconnect-loop.maximum-pre-auth-disconnects` | `5` | Limit wyjść przed logowaniem; korzysta z okna i blokady ochrony nicków |
| `anti-bot.registration-attempts.maximum-accounts-per-address` | `3` | Trwały limit kont offline utworzonych z jednego IP |
| `anti-bot.registration-attempts.capacity` | `3` | Maksymalny zapas prób rejestracji na adres |
| `anti-bot.registration-attempts.refill-tokens` | `1` | Ile prób wraca po każdym okresie odnowienia |
| `anti-bot.registration-attempts.refill-seconds` | `60` | Czas odnowienia prób |
| `anti-bot.registration-attempts.state-ttl-seconds` | `600` | Jak długo pamiętać nieaktywny licznik prób |

Zapas prób działa jak bilety: każda próba zużywa jeden, a kolejne stopniowo wracają. Odnowienie prób nie usuwa limitu liczby kont. Przy wyczerpaniu miejsca na bezpieczne śledzenie adresów nowe próby mogą być odrzucane.

## Baza danych

| Opcja | Domyślnie | Znaczenie |
| --- | --- | --- |
| `storage.type` | `SQLITE` | `SQLITE`, `MYSQL`, `MARIADB` lub `POSTGRESQL` |
| `storage.sqlite-file` | `authgatewayx.db` | Nazwa pliku bazy w folderze pluginu |
| `storage.pool-size` | `2` | Maksymalna liczba jednoczesnych połączeń z bazą |
| `storage.remote.host` | `127.0.0.1` | Adres osobnej bazy danych |
| `storage.remote.port` | `3306` | Port bazy; dla PostgreSQL ustaw port swojej usługi, zwykle `5432` |
| `storage.remote.database` | `authgatewayx` | Nazwa bazy |
| `storage.remote.username` | `authgatewayx` | Użytkownik bazy |
| `storage.remote.password` | pusty tekst | Hasło bazy |
| `storage.remote.parameters` | pusty tekst | Dodatkowe ustawienia połączenia wymagane przez hosting |

`storage.remote` jest używane przy bazie innej niż SQLite. Hasło ustawione w środowisku hostingu jako `AUTHGATEWAYX_DB_PASSWORD` ma pierwszeństwo przed hasłem w pliku. Instrukcje i kopie opisuje [Baza danych](Baza-danych.md).

## Ustawienia obciążenia

Te opcje określają, ile zadań może działać naraz i ile może poczekać. Zbyt duże wartości mogą przeciążyć serwer. Na początek pozostaw je bez zmian.

| Opcja | Domyślnie | Znaczenie |
| --- | --- | --- |
| `executors.storage-threads` | `2` | Jednoczesne zadania bazy danych |
| `executors.storage-queue` | `256` | Zadania bazy oczekujące w kolejce |
| `executors.password-threads` | `2` | Jednoczesne zadania sprawdzania lub zabezpieczania haseł |
| `executors.password-queue` | `64` | Oczekujące zadania haseł |
| `executors.mojang-threads` | `4` | Jednoczesne zadania sprawdzania nicków |
| `executors.mojang-queue` | `128` | Oczekujące zadania sprawdzania nicków |
| `authentication.password.argon2.iterations` | `3` | Liczba powtórzeń obliczeń zabezpieczających hasło |
| `authentication.password.argon2.memory-kib` | `65536` | Pamięć na jedno obliczenie hasła: 64 MiB |
| `authentication.password.argon2.parallelism` | `1` | Podział pracy przy jednym obliczeniu hasła |

Przy dwóch jednoczesnych zadaniach haseł same te obliczenia mogą zajmować około 128 MiB. Serwer i pozostałe funkcje potrzebują dodatkowej pamięci. Zwiększanie kolejek nie przyspiesza obsługi.

## Integracje

| Opcja | Domyślnie | Znaczenie |
| --- | --- | --- |
| `integrations.cleanerx.mode` | `AUTO` | Sposób korzystania z CleanerX |
| `integrations.cleanerx.failure-strategy` | `FAIL_CLOSED` | Zachowanie przy błędzie sprawdzenia |
| `integrations.punisherx.mode` | `AUTO` | Sposób korzystania z PunisherX |
| `integrations.punisherx.failure-strategy` | `FAIL_CLOSED` | Zachowanie przy błędzie sprawdzenia |

Tryby to `AUTO`, `REQUIRED` i `DISABLED`. Strategie to `FAIL_CLOSED` i `FAIL_OPEN`. Ich znaczenie oraz przykłady znajdziesz w [Integracjach](Integracje.md).

## Czego nie dopisywać?

Obecny plik nie udostępnia sekcji `anti-flood`, listy dozwolonych komend przed logowaniem ani przełącznika `premium.enabled`. Dopisanie takich nazw nie włączy nowej funkcji. Velocity ma własny, krótszy [plik ustawień](Velocity.md).
