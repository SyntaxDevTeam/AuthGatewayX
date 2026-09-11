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
