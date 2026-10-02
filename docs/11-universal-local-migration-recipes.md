# AuthGatewayX — uniwersalne recepty migracji lokalnych danych UUID

## Po co istnieje ta warstwa?

Dedykowany `IdentityMigrationProvider` nadal ma najwyższy priorytet, ponieważ zna semantykę
danego pluginu i może obsłużyć API, SQL, cache oraz własny rollback. W praktyce wiele pluginów
trzyma jednak dane gracza znacznie prościej, np.:

```text
plugins/SomePlugin/playerData/<uuid>.yml
```

Dla takich przypadków AuthGatewayX posiada `UniversalLocalIdentityMigrationProvider`.
Pipeline ma kolejność:

```text
dedykowany provider
  -> wbudowana / administracyjna recepta
  -> bezpieczny generic UUID_FILE
  -> review required / BLOCKED
```

Warstwa uniwersalna nigdy nie wykonuje globalnego search/replace, nie modyfikuje baz
`.db`/SQLite ani zdalnego SQL/Redis i nie interpretuje nieznanych pól YAML „na oko”.

## Generic UUID_FILE

Domyślnie AuthGatewayX może automatycznie przenieść plik, którego nazwa jest dokładnie
starym UUID:

```text
<old-uuid>.yml
<old-uuid>.yaml
<old-uuid>.json
<old-uuid>.toml
<old-uuid>.properties
```

Obsługiwany jest także zapis UUID bez myślników.

Automatyczna operacja jest dozwolona tylko wtedy, gdy stare UUID **nie występuje również
w zawartości pliku**. Oznacza to, że AuthGatewayX ma silny dowód, że UUID jest kluczem
magazynu (nazwą pliku), ale nie próbuje zgadywać znaczenia pól wewnątrz dokumentu.

Source pozostaje zachowany. Jeżeli target już istnieje, jego dokładna kopia jest zapisywana
przed zastąpieniem. Trwały journal pozwala rollbackowi odtworzyć target albo usunąć go,
jeżeli wcześniej nie istniał.

## Wbudowana recepta AdvancedPortals

AdvancedPortals zapisuje dane gracza jako:

```text
AdvancedPortals/playerData/<uuid>.yaml
```

W obecnym formacie `PlayerData` UUID nie jest częścią serializowanego obiektu, dlatego
AuthGatewayX ma wbudowaną receptę:

```yaml
id: advancedportals
plugin-directory: AdvancedPortals
rules:
  - type: UUID_FILE
    source: "playerData/{source_uuid}.yaml"
    target: "playerData/{target_uuid}.yaml"
    rewrite-uuid-in-content: false
    require-source-uuid-absent: true
```

Jeżeli przyszła wersja AdvancedPortals zacznie umieszczać UUID również w treści tego pliku,
recepta przestanie być automatycznie bezpieczna i migracja zostanie zatrzymana do przeglądu.

## Własne recepty administratora

Pliki recept należy umieszczać w:

```text
plugins/AuthGatewayX/migration-recipes/*.yml
```

Minimalny przykład:

```yaml
id: my-plugin-users
plugin-directory: MyPlugin

rules:
  - type: UUID_FILE
    source: "users/{source_uuid}.yml"
    target: "users/{target_uuid}.yml"
```

Dostępne placeholdery:

```text
{source_uuid}
{source_uuid_compact}
{target_uuid}
{target_uuid_compact}
```

Ścieżki muszą być względne i pozostać wewnątrz katalogu pluginu. `..`, ścieżki absolutne,
backslash oraz symlinki są odrzucane.

## Jawna zmiana UUID w treści

Jeżeli administrator zna format konkretnego pluginu i wie, że dany plik powinien przejść
pełną zmianę tożsamości, może jawnie włączyć zamianę UUID tylko w tym jednym pliku:

```yaml
id: my-plugin-profile
plugin-directory: MyPlugin

rules:
  - type: UUID_FILE
    source: "profiles/{source_uuid}.yaml"
    target: "profiles/{target_uuid}.yaml"
    rewrite-uuid-in-content: true
    require-source-uuid-absent: false
```

Plik musi być poprawnym UTF-8. Zamieniane są kanoniczne i kompaktowe formy starego UUID,
wraz z wariantem wielkich liter. Nie jest to mechanizm globalny — zakres jest ograniczony
do jawnie zadeklarowanego pliku.

Dla współdzielonych plików z polami typu `owner`, `last-killer`, historią audytu itd.
należy nadal użyć dedykowanego providera. Sama obecność UUID nie mówi, czy dane pole ma
reprezentować bieżącą tożsamość, czy historyczny zapis.

## Diagnostyka

`/agx migrate inspect <nick>` rozróżnia:

- bezpieczne operacje z recept,
- bezpieczne operacje generic UUID_FILE,
- znalezione dane wymagające review,
- błędną lub niebezpieczną receptę,
- przekroczenie limitów skanowania.

Jeżeli obok bezpiecznych operacji istnieje choć jedno nierozpoznane wystąpienie starego UUID,
cała migracja pozostaje `BLOCKED`. AuthGatewayX nie wykonuje częściowego „best effort”
przed uzyskaniem kompletnego planu.

## Świadome pominięcie danych pluginu

Jeżeli administrator świadomie nie chce zachować danych starej tożsamości z konkretnego
pluginu, może podać dokładną nazwę jego katalogu danych:

```yaml
migration:
  unmanaged-plugin-scan:
    ignored-plugin-directories:
      - BeautyQuests
      - CoreProtect
```

Porównanie nazw nie rozróżnia wielkości liter. AuthGatewayX skanuje wskazany katalog
wyłącznie w celu potwierdzenia obecności starego UUID, ale nie kopiuje ani nie modyfikuje
żadnego jego pliku. `inspect` wymienia pominięte katalogi, a potwierdzone wystąpienie UUID
stanowi dowód starej tożsamości wymagany przez recovery. Pozwala to zakończyć recovery bez
tych danych, lecz gracz zachowa je wyłącznie pod starym UUID. Lista jest domyślnie pusta
i nie zastępuje providera, gdy dane mają zostać zachowane. Po edycji listy można wykonać
`/agx reload`; odczyt pliku odbywa się na ograniczonym executorze migracji, a nowa,
zwalidowana lista jest atomowo używana przez kolejne `inspect`, `recover` i `retry`.
Niepoprawny plik nie zastępuje ostatniej poprawnej listy.

## Ograniczenia

Warstwa uniwersalna celowo nie modyfikuje:

- SQLite ani innych plików `.db`,
- NBT i pozostałych formatów binarnych,
- MySQL/MariaDB/PostgreSQL/Redis,
- współdzielonych dokumentów, w których znaczenie znalezionego UUID nie jest znane.

Takie magazyny wymagają dedykowanego `IdentityMigrationProvider` albo rozszerzenia katalogu
recept o jednoznacznie zdefiniowany i odwracalny typ operacji.
