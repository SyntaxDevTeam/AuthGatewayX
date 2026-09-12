# AuthGatewayX — pozostałe prace do 1.0.0

Ten dokument jest kanonicznym, skróconym widokiem prac pozostałych do pełnego wydania.
Szczegółowe wymagania pozostają w dokumentach `01`–`07` i kompletnej dokumentacji.
Checkbox wolno zamknąć dopiero po implementacji, integracji, testach i aktualizacji docs.

## Stan ukończony i zweryfikowany w kodzie

- [x] Fundament domenowy kont, tożsamości i sesji.
- [x] SQLite z migracjami, HikariCP i asynchronicznym bounded executorem.
- [x] Rejestracja i logowanie offline z Argon2id, lockoutem i neutralnym feedbackiem.
- [x] `/changepassword` oraz administracyjne ustawianie hasła przez bezpieczny Dialog API.
- [x] `/logout` z unieważnieniem sesji, audytem i rozłączeniem gracza.
- [x] Izolacja PRE_AUTH w runtime Paper oraz admission control.
- [x] Wczesne token buckety, username burst, reconnect signals i behavioral scoring.
- [x] Paper standalone premium lookup i rozpoczęcie natywnego szyfrowanego handshake.
- [x] Podstawowy selektor online/offline w module Velocity.
- [x] Paper lifecycle: loader, bootstrap, composition root i Lifecycle Commands API.
- [x] Powtarzalny Paper dev bundle przypięty do `26.2.build.121-stable` zamiast wersji dynamicznej.
- [x] Konfigurowalne runtime JDBC dla SQLite, MySQL, MariaDB i PostgreSQL wraz z
  dialektami migracji, slotów rejestracji i sterownikami PluginLoader.
- [x] CleanerX przez publiczne API `ServicesManager` oraz kontraktowy
  `UsernamePolicyProvider`, wykonywany przed lookupem premium i rejestracją.
- [x] PunisherX przez kontraktowy `PunishmentProvider` oraz kompatybilny fallback
  obecnego publicznego API aktywnych kar po UUID.

## Blokery wydania 1.0.0

### Uwierzytelnianie i bezpieczeństwo kont

- [X] Wykonać pełny login premium rzeczywistym klientem i potwierdzić official UUID,
  profil oraz brak fallbacku offline na każdej ścieżce błędu.
- [ ] Dokończyć i przetestować migrację konta `OFFLINE -> MOJANG`, w tym konflikt nazwy,
  konflikt UUID, równoległe logowania oraz audit migracji.
- [ ] Dodać bezpieczną politykę concurrent login/kick lub deny i test dwóch jednoczesnych
  logowań tego samego konta.
- [ ] Zweryfikować disconnect podczas Argon2/JDBC/HTTP oraz późne callbacki po shutdownie.
- [ ] Przeprowadzić benchmark parametrów Argon2id i bounded queue na wspieranym sprzęcie.

### Paper, Purpur i Folia

- [x] Wykonać testy serwerowe całego flow na Paper i Purpur.
- [ ] Wykonać testy na Folia: ownership Entity/Region/Global schedulerów, login, timeout,
  aktywacja, logout, zmiana hasła i shutdown.
- [ ] Domknąć politykę przychodzących plugin messages w PRE_AUTH i potwierdzić brak
  obejścia izolacji przez inne pluginy.
- [x] Przetestować PRE_AUTH event surface w środowisku z popularnymi pluginami teleportu,
  inventory, combat i portal.
- [ ] Dopiero po testach Folia ustawić i zatwierdzić `folia-supported: true`.

### Velocity i granica proxy/backend

- [ ] Ujednolicić dokumentację handoff Velocity -> Paper z faktycznym kodem i usunąć
  sprzeczne opisy bieżącego stanu.
- [ ] Zaimplementować oraz przetestować odporny na spoofing i replay handoff principal,
  albo formalnie oprzeć go wyłącznie na poprawnie zabezpieczonym modern forwarding.
- [ ] Przetestować online/offline selection, reconnect, backend switch, brak decyzji,
  wygaśnięcie decyzji oraz mismatch `Player.isOnlineMode`.
- [ ] Udokumentować i zweryfikować firewall backendów oraz sekret Velocity forwarding.

### Anti-bot, anti-flood i obserwowalność

- [ ] Wykonać test obciążeniowy connection flood, username burst, reconnect loop,
  registration flood i behavioral scoring; dobrać limity na podstawie wyników.
- [ ] Dodać flood metrics, liczniki decyzji, latencję DB/Mojang/Argon2 oraz kontrolowany
  eksport statystyk bez wpływu na krytyczną ścieżkę auth.
- [ ] Zweryfikować limity pamięci, TTL, invalidację i zachowanie po stale data każdego cache.
- [ ] Przejrzeć logi pod kątem sekretów, danych osobowych i odporności na log injection.

### Storage i integralność danych

- [ ] Wykonać testy integracyjne MySQL, MariaDB i PostgreSQL w kontenerach/CI dla
  wdrożonych dialektów oraz migracji; kod runtime i sterowniki są już gotowe.
