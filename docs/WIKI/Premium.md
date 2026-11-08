# Konta premium

**Język / Language:** Polski · [English](EN/Premium.md)

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

Plugin przenosi dane vanilla zależne od UUID po wcześniejszym rozłączeniu gracza:

- `playerdata/<uuid>.dat` i `.dat_old`,
- `stats/<uuid>.json`,
- `advancements/<uuid>.json`.

Przed zmianą pliku wykonywana jest kopia w `plugins/AuthGatewayX/migration-backups/<migration-id>/`.
Jeśli którykolwiek etap przenoszenia danych zawiedzie, plugin próbuje przywrócić wcześniejszy stan.

AuthGatewayX **nie wykonuje globalnego search/replace UUID**. Przed finalizacją skanuje lokalne
katalogi danych pluginów bez dedykowanej integracji migracyjnej. Jeśli znajdzie stare UUID, migracja zostaje zatrzymana i konto
pozostaje OFFLINE. Administrator powinien sprawdzić raport `/agx migrate inspect <nick>` i zapewnić zgodną integrację przenoszącą dane wskazanego pluginu.

Dla prostych lokalnych magazynów AuthGatewayX potrafi zrobić więcej niż sam skan. Wbudowane
i własne recepty migracji obsługują jednoznaczne pliki indeksowane UUID, a bezpieczny fallback
może skopiować plik nazwany dokładnie `<stare-uuid>.yml/.yaml/.json/.toml/.properties` na
nowe UUID, jeżeli stare UUID nie występuje również w treści. Source pozostaje zachowany,
target jest backupowany, a operacja ma rollback.

AdvancedPortals ma wbudowaną receptę dla `playerData/<uuid>.yaml`. Dodatkowe recepty można
umieszczać w `plugins/AuthGatewayX/migration-recipes/*.yml`; podstawowy przykład konfiguracji znajduje się poniżej.

Skan pozostaje ograniczony liczbą plików oraz łączną liczbą odczytanych bajtów. Nie blokuje
tylko dlatego, że pojedynczy plik jest duży — pliki są czytane strumieniowo. Gdy budżet
zostanie przekroczony, `/agx migrate inspect` pokazuje ścieżkę i rozmiary potrzebne do
podjęcia decyzji.

EssentialsX ma własny migrator `userdata/<uuid>.yml`. Jeżeli nowe UUID zdążyło już utworzyć
odmienny plik userdata, AuthGatewayX zachowuje go w backupie, przenosi dane starego profilu,
a w razie niepowodzenia może dokładnie odtworzyć poprzedni target. Sam fakt istnienia innego
targetu EssentialsX nie blokuje więc recovery.

Skan lokalny nie daje wiedzy o zdalnej bazie MySQL/PostgreSQL innego pluginu. Pluginy trzymające
dane gracza poza lokalnym katalogiem wymagają dedykowanej integracji migracyjnej.

Jeżeli danych konkretnego pluginu nie chcesz przenosić, dodaj dokładną nazwę jego katalogu
do `migration.unmanaged-plugin-scan.ignored-plugin-directories`. AuthGatewayX sprawdzi w nim
jedynie obecność starego UUID, pominie zapis i pokaże tę decyzję w `migrate inspect`. Dane
pozostaną wyłącznie pod starym UUID; lista jest pusta domyślnie, ponieważ jej użycie oznacza
świadomą rezygnację z tych danych. Po zapisaniu listy wykonaj `/agx reload`; pełny restart
nie jest wymagany dla tego jednego ustawienia.

## Przykład własnej recepty lokalnej

Utwórz `plugins/AuthGatewayX/migration-recipes/my-plugin.yml` dopiero po potwierdzeniu, że format danych używa UUID wyłącznie w nazwie pliku:

```yaml
id: my-plugin-users
plugin-directory: MyPlugin
rules:
  - type: UUID_FILE
    source: "users/{source_uuid}.yml"
    target: "users/{target_uuid}.yml"
    rewrite-uuid-in-content: false
    require-source-uuid-absent: true
```

Ścieżki muszą być względne wobec katalogu wskazanego pluginu. Ścieżki absolutne, `..`, backslash i symlinki są odrzucane. Dostępne placeholdery: `{source_uuid}`, `{target_uuid}`, `{source_uuid_compact}` i `{target_uuid_compact}`. Zachowaj backup i sprawdź migrację oraz rollback na kopii. Współdzielone pliki, UUID wewnątrz danych i zdalne magazyny wymagają providera znającego format danych.
