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

- [ ] Dodać backendy MySQL/MariaDB i PostgreSQL wraz z migracjami i testami integracyjnymi.
- [ ] Zapewnić równoważną atomowość rejestracji, limitów IP, lockoutu, migracji tożsamości
  i compare-and-set hasła na każdym backendzie.
- [ ] Dodać test recovery po błędzie migracji, timeoutach puli i utracie połączenia DB.

### Integracje i publiczne API

- [ ] Zaimplementować opcjonalny adapter CleanerX przez publiczne API/ServicesManager,
  z konfigurowalną strategią awarii i odrzuceniem przed rejestracją.
- [ ] Zaimplementować opcjonalny adapter PunisherX dla name/UUID/IP/network ban przed
  aktywacją sesji, z jawną strategią fail-open/fail-closed.
- [ ] Dokończyć `AuthGatewayApi`: lookup konta i sesji, authentication state, identity type,
  stabilne eventy oraz interfejsy providerów bez zależności platformowych.
- [ ] Udokumentować wersjonowanie API i test kompatybilności Paper/Velocity.

### Wydanie i operacje

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
- Geyser/Floodgate oraz zewnętrzni dostawcy reputacji ASN/datacenter.
- Pełny workflow odzyskiwania konta przez użytkownika.
