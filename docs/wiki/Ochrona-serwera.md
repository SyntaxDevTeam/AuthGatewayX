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
Raport pokazuje poszlaki do oceny, bez ujawniania adresów. Nie wykrywa samego VPN;
nowy nick i zupełnie nowe IP bez wspólnej historii mogą pozostać niewykryte.
Brak wyników nie jest potwierdzeniem braku multi-konta.

Plugin pamięta do 16 ostatnich różnych adresów na konto. Raport obejmuje obserwacje
obu kont z ostatnich 30 dni, maksymalnie 20 wyników. Historia zaczyna się od instalacji
wersji z tą funkcją i przetrwa restart. Wygasłe wpisy są pomijane; fizyczne usunięcie
następuje przy kolejnym zapisie tego konta. Migracja konta do premium usuwa jego
historię z detektora. Te limity nie zmieniają limitu rejestracji na IP.

Raport działa na Paper/Purpur/Folia, także na backendzie za poprawnie skonfigurowanym
Velocity modern forwarding. Komendę wykonuje się na serwerze gry lub w jego konsoli.
