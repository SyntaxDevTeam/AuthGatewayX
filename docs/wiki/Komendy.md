# Komendy

[Home](Home.md) · [Uprawnienia](Uprawnienia.md) · [Poradnik gracza](Poradnik-gracza.md)

Poniższe komendy działają na serwerze gry. Administrator w grze musi być zalogowany;
samo uprawnienie nie omija ochrony logowania. Konsola obsługuje raporty i migracje, ale
nie otworzy okna do wpisania hasła. Podstawowa komenda `/authgatewayx` ma krótszy alias
`/agx`.

| Komenda | Kto i gdzie | Co robi | Jaką informację zwrotną daje |
| --- | --- | --- | --- |
| `/changepassword` | Zalogowany gracz offline | Otwiera bezpieczne okno zmiany własnego hasła | Okno wyjaśnia, czy stare hasło jest błędne, nowe hasła są różne albo zmiana się udała |
| `/logout` | Zalogowany gracz offline | Unieważnia sesję i rozłącza gracza | Ekran rozłączenia potwierdza bezpieczne zakończenie sesji; konto premium ani gracz bez aktywnej sesji offline nie są zmieniani |
| `/authgatewayx info <nick>` | Zalogowany administrator lub konsola | Pokazuje raport konta i bieżącą klasyfikację nicku Mojang | Pokazuje znalezione sekcje raportu albo informuje o braku konta lub chwilowej niedostępności; zakres zależy od uprawnień |
| `/authgatewayx alts <nick>` | Zalogowany administrator lub konsola | Szuka kont połączonych historią wspólnych adresów | Pokazuje wyniki, brak powiązań, obcięcie do 20 pozycji, brak konta albo chwilową niedostępność |
| `/authgatewayx setpassword <nick>` | Zalogowany administrator **w grze** | Otwiera okno ustawienia nowego hasła konta offline | Informuje o braku konta, złym typie konta, różnych hasłach albo sukcesie; aktywny cel zostaje rozłączony |
| `/authgatewayx migrate status <nick>` | Zalogowany administrator lub konsola | Pokazuje ostatni trwały ticket migracji UUID | Zwraca typ, stan, stare i nowe UUID, ostatni błąd albo informację o braku ticketu/konta |
| `/authgatewayx migrate inspect <nick> [stare-uuid]` | Zalogowany administrator lub konsola | Sprawdza migratory i ślady danych bez zmieniania danych | Pokazuje wynik każdego migratora albo błąd UUID, brak konta czy chwilową niedostępność |
| `/authgatewayx migrate retry <nick>` | Zalogowany administrator lub konsola | Ponawia istniejący niezakończony ticket z tymi samymi kopiami | Informuje o starcie, ukończeniu, blokadzie i jej przyczynie, błędzie, trwającej operacji lub graczu online |
| `/authgatewayx migrate recover <nick> [stare-uuid]` | Zalogowany administrator lub konsola | Przygotowuje recovery konta zmienionego przez starszą wersję | Pokazuje znalezione dowody i wynik migratorów; odmawia bez śladu danych, dla konta non-premium, konfliktu tożsamości lub gracza online |

Komenda administracyjna `/authgatewayx` ma alias `/agx`; podkomendy, argumenty i
uprawnienia są identyczne, np. `/agx migrate status Alex`.

`<nick>` zastąp nazwą gracza, bez nawiasów. Przykład: `/authgatewayx setpassword Alex`.

## Raport konta `info`

Podstawowy raport pokazuje nick, ID konta AuthGatewayX, Minecraft UUID, typ i stan konta,
daty, aktywną sesję, sposób logowania oraz aktualny wynik sprawdzenia nicku Mojang.
Nick bez konta w bazie nadal może otrzymać klasyfikację premium/non-premium.

Dodatkowe uprawnienia dodają historię IP, GeoIP i reputację sieci, stan blokady, licznik
błędnych logowań, najwyżej 10 najnowszych zdarzeń audytu oraz powiązane konta. Pełna
historia audytu pozostaje w bazie. Konsola widzi wszystkie sekcje. Hashe haseł i sekrety
nigdy nie są pokazywane.

Dialog rozdziela długie identyfikatory do osobnych wierszy, używa węższej kolumny oraz
skraca znaczniki czasu do sekund w UTC, aby dane nie wychodziły poza ekran przy typowej
skali interfejsu Minecrafta. Konsola nadal otrzymuje ten sam raport tekstowy.

