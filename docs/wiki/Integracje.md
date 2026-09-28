# Integracje

[Home](Home.md) · [Konfiguracja](Konfiguracja.md)

## CleanerX — zasady dotyczące nicków

CleanerX może sprawdzić, czy nick spełnia zasady Twojego serwera, na przykład czy nie zawiera zakazanego słowa. Jeśli nazwa zostanie odrzucona, AuthGatewayX zatrzyma wejście.

## PunisherX — sprawdzanie kar

PunisherX zarządza karami, a AuthGatewayX może uwzględnić jego odpowiedź przy logowaniu. Obecna integracja sprawdza aktywne kary powiązane z identyfikatorem konta gracza. Nie należy traktować jej jako pełnego sprawdzania wszystkich banów po nicku, IP i całej sieci — ten zakres jest jeszcze rozwijany.

## Czy muszę instalować oba pluginy?

Nie. Domyślny tryb `AUTO` używa integracji, gdy zgodna usługa jest dostępna. Jej brak nie wymaga instalacji dodatku, żeby uruchomić podstawowe logowanie.

Obie integracje mają takie same rodzaje ustawień:

| Tryb `mode` | Działanie |
| --- | --- |
| `AUTO` | Korzystaj z dostępnej integracji; brak usługi pomija sprawdzenie |
| `REQUIRED` | Traktuj brak usługi jak jej niedostępność i zastosuj strategię awarii |
| `DISABLED` | Nie korzystaj z tej integracji |

| `failure-strategy` | Działanie przy niedostępności lub błędzie |
| --- | --- |
| `FAIL_CLOSED` | Zatrzymaj logowanie, jeśli sprawdzenia nie można dokończyć |
| `FAIL_OPEN` | Pozwól kontynuować bez wyniku tego sprawdzenia |

`FAIL_OPEN` nie anuluje potwierdzonego bana ani odrzucenia nicku. Dotyczy sytuacji, gdy sprawdzenie nie daje wyniku. Nie wyłącza też weryfikacji premium.

## Przykład: PunisherX ma być wymagany

W istniejącej sekcji `integrations` ustaw:

```yaml
integrations:
  cleanerx:
    mode: AUTO
    failure-strategy: FAIL_CLOSED
  punisherx:
    mode: REQUIRED
    failure-strategy: FAIL_CLOSED
```

Po restarcie niedostępność wymaganej integracji PunisherX zatrzyma logowanie. Sprawdź działanie na koncie testowym z karą i bez niej. Jeśli wybierzesz `REQUIRED` razem z `FAIL_OPEN`, awaria lub brak usługi nie zatrzyma wejścia.

## Integracja z migracją UUID OFFLINE -> PREMIUM

Plugin przechowujący dane gracza pod UUID może zarejestrować w Bukkit/Paper ServicesManager
implementację `pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationProvider`.

Provider otrzymuje wyłącznie kontekst migracji: stabilne `accountId`, nick, stare UUID,
nowe UUID oraz ID migracji. Nie otrzymuje hasła gracza ani adresu IP.

Kontrakt ma trzy fazy:

1. `inspect(context)` — zgłasza `READY`, `NO_DATA` albo `BLOCKED`,
2. `migrate(context)` — wykonuje idempotentne przeniesienie z własnym backupem,
3. `rollback(context)` — odtwarza stan sprzed migracji.

Provider powinien również zadeklarować `managedDataOwners`, np. `setOf("PlotsX")`. Wtedy
wbudowany skaner AuthGatewayX nie potraktuje katalogu `plugins/PlotsX` jako niezarządzanego,
bo za jego migrację odpowiada jawny adapter.

AuthGatewayX uruchamia providery sekwencyjnie. Błąd późniejszego providera powoduje rollback
już wykonanych providerów w odwrotnej kolejności. Dopiero po ich sukcesie storage może
wykonać `completePremiumMigration(...)` i podmienić UUID w rekordzie konta.


### HorseManagerX

AuthGatewayX ma wbudowany adapter do usługi migracyjnej HorseManagerX. Migracja obejmuje
własność koni, relacje zaufania, aktywne oferty rynku i wpisy aktora w logach. HorseManagerX
prowadzi własny journal i rollback oraz czyści cache po zmianie UUID.

Jeżeli HorseManagerX jest zainstalowany, ale jego bridge migracyjny nie jest dostępny,
AuthGatewayX blokuje finalizację zamiast pomijać dane.
