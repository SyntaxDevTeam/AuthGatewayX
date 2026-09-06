# AuthGatewayX — indeks dokumentacji

Dokumentacja użytkowa: [WIKI dla graczy i administratorów](wiki/Home.md).
Każdy temat ma osobną stronę; WIKI opisuje obecne wydanie WIP, bez nazw klas i opisu
architektury. Zawiera pełny opis dostarczanych opcji Paper i Velocity oraz ograniczenia
funkcji, instrukcje instalacji, komend, uprawnień, kopii i rozwiązywania problemów.

Dokumentacja w tym katalogu jest źródłem prawdy dla implementacji AuthGatewayX.
Dokumenty należy czytać w kolejności:

1. `01-koncepcja-ogolna.md`
2. `02-zalozenia-praktyczne.md`
3. `03-zalozenia-techniczne.md`
4. `04-bezpieczenstwo-wydajnosc-integracje.md`
5. `05-propozycja-zakresu-1.0.0.md`
6. `06-standard-wdrozeniowy-syntaxdevteam-paper-folia.md`
7. `07-premium-velocity-paper-handoff.md`
8. `AuthGatewayX-1.0.0-kompletna-dokumentacja.md`
9. `08-roadmap-do-1.0.0.md` — kanoniczna lista prac pozostałych do wydania

Dokument kompletnej dokumentacji agreguje decyzje z dokumentów tematycznych, ale jego
sekcje stanu implementacji muszą być aktualizowane razem z kodem. W razie rozbieżności
obowiązuje nowsza, bardziej szczegółowa i jawnie uzasadniona decyzja.

## Stan ogólny

Projekt jest aktywnym `1.0.0-WIP`, a nie gotowym wydaniem. Istnieją fundament domenowy,
SQLite, Argon2id, login i rejestracja offline, audit, limitery, Paper Dialog API,
aktywna izolacja PRE_AUTH, trwały limit rejestracji per IP, bezpieczny lookup nazw
premium, per-połączeniowy adapter premium LOGIN dla Paper standalone, selektor trybu
Velocity oraz bezpieczny handoff zweryfikowanej tożsamości
premium z Velocity do Paper oparty o zgodność oficjalnego UUID z UUID przekazanym przez
Velocity modern forwarding. Paper posiada także ograniczony pamięciowo, ważony scoring
zachowania IP łączący połączenia, różne nicki, nieudane auth i rozłączenia PRE_AUTH.
Otwarte pozostają między innymi test rzeczywistym klientem premium adaptera standalone,
testy serwerowe Purpur/Folia/Velocity, pozostałe backendy JDBC, kompletne anti-bot,
integracje CleanerX/PunisherX i obserwowalność.

Checkbox `[x]` wolno ustawić dopiero po wdrożeniu, integracji i weryfikacji danego
elementu w zakresie wymaganym przez dokumentację.
