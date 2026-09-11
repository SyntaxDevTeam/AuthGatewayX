# Uprawnienia

[Home](Home.md) · [Komendy](Komendy.md)

Uprawnienie określa, kto może użyć danej funkcji. Zwykłe logowanie i rejestracja przez okno nie wymagają dodatkowej permisji.

| Uprawnienie | Dostęp | Domyślnie |
| --- | --- | --- |
| `authgatewayx.command.changepassword` | Zmiana własnego hasła | Wszyscy gracze |
| `authgatewayx.command.logout` | Wylogowanie | Wszyscy gracze |
| `authgatewayx.admin.alerts` | Odbiór automatycznych alertów po zalogowaniu | Operatorzy serwera |
| `authgatewayx.admin.alts` | Raport podejrzanych powiązań kont offline | Operatorzy serwera |
| `authgatewayx.admin.password` | Ustawianie hasła cudzego konta offline | Operatorzy serwera |

Uprawnienia możesz przypisać w używanym na serwerze pluginie do zarządzania rangami. Zachowaj dokładną pisownię z tabeli.

## Jak rozdzielić dostęp?

Graczom wystarczą dwa domyślne uprawnienia. Dostęp do `authgatewayx.admin.password` daj tylko osobom, którym powierzasz odzyskiwanie kont. Nie musi go mieć każdy moderator czatu.

Samo nadanie uprawnienia nie omija logowania i nie zamienia konta premium w offline. Zmiana hasła oraz wylogowanie dotyczą aktywnych kont offline. Administrator także musi najpierw się zalogować.

Obecna wersja nie ma osobnej permisji do omijania logowania, limitów ani ochrony nicków premium.

## VPN i multi-konta na proxy

Velocity ma własne ustawienia w `authgatewayx.yml`: `storage.enabled` włącza odczyt
wspólnej bazy Paper, `multi-account.action` wybiera `ALERT`, `DENY` lub `DISABLED`.
`ip-intelligence.enabled` włącza VPN/proxy/Tor (domyślna akcja `DENY`);
`show-geo` dodaje kraj i ASN. Kontrole odrzucają połączenie przed serwerem gry.
Obie integracje są domyślnie wyłączone; brak wspólnej historii nie wykryje zmiany IP.

`alerts.enabled`, `alerts.console` i `alerts.cooldown-seconds` sterują powiadomieniami
proxy. Alert dotyczy próby wejścia pod nickiem, nie potwierdzonego logowania hasłem.
Nadaj na proxy `authgatewayx.admin.alerts` oraz `authgatewayx.admin.alts` dla raportu
`/authgatewayx alts <nick>`. Pozostałe podkomendy trafiają do Paper.
Administrator offline wymaga poprawnego zalogowania na backendzie i wspólnego,
osobnego `staff-proof.secret` (co najmniej 32 bajty) na Paper i proxy.
Konsola i administrator uwierzytelniony przez Mojang nie potrzebują tego sekretu.

Pełne ustawienia, zachowanie przy awarii i kolejność wdrożenia:
[instrukcja kontroli proxy](../09-proxy-risk-admission.md). Opcje alertów opisane
powyżej dla Paper pozostają lokalne; można je wyłączyć, aby nie dublować powiadomień.
