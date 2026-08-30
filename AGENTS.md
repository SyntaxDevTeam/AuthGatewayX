# AGENTS.md — AuthGatewayX

## Cel

Ten plik definiuje obowiązujące zasady pracy dla każdego agenta, modelu AI, automatyzacji lub programisty wykonującego zadania w repozytorium AuthGatewayX.

Dokumentacja projektowa znajdująca się w katalogu dokumentacji AuthGatewayX jest źródłem prawdy dla architektury, bezpieczeństwa, zakresu funkcjonalnego, integracji, standardów Paper/Folia/Velocity oraz sposobu wdrożenia.

## Zasada nadrzędna

**Należy ściśle trzymać się dokumentacji projektu.**

Nie wolno:

- omijać wymagań opisanych w dokumentacji,
- upraszczać mechanizmów bezpieczeństwa kosztem ochrony kont lub serwera,
- zmieniać architektury bez udokumentowania przyczyny,
- wprowadzać rozwiązań sprzecznych z założeniami Paper/Folia/Velocity,
- kopiować rozwiązań z innych projektów SyntaxDevTeam bez oceny ich poprawności w kontekście AuthGatewayX,
- traktować checklist jako sugestii — pozycje oznaczone jako wymagane są warunkiem ukończenia danego etapu.

## Dokumenty obowiązujące

Przed rozpoczęciem pracy należy zapoznać się co najmniej z:

```text
00-README.md
01-koncepcja-ogolna.md
02-zalozenia-praktyczne.md
03-zalozenia-techniczne.md
04-bezpieczenstwo-wydajnosc-integracje.md
05-propozycja-zakresu-1.0.0.md
06-standard-wdrozeniowy-syntaxdevteam-paper-folia.md
AuthGatewayX-1.0.0-kompletna-dokumentacja.md
```

W przypadku rozbieżności należy:

1. ustalić, który dokument zawiera nowszą lub bardziej szczegółową decyzję,
2. nie zgadywać intencji,
3. ujednolicić dokumentację przy okazji wykonywanej zmiany,
4. pozostawić końcowy stan dokumentacji bez sprzeczności.

## Aktualizacja dokumentacji jest częścią implementacji

Dokumentacja MUSI być aktualizowana na bieżąco wraz z rozwojem projektu.

Zmiana kodu nie jest kompletna, jeżeli powoduje zmianę:

- architektury,
- API,
- przepływu logowania,
- modelu danych,
- konfiguracji,
- zachowania gracza,
- obsługiwanych platform,
- zależności,
- sposobu inicjalizacji,
- schedulerów,
- bezpieczeństwa,
- anti-bot,
- anti-flood,
- cache,
- integracji,
- procesu migracji,
- checklisty wydania,

a odpowiedni dokument nadal opisuje stary stan.

## Zasada "code + docs"

Każda istotna zmiana powinna być traktowana jako jeden pakiet:

```text
IMPLEMENTACJA
    +
TESTY
    +
AKTUALIZACJA DOKUMENTACJI
    =
UKOŃCZONE ZADANIE
```

Jeżeli którykolwiek z tych elementów jest brakujący, zadanie nie powinno być uznawane za zakończone.

## Aktualizowanie checklist

W trakcie realizacji należy aktualizować checkboxy w dokumentacji.

Konwencja:

```text
[ ] nie rozpoczęto / nie zweryfikowano
[x] wykonano i zweryfikowano
```

Nie wolno oznaczać elementu jako wykonanego tylko dlatego, że:

- istnieje szkic klasy,
- kod się kompiluje,
- funkcja jest częściowo zaimplementowana,
- brakuje testów,
- nie została sprawdzona zgodność z Folia/Velocity,
- nie zostały sprawdzone wymagania bezpieczeństwa.

Checkbox `[x]` oznacza, że funkcja została wdrożona, zintegrowana i zweryfikowana w zakresie przewidzianym przez dokumentację.

## Dziennik decyzji

Jeżeli podczas implementacji pojawi się konieczność odejścia od pierwotnego założenia, należy:

1. opisać problem,
2. uzasadnić decyzję techniczną,
3. wskazać wpływ na bezpieczeństwo i kompatybilność,
4. zaktualizować odpowiedni dokument,
5. dopiero później traktować nowe rozwiązanie jako obowiązujące.

Nie wolno pozostawiać istotnych decyzji architektonicznych wyłącznie:

- w komentarzu w kodzie,
- w opisie commita,
- w Pull Request,
- w rozmowie z AI.

Docelowa decyzja musi znaleźć się w dokumentacji repozytorium.

# Wymagania nienegocjowalne

Podczas całego procesu rozwoju należy bezwzględnie chronić:

1. bezpieczeństwo kont graczy,
2. konta premium przed przejęciem przez użytkowników offline,
3. świat gry przed graczem w stanie PRE_AUTH,
4. serwer przed botami,
5. warstwę połączeń przed floodem,
6. stabilność i wydajność Paper/Purpur/Folia/Velocity,
7. integralność sesji i modelu tożsamości.