## Ustawienie hasła przez administratora

Najpierw upewnij się, że pomagasz właścicielowi konta. Zaloguj się na serwerze, wpisz komendę z nickiem, a nowe hasło podaj w otwartym oknie. Operacja zostaje zapisana w historii zdarzeń. Jeśli wskazany gracz jest online, zostanie rozłączony i będzie musiał zalogować się nowym hasłem.

Przekaż nowe hasło prywatnie i poproś gracza o zmianę przez `/changepassword`. Ta komenda nie zmienia hasła konta Microsoft i nie służy do resetowania kont premium.

## A gdzie `/login` i `/register`?

Logowanie i rejestracja otwierają się automatycznie w oknie. Ta wersja nie udostępnia tych komend. Przed zalogowaniem wszystkie komendy są zablokowane.

Nie ma również komend `/premium`, `/unregister` ani `/authgatewayx reload`. Po edycji ustawień wykonaj pełny restart serwera.

## Podejrzane multi-konta

`/authgatewayx alts Alex` pokazuje konta używające wspólnych adresów z Alexem
w dostępnej historii ostatnich 30 dni oraz liczbę wspólnych adresów. Wymaga
`authgatewayx.admin.alts`. Wynik zawiera maksymalnie 20 kont i informuje o obcięciu.
Szczegóły i ograniczenia opisuje [Ochrona serwera](Ochrona-serwera.md).

## VPN i multi-konta na proxy

Velocity ma własne ustawienia w `authgatewayx.yml`: `storage.enabled` włącza odczyt
wspólnej bazy Paper, `multi-account.action` wybiera `ALERT`, `DENY` lub `DISABLED`.
`ip-intelligence.enabled` włącza VPN/proxy/Tor (domyślna akcja `DENY`);
`show-geo` dodaje kraj i ASN. Kontrole odrzucają połączenie przed serwerem gry.
Obie integracje są domyślnie wyłączone; brak wspólnej historii nie wykryje zmiany IP.

`alerts.enabled`, `alerts.console` i `alerts.cooldown-seconds` sterują powiadomieniami
proxy. Alert dotyczy próby wejścia pod nickiem, nie potwierdzonego logowania hasłem.
Nadaj na proxy `authgatewayx.admin.alerts` oraz `authgatewayx.admin.alts` dla raportu
`/authgatewayx alts <nick>`. Pozostałe podkomendy trafiają do Paper.
Administrator offline wymaga poprawnego zalogowania na backendzie i wspólnego,
osobnego `staff-proof.secret` (co najmniej 32 bajty) na Paper i proxy.
Konsola i administrator uwierzytelniony przez Mojang nie potrzebują tego sekretu.

Pełne ustawienia, zachowanie przy awarii i kolejność wdrożenia:
[instrukcja kontroli proxy](../09-proxy-risk-admission.md). Opcje alertów opisane
powyżej dla Paper pozostają lokalne; można je wyłączyć, aby nie dublować powiadomień.


## Migracja OFFLINE → PREMIUM

Dla nowych przypadków migracja uruchamia się automatycznie po poprawnej weryfikacji Mojang
i podaniu starego hasła konta non-premium. Gracz jest rozłączany przed kopiowaniem danych.

Komendy `migrate` służą przede wszystkim administracji:

- `status` — odczyt trwałego ticketu: typ `UPGRADE/RECOVERY`, stan, source/target UUID i ostatni błąd,
- `inspect` — diagnostyka providerów bez zapisu; opcjonalne UUID pomaga, gdy historyczna wielkość liter nicku zmieniła standardowe offline UUID,
- `retry` — ponawia ten sam ticket; nie tworzy nowej migracji i nie usuwa backupów,
- `recover` — naprawia konto już zmigrowane przez starą wersję AuthGatewayX.

`recover` nie ufa samemu nickowi. AuthGatewayX musi znaleźć rzeczywisty ślad danych pod
starym UUID w kontrolowanym providerze lub w skanerze niezarządzanych katalogów. Jeżeli
nie ma dowodu danych, recovery nie zostanie utworzone.

Docelowe konto musi być offline podczas `retry` i `recover`. Niedokończone recovery
blokuje logowanie premium do czasu bezpiecznego zakończenia albo skutecznego rollbacku.
