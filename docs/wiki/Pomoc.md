# Pomoc i FAQ

[Home](Home.md) · [Poradnik gracza](Poradnik-gracza.md) · [Konfiguracja](Konfiguracja.md)

## Nie działa `/login` lub `/register`

To wydanie korzysta z automatycznego okna Minecrafta. Hasło wpisujesz w jego polu. Przed logowaniem komendy są zablokowane.

## Nie widzę okna logowania

Przy poprawnie sprawdzonym koncie premium okno nie jest potrzebne. Jeśli konto offline nie widzi formularza, administrator powinien sprawdzić wersję klienta, zgodność z serwerem, uruchomienie pluginu i błędy w konsoli. W pierwszej próbie użyj klienta zgodnego z wersją serwera, bez dodatków zmieniających ekran gry.

## Hasła nie są identyczne

Wpisz to samo hasło w oba pola. Sprawdź wielkie litery i przypadkowe spacje. Przy zmianie hasła obecne hasło ma osobne pole.

## Nieprawidłowe dane logowania

Sprawdź nick i hasło. Jeśli nie pamiętasz hasła, poproś administrację o pomoc. Nie zgaduj go wielokrotnie — możesz uruchomić czasową blokadę.

## Konto jest tymczasowo zablokowane

Domyślna blokada po błędnych hasłach trwa 10 minut. Odczekaj i spróbuj ponownie. Administrator może ustawić inny czas. Nie próbuj omijać blokady zmianą nicku lub adresu internetu.

## Zbyt wiele prób

Odczekaj przed następną próbą. Ochrona może reagować na szybkie wejścia, wychodzenie bez logowania lub wiele kont z jednego internetu. Jeśli problem dotyczy domowników, administrator powinien sprawdzić limity wspólnego IP.

## Nie mogę utworzyć kolejnego konta

Domyślny limit wynosi 3 konta offline na IP. Ten limit jest trwały, więc samo czekanie go nie usuwa. Przyczyną odmowy może być też czasowy limit prób albo zajęty nick. Zgłoś problem administracji.

## Nie potwierdzono sesji premium

Zamknij grę i zaloguj się w launcherze na konto Microsoft będące właścicielem nicku. Uruchom Minecrafta ponownie. Więcej na stronie [Premium](Premium.md).

## Nie można sprawdzić statusu nicku

Usługi Minecrafta mogą być niedostępne albo serwer nie ma z nimi połączenia. Spróbuj później. Administrator powinien sprawdzić błędy połączenia i dostęp serwera do internetu. Wyłączenie ochrony nicków nie jest rozwiązaniem tej awarii.

## Serwer osiągnął limit trwających logowań

Poczekaj chwilę. Administrator powinien sprawdzić, czy nie trwa fala połączeń i czy logowanie nie utknęło przez problemy z bazą lub internetem. Samo zwiększenie limitu może powiększyć obciążenie.

## Uwierzytelnianie jest chwilowo niedostępne

Podczas uruchamiania serwer może jeszcze przygotowywać logowanie. Jeśli komunikat nie znika, administrator powinien sprawdzić pierwszy błąd AuthGatewayX w konsoli, połączenie z bazą, pobieranie bibliotek i poprawność configu.

## Komenda zmiany hasła nic nie otwiera

Wykonaj ją w grze po zalogowaniu na konto offline. Sprawdź [uprawnienia](Uprawnienia.md). Konsola nie obsłuży okna, a konto premium nie ma hasła serwerowego do zmiany.

## Czy mogę przeładować plugin bez restartu?

Obecna wersja nie udostępnia komendy reload. Ustawienia i wiadomości zmieniaj z pełnym restartem. Nie używaj narzędzi do wymuszania przeładowania działającego pluginu logowania.

## Czy działa Bedrock, 2FA albo odzyskiwanie przez e-mail?

Te funkcje nie są dostępne w obecnym wydaniu. Geyser/Floodgate, 2FA i odzyskiwanie kont przez e-mail nie należą do gotowej obsługi.

## Jak zgłosić problem?

Podaj wersję AuthGatewayX, wersję serwera i klienta, informację o Velocity oraz kroki prowadzące do błędu. Dołącz treść komunikatu i potrzebny fragment logu. Usuń hasła bazy, sekret Velocity, adresy IP i inne prywatne dane. Nie wysyłaj całej bazy graczy ani zdjęcia widocznego hasła.