Żadna optymalizacja, funkcja UX ani skrót implementacyjny nie może osłabiać tych zasad.

# Premium / offline authentication

Należy przestrzegać zasady:

```text
chroniony nick premium
    +
nieudane Mojang authentication
    =
DENY
```

Nie wolno implementować niekontrolowanego:

```text
premium auth failed
    ->
fallback offline
```

jeżeli może to prowadzić do przejęcia konta.

# PRE_AUTH isolation

Gracz non-premium przed poprawnym `/login` lub `/register` musi pozostawać w izolacji.

Należy blokować co najmniej operacje opisane w dokumentacji bezpieczeństwa:

- ruch,
- interakcje,
- inventory,
- block break/place,
- combat,
- chat,
- nieautoryzowane komendy,
- plugin messaging mogący ominąć auth,
- teleporty lub inne akcje mogące wpłynąć na świat.

# Integracje SyntaxDevTeam

Należy korzystać z:

```text
SyntaxDevTeam/MessageHandler
SyntaxDevTeam/SyntaxCore
```

zgodnie z dokumentem:

```text
06-standard-wdrozeniowy-syntaxdevteam-paper-folia.md
```

Projekt `SyntaxDevTeam/PunisherX` jest referencją strukturalną, ale nie bezwarunkowym wzorem do kopiowania.

Jeżeli rozwiązanie z PunisherX jest mniej bezpieczne lub mniej poprawne dla AuthGatewayX, należy zastosować rozwiązanie właściwe dla AuthGatewayX i opisać to w dokumentacji.

# MessageHandler

Nie tworzyć równoległego systemu wiadomości.

Należy używać odpowiedniej implementacji:

```text
MessageHandler-Paper
MessageHandler-Velocity
```

dla:

- MiniMessage,
- plików językowych,
- locale,
- placeholderów,
- prefixów,
- formatowania wiadomości.

# SyntaxCore

Należy korzystać z SyntaxCore tam, gdzie dostarcza wspólną infrastrukturę projektu.

W szczególności:

- Logger,
- DebugLevel,
- ServerEnvironment,
- PluginManagerX,
- update checker,
- StatsCollector,
- inne uzgodnione komponenty wspólne.

Jednocześnie awaria telemetryki, statystyk lub update-checkera nie może blokować krytycznej ścieżki logowania.

# Paper Plugin Lifecycle

Moduł Paper/Purpur/Folia powinien używać natywnego modelu Paper:

```text
paper-plugin.yml
PluginLoader
PluginBootstrap
Lifecycle API
```

W szczególności:

```java
io.papermc.paper.plugin.loader.PluginLoader
io.papermc.paper.plugin.bootstrap.PluginBootstrap
```

Należy utrzymywać izolację odpowiedzialności:

- `PluginLoader` — runtime dependencies/classpath,
- `PluginBootstrap` — bootstrap-safe lifecycle,
- główna klasa pluginu — runtime services,
- initializer/composition root — składanie usług,
- domain — logika niezależna od platformy.

# Paper / Folia threading

Zgodność z Folia jest obowiązkowa i musi być rzeczywista, a nie tylko zadeklarowana przez:

```yaml
folia-supported: true
```

Należy przestrzegać ownership schedulerów:

```text
EntityScheduler
    -> gracz / entity

RegionScheduler
    -> lokalizacja / chunk / region

GlobalRegionScheduler
    -> prawdziwy global server state

AsyncScheduler / bounded executor
    -> JDBC / HTTP / Argon2 / I/O / CPU-heavy
```

Nie wolno używać `GlobalRegionScheduler` jako zamiennika dla async I/O.

Nie wolno wykonywać blokującego:

- JDBC,
- HTTP,
- DNS,
- Argon2id,
- file I/O,

na threadach odpowiedzialnych za stan gry.

# Wydajność

Optymalizacja ma być oparta na realnym koszcie.

Preferować:

- async I/O,
- bounded executors,
- Caffeine cache,
- TTL,
- size limits,
- backpressure,
- batch operations,
- eliminowanie powtarzanych lookupów,
- minimalizowanie zewnętrznych requestów,
- wykonywanie tanich filtrów przed drogimi operacjami.

Kolejność przykładowa:

```text
connection validation
    ->
anti-flood
    ->
anti-bot
    ->
cache
    ->
DB
    ->
Mojang / external API
```

Nie wolno wykonywać kosztownych requestów przed cheap guards, jeśli nie jest to konieczne.

# Anti-bot i anti-flood

Mechanizmy anti-bot i connection anti-flood są częścią podstawowej architektury, nie dodatkiem opcjonalnym na koniec projektu.

Każda zmiana w login pipeline powinna zostać przeanalizowana pod kątem:

- możliwości masowego wywołania,
- zużycia CPU,
- użycia pamięci,
- liczby requestów HTTP,
- liczby zapytań DB,
- możliwości tworzenia nieograniczonej liczby tasków/futures,
- reconnect flood,
- multi-account flood,
- brute-force.

