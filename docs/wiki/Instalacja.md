# Pobieranie i instalacja

[Home](Home.md) · [Konfiguracja](Konfiguracja.md) · [Pomoc](Pomoc.md)

## Zanim zaczniesz

Przygotuj serwer testowy Minecraft Java Edition, dostęp do jego plików i konsoli oraz Javę 25. Obecny plugin jest przygotowany dla Paper 26.2; punktem odniesienia jest build 121. Informacje o Purpur, Folia i Velocity znajdziesz w [zgodności](Zgodnosc.md).

Serwer potrzebuje dostępu do internetu, aby pobrać wymagane biblioteki i sprawdzać konta premium. Gracze potrzebują klienta obsługującego okna logowania Minecrafta. Na pierwszą próbę użyj wersji gry zgodnej z serwerem.

## Jaki plik wybrać?

Do serwera Paper wybierz plik `AuthGatewayX-1.0.0-WIP.jar` udostępniony przez autorów. Wersja Velocity jest osobnym plikiem przeznaczonym na proxy. Nie zamieniaj ich miejscami.

Ta wersja WIKI nie zawiera jeszcze potwierdzonego adresu publicznego pobierania. Gdy autorzy udostępnią paczkę testową, możesz przejść przez poniższe kroki.

## Instalacja na jednym serwerze

1. Wyłącz serwer i zrób kopię jego plików.
2. Umieść plik AuthGatewayX w katalogu `plugins`.
3. W `server.properties` ustaw `online-mode=false`. To ustawienie jest wymagane przez mieszany tryb logowania AuthGatewayX.
4. Jeśli serwer działa samodzielnie, pozostaw obsługę Velocity wyłączoną w `config/paper-global.yml`.
5. Uruchom serwer testowy. Plugin przygotuje swoje pliki i bazę kont.
6. Sprawdź konsolę. Jeśli pojawi się błąd uruchomienia AuthGatewayX, rozwiąż go przed otwarciem serwera dla graczy.
7. Wyłącz serwer, dopasuj `plugins/AuthGatewayX/config.yml` i uruchom go ponownie.
8. Sprawdź rejestrację konta offline, ponowne logowanie oraz wejście z własnego konta premium.

Nie zostawiaj publicznie dostępnego serwera z `online-mode=false`, jeśli ochrona logowania nie działa. Na czas instalacji i napraw ogranicz dostęp do serwera testowego.

Nie uruchamiaj dwóch pluginów logowania jednocześnie bez sprawdzonej zgodności. Przeniesienie kont ze starego pluginu wymaga osobnego planu — przeczytaj [bazę danych i kopie](Baza-danych.md).

## Szybka próba po instalacji

- [ ] Nowe konto offline widzi rejestrację z dwoma polami hasła.
- [ ] Przy kolejnym wejściu pojawia się logowanie z jednym polem.
- [ ] Przed logowaniem nie można chodzić, używać skrzynek ani komend.
- [ ] Poprawne hasło otwiera dostęp do gry, a błędne go nie daje.
- [ ] Własne konto premium przechodzi weryfikację.
- [ ] `/changepassword` i `/logout` działają po zalogowaniu konta offline.
- [ ] Po restarcie konto nadal istnieje i można się zalogować.

Masz sieć serwerów? Przejdź do [Velocity](Velocity.md).
