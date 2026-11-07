<div align="center">

# AuthGatewayX

### Twoje konto. Twój nick. Twoja gra.

**Logowanie premium i non-premium, wygodne formularze w Minecraft i ochrona serwera od pierwszego połączenia.**

[![Build](https://github.com/SyntaxDevTeam/AuthGatewayX/actions/workflows/buildexplorer.yml/badge.svg)](https://github.com/SyntaxDevTeam/AuthGatewayX/actions/workflows/buildexplorer.yml)
[![Issues](https://img.shields.io/github/issues/SyntaxDevTeam/AuthGatewayX)](https://github.com/SyntaxDevTeam/AuthGatewayX/issues)
[![Last commit](https://img.shields.io/github/last-commit/SyntaxDevTeam/AuthGatewayX)](https://github.com/SyntaxDevTeam/AuthGatewayX/commits)
[![Java 25](https://img.shields.io/badge/Java-25-orange)](docs/WIKI/Zgodnosc.md)
[![License MIT](https://img.shields.io/badge/License-MIT-green)](LICENSE)

**[📦 Pobierz](#-pobieranie) · [📚 WIKI PL](docs/WIKI/Home.md) · [📚 WIKI EN](docs/WIKI/EN/Home.md) · [⚙️ Instalacja](docs/WIKI/Instalacja.md) · [💬 Pomoc](https://github.com/SyntaxDevTeam/AuthGatewayX/issues)**

</div>

---

AuthGatewayX łączy dwa sposoby logowania w jednym pluginie: weryfikację kont premium przez oficjalne usługi Mojang oraz logowanie hasłem dla kont non-premium. Gracze korzystają z formularzy bezpośrednio w Minecraft, a administrator otrzymuje narzędzia do ochrony kont, kontroli połączeń i diagnozowania problemów.

Budujesz kameralny serwer dla znajomych albo rozwijasz społeczność? Zacznij od domyślnej bazy SQLite, dopasuj wiadomości do swojego stylu i zarządzaj logowaniem z jednego miejsca.

## ✨ Dlaczego AuthGatewayX?

- **Wygodne formularze logowania i rejestracji.** Hasło trafia do pola w oknie gry; gracz nie musi wpisywać go w komendzie ani na czacie.
- **Oficjalna weryfikacja premium.** Właściciel konta premium loguje się przez sesję Minecraft. Nieudana weryfikacja chronionego nicku kończy się odmową wejścia.
- **Ochrona świata przed logowaniem.** Izolacja PRE_AUTH blokuje ruch, interakcje, ekwipunek, walkę, czat i nieautoryzowane komendy.
- **Ochrona przed nadużyciami.** Limity połączeń, prób haseł, rejestracji i ponownych wejść pomagają ograniczać boty oraz brute-force.
- **Hasła zabezpieczone Argon2id.** Zmiana i administracyjny reset hasła odbywają się przez formularze, a ważne operacje trafiają do audytu.
- **Narzędzia dla administracji.** Raport konta, stan sesji, historia bezpieczeństwa i powiązania kont przez wspólne adresy IP — z osobnymi uprawnieniami do danych wrażliwych.
- **Wiadomości w Twoim stylu.** Pliki językowe, MiniMessage i obsługa wiadomości przez MessageHandler.
- **Wybór bazy danych.** SQLite na prosty start oraz konfiguracja MySQL, MariaDB i PostgreSQL.
- **Integracje z ekosystemem serwera.** CleanerX sprawdza politykę nazw, PunisherX dostarcza informacje o karach, a kontrolowana migracja UUID pomaga przenosić dane przy przejściu offline → premium.

[Poznaj wszystkie możliwości →](docs/WIKI/Mozliwosci.md)

## 📦 Pobieranie

**[Przejdź do buildów AuthGatewayX →](https://github.com/SyntaxDevTeam/AuthGatewayX/actions)**

Wybierz zakończony powodzeniem workflow pakowania i pobierz właściwy artefakt z sekcji **Artifacts**. Pobieranie artefaktów GitHub Actions wymaga zalogowania do GitHub; ich dostępność zależy od okresu przechowywania.

| Wariant | Gdzie go instalujesz? |
| --- | --- |
| **AuthGatewayX Paper** | Katalog `plugins` serwera Paper lub Purpur; dobierz JAR do wersji Minecraft |
| **AuthGatewayX Velocity** | Katalog `plugins` proxy Velocity, razem z modułem Paper na backendzie |

Instrukcję wyboru pliku i samodzielnego zbudowania pluginu znajdziesz w [WIKI instalacji](docs/WIKI/Instalacja.md). Szczegóły wymagań Java 25 i poszczególnych platform opisuje [zgodność](docs/WIKI/Zgodnosc.md).

## ⚙️ Szybki start

1. Przygotuj serwer testowy z Javą 25 i wykonaj kopię danych.
2. Zatrzymaj serwer i umieść właściwy JAR w katalogu `plugins`.
3. Dla samodzielnego serwera ustaw `online-mode=false` w `server.properties`; obsługę Velocity w Paper pozostaw wyłączoną.
4. Uruchom serwer i sprawdź gotowość AuthGatewayX w konsoli. Plugin przygotuje konfigurację oraz bazę SQLite.
5. Dopasuj `plugins/AuthGatewayX/config.yml`, wiadomości i adres polityki prywatności. Uruchom serwer ponownie.
6. Sprawdź rejestrację, ponowne logowanie, własne konto premium i blokadę działań przed zalogowaniem.

Serwer z `online-mode=false` udostępniaj graczom dopiero przy działającej ochronie logowania. Dla sieci proxy skorzystaj z [osobnej instrukcji Velocity](docs/WIKI/Velocity.md), obejmującej forwarding i zabezpieczenie backendu.

**[Pełna instalacja krok po kroku →](docs/WIKI/Instalacja.md)**

## 🎮 Komendy pod ręką

| Komenda | Zastosowanie |
| --- | --- |
| `/changepassword` | Zmiana własnego hasła konta offline |
| `/logout` | Zakończenie sesji offline i rozłączenie |
| `/privacy` | Polityka prywatności i zarządzanie akceptacją |
| `/agx help` | Lista dostępnych komend |
| `/agx info <nick>` | Administracyjny raport konta i sesji |
| `/agx alts <nick>` | Raport powiązań przez wspólne IP |
| `/agx setpassword <nick>` | Administracyjny formularz resetu hasła offline |

Rejestracja i logowanie otwierają się automatycznie. `/agx` jest aliasem `/authgatewayx`.

**[Wszystkie komendy](docs/WIKI/Komendy.md) · [Uprawnienia](docs/WIKI/Uprawnienia.md)**

## 📚 Dokumentacja

**[Polski](docs/WIKI/Home.md) · [English](docs/WIKI/EN/Home.md)**

| Chcesz… | Otwórz |
| --- | --- |
| Uruchomić plugin i poznać codzienną obsługę | [Przewodnik administratora](docs/WIKI/ADMIN-GUIDE.md) |
| Pomóc graczom w pierwszym logowaniu | [Poradnik gracza](docs/WIKI/Poradnik-gracza.md) |
| Dopasować ustawienia i wygląd | [Konfiguracja](docs/WIKI/Konfiguracja.md) · [Wiadomości](docs/WIKI/Wiadomosci.md) |
| Zrozumieć ochronę kont premium | [Premium i migracja kont](docs/WIKI/Premium.md) |
| Ustawić zabezpieczenia | [Ochrona serwera](docs/WIKI/Ochrona-serwera.md) |
| Przygotować backup lub aktualizację | [Baza danych i kopie](docs/WIKI/Baza-danych.md) |
| Połączyć plugin z innymi usługami | [Integracje](docs/WIKI/Integracje.md) · [Velocity](docs/WIKI/Velocity.md) |
| Rozwiązać problem | [Pomoc i FAQ](docs/WIKI/Pomoc.md) |

## 🛠️ Dla programistów

Projekt składa się z niezależnych modułów domeny, API, auth, bezpieczeństwa, integracji i storage oraz adapterów Paper i Velocity. Instrukcje użytkowania znajdziesz w [WIKI PL](docs/WIKI/Home.md) i [WIKI EN](docs/WIKI/EN/Home.md).

Budowa modułu Paper:

```bash
./gradlew :authgatewayx-paper:build
```

Pełny build, testy i weryfikacja zawartości JAR-ów:

```bash
./gradlew clean buildAll
```

Dystrybucje trafiają do `authgatewayx-paper/build/libs` i `authgatewayx-velocity/build/libs`. Wybór wariantu Minecraft opisuje [instrukcja instalacji](docs/WIKI/Instalacja.md).

## 💬 Wsparcie i rozwój

Masz pomysł lub problem? [Otwórz zgłoszenie](https://github.com/SyntaxDevTeam/AuthGatewayX/issues) z wersją pluginu, serwera i Javy oraz opisem kroków do odtworzenia. Przed publikacją logów usuń hasła, sekrety, klucze API i dane graczy.

## 📄 Licencja

AuthGatewayX jest udostępniany na licencji [MIT](LICENSE).

Tworzony przez [SyntaxDevTeam](https://github.com/SyntaxDevTeam).