# Cache

Każdy cache musi posiadać:

- jasno zdefiniowany cel,
- ograniczenie rozmiaru,
- TTL lub inną politykę wygaszania,
- sposób invalidacji,
- określone zachowanie po stale data,
- testy krytycznych scenariuszy.

Nie tworzyć cache "na wszelki wypadek".

# Baza danych

Operacje storage mają być:

- asynchroniczne względem threadów gry,
- odporne na timeout,
- ograniczone przez connection pool,
- bezpieczne przy równoległych logowaniach,
- atomowe tam, gdzie zmieniają stan konta lub sesji.

Należy szczególnie testować:

- dwa jednoczesne `/register`,
- dwa jednoczesne logowania,
- migrację OFFLINE -> MOJANG,
- disconnect podczas operacji,
- ban pojawiający się w trakcie auth.

# CleanerX

Integracja CleanerX odpowiada za dodatkową politykę nazw.

Nie wiązać domeny AuthGatewayX bezpośrednio z prywatnymi klasami CleanerX.

Preferować:

```text
public API
ServicesManager
adapter/provider
```

# PunisherX

PunisherX powinien pełnić rolę zewnętrznego systemu wykonawczego kar.

AuthGatewayX powinien korzystać z jego publicznego API tam, gdzie dostępne.

Nie duplikować kompletnego systemu banów PunisherX wewnątrz AuthGatewayX.

# API

Publiczne API AuthGatewayX musi być:

- stabilne,
- dokumentowane,
- niezależne od szczegółów storage,
- niezależne od NMS,
- możliwie wspólne dla Velocity i Paper.

Zmiany breaking API wymagają aktualizacji dokumentacji i świadomej decyzji wersjonowania.

# Testy

Zmiana nie jest kompletna bez testów właściwych dla jej charakteru.

Minimalnie należy rozważyć:

- unit tests,
- integration tests,
- Paper test,
- Folia test,
- Velocity test,
- concurrency test,
- security test,
- reconnect/flood test,
- failure-path test.

W przypadku krytycznego auth flow test happy-path jest niewystarczający.

# Obsługiwane platformy

Przy każdej zmianie należy rozważyć wpływ na:

```text
Velocity
Paper
Purpur
Folia
```

Kod wspólny nie powinien niepotrzebnie importować API platformowego.

# Zależności

Każda nowa biblioteka powinna być oceniona pod kątem:

- rzeczywistej potrzeby,
- bezpieczeństwa,
- wielkości,
- aktywności projektu,
- licencji,
- kompatybilności z Java/Paper/Folia,
- możliwości runtime loading przez PluginLoader.

W stabilnym `1.0.0` preferować release dependencies zamiast SNAPSHOT, jeżeli stabilne wersje są dostępne.

# Dokumentowanie wykonanych zmian

Po zakończeniu większego etapu należy dopisać do właściwego dokumentu:

- co zostało wdrożone,
- jakie klasy/moduły powstały,
- jakie decyzje zostały zmienione,
- co pozostaje do wykonania,
- które checkboxy można oznaczyć jako `[x]`.

Jeżeli dokumentacja zawiera plan przyszłej klasy, a implementacja użyła innej nazwy lub odpowiedzialności, dokumentację należy poprawić.

# Zakaz pozostawiania dokumentacji "na później"

Nie stosować podejścia:

```text
najpierw napiszemy kod,
dokumentację poprawimy kiedyś
```

Dokumentacja powinna ewoluować wraz z kodem w tym samym etapie pracy.

# Priorytet w przypadku konfliktu

Jeżeli implementacja, istniejący kod lub starszy komentarz jest sprzeczny z aktualną dokumentacją:

```text
AKTUALNA DOKUMENTACJA PROJEKTOWA
            >
STARY KOD
            >
STARY KOMENTARZ / PR / CHAT
```

Jeżeli dokumentacja jest ewidentnie nieaktualna wobec zatwierdzonej decyzji projektowej, należy najpierw ją uaktualnić, a następnie kontynuować implementację.

# Definition of Done

Zadanie można oznaczyć jako zakończone dopiero wtedy, gdy:

```text
[ ] kod jest zaimplementowany
[ ] kod się kompiluje
[ ] testy przechodzą
[ ] sprawdzono security impact
[ ] sprawdzono Paper/Folia/Velocity impact
[ ] nie wprowadzono blokującego I/O na thread gry
[ ] zaktualizowano dokumentację
[ ] zaktualizowano checklisty
[ ] brak znanych regresji
```

Dopiero po spełnieniu odpowiednich punktów zadanie jest kompletne.

# Końcowa zasada

**Kod ma odzwierciedlać dokumentację, a dokumentacja ma odzwierciedlać aktualny kod.**

Oba elementy muszą być utrzymywane równolegle przez cały rozwój AuthGatewayX.
