# Komendy

[Home](Home.md) · [Uprawnienia](Uprawnienia.md) · [Poradnik gracza](Poradnik-gracza.md)

Poniższe komendy działają na serwerze gry. Używa się ich po zalogowaniu, bezpośrednio w Minecraft. Konsola nie otworzy okna do wpisania hasła, ale obsługuje raport multi-kont.

| Komenda | Dla kogo | Co robi |
| --- | --- | --- |
| `/changepassword` | Zalogowane konto offline | Otwiera okno zmiany własnego hasła |
| `/logout` | Zalogowane konto offline | Kończy sesję i rozłącza gracza |
| `/authgatewayx alts <nick>` | Zalogowany administrator lub konsola | Pokazuje podejrzane powiązania kont offline przez historię wspólnych adresów |
| `/authgatewayx setpassword <nick>` | Administrator z odpowiednim uprawnieniem | Otwiera okno ustawienia nowego hasła konta offline |

`<nick>` zastąp nazwą gracza, bez nawiasów. Przykład: `/authgatewayx setpassword Alex`.

## Ustawienie hasła przez administratora

Najpierw upewnij się, że pomagasz właścicielowi konta. Zaloguj się na serwerze, wpisz komendę z nickiem, a nowe hasło podaj w otwartym oknie. Operacja zostaje zapisana w historii zdarzeń. Jeśli wskazany gracz jest online, zostanie rozłączony i będzie musiał zalogować się nowym hasłem.

Przekaż nowe hasło prywatnie i poproś gracza o zmianę przez `/changepassword`. Ta komenda nie zmienia hasła konta Microsoft i nie służy do resetowania kont premium.

## A gdzie `/login` i `/register`?

Logowanie i rejestracja otwierają się automatycznie w oknie. Ta wersja nie udostępnia tych komend. Przed zalogowaniem wszystkie komendy są zablokowane.

Nie ma również komend `/premium`, `/unregister` ani `/authgatewayx reload`. Po edycji ustawień wykonaj pełny restart serwera.

## Podejrzane multi-konta

`/authgatewayx alts Alex` pokazuje konta offline używające wspólnych adresów z Alexem
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
