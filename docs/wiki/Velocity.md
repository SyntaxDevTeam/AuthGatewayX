# Velocity — sieć serwerów

[Home](Home.md) · [Instalacja](Instalacja.md) · [Zgodność](Zgodnosc.md)

Velocity to wejście do sieci serwerów: gracz łączy się z jednym adresem, a proxy przekazuje go do serwera gry. AuthGatewayX ma osobną wersję na Velocity oraz wersję Paper na serwer gry.

Obsługa sieci jest nadal w rozwoju. Poniższe ustawienia służą przygotowaniu środowiska testowego; nie oznaczają zakończonej weryfikacji całej sieci.

## Co zainstalować?

Plik AuthGatewayX dla Velocity umieść w `plugins` proxy. Plik Paper umieść w `plugins` serwera gry. Sam plugin na proxy nie zapewnia formularzy i ochrony świata na serwerze Paper.

## Ustawienia Velocity

Przy wyłączonym proxy w `velocity.toml` ustaw:

```toml
online-mode = false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
```

AuthGatewayX wybiera sposób sprawdzenia konta osobno dla każdego połączenia. Użyj prawdziwego sekretu z pliku wskazanego przez `forwarding-secret-file`.

## Ustawienia serwera Paper

W `server.properties`:

```properties
online-mode=false
```

W istniejącej sekcji `proxies` pliku `config/paper-global.yml`:

```yaml
proxies:
  velocity:
    enabled: true
    online-mode: false
    secret: "TU_WKLEJ_PRAWDZIWY_SEKRET_VELOCITY"
```

Oba miejsca muszą korzystać z tego samego sekretu. Nie zostawiaj tekstu przykładowego i nie publikuj wartości. `online-mode` w tej sekcji ma odpowiadać ogólnemu ustawieniu Velocity.

Włączona obsługa Velocity na Paper pozwala AuthGatewayX rozpoznać, że działa za proxy. Na samodzielnym serwerze pozostaje wyłączona.

## Zamknij bezpośrednie wejście na serwer gry

W panelu hostingu lub zaporze sieciowej dopuść do portu Paper tylko połączenia z zaufanego proxy. Gracze mają wchodzić adresem Velocity. Sam sekret nie zastępuje ograniczenia dostępu do tego portu.

## Config pluginu na Velocity

W folderze danych AuthGatewayX na proxy znajduje się `authgatewayx.yml`. To osobny plik od `config.yml` na Paper.

| Opcja | Domyślnie | Znaczenie |
| --- | --- | --- |
| `premium.lookup.timeout-millis` | `3000` | Czas oczekiwania na sprawdzenie nicku |
| `premium.lookup.positive-ttl-seconds` | `21600` | Czas pamiętania profilu premium |
| `premium.lookup.negative-ttl-seconds` | `300` | Czas pamiętania braku profilu premium |
| `premium.lookup.maximum-cache-size` | `50000` | Limit zapamiętanych wyników |
| `connections.pending-ttl-seconds` | `30` | Jak długo czeka decyzja dotycząca rozpoczętego połączenia |
| `connections.maximum-pending` | `10000` | Maksymalna liczba oczekujących decyzji |
| `executors.mojang-threads` | `4` | Liczba jednoczesnych zadań sprawdzania nicków |
| `executors.mojang-queue` | `128` | Maksymalna liczba zadań czekających |

Po zmianach wykonaj restart odpowiedniego serwera lub proxy.

## Próba działania

Sprawdź wejście premium i offline przez Velocity, ponowne połączenie oraz odmowę wejścia bezpośrednio na Paper spoza proxy. Przetestuj również zmianę serwera, jeśli Twoja sieć z niej korzysta. Nie zakładaj, że konto raz zalogowane będzie automatycznie zalogowane na każdym serwerze — wspólne sesje całej sieci nie są gotową funkcją tego wydania.

Komunikat `Backend server is online-mode` może oznaczać, że serwer gry używa niewłaściwego trybu. Sprawdź `online-mode=false` w `server.properties`, włączenie Velocity w `paper-global.yml` i zgodność sekretu.

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
