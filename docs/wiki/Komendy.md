# Komendy

[Home](Home.md) · [Uprawnienia](Uprawnienia.md) · [Poradnik gracza](Poradnik-gracza.md)

Poniższe komendy działają na serwerze gry. Używa się ich po zalogowaniu, bezpośrednio w Minecraft. Konsola nie otworzy okna do wpisania hasła, ale obsługuje raport multi-kont.

| Komenda | Dla kogo | Co robi |
| --- | --- | --- |
| `/changepassword` | Zalogowane konto offline | Otwiera okno zmiany własnego hasła |
| `/logout` | Zalogowane konto offline | Kończy sesję i rozłącza gracza |
| `/agx`, `/agx help` | Zalogowany gracz lub konsola | Pokazuje sformatowaną listę komend dostępnych dla bieżącego nadawcy |
| `/agx info <nick>` | Zalogowany administrator lub konsola | Pokazuje raport konta, sesji, bezpieczeństwa i danych dostępnych dla uprawnień administratora |
| `/agx alts <nick>` | Zalogowany administrator lub konsola | Pokazuje podejrzane powiązania kont offline przez historię wspólnych adresów |
| `/agx setpassword <nick>` | Administrator z odpowiednim uprawnieniem | Otwiera okno ustawienia nowego hasła konta offline |
| `/agx migrate status <nick>` | Administrator lub konsola | Pokazuje ostatni trwały ticket migracji UUID |
| `/agx migrate inspect <nick> [stare-uuid]` | Administrator lub konsola | Sprawdza providery i potencjalne dane starego UUID bez wykonywania migracji |
| `/agx migrate retry <nick>` | Administrator lub konsola | Ponawia istniejący `FAILED/PREPARED/MIGRATING` ticket przy użyciu tych samych backupów |
| `/agx migrate recover <nick> [stare-uuid]` | Administrator lub konsola | Tworzy recovery dla konta już przełączonego przez starszą wersję AGX, wyłącznie po znalezieniu śladów starego UUID |

Komenda administracyjna `/authgatewayx` ma alias `/agx`; podkomendy, argumenty i
uprawnienia są identyczne, np. `/agx migrate status Alex`. Samo `/agx` oraz
`/agx help` wyświetla czytelną, kolorową listę komend. Lista jest filtrowana według
uprawnień nadawcy, więc zwykły gracz nie zobaczy komend administracyjnych, których nie
może wykonać. W grze pozycje listy są klikalne i wstawiają odpowiednią komendę do pola
czatu bez automatycznego jej wykonywania.

Dla `/authgatewayx alts <nick>` (także `/agx alts <nick>`) klient Minecraft
podpowiada nazwy aktualnie połączonych graczy podczas wpisywania argumentu. Podpowiedzi
są tylko ułatwieniem — nadal można ręcznie podać nazwę gracza offline. Lista jest
udostępniana konsoli albo zalogowanemu graczowi; sama gałąź `alts` nadal wymaga
`authgatewayx.admin.alts`, a sesja PRE_AUTH nie otrzymuje tych nazw.

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
