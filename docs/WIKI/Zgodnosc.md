# Zgodność i wymagania

**Język / Language:** Polski · [English](EN/Compatibility.md)

[Home](Home.md) · [Instalacja](Instalacja.md)

## Dobierz plik do serwera

AuthGatewayX wymaga **Javy 25**. Wybierz JAR zgodny z platformą i wersją Minecraft swojego serwera.

| Platforma | Sposób instalacji |
| --- | --- |
| Paper / Purpur | Wybierz wariant Minecraft 26.1.2, 26.2 lub 26.3; nie zakładaj, że jeden JAR pasuje do wszystkich wersji |
| Folia | Używaj wyłącznie dystrybucji wyraźnie oznaczonej dla Twojej wersji Folia |
| Velocity | Zainstaluj osobny JAR na proxy i moduł Paper na backendzie; skorzystaj z [instrukcji sieci](Velocity.md) |
| Spigot / Bukkit | Brak osobnego wspieranego wariantu |
| Bedrock przez Geyser/Floodgate | Brak potwierdzonej obsługi |

Gracze potrzebują klientów obsługujących formularze logowania. Samo dopuszczenie starszego klienta przez inny plugin nie gwarantuje, że formularze będą działały.

## Logowanie przed wejściem do świata

Domyślnie gracz czekający na logowanie pozostaje odizolowany od rozgrywki. Opcjonalny tryb `authentication.prejoin.experimental-enabled` przenosi logowanie przed wejście do świata. Jest domyślnie wyłączony; na publicznym serwerze pozostaw go wyłączonym. Przy przejściu offline → premium gracz potwierdza stare hasło serwerowe i zostaje rozłączony na czas przenoszenia danych.

## PlugManX i restarty

Używaj PlugManX tylko wtedy, gdy obsługuje natywne pluginy Paper i konkretną wersję Twojego serwera. Starsze wersje bez obsługi natywnego Paper są niezgodne. Do wprowadzania zmian konfiguracji zalecany jest pełny restart procesu serwera.

`/plugman restart AuthGatewayX` i `/plugman reload AuthGatewayX` rozłączają wszystkich graczy. Połączenia są przyjmowane po komunikacie READY w konsoli; każdy gracz loguje się ponownie. Po disable/unload połączenia i ping statusu pozostają zablokowane do enable/load lub restartu procesu. Nie usuwaj ręcznie ochrony połączeń.

Jeżeli sprzątanie zasobów zakończy się błędem lub przekroczy czas oczekiwania, zrestartuj proces serwera przed kolejną próbą.

## Niedostępne funkcje

Panel WWW, 2FA, łączenie konta z Discordem, odzyskiwanie przez e-mail i synchronizacja sesji między wieloma proxy nie są dostępne.

## Komunikaty wersji przy starcie

Podczas startu plugin zapisuje zgodność numeru Minecraft z listą docelową (26.1, 26.1.1, 26.1.2, 26.2, 26.3) lub ostrzega o wersji spoza listy. Dobierz JAR do wersji serwera; sam komunikat o numerze wersji nie gwarantuje działania wszystkich funkcji. Nierozpoznany numer lub brak wymaganych nowoczesnych zdarzeń logowania zatrzymuje inicjalizację z zamkniętą bramką połączeń.
