# Możliwości pluginu

[Home](Home.md) · [Instalacja](Instalacja.md) · [Komendy](Komendy.md) · [Zgodność](Zgodnosc.md)

Ta strona opisuje funkcje dostępne w obecnej wersji **1.0.0-WIP**. WIP oznacza wersję
rozwojową, a nie gotowe wydanie produkcyjne. Funkcja opisana poniżej może nadal wymagać
testów na konkretnej platformie; ograniczenia zebrano na stronie [Zgodność](Zgodnosc.md).

## Logowanie graczy

- Konto non-premium rejestruje się i loguje w oknie Minecrafta. Hasło nie trafia do
  czatu ani do argumentu komendy.
- Konto premium jest sprawdzane przez usługi Minecraft. Po poprawnej weryfikacji nie
  podaje hasła serwerowego.
- Nick premium jest chroniony. Nieudana weryfikacja właściciela kończy połączenie —
  plugin nie wpuszcza wtedy gracza jako konta offline.
- Plugin pilnuje jednej aktywnej sesji konta i pozwala graczowi bezpiecznie zakończyć ją
  komendą `/logout`.
- Gracz może zmienić własne hasło, a uprawniony administrator może ustawić nowe hasło
  konta offline bez umieszczania go w historii komend.

## Ochrona świata przed zalogowaniem

Do chwili poprawnego logowania gracz pozostaje w stanie chronionym. Nie może normalnie
chodzić, walczyć, pisać na czacie, wykonywać komend, przenosić przedmiotów, używać bloków
ani wpływać na świat. Plugin blokuje również mniej oczywiste drogi działania, między
innymi wiadra, książki i pulpity, stojaki na zbroję, strzyżenie, wędki, pociski oraz
wiszące obiekty. Lista podpowiedzi komend jest wtedy ukryta.

Limit czasu i maksymalna liczba osób oczekujących na logowanie chronią serwer przed
zapełnieniem sesjami, które nigdy nie kończą uwierzytelniania.

## Hasła i nadużycia

- Hasła kont offline są zabezpieczane algorytmem Argon2id, a kosztowne obliczenia mają
  ograniczoną liczbę wątków i kolejkę.
- Kolejne błędne hasła uruchamiają limit prób i czasową blokadę konta.
- Ochrona połączeń ogranicza ruch z jednego adresu i całego serwera, zanim rozpocznie się
  kosztowne sprawdzanie bazy lub usług zewnętrznych.
- Plugin wykrywa szybkie zmiany nicków, wielokrotne rozłączenia przed logowaniem i inne
  podejrzane zachowanie z jednego adresu. Może czasowo poddać taki adres kwarantannie.
- Rejestracja ma krótkoterminowy limit prób oraz trwały limit liczby kont z jednego
  adresu IP. Limity mogą dotknąć także prawdziwych graczy we wspólnej sieci, dlatego
  administrator powinien dobierać je do swojej społeczności.

## Konta premium i zmiana UUID

Gdy dotychczasowy gracz non-premium kupi Minecraft i zachowa nick, plugin prosi o stare
hasło serwerowe i tworzy kontrolowaną migrację `OFFLINE -> PREMIUM`. Dane nie są
przenoszone przez ślepą zamianę UUID. Wbudowane migratory obsługują dane vanilla,
EssentialsX, LuckPerms, HorseManagerX, PunisherX oraz PlotsX, jeśli wymagana integracja
jest dostępna. Niezarządzane dane mogą zatrzymać zmianę zamiast ryzykować ich utratę.

Migracja tworzy kopie, potrafi wycofać wykonane kroki i jest wznawiana po restarcie.
Administracja może sprawdzić jej stan, wykonać inspekcję, ponowić ticket lub przygotować
recovery dla konta zmienionego przez starszą wersję. Szczegóły opisują strony
[Konta premium](Premium.md) i [Komendy](Komendy.md).

## Narzędzia administracji

- `/authgatewayx info <nick>` pokazuje tożsamość konta, stan, daty, bieżącą sesję i
  aktualną klasyfikację nicku Mojang. Dodatkowe uprawnienia odsłaniają IP, GeoIP,
  reputację sieci, blokadę, ostatnie zdarzenia bezpieczeństwa i powiązane konta.
- `/authgatewayx alts <nick>` szuka kont korzystających ze wspólnych adresów w
  ograniczonej historii. Wspólne IP jest poszlaką, a nie dowodem jednej osoby.
- Automatyczne alerty mogą informować zalogowaną administrację i konsolę o podejrzanym
  koncie, VPN, proxy lub Tor. Zewnętrzne sprawdzanie IP jest domyślnie wyłączone.
- Osobne uprawnienia rozdzielają zwykły raport, dane IP, dane sieciowe, audyt,
  powiązania kont, zmianę hasła i operacje migracji.

## Bazy, proxy i integracje

- Dostępne są SQLite, MySQL, MariaDB i PostgreSQL. Hasło zdalnej bazy może pochodzić ze
  zmiennej środowiskowej zamiast z pliku.
- Osobny moduł Velocity wybiera tryb premium lub offline przed backendem. Opcjonalnie
  sprawdza wspólną historię kont oraz VPN/proxy/Tor przed wpuszczeniem gracza.
- CleanerX może stosować politykę nazw, a PunisherX — sprawdzać dostępne przez publiczne
  API aktywne kary. Awaria integracji może blokować albo przepuszczać połączenie zgodnie
  z konfiguracją.
- Publiczne API pozwala innym pluginom nieblokująco sprawdzić, czy gracz ma aktywną,
  uwierzytelnioną sesję. Kanały `authgatewayx:auth` i `craftconnect:auth` przekazują tę
  informację zgodnym klientom; nie zastępują uprawnień administracyjnych.
- Wiadomości korzystają z MiniMessage i pliku językowego. Administrator może zmienić
  treść, kolory i etykiety bez tworzenia osobnego systemu wiadomości.

## Czego ta wersja nie oferuje

Nie ma panelu WWW, 2FA, odzyskiwania przez e-mail, połączenia konta z Discordem,
komend `/login`, `/register`, `/premium`, `/unregister` ani bezpiecznego hot-reloadu.
Nie ma też potwierdzonej obsługi Bedrock/Geyser. AuthGatewayX nie jest systemem kar i nie
uznaje wspólnego IP lub wyniku usługi VPN za niepodważalny dowód nadużycia.
