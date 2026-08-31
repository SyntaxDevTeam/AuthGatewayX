# AuthGatewayX — indeks dokumentacji

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

Dokument kompletnej dokumentacji agreguje decyzje z dokumentów tematycznych, ale jego
sekcje stanu implementacji muszą być aktualizowane razem z kodem. W razie rozbieżności
obowiązuje nowsza, bardziej szczegółowa i jawnie uzasadniona decyzja.

## Stan ogólny

Projekt jest aktywnym `1.0.0-WIP`, a nie gotowym wydaniem. Istnieją fundament domenowy,
SQLite, Argon2id, login i rejestracja offline, audit, limitery, Paper Dialog API,
aktywna izolacja PRE_AUTH, trwały limit rejestracji per IP, bezpieczny lookup nazw
premium, selektor trybu Velocity oraz bezpieczny handoff zweryfikowanej tożsamości
premium z Velocity do Paper oparty o zgodność oficjalnego UUID z UUID przekazanym przez
Velocity modern forwarding.
Otwarte pozostają między innymi testy serwerowe Paper/Purpur/Folia/Velocity, pełne
uwierzytelnienie premium standalone Paper, pozostałe backendy JDBC, kompletne anti-bot,
integracje CleanerX/PunisherX i obserwowalność.

Checkbox `[x]` wolno ustawić dopiero po wdrożeniu, integracji i weryfikacji danego
elementu w zakresie wymaganym przez dokumentację.
