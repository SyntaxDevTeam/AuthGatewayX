# Integracje

**Język / Language:** Polski · [English](EN/Integrations.md)

[Home](Home.md) · [Konfiguracja](Konfiguracja.md)

## CleanerX — zasady dotyczące nicków

CleanerX może sprawdzić, czy nick spełnia zasady Twojego serwera, na przykład czy nie zawiera zakazanego słowa. Jeśli nazwa zostanie odrzucona, AuthGatewayX zatrzyma wejście.

## PunisherX — sprawdzanie kar

PunisherX zarządza karami, a AuthGatewayX może uwzględnić jego odpowiedź przy logowaniu. Obecna integracja sprawdza aktywne kary powiązane z identyfikatorem konta gracza. Nie należy traktować jej jako pełnego sprawdzania wszystkich banów po nicku, IP i całej sieci.

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

`FAIL_OPEN` nie anuluje potwierdzonego bana ani odrzucenia nicku. Dotyczy sytuacji, gdy sprawdzenie nie daje wyniku. Nie wyłącza też weryfikacji tożsamości Minecraft.

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

## Migracja UUID

Przed zmianą typu konta wykonaj kopię danych gracza i sprawdź `/agx migrate inspect <nick>`. Pluginy zapisujące dane pod UUID gracza wymagają zgodnej integracji migracyjnej. Jeśli jej brakuje, usuń zgłoszoną przyczynę blokady przed ponowieniem; nie podmieniaj UUID ręcznie. Zobacz [weryfikację konta](Premium.md).

### EssentialsX i LuckPerms

Migrator EssentialsX kopiuje `userdata/<stare-uuid>.yml`, aktualizuje UUID wewnątrz pliku
i zachowuje trwały backup istniejącego targetu. Plik źródłowy nie jest usuwany. Istniejące odmienne dane
pod docelowym UUID są backupowane przed zastąpieniem i odtwarzane przy rollbacku.

Migracja LuckPerms działa niezależnie od wybranego sposobu zapisu danych. Kopiuje komplet trwałych węzłów użytkownika na nowe UUID — bezpośrednie permisje,
dziedziczenie grup/rang, prefixy, suffixy, meta, konteksty, wartości negowane i terminy
ważności — zachowując source. Same grupy są globalne i nie wymagają kopiowania. Nie nadpisuje
niepustego profilu docelowego z innymi węzłami. Rollback czyści target tylko wtedy, gdy
nadal jest dokładną kopią source; późniejsza zmiana powoduje fail-closed.


### HorseManagerX

AuthGatewayX ma wbudowany adapter do usługi migracyjnej HorseManagerX. Migracja obejmuje
własność koni, relacje zaufania, aktywne oferty rynku i wpisy aktora w logach. W razie błędu integracja umożliwia wycofanie zmiany UUID.

Jeżeli HorseManagerX jest zainstalowany, ale jego bridge migracyjny nie jest dostępny,
AuthGatewayX blokuje finalizację zamiast pomijać dane.


### PunisherX — migracja UUID

Przy migracji UUID na oficjalnie zweryfikowaną tożsamość AuthGatewayX korzysta z transakcyjnego bridge
PunisherX. Obejmuje on aktywne kary, historię kar, raporty, wpisy bridge queue, zaszyfrowany
player cache oraz jail cache. Integracja zachowuje kopie danych potrzebne do wycofania zmiany.

Jeżeli PunisherX jest aktywny, ale jego bridge migracyjny jest niedostępny, AuthGatewayX
blokuje finalizację UUID zamiast pozostawić część danych pod poprzednią tożsamością.
