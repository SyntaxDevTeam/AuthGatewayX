# Przewodnik administratora

**Język / Language:** Polski · [English](EN/ADMIN-GUIDE.md)

[WIKI](Home.md) · [Instalacja](Instalacja.md) · [Konfiguracja](Konfiguracja.md) · [Pomoc](Pomoc.md)

Od pierwszego uruchomienia do codziennej obsługi: ten przewodnik prowadzi przez ustawienia, które warto przygotować przed zaproszeniem graczy. Szczegółowe wartości wszystkich opcji znajdziesz w [konfiguracji](Konfiguracja.md).

## 1. Wybierz sposób uruchomienia

| Twój serwer | Co przygotować |
| --- | --- |
| Jeden Paper/Purpur | Właściwy wariant Paper, Java 25, lokalna baza SQLite na start |
| Sieć za Velocity | Moduł Velocity na proxy, Paper na backendzie, modern forwarding i zamknięty publiczny dostęp do portu backendu |

Dobierz plik do wersji Minecraft według [wymagań platformowych](Zgodnosc.md). Przejdź przez [instalację](Instalacja.md), zanim zmienisz ustawienia zabezpieczeń. Nie uruchamiaj kilku pluginów logowania naraz.

## 2. Przygotuj konfigurację

Plik serwera gry: `plugins/AuthGatewayX/config.yml`. Plik proxy: `authgatewayx.yml` w folderze danych pluginu na Velocity. To dwa różne zestawy ustawień.

Zatrzymaj serwer i wykonaj kopię plików. Poniższy fragment pokazuje podstawowe wartości Paper — edytuj istniejące sekcje, zamiast zastępować nim cały plik:

```yaml
language: "PL"
authentication:
  timeout-seconds: 120
  maximum-pre-auth-players: 500
  password:
    minimum-length: 8
    maximum-length: 128
  lockout:
    attempts: 5
    duration-seconds: 600
storage:
  type: SQLITE
  sqlite-file: authgatewayx.db
  pool-size: 2
```

Zachowaj pozostałe pola, szczególnie ustawienia Argon2id. Domyślne limity i ograniczone kolejki są dobrym punktem wyjścia; zwiększaj je na podstawie obserwacji obciążenia. Nie zmniejszaj kosztu hashowania, żeby ukryć problem masowych połączeń.

SQLite nie wymaga osobnej usługi bazy. Jeśli potrzebujesz MySQL, MariaDB lub PostgreSQL, przygotuj bazę i użytkownika według [instrukcji storage](Baza-danych.md). Sama zmiana `storage.type` nie przenosi istniejących kont.

## 3. Dopasuj wiadomości i politykę prywatności

W `lang/messages_pl.yml` zmień nagłówki, instrukcje, prefix i kolory. Nie usuwaj kluczy ani wymaganych placeholderów. Obsługę formatowania zapewnia MessageHandler.

Wskaż faktyczny adres HTTPS polityki swojego serwera w `privacy.policy-url` lub w wiadomości `privacy.link`. Przy zmianie zasad zwiększ `privacy.policy-version`. Domyślnie decyzja jest wymagana po uwierzytelnieniu; odrzucenie lub wycofanie akceptacji rozłącza gracza. Wycofanie nie usuwa automatycznie konta.

[Wiadomości i przykłady](Wiadomosci.md) · [Ustawienia prywatności](Konfiguracja.md)

## 4. Przydziel dostęp administracji

Nadaj konkretne uprawnienia przez swój system rang. Nie każdy moderator potrzebuje dostępu do IP ani możliwości resetowania haseł.

| Rola / zadanie | Uprawnienia |
| --- | --- |
| Podstawowa diagnostyka kont | `authgatewayx.admin.info` |
| Odbieranie alertów | `authgatewayx.admin.alerts` |
| Analiza wspólnych adresów | `authgatewayx.admin.alts` |
| Podgląd IP, GeoIP lub audytu | Osobno `authgatewayx.admin.view-ip`, `authgatewayx.admin.view-geo`, `authgatewayx.admin.view-security` |
| Reset hasła konta | `authgatewayx.admin.password` |
| Diagnostyka migracji UUID | `authgatewayx.admin.migration.view` |

Operatorzy mają domyślnie uprawnienia administracyjne. Nadając rolę z ograniczonym dostępem, sprawdź również dziedziczone permisje i status operatora. Uprawnienie nie zastępuje uwierzytelnionej sesji.

[Pełna lista uprawnień](Uprawnienia.md)

