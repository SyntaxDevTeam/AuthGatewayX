# AuthGatewayX — migracja UUID OFFLINE -> PREMIUM

## Cel

Zmiana istniejącego konta non-premium na zweryfikowane konto premium o tym samym nicku
nie może być zwykłym UPDATE `minecraft_uuid`. Większość świata i pluginów identyfikuje
gracza po UUID, dlatego proces jest transakcją logiczną obejmującą storage AGX i providery
danych.

## Inwariant

`accounts.minecraft_uuid` pozostaje starym UUID OFFLINE, a `password_hash` pozostaje
aktywny, dopóki wszystkie kontrolowane migratory nie zakończą się sukcesem.

## Przebieg

```text
Mojang verified
  -> istniejący OFFLINE account
  -> MigrationRequired
  -> dialog: stare hasło AuthGatewayX
  -> PREPARED
  -> kick / koniec aktywnej sesji premium
  -> inspect providers
  -> unmanaged plugin UUID scan
  -> MIGRATING
  -> migrate providers
  -> completePremiumMigration CAS
  -> account_identities: old inactive + new active
  -> MOJANG / new UUID / password_hash NULL
```

## Wbudowane zabezpieczenia

- hasło jest weryfikowane istniejącym LoginService (Argon2id, rate limit, lockout),
- source UUID pochodzi z istniejącego rekordu OFFLINE, nie z rekonstrukcji nicku,
- target UUID pochodzi ze zweryfikowanej sesji Mojang,
- migracja plików odbywa się dopiero po rozłączeniu gracza,
- provider vanilla kopiuje source do backupu i zachowuje pierwotny target,
- zapis targetu używa pliku tymczasowego i atomic move, gdy filesystem go wspiera,
- providery mają obowiązek idempotencji,
- błąd powoduje rollback także providera, który mógł zatrzymać się w połowie,
- finalizacja konta jest compare-and-set po account_id, source UUID i typie OFFLINE,
- historia UUID pozostaje w account_identities.

## Dane vanilla

Wbudowany provider obejmuje dokładne ścieżki UUID w katalogach załadowanych światów:

- `playerdata/<uuid>.dat`,
- `playerdata/<uuid>.dat_old`,
- `stats/<uuid>.json`,
- `advancements/<uuid>.json`.

Source pozostaje nietknięty; target jest tworzony/zastępowany po wykonaniu backupu.
Rollback odtwarza wcześniejszy target albo usuwa target, jeżeli przed migracją go nie było.

## Dane pluginów

Nie istnieje bezpieczny uniwersalny UPDATE dla dowolnego pluginu. Plugin może trzymać UUID
w YAML, SQLite, wielu tabelach SQL, Redisie lub własnym formacie. Dlatego właściwym
rozszerzeniem jest publiczny `IdentityMigrationProvider`.

AuthGatewayX dodatkowo skanuje lokalne katalogi pluginów bez providera. Szuka starego UUID
w nazwach plików, zapisie tekstowym z/bez myślników oraz jako surowe 16 bajtów. Znalezienie
referencji blokuje migrację. Skan ma limity I/O i również fail-closed po ich przekroczeniu.

Skan nie jest dowodem braku danych w zdalnej bazie. Plugin korzystający z zewnętrznego
MySQL/MariaDB/PostgreSQL/Redis powinien rejestrować własny provider.

## Publiczny provider

`IdentityMigrationProvider` udostępnia:

- `inspect(context)`,
- `migrate(context)`,
- `rollback(context)`,
- `managedDataOwners`.

Provider rejestruje się w Paper ServicesManager. `managedDataOwners` powinno zawierać
nazwy katalogów danych, za które provider bierze odpowiedzialność, np. `PlotsX`.

## Recovery starszych automatycznych migracji

Ten pipeline chroni nowe migracje. Gracz, którego starsza wersja AuthGatewayX zdążyła
automatycznie przełączyć do MOJANG i nadpisać source UUID w głównym rekordzie, wymaga
osobnego trybu recovery. Historia sprzed migracji v7 może nie istnieć, dlatego recovery
powinno rekonstruować standardowy offline UUID z historycznego nicku i potwierdzać go
dowodami w playerdata/pluginach przed jakąkolwiek zmianą. Ten przypadek pozostaje osobnym
zadaniem roadmapy i nie może używać automatycznej finalizacji opisanej powyżej.
