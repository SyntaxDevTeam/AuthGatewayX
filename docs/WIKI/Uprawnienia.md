# Uprawnienia

**Język / Language:** Polski · [English](EN/Permissions.md)

[Home](Home.md) · [Komendy](Komendy.md)

Uprawnienie określa, kto może użyć danej funkcji. Zwykłe logowanie i rejestracja przez okno nie wymagają dodatkowej permisji.

| Uprawnienie | Dostęp | Domyślnie |
| --- | --- | --- |
| `logincorex.command.changepassword` | Zmiana własnego hasła | Wszyscy gracze |
| `logincorex.auth.skip-password` | Pominięcie hasła serwerowego po potwierdzeniu tożsamości Minecraft | Domyślnie wyłączone |
| `logincorex.command.privacy` | Wyświetlanie polityki i zarządzanie akceptacją | Wszyscy gracze |
| `logincorex.command.logout` | Wylogowanie | Wszyscy gracze |
| `logincorex.admin.info` | Podstawowy raport `/logincorex info <nick>` | Operatorzy serwera |
| `logincorex.admin.alerts` | Odbiór automatycznych alertów po zalogowaniu | Operatorzy serwera |
| `logincorex.admin.view-ip` | Pokazywanie źródłowego adresu IP w alertach administracyjnych | Wymaga jawnego nadania |
| `logincorex.admin.view-geo` | Pokazywanie GeoIP, ASN, dostawcy/organizacji sieci i operatora anonimizującego | Wymaga jawnego nadania |
| `logincorex.admin.view-security` | Licznik błędnych logowań, blokada i ostatnie zdarzenia audytu w raporcie konta | Wymaga jawnego nadania |
| `logincorex.admin.alts` | Raport podejrzanych powiązań kont przez wspólne adresy | Wymaga jawnego nadania |
| `logincorex.admin.password` | Ustawianie hasła konta wskazanego gracza | Operatorzy serwera |
| `logincorex.admin.reload` | Przeładowanie ustawień obsługiwanych w runtime przez `/lcx reload` | Operatorzy serwera |
| `logincorex.admin.migration.view` | Podgląd ticketów i diagnostyki providerów migracji UUID | Operatorzy serwera |
| `logincorex.admin.migration.execute` | Ponawianie istniejących migracji UUID | Operatorzy serwera |
| `logincorex.admin.migration.recover` | Tworzenie recovery dla kont zmigrowanych przez starsze wersje LCX | Operatorzy serwera |

Uprawnienia możesz przypisać w używanym na serwerze pluginie do zarządzania rangami. Zachowaj dokładną pisownię z tabeli.

## Jak rozdzielić dostęp?

Graczom wystarczą domyślne uprawnienia komend gracza. Dostęp do `logincorex.admin.password` daj tylko osobom, którym powierzasz odzyskiwanie kont. Nie musi go mieć każdy moderator czatu. Jeśli moderator ma otrzymywać alerty, ale nie powinien widzieć danych sieciowych, nadaj mu samo `logincorex.admin.alerts`. `logincorex.admin.info` otwiera raport podstawowy, natomiast `view-ip`, `view-geo`, `view-security` i `alts` niezależnie dodają sekcje wrażliwe. Konsola serwera otrzymuje pełny wariant diagnostyczny.

Samo nadanie uprawnienia nie omija weryfikacji tożsamości Minecraft, zabezpieczeń przed wejściem do świata ani limitów połączeń. Zmiana hasła oraz wylogowanie dotyczą uwierzytelnionych kont. Uprawnienie `logincorex.auth.skip-password` umożliwia pominięcie dodatkowego hasła wyłącznie graczowi z już zweryfikowaną oficjalną tożsamością Minecraft. Domyślnie każdy gracz korzysta z rejestracji i logowania hasłem.

## VPN i multi-konta na proxy

Velocity ma własne ustawienia w `logincorex.yml`: `storage.enabled` włącza odczyt
wspólnej bazy Paper, `multi-account.action` wybiera `ALERT`, `DENY` lub `DISABLED`.
`ip-intelligence.enabled` włącza VPN/proxy/Tor (domyślna akcja `DENY`);
`show-geo` pozwala przetwarzać i prezentować rozszerzone GeoIP/metadane sieciowe, ale gracz administracji zobaczy je wyłącznie z `logincorex.admin.view-geo`. Kontrole odrzucają połączenie przed serwerem gry.
Obie integracje są domyślnie wyłączone; brak wspólnej historii nie wykryje zmiany IP.

`alerts.enabled`, `alerts.console` i `alerts.cooldown-seconds` sterują powiadomieniami
proxy. Alert dotyczy próby wejścia pod nickiem, nie potwierdzonego logowania hasłem.
Nadaj na proxy `logincorex.admin.alerts` oraz `logincorex.admin.alts` dla raportu
`/logincorex alts <nick>`. Pozostałe podkomendy trafiają do Paper.
Administrator z lokalnie przypisanym UUID wymaga poprawnego zalogowania na backendzie i wspólnego,
osobnego `staff-proof.secret` (co najmniej 32 bajty) na Paper i proxy.
Konsola i administrator uwierzytelniony przez Mojang nie potrzebują tego sekretu.

Pełne ustawienia, zachowanie przy awarii i kolejność wdrożenia:
[Konfiguracja Velocity](Velocity.md). Opcje alertów opisane
powyżej dla Paper pozostają lokalne; można je wyłączyć, aby nie dublować powiadomień.


## Uprawnienia migracji

Rozdzielenie `view`, `execute` i `recover` jest celowe. Osoba mająca wyłącznie
`logincorex.admin.migration.view` może diagnozować stan, ale nie uruchomi operacji
zmieniającej dane. `recover` jest najbardziej wrażliwą operacją i warto pozostawić ją
wyłącznie administratorom odpowiedzialnym za dane graczy.

Konsola serwera może wykonywać wszystkie podkomendy migracji. Administrator w grze musi
mieć aktywną, uwierzytelnioną sesję LoginCoreX; sama permisja nie omija logowania.

Dane wrażliwe wymagają jawnego nadania uprawnień także operatorom Paper. `view-geo` obejmuje również VPN/proxy/Tor i ocenę ryzyka; `alts` kontroluje także listy powiązanych kont w alertach. `alerts` samo udostępnia jedynie ogólne powiadomienie. Sprawdź dziedziczenie i wildcardy w pluginie uprawnień; `*` może nadal przyznać dostęp. Uprawnienia na Velocity nadaje się osobno.