## 5. Sprawdź pierwsze logowanie

Przeprowadź te próby na prywatnym serwerze testowym przed otwarciem dostępu. Checkboxy służą Twojemu wdrożeniu — zaznacz je po rzeczywistym sprawdzeniu.

- [ ] Każdy nowy gracz otrzymuje formularz rejestracji, a powracający gracz formularz logowania.
- [ ] Błędne hasło nie otwiera dostępu do gry; poprawne pozwala przejść dalej.
- [ ] Przed zakończeniem logowania i wymaganej akceptacji nie można wpływać na świat.
- [ ] Własne oficjalne konto Minecraft przechodzi weryfikację i domyślnie wymaga również hasła serwerowego.
- [ ] `/changepassword`, `/logout` i ponowne logowanie działają dla uwierzytelnionego konta.
- [ ] Akceptacja polityki jest zapisana, a odmowa kończy połączenie.
- [ ] Po restarcie konta nadal istnieją i można się zalogować.
- [ ] Administrator widzi tylko dane, do których otrzymał dostęp.
- [ ] Kopię bazy i konfiguracji można odtworzyć na osobnym środowisku.

Hasła w polach Minecraft Dialog są widoczne na ekranie. Uprzedź graczy, by ukryli obraz gry podczas streamowania lub udostępniania ekranu.

## 6. Codzienna obsługa

**Gracz zapomniał hasła serwerowego:** najpierw potwierdź własność konta. Zalogowany administrator używa `/agx setpassword <nick>` i podaje nowe hasło w formularzu. Reset rozłącza aktywnego gracza. Przekaż hasło prywatnie i poproś o zmianę przez `/changepassword`. Nie jest to reset konta Microsoft.

**Gracz nie może wejść:** sprawdź komunikat, `/agx info <nick>` oraz stan usług i bazy w konsoli. Przy błędzie sesji Minecraft poproś o ponowne zalogowanie w oficjalnym launcherze. Nie wyłączaj weryfikacji tożsamości w celu obejścia problemu.

**Podejrzenie multi-kont:** sprawdź `/agx alts <nick>`. Wspólny IP jest poszlaką — sieci domowe, szkoły i CGNAT mogą łączyć różne osoby. Raport nie nakłada automatycznie kar.

**Migracja UUID po weryfikacji konta Minecraft:** gracz musi potwierdzić stare hasło po oficjalnej weryfikacji Minecraft. Migracja danych odbywa się po rozłączeniu. Użyj `/agx migrate status <nick>` i `inspect`, aby sprawdzić stan; nie zamieniaj UUID ręcznie w bazie. [Procedura migracji](Premium.md).

## 7. Zabezpieczenia i integracje

Zacznij od domyślnych limiterów. Rozróżniaj blokadę błędnych haseł, ochronę przed reconnectem i natywne timeouty LOGIN. Na standalone powtarzane timeouty LOGIN mają osobny trwały licznik oraz czasową blokadę IP; restart jej nie usuwa. Zwróć uwagę na graczy za wspólnym adresem.

CleanerX i PunisherX w trybie `AUTO` są używane, jeśli dostępna jest zgodna usługa. Jeśli sprawdzenie jest warunkiem wejścia, zapoznaj się z trybem `REQUIRED` i strategią `FAIL_CLOSED` w [integracjach](Integracje.md).

Zewnętrzna reputacja IP jest domyślnie wyłączona. Włączenie jej przekazuje publiczne adresy do dostawcy usługi. Na Paper wyniki służą alertom; na proxy osobno konfiguruje się decyzję o wpuszczeniu. [Ochrona serwera](Ochrona-serwera.md) · [Velocity](Velocity.md).

## 8. Zmiany ustawień, backup i aktualizacja

Większość zmian wymaga pełnego restartu. `/agx reload` odświeża obecnie wyłącznie `migration.unmanaged-plugin-scan.ignored-plugin-directories`; nie przeładowuje wszystkich ustawień, wiadomości ani połączeń z bazą.

Przed aktualizacją zatrzymaj serwer, wykonaj kopię folderu pluginu i bazy, a następnie przetestuj nowy JAR na kopii środowiska. W razie cofania wersji przywracaj pasujące do siebie pliki pluginu, konfiguracji oraz danych. [Backup i przywracanie](Baza-danych.md).

Jeśli problem pozostaje, przejdź do [FAQ i diagnostyki](Pomoc.md). Zgłaszając błąd, podaj wersje i kroki odtworzenia; usuń z logów dane graczy i sekrety.
