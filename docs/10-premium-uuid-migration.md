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
- przegrany równoległy finalizer po nieudanym CAS odświeża ticket i konto w nowej
  transakcji; zwraca idempotentny sukces tylko wtedy, gdy konkurent zakończył dokładnie
  ten sam ticket z oczekiwanym target UUID,
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
w YAML, SQLite, wielu tabelach SQL, Redisie lub własnym formacie. Dlatego dedykowany
`IdentityMigrationProvider` nadal jest najwyższym poziomem integracji.

Dla prostych lokalnych magazynów AuthGatewayX ma jednak warstwę pośrednią:
`UniversalLocalIdentityMigrationProvider`. Najpierw stosuje wbudowane lub administracyjne
recepty, a następnie bezpieczny fallback dla plików nazwanych dokładnie starym UUID
(`.yml/.yaml/.json/.toml/.properties`). Generic fallback działa tylko wtedy, gdy stare UUID
nie występuje również w treści pliku. Source pozostaje zachowany, target ma backup i journal
rollbacku.

Jeżeli stare UUID zostanie znalezione w innym miejscu, migracja nadal jest fail-closed i
`inspect` pokazuje plugin oraz przykładowe ścieżki wymagające review. Pliki `.db`, formaty
binarne oraz zdalne MySQL/MariaDB/PostgreSQL/Redis nie są modyfikowane automatycznie.

Skan ma limit liczby plików i łącznego odczytu bajtów. Szczegółowy format recept i wbudowana
obsługa AdvancedPortals są opisane w `docs/11-universal-local-migration-recipes.md`.

## Publiczny provider

`IdentityMigrationProvider` udostępnia:

- `inspect(context)`,
- `migrate(context)`,
- `rollback(context)`,
- `managedDataOwners`.

Provider rejestruje się w Paper ServicesManager. `managedDataOwners` powinno zawierać
nazwy katalogów danych, za które provider bierze odpowiedzialność, np. `PlotsX`.

## Recovery starszych automatycznych migracji

Starsza wersja AuthGatewayX mogła już przełączyć konto na `MOJANG`, zanim istniał
koordynator danych. Dla takiego przypadku istnieje osobny typ ticketu `RECOVERY`.

`RECOVERY` różni się od `UPGRADE`:

- konto pozostaje `MOJANG` przez cały proces,
- target UUID to aktualny oficjalny UUID konta,
- source UUID jest rekonstruowany standardowo z bieżącej nazwy lub podawany jawnie przez administratora,
- samo wyliczenie UUID nie wystarcza — co najmniej jeden provider/skaner musi potwierdzić realny ślad danych,
- po sukcesie stare UUID jest dopisywane do `account_identities` jako nieaktywna tożsamość OFFLINE,
- bieżący rekord `accounts` nie jest cofany ani przepisywany.

Komendy:

```text
/authgatewayx migrate status <nick>
/authgatewayx migrate inspect <nick> [source-uuid]
/authgatewayx migrate retry <nick>
/authgatewayx migrate recover <nick> [source-uuid]
```

`inspect` nie modyfikuje danych. `recover` wymaga, aby docelowy gracz był offline.
Jeżeli historyczna wielkość liter nicku różniła się od aktualnej nazwy Mojang, administrator
może podać jawnie stare UUID, ale nadal wymagany jest dowód danych po tym UUID.

## Restart i przerwana migracja

`PREPARED` i `MIGRATING` są trwałe. Po uruchomieniu serwera
`PremiumMigrationStartupRecovery` pobiera ograniczoną liczbę niedokończonych ticketów
i ponawia ten sam idempotentny pipeline. Limit jest ustawiany przez
`migration.recovery.startup-batch-limit`.

Dla recovery aktywnego konta premium storage blokuje Mojang activation, gdy ticket ma
stan `PREPARED` lub `MIGRATING`. Blokada pozostaje również dla `FAILED`, jeżeli
failure reason wskazuje nieudany rollback. Dzięki temu gracz nie może wejść na profil,
który mógł zostać częściowo zmieniony przed crashem.

Zwykły `FAILED` po poprawnym rollbacku nie blokuje logowania. Administrator może usunąć
przyczynę i użyć `migrate retry`; ponawiany jest ten sam ticket i te same backupy.


## Wbudowane integracje pluginów

AuthGatewayX dostarcza kontrolowane adaptery dla pluginów SyntaxDevTeam, które wystawiają
własny transakcyjny bridge migracji UUID:

- PlotsX — właściciele działek, członkowie i własny journal/rollback,
- HorseManagerX — właściciele koni, relacje trust, sprzedawcy ofert i actor logs,
- PunisherX — aktywne kary, historia, raporty, bridge queue, zaszyfrowany player cache i jail cache.

Każdy z tych pluginów pozostaje właścicielem swojej transakcji i rollbacku. AuthGatewayX
nie modyfikuje ich tabel bezpośrednio; odkrywa bridge przez Bukkit ServicesManager.
Jeżeli plugin jest zainstalowany, ale bridge jest niedostępny, migracja kończy się fail-closed.

## Popularne integracje zewnętrzne

EssentialsX ma kontrolowany migrator pliku YAML `userdata/<uuid>.yml` z trwałym backupem
targetu, zapisem przez plik tymczasowy i rollbackiem. Source pozostaje zachowany. Jeżeli
docelowy plik już istnieje i różni się od danych starego UUID (typowy skutek wcześniejszego,
przedwczesnego przełączenia UUID), nie jest to już automatycznie konflikt: dokładny target
jest najpierw kopiowany do backupu, a następnie zastępowany zmigrowanym source. Rollback
odtwarza poprzedni target bajt w bajt. Nadal fail-closed są dowiązania symboliczne i ścieżki,
które nie są zwykłymi plikami.

LuckPerms jest obsługiwany przez jego publiczne API 5.5, bez bezpośrednich zapytań do tabel.
Provider kopiuje trwałe węzły source (w tym dziedziczenie grup/rang i bezpośrednie
uprawnienia wraz z kontekstami oraz expiry) do pustego targetu, zapisuje mapowanie gracza i zachowuje
source. Różny, niepusty target jest konfliktem. Ponowienie jest idempotentne, gdy target
jest dokładną kopią source. Rollback czyści target wyłącznie przy nadal identycznym zbiorze
węzłów; zmiana targetu po migracji kończy się fail-closed.

Vault nie przechowuje sald i nie jest providerem danych — jest wyłącznie warstwą API.
Migracja ekonomii musi należeć do konkretnego pluginu ekonomii i jego transakcyjnego
`IdentityMigrationProvider`; samo wykrycie Vault nie dowodzi ani obecności, ani braku salda.
Do czasu dostarczenia takiego providera migrację należy traktować jako nieobsługiwaną.


## Czytelna diagnostyka administratora

`/authgatewayx migrate inspect <nick> [source-uuid]` jest raportem administracyjnym, a nie
zrzutem wewnętrznych kodów providera. Raport pokazuje opis trybu i stanu, podsumowanie liczby
integracji gotowych / bez danych / zablokowanych oraz opisuje każdą integrację językiem
operacyjnym. Surowy kod pozostaje widoczny jako informacja techniczna tylko przy blokadzie.

Dla providerów z licznikami (PlotsX, HorseManagerX, PunisherX) kody są rozbijane na czytelne
liczby rekordów. Skan niezarządzanych pluginów raportuje przykładową względną ścieżkę pliku,
który przekroczył budżet lub zawiera stare UUID. Dzięki temu administrator widzi zarówno
przyczynę blokady, jak i sposób jej usunięcia bez zgadywania znaczenia kodu.
