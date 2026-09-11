# Ochrona serwera

[Home](Home.md) · [Konfiguracja](Konfiguracja.md) · [Integracje](Integracje.md)

## Najpierw logowanie, potem rozgrywka

Przed podaniem poprawnego hasła gracz nie może poruszać się, niszczyć ani stawiać bloków, używać przedmiotów, walczyć, pisać na czacie czy wykonywać komend. Jest też oddzielony od zalogowanych graczy i chroniony przed obrażeniami.

Ochrona obejmuje również mniej oczywiste akcje, takie jak używanie wiader, książek, pojazdów i portali. Własny zestaw pluginów zawsze sprawdź na serwerze testowym; zgodność wszystkich możliwych dodatków nie jest zagwarantowana.

## Zgadywanie hasła

AuthGatewayX ogranicza próby logowania. Domyślnie po 5 błędnych próbach konto zostaje czasowo zablokowane na 10 minut. Dodatkowe limity mogą zatrzymać próby wcześniej.

Hasła są przechowywane w postaci wyniku zabezpieczającego obliczenia, a nie czytelnego tekstu. Nie da się po prostu otworzyć pliku kont i przeczytać hasła gracza.

## Boty i szybkie ponowne połączenia

Plugin obserwuje między innymi wiele wejść z jednego adresu internetu, zmiany nicków, błędne logowania i wychodzenie przed zakończeniem logowania. Podejrzane zachowanie może spowodować czasową blokadę adresu.

Domyślnie użycie więcej niż 5 różnych nicków z jednego IP w ciągu 30 sekund uruchamia blokadę na 10 minut. Osobny licznik przyznaje punkty za podejrzane działania. Limity chronią też liczbę osób jednocześnie oczekujących na logowanie.

## Kilka osób w jednym domu

Rodzeństwo lub znajomi korzystający z tego samego Wi-Fi mogą mieć wspólne IP. Domyślny limit to 3 zarejestrowane konta offline na adres. To limit zapisanych kont, więc samo odczekanie lub restart serwera go nie zeruje.

Jeśli prowadzisz serwer dla większej grupy korzystającej z jednego internetu, uwzględnij to w ustawieniach. Zmieniaj jeden limit naraz i obserwuj efekty.

## Co zostaje zapisane?

Historia bezpieczeństwa obejmuje takie zdarzenia jak rejestracja, logowanie, odmowy, zmiana hasła i wylogowanie. Może zawierać nick, identyfikator konta i IP. Nie publikuj bazy ani pełnych danych graczy. Hasła nie są treścią tych wpisów.

AuthGatewayX pomaga ograniczać nadużycia w logowaniu. Nie zastępuje ochrony hostingu przed atakiem przeciążającym całe łącze. Testy obciążeniowe obecnej wersji są jeszcze do wykonania.

## Detektor podejrzanych multi-kont

Administrator może użyć `/authgatewayx alts <nick>`. Raport porównuje historię
poprawnych logowań i rejestracji kont offline, więc wcześniejsze wspólne IP może
pozostać widoczne po zmianie adresu. Nie zapisuje powiązań na podstawie błędnych haseł
ani samego wejścia pod cudzym nickiem. Nie nakłada automatycznie kar.

Wspólne IP może oznaczać jedną osobę, rodzinę, NAT operatora albo wspólny VPN.
Raport pokazuje poszlaki do oceny, bez ujawniania adresów. Sam raport nie wykrywa VPN;
nowy nick i zupełnie nowe IP bez wspólnej historii mogą pozostać niewykryte.
Brak wyników nie jest potwierdzeniem braku multi-konta.

Plugin pamięta do 16 ostatnich różnych adresów na konto. Raport obejmuje obserwacje
obu kont z ostatnich 30 dni, maksymalnie 20 wyników. Historia zaczyna się od instalacji
wersji z tą funkcją i przetrwa restart. Wygasłe wpisy są pomijane; fizyczne usunięcie
następuje przy kolejnym zapisie tego konta. Migracja konta do premium usuwa jego
historię z detektora. Te limity nie zmieniają limitu rejestracji na IP.

Raport działa na Paper/Purpur/Folia, także na backendzie za poprawnie skonfigurowanym
Velocity modern forwarding. Komendę wykonuje się na serwerze gry lub w jego konsoli.

## Automatyczne powiadomienia i VPN/GeoIP

`multi-account.alerts.enabled: true` włącza powiadomienia o podejrzanych kontach po
poprawnym logowaniu/rejestracji offline. Odbiorcy muszą być zalogowani i mieć
`authgatewayx.admin.alerts` (domyślnie operatorzy). `multi-account.alerts.console`
steruje kopią w konsoli. Domyślny cooldown jednego konta to 300 sekund.
Alert pokazuje do pięciu powiązanych nicków; pełniejszy raport daje `/authgatewayx alts`.

Opcjonalne `ip-intelligence.enabled: true` uruchamia [proxycheck.io v3](https://proxycheck.io/api/).
Domyślnie ta opcja jest wyłączona. Po włączeniu publiczne IP gracza trafia do dostawcy
przez HTTPS; nick, UUID i hasło nie są wysyłane. Klucz można ustawić w
`AUTHGATEWAYX_PROXYCHECK_API_KEY` albo `ip-intelligence.api-key`. Pusty klucz korzysta
z limitów anonimowych; sprawdź aktualny limit planu u dostawcy.

`ip-intelligence.notify-on-vpn` pozwala zgłaszać VPN/proxy/Tor także bez powiązanego
konta. `ip-intelligence.show-geo` dodaje kraj i ASN do podejrzanych alertów. Sam kraj,
ASN ani hosting nie wywołują alarmu. Dane dotyczą wyjścia sieciowego, nie miejsca
pobytu osoby. Znak `?` oznacza brak danych; awaria dostawcy nie oznacza „brak VPN”.

Sprawdzenia są ograniczone równoległością, cooldownem, cache i budżetem żądań. Przy
przeciążeniu część sprawdzeń może zostać pominięta. Timeout/błąd API nie zatrzymuje
logowania ani lokalnego raportu. Nie są nakładane bany ani blokady krajów. Historia
multi-kont i VPN pozostają poszlakami, a nie dowodem tożsamości.

Po zmianie configu wykonaj pełny restart. Testy automatyczne używają atrap i lokalnego
serwera HTTP; test realnego dostawcy oraz wszystkich platform pozostaje wymagany.
