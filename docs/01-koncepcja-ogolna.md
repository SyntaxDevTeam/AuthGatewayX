# AuthGatewayX 1.0.0 — ogólna koncepcja

## 1. Czym ma być AuthGatewayX

AuthGatewayX 1.0.0 ma być publicznym pluginem uwierzytelniającym dla serwerów Minecraft, który umożliwia jednoczesną obsługę:

- graczy premium/online — uwierzytelnionych przez oficjalny system Microsoft/Mojang,
- graczy non-premium/offline — uwierzytelnianych przez AuthGatewayX przy pomocy `/register` i `/login`.

Plugin nie powinien być tylko „AuthMe z dodatkiem premium”, lecz pełną warstwą zarządzania tożsamością gracza na etapie wejścia do serwera.

## 2. Główna zasada

Docelowy przepływ:

```text
                         POŁĄCZENIE GRACZA
                                |
                                v
                         AuthGatewayX
                                |
                 +--------------+--------------+
                 |                             |
                 v                             v
           KONTO PREMIUM                 KONTO OFFLINE
                 |                             |
                 v                             v
      oficjalne uwierzytelnienie         /register lub /login
      Microsoft/Mojang                         |
                 |                             |
                 +--------------+--------------+
                                |
                                v
                       GRACZ UWIERZYTELNIONY
                                |
                                v
                           ŚWIAT SERWERA
```

Gracz premium:

- nie wpisuje hasła,
- musi przejść prawdziwe uwierzytelnienie online,
- otrzymuje prawidłowy oficjalny UUID,
- powinien otrzymać prawidłowy profil i skórkę.

Gracz non-premium:

- otrzymuje tożsamość offline,
- musi zarejestrować konto lub się zalogować,
- do czasu zakończenia uwierzytelniania nie może normalnie korzystać z serwera.

## 3. Velocity

Velocity posiada natywne narzędzia do wyboru trybu uwierzytelniania per połączenie.

AuthGatewayX może w zależności od polityki konta wybrać:

```text
forceOnlineMode()
```

lub:

```text
forceOfflineMode()
```

Dzięki temu implementacja Velocity może korzystać z natywnej warstwy proxy i nie musi samodzielnie rekonstruować pełnego protokołu uwierzytelniania.

## 4. Paper / Purpur / Folia

Standalone Paper/Purpur/Folia powinien pracować w:

```properties
online-mode=false
```

AuthGatewayX przejmuje kontrolę nad etapem LOGIN i dla kont premium wykonuje własną wersję procesu online-mode:

```text
LOGIN_START
    |
    v
ENCRYPTION_REQUEST
    |
    v
ENCRYPTION_RESPONSE
    |
    v
Mojang Session Server verification
    |
    v
authenticated GameProfile
    |
    v
Paper login pipeline
```

Dla kont non-premium plugin przepuszcza gracza jako offline i nakłada własną warstwę uwierzytelniania.

## 5. Rozdzielenie tożsamości od sesji

AuthGatewayX powinien rozróżniać:

### Tożsamość

```text
MOJANG
OFFLINE
```

### Stan konta

```text
UNREGISTERED
REGISTERED
AUTHENTICATING
AUTHENTICATED
LOCKED
```

### Stan połączenia

```text
CONNECTING
PRE_AUTH
ACTIVE
DISCONNECTED
```

Nie należy traktować „premium” i „non-premium” jako jedynego stanu gracza.

## 6. Ochrona nazw premium

Konto premium musi mieć pierwszeństwo przed graczem non-premium używającym tego samego nicku.

Przykład:

```text
Mojang:
username = ExampleUser
uuid = official-uuid
```

Jeżeli gracz non-premium spróbuje wejść jako `ExampleUser`, AuthGatewayX może — zależnie od konfiguracji — wymusić online authentication lub odrzucić połączenie.

Nigdy nie należy stosować bezpiecznościowo niepoprawnego mechanizmu:

```text
premium authentication failed
        |
        v
fallback to offline
```

dla nicku, który jest chroniony jako premium.

## 7. Tryby polityki premium

Przykładowa konfiguracja:

```yaml
premium:
  mode: AUTO

  protect-premium-usernames: true

  authentication-failure:
    action: DENY
```

Możliwe tryby:

```text
DISABLED
AUTO
FORCE_FOR_KNOWN
FORCE_FOR_ALL_MATCHING
```

## 8. Migracja konta offline do premium

Projekt powinien od początku zakładać sytuację:

```text
gracz dzisiaj:
OFFLINE UUID

gracz później:
kupuje Minecraft
otrzymuje MOJANG UUID
```

AuthGatewayX powinien posiadać wewnętrzny identyfikator konta niezależny od Minecraft UUID:

```text
account_id
```

Dzięki temu migracja:

```text
OFFLINE -> MOJANG
```

nie wymaga tworzenia nowego logicznego konta AuthGatewayX.

## 9. Pozycjonowanie projektu

AuthGatewayX ma być:

- systemem authentication-first,
- platform-agnostic w warstwie biznesowej,
- bezpiecznym domyślnie,
- gotowym do integracji z innymi pluginami,
- projektowanym pod duże sieci i małe serwery standalone,
- odpornym na boty, flood i próby przejęcia kont.
