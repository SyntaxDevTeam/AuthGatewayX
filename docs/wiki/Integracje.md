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
