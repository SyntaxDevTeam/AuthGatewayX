# AuthGatewayX 1.0.0 — premium handoff Velocity -> Paper

## 1. Problem

Sam lookup nazwy w Minecraft Services odpowiada wyłącznie na pytanie, czy dana nazwa
jest aktualnie nazwą konta premium. Nie jest dowodem, że bieżące połączenie należy do
właściciela tego konta.

Poprzednia implementacja Paper wykonywała lookup po `PlayerJoinEvent` i dla każdego
wyniku `PREMIUM` odrzucała gracza komunikatem o wymaganym gatewayu premium. Nie
sprawdzała, czy Velocity właśnie wykonało dla tego samego połączenia prawidłowe Mojang
online authentication. W efekcie także prawidłowo uwierzytelniony gracz premium był
odrzucany.

## 2. Inwariant bezpieczeństwa

AuthGatewayX nie może uznać gracza za premium tylko dlatego, że jego nick istnieje w
Minecraft Services.

Dla ścieżki Velocity -> Paper obowiązuje:

```text
lookup nazwy -> official Mojang UUID
                    |
                    v
UUID przekazany do Paper przez zaufany Velocity forwarding
                    |
             +------+------+
             |             |
          zgodny         różny
             |             |
             v             v
   MOJANG VERIFIED        DENY
```

Nie istnieje fallback `PREMIUM -> OFFLINE` po niezgodności UUID.

## 3. Velocity

`VelocityLoginListener` wybiera tryb przed uwierzytelnieniem:

```text
PREMIUM     -> forceOnlineMode()
NOT_PREMIUM -> forceOfflineMode()
```

`forceOnlineMode()` na proxy działającym globalnie w offline mode uruchamia normalny
proces szyfrowania i Mojang Session Server. Po sukcesie Velocity posiada oficjalny
`GameProfile`, w tym oficjalny Minecraft UUID.

Do backendu ten profil musi zostać przekazany przez Velocity modern forwarding. Modern
forwarding podpisuje dane współdzielonym sekretem i przekazuje m.in. IP, UUID, nazwę i
properties profilu.

## 4. Paper

Paper nadal rozpoczyna od krótkiego `PRE_AUTH`, aby do czasu rozwiązania tożsamości nie
udostępnić świata. `MojangProfileLookup` zwraca teraz nie tylko status `PREMIUM`, ale
również oficjalny UUID z odpowiedzi Minecraft Services.

Dla nazwy premium Paper porównuje:

```text
player.uniqueId == officialMojangUuid
```

Znaczenie tego porównania jest następujące:

- zgodność — backend otrzymał ten sam profil, który Minecraft Services przypisuje do
  nazwy; połączenie może przejść ścieżką `MOJANG`,
- niezgodność — backend otrzymał offline UUID albo inną tożsamość; połączenie jest
  odrzucane,
- niedostępność Minecraft Services lub niepoprawna odpowiedź — fail closed.

Samo porównanie UUID jest traktowane jako dowód wyłącznie w konfiguracji, w której
backend przyjmuje informacje gracza przez zaufany Velocity modern forwarding. Nie jest
to zamiennik poprawnego forwarding secret ani ochrony portu backendu.

## 5. Wymagana konfiguracja proxy/backend

Dla hybrydowego AuthGatewayX, gdzie Velocity jest globalnie w offline mode i plugin
wybiera online/offline per połączenie, konfiguracja powinna mieć co najmniej:

### Velocity

```toml
online-mode = false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
```

### Paper — `server.properties`

```properties
online-mode=false
```

### Paper — `config/paper-global.yml`

```yaml
proxies:
  velocity:
    enabled: true
    online-mode: false
    secret: "TEN_SAM_SEKRET_CO_VELOCITY"
```

Wartość `proxies.velocity.online-mode` musi odpowiadać globalnemu `online-mode` proxy,
a nie per-player decyzji `forceOnlineMode()`.

Port backendu Paper powinien być dodatkowo odcięty firewallem od bezpośrednich
połączeń spoza zaufanego proxy. Forwarding secret nie powinien być publikowany ani
commitowany do repozytorium.

## 6. Konto i sesja po poprawnym handoff

Po zgodności UUID `VerifiedMojangAuthenticationService`:

1. atomowo wiąże zweryfikowaną tożsamość z rekordem `accounts`,
2. tworzy konto `MOJANG`, jeśli nie istniało,
3. jeżeli istniało konto `OFFLINE` o tej samej nazwie, migruje je do `MOJANG` z
   zachowaniem wewnętrznego `account_id`,
4. po migracji usuwa `password_hash`, resetuje lockout i usuwa offline registration
   slot,
5. aktywuje sesję jako `IdentityType.MOJANG` + `AuthenticationMethod.MOJANG`,
6. zwalnia gracza z `PRE_AUTH` bez wyświetlania formularza hasła,
7. zapisuje `PREMIUM_VERIFIED` albo `OFFLINE_TO_PREMIUM_MIGRATION` w security audit.

`findCredentials()` zwraca dane wyłącznie dla kont `OFFLINE` posiadających hash hasła,
więc zmigrowane konto premium nie może później wejść ścieżką hasłową.

## 7. Konflikty

Fail closed obowiązuje między innymi gdy:

- oficjalny UUID różni się od UUID widzianego przez Paper,
- ten sam nick jest już związany z innym kontem `MOJANG`,
- oficjalny UUID jest związany z innym, kolidującym kontem,
- lookup Minecraft Services nie jest dostępny,
- persistence/session activation kończy się błędem.

Zmiana nazwy konta premium może zostać automatycznie odzwierciedlona tylko wtedy, gdy
oficjalny UUID wskazuje już istniejące konto `MOJANG`, a nowa nazwa nie koliduje z innym
rekordem.

## 8. Diagnostyka offline UUID

Jeżeli po `forceOnlineMode()` Paper nadal loguje UUID odpowiadający formule
`OfflinePlayer:<nick>`, oznacza to, że zweryfikowany profil premium nie został poprawnie
przekazany do backendu. W takiej sytuacji AuthGatewayX ma odrzucić połączenie, a nie
obchodzić ochronę nicku.

Należy wtedy sprawdzić przede wszystkim:

- `player-info-forwarding-mode = "modern"` na Velocity,
- zgodność forwarding secret,
- `proxies.velocity.enabled: true` na Paper,
- zgodność `proxies.velocity.online-mode` z globalnym `online-mode` Velocity,
- czy gracz faktycznie wchodzi przez Velocity, a nie bezpośrednio na port Paper.

## 9. Zakres tej decyzji

Ten handoff rozwiązuje ścieżkę sieciową Velocity -> Paper/Purpur/Folia. Nie oznacza
ukończenia premium authentication dla standalone Paper. Samodzielny backend bez
zaufanego proxy nadal wymaga planowanego protocol interceptora i własnej weryfikacji
Mojang Session Server opisanej w dokumentacji technicznej.
