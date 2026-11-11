# Możliwości pluginu

**Język / Language:** Polski · [English](EN/Features.md)

[Home](Home.md) · [Instalacja](Instalacja.md) · [Komendy](Komendy.md) · [Zgodność](Zgodnosc.md)

Poznaj funkcje LoginCoreX. Wymagania serwera i dobór pliku pluginu opisuje [Zgodność](Zgodnosc.md).

## Logowanie graczy

- Każdy gracz rejestruje konto i loguje się w oknie Minecrafta, również przy `online-mode=false`.
  Hasło nie trafia do czatu ani do argumentu komendy.
- Tożsamość powiązana z oficjalnym kontem Minecraft jest dodatkowo weryfikowana.
  Wyłącznie uprawniony gracz ze zweryfikowaną tożsamością może pominąć hasło serwerowe.
- Chronionego nicku nie można przejąć poprzez wpisanie go w launcherze. Nieudana
  weryfikacja właściciela kończy połączenie.
- Plugin pilnuje jednej aktywnej sesji konta i pozwala graczowi bezpiecznie zakończyć ją
  komendą `/logout`.
- Gracz może zmienić własne hasło, a uprawniony administrator może ustawić nowe hasło
  konta gracza bez umieszczania go w historii komend.

## Ochrona świata przed zalogowaniem

Do chwili poprawnego logowania gracz pozostaje w stanie chronionym. Nie może normalnie
chodzić, walczyć, pisać na czacie, wykonywać komend, przenosić przedmiotów, używać bloków
ani wpływać na świat. Plugin blokuje również mniej oczywiste drogi działania, między
innymi wiadra, książki i pulpity, stojaki na zbroję, strzyżenie, wędki, pociski oraz
wiszące obiekty. Lista podpowiedzi komend jest wtedy ukryta.

Limit czasu i maksymalna liczba osób oczekujących na logowanie chronią serwer przed
zapełnieniem sesjami, które nigdy nie kończą uwierzytelniania.

## Hasła i nadużycia

- Hasła kont są zabezpieczane algorytmem Argon2id, a kosztowne obliczenia mają
  ograniczoną liczbę wątków i kolejkę.
- Kolejne błędne hasła uruchamiają limit prób i czasową blokadę konta.
- Ochrona połączeń ogranicza ruch z jednego adresu i całego serwera, zanim rozpocznie się
  kosztowne sprawdzanie bazy lub usług zewnętrznych.
- Plugin wykrywa szybkie zmiany nicków, wielokrotne rozłączenia przed logowaniem i inne
  podejrzane zachowanie z jednego adresu. Może czasowo poddać taki adres kwarantannie.
- Rejestracja ma krótkoterminowy limit prób oraz trwały limit liczby kont z jednego
  adresu IP. Limity mogą dotknąć także prawdziwych graczy we wspólnej sieci, dlatego
  administrator powinien dobierać je do swojej społeczności.

## Weryfikacja konta i zmiana UUID

Gdy dotychczasowy gracz zacznie korzystać z oficjalnej tożsamości Minecraft pod tym samym nickiem, plugin prosi o dotychczasowe hasło serwerowe i przygotowuje kontrolowaną migrację UUID. Dane nie są
przenoszone przez ślepą zamianę UUID. Wbudowane migratory obsługują dane vanilla,
EssentialsX, LuckPerms, HorseManagerX, PunisherX oraz PlotsX, jeśli wymagana integracja
jest dostępna. Niezarządzane dane mogą zatrzymać zmianę zamiast ryzykować ich utratę.

Migracja tworzy kopie, potrafi wycofać wykonane kroki i jest wznawiana po restarcie.
Administracja może sprawdzić jej stan, wykonać inspekcję, ponowić ticket lub przygotować
recovery dla konta zmienionego przez starszą wersję. Szczegóły opisują strony
[Weryfikacja konta](Premium.md) i [Komendy](Komendy.md).

## Narzędzia administracji

- `/logincorex info <nick>` pokazuje tożsamość konta, stan, daty, bieżącą sesję i
  aktualną klasyfikację nicku Mojang. Dodatkowe uprawnienia odsłaniają IP, GeoIP,
  reputację sieci, blokadę, ostatnie zdarzenia bezpieczeństwa i powiązane konta.
- `/logincorex alts <nick>` szuka kont korzystających ze wspólnych adresów w
  ograniczonej historii. Wspólne IP jest poszlaką, a nie dowodem jednej osoby.
- Automatyczne alerty mogą informować zalogowaną administrację i konsolę o podejrzanym
  koncie, VPN, proxy lub Tor. Zewnętrzne sprawdzanie IP jest domyślnie wyłączone.
- Osobne uprawnienia rozdzielają zwykły raport, dane IP, dane sieciowe, audyt,
  powiązania kont, zmianę hasła i operacje migracji.

## Bazy, proxy i integracje

- Dostępne są SQLite, MySQL, MariaDB i PostgreSQL. Hasło zdalnej bazy może pochodzić ze
  zmiennej środowiskowej zamiast z pliku.
- Osobny moduł Velocity weryfikuje tożsamość Minecraft przed połączeniem z backendem. Opcjonalnie
  sprawdza wspólną historię kont oraz VPN/proxy/Tor przed wpuszczeniem gracza.
- CleanerX może stosować politykę nazw, a PunisherX — sprawdzać dostępne przez publiczne
  API aktywne kary. Awaria integracji może blokować albo przepuszczać połączenie zgodnie
  z konfiguracją.
- Zgodne pluginy i klienci, w tym CraftConnect, mogą otrzymać potwierdzenie zalogowania gracza. Nie nadaje ono uprawnień administracyjnych.
- Wiadomości korzystają z MiniMessage i pliku językowego. Administrator może zmienić
  treść, kolory i etykiety bez tworzenia osobnego systemu wiadomości.

## Czego ta wersja nie oferuje

Nie ma panelu WWW, 2FA, odzyskiwania przez e-mail, połączenia konta z Discordem,
komend `/login`, `/register`, `/premium`, `/unregister` ani pełnego przeładowania konfiguracji bez restartu. `/lcx reload` odświeża jedynie listę pomijanych katalogów migracji.
Nie ma też potwierdzonej obsługi Bedrock/Geyser. LoginCoreX nie jest systemem kar i nie
uznaje wspólnego IP lub wyniku usługi VPN za niepodważalny dowód nadużycia.


## Polityka prywatności / RODO — Paper

Po poprawnym uwierzytelnieniu konta gracz pozostaje w izolacji do zaakceptowania aktualnej polityki. Ustawienie `privacy.dialogs.enabled: false` pozwala wyświetlać ją na czacie zamiast w oknie.

Decyzja jest zapisywana razem z datą i wersją polityki oraz zachowywana po migracji do premium. Zwiększenie `privacy.policy-version` wymaga ponownej akceptacji przy kolejnym wejściu. Przekroczenie `privacy.timeout-seconds` lub błąd zapisu kończy połączenie. Odmowa i wycofanie nie nakładają automatycznej kary PunisherX.

Podczas oczekiwania na decyzję dostępna jest tylko komenda `/privacy`; pozostałe działania pozostają zablokowane. Funkcja wymaga modułu Paper. Ustaw własny adres HTTPS polityki przez `privacy.policy-url` lub `privacy.link` i dostosuj treść do swojego serwera. Akceptacja nie zastępuje ustalenia właściwej podstawy przetwarzania danych. Dane ConsentChecker nie są importowane automatycznie.
