# Konta premium

[Home](Home.md) · [Poradnik gracza](Poradnik-gracza.md) · [Pomoc](Pomoc.md)

## Wejdź na swoje konto i graj

AuthGatewayX sprawdza, czy nick należy do konta premium. Jeśli tak, Minecraft musi potwierdzić, że gracz rzeczywiście korzysta z tego konta. Po poprawnej weryfikacji dodatkowe hasło serwerowe nie jest potrzebne.

Nie podawaj hasła do Microsofta w oknie serwera. Na konto Microsoft logujesz się w launcherze.

## Ochrona Twojego nicku

Ktoś korzystający z konta offline nie może wejść na chroniony nick tylko dlatego, że wpisał go w launcherze. Jeśli nie uda się potwierdzić właściciela konta premium, połączenie zostanie odrzucone.

Jeśli grasz offline i wybrany nick należy do kogoś z premium, wybierz inną nazwę, która nie jest zajęta przez konto premium.

## „Nie potwierdzono sesji konta premium”

Zamknij grę, zaloguj się ponownie w launcherze na konto będące właścicielem tego nicku i uruchom Minecrafta. Wygasłe logowanie lub wybrane inne konto mogą powodować taki komunikat.

Jeśli problem dotyczy wielu osób, administracja powinna sprawdzić połączenie serwera z usługami Minecrafta. Niedostępność tych usług może również zablokować sprawdzenie nowego nicku offline.

## Kupno premium przy zachowaniu dotychczasowego nicku

Jeżeli konto non-premium o danym nicku już istnieje, a właściciel później kupi Minecraft
i posiada oficjalne konto premium o tej samej nazwie, AuthGatewayX nie przełącza już
automatycznie UUID przy pierwszym wejściu.

Po poprawnym uwierzytelnieniu Mojang zobaczysz osobny dialog migracyjny. Należy podać
**hasło używane wcześniej na tym serwerze do konta non-premium**. Nie jest to hasło do
Microsofta/Mojang.

Poprawne hasło potwierdza, że osoba posiada jednocześnie:

1. zweryfikowane konto premium z nowym UUID,
2. poprzednie konto offline ze starym UUID.

Po potwierdzeniu tworzony jest bezpieczny ticket migracji. Do czasu przeniesienia danych
zależnych od UUID konto AuthGatewayX pozostaje OFFLINE i zachowuje stare UUID oraz hash
hasła. Dzięki temu samo pierwsze wejście premium nie odcina gracza od poprzedniej
tożsamości.

Dane takie jak ekwipunek, statystyki, ekonomia, działki, rangi lub dane innych pluginów
nie mogą być przenoszone przez ślepe wyszukiwanie i zamianę UUID. Każdy magazyn danych
musi mieć kontrolowany migrator. AuthGatewayX finalizuje `OFFLINE -> MOJANG` dopiero
po zakończeniu takich migracji.

Jeżeli migracja nie została jeszcze zakończona, skontaktuj się z administracją. Nie próbuj
usuwać plików `playerdata` ani ręcznie edytować baz danych bez kopii zapasowej.

## Co AuthGatewayX przenosi automatycznie?

Wbudowany provider przenosi dane vanilla zależne od UUID po wcześniejszym rozłączeniu gracza:

- `playerdata/<uuid>.dat` i `.dat_old`,
- `stats/<uuid>.json`,
- `advancements/<uuid>.json`.

Przed zmianą pliku wykonywana jest kopia w `plugins/AuthGatewayX/migration-backups/<migration-id>/`.
Jeżeli późniejszy provider zawiedzie albo finalizacja konta nie przejdzie compare-and-set,
wcześniej wykonane providery są wycofywane w odwrotnej kolejności.

AuthGatewayX **nie wykonuje globalnego search/replace UUID**. Przed finalizacją skanuje lokalne
katalogi danych pluginów, które nie zostały przejęte przez zarejestrowany
`IdentityMigrationProvider`. Jeśli znajdzie stare UUID, migracja zostaje zatrzymana i konto
pozostaje OFFLINE. Administrator musi wtedy zainstalować/dodać provider dla danego pluginu
albo przenieść te dane świadomie.

Dla prostych lokalnych magazynów AuthGatewayX potrafi zrobić więcej niż sam skan. Wbudowane
i własne recepty migracji obsługują jednoznaczne pliki indeksowane UUID, a bezpieczny fallback
może skopiować plik nazwany dokładnie `<stare-uuid>.yml/.yaml/.json/.toml/.properties` na
nowe UUID, jeżeli stare UUID nie występuje również w treści. Source pozostaje zachowany,
target jest backupowany, a operacja ma rollback.

AdvancedPortals ma wbudowaną receptę dla `playerData/<uuid>.yaml`. Dodatkowe recepty można
umieszczać w `plugins/AuthGatewayX/migration-recipes/*.yml`; pełny format opisuje
`docs/11-universal-local-migration-recipes.md`.

Skan pozostaje ograniczony liczbą plików oraz łączną liczbą odczytanych bajtów. Nie blokuje
tylko dlatego, że pojedynczy plik jest duży — pliki są czytane strumieniowo. Gdy budżet
zostanie przekroczony, `/agx migrate inspect` pokazuje ścieżkę i rozmiary potrzebne do
podjęcia decyzji.

EssentialsX ma własny migrator `userdata/<uuid>.yml`. Jeżeli nowe UUID zdążyło już utworzyć
odmienny plik userdata, AuthGatewayX zachowuje go w backupie, przenosi dane starego profilu,
a w razie niepowodzenia może dokładnie odtworzyć poprzedni target. Sam fakt istnienia innego
targetu EssentialsX nie blokuje więc recovery.

Skan lokalny nie daje wiedzy o zdalnej bazie MySQL/PostgreSQL innego pluginu. Pluginy trzymające
dane gracza poza lokalnym katalogiem powinny zawsze dostarczyć własny provider.