- [ ] Zapewnić równoważną atomowość rejestracji, limitów IP, lockoutu, migracji tożsamości
  i compare-and-set hasła na każdym backendzie.
- [ ] Dodać test recovery po błędzie migracji, timeoutach puli i utracie połączenia DB.

### Integracje i publiczne API

- [x] Zaimplementować opcjonalny adapter CleanerX przez publiczne API/ServicesManager,
  z konfigurowalną strategią awarii i odrzuceniem przed rejestracją.
- [ ] Rozszerzyć publiczne API PunisherX o name/IP/network-ban login check. Obecny adapter
  egzekwuje aktywny ban UUID; pozostałych danych obecne API PunisherX nie udostępnia.
- [ ] Dokończyć `AuthGatewayApi`: lookup konta i sesji, authentication state, identity type,
  stabilne eventy oraz interfejsy providerów bez zależności platformowych.
- [ ] Udokumentować wersjonowanie API i test kompatybilności Paper/Velocity.

### Wydanie i operacje

- [x] Przygotować WIKI obecnego WIP: osobne strony użytkowe, komendy i permisje,
  opis wszystkich dostarczanych opcji Paper/Velocity, komunikatów, kopii i ograniczeń migracji.
  Instrukcje użytkowe znajdują się w `wiki/Home.md`; nie zamyka to testów wdrożenia
  ani dokumentacji przyszłych opcji stabilnego wydania.
- [ ] Uzupełnić domyślną konfigurację i dokumentację administratora dla wszystkich opcji,
  permisji, komunikatów, trybów standalone/proxy oraz procedury migracji.
- [ ] Sprawdzić dostępność stabilnych wydań SyntaxCore i MessageHandler; usunąć SNAPSHOT
  z artefaktu stabilnego, jeśli istnieją wersje release.
- [ ] Wykonać test czystej instalacji, upgrade istniejącej SQLite, restart, reload policy,
  graceful shutdown oraz recovery po nieudanym starcie.
- [ ] Zbudować finalne artefakty Paper i Velocity, sprawdzić zawartość JAR, licencje
  zależności, kompatybilność Java/Minecraft i checklistę publikacji.
- [ ] Przeprowadzić końcowy security review i zamknąć wyłącznie checklisty poparte testami.

## Poza zakresem blokującym 1.0.0

- Redis i synchronizacja wielu proxy.
- Panel WWW, REST API, Discord linking, e-mail i 2FA/TOTP.
- Geyser/Floodgate oraz egzekwowanie blokad według reputacji ASN/datacenter
  (opcjonalne informacyjne alerty proxycheck.io są już zaimplementowane).
- Pełny workflow odzyskiwania konta przez użytkownika.

## Raport multi-kont offline

- [ ] Sprawdzić `/authgatewayx alts` na Paper/Purpur/Folia: konsola, brak permisji,
  PRE_AUTH, disconnect i cofnięcie uprawnienia podczas zapytania oraz shutdown.
- [ ] Sprawdzić backend za Velocity modern forwarding: raport używa IP gracza.
- [ ] Zweryfikować migrację v5, raport i równoległą rotację historii na zdalnych bazach.
- [ ] Test obciążeniowy raportu dla dużej współdzielonej sieci i limitu zapytań.

Zaimplementowany raport jest poszlaką wspólnego adresu. Opcjonalne alerty VPN/ASN
opisuje sekcja 27 dokumentu bezpieczeństwa; brak wspólnego śladu nie dowodzi braku multi-konta.

- [ ] Alerty automatyczne: test serwerowy odbiorców/permisji/Folia i disconnect.
- [ ] VPN/GeoIP: test realnego proxycheck.io, limitów planu i backendu Velocity.

## Kontrole ryzyka na proxy

- [x] Kontrole PreLogin VPN/multi-kont, akcje ALERT/DENY/DISABLED i obsługa awarii — testy automatyczne.
- [x] Odczyt historii v5, test SQLite bieżącego IP bez zapisu próby połączenia.
- [x] Podpisany jednorazowy dowód ACTIVE: testy spoof/replay, timeout, zmiana serwera i shutdown.
- [ ] Test rzeczywistego Velocity + Paper/Purpur/Folia: odmowa bez wejścia na backend, alerty i komenda.
- [ ] Test zdalnych baz i realnego dostawcy VPN przy limitach planu oraz reconnect flood.

Instrukcja wdrożenia: [09-proxy-risk-admission.md](09-proxy-risk-admission.md).

### Korekta po teście Velocity (2026-09-12)

- [x] Adapter MessageHandler bez tworzenia `config.yml`, test rzeczywistej inicjalizacji biblioteki i zgodności języka.
- [x] Diagnostyka błędów JDBC bez ujawniania sekretów, test kategorii przyczyn.
- [ ] Ponowny test startu na zgłoszonej instalacji Velocity po sprawdzeniu wspólnej bazy.

- [x] Diagnostyka AGX-STARTUP przy odmowie: testy zachowania blokady, limitu logów i cudzej odmowy.
- [ ] Ustalenie przyczyny braku READY na zgłoszonym proxy na podstawie logu startowego/AGX-STARTUP.
