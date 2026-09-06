# Wiadomości i wygląd

[Home](Home.md) · [Konfiguracja](Konfiguracja.md)

Nadaj oknom logowania styl swojego serwera. Teksty Paper znajdziesz w `plugins/AuthGatewayX/lang/messages_pl.yml`, a język wybierasz przez `language: "PL"` w `config.yml`. Z pluginem dostarczany jest polski plik.

## Co możesz zmienić?

| Część pliku | Zawartość |
| --- | --- |
| `prefix` | Wygląd oznaczenia AuthGatewayX |
| `auth.login_title`, `auth.registration_title` | Nagłówki logowania i rejestracji |
| `auth.login_prompt`, `auth.registration_prompt` | Instrukcje w oknach |
| `auth.password_label`, `auth.repeat_password_label` | Nazwy pól hasła |
| `auth.submit_label`, `auth.cancel_label` | Teksty przycisków |
| Pozostałe wpisy `auth` | Sukcesy, blokady i problemy z logowaniem |
| Wpisy `password` | Okna i wyniki zmiany hasła |

## Przykład własnych tekstów

W istniejącej sekcji `auth` możesz zmienić wybrane wpisy tak:

```yaml
auth:
  login_title: "<aqua>Hej, <white>{username}</white>!</aqua>"
  login_prompt: "<gray>Wpisz hasło i wracaj do gry.</gray>"
  registration_prompt: "<gray>Wybierz własne hasło i powtórz je poniżej.</gray>"
  offline_authentication_success: "<green>Gotowe! Miłej gry!</green>"
```

To fragment, nie zamiennik całego pliku. Zachowaj pozostałe wpisy i nie twórz drugiej sekcji `auth`.

## Kolory bez kombinowania

`<green>Tekst</green>` daje zielony tekst, `<red>` czerwony, `<aqua>` jasnoniebieski, a `<gray>` szary. `<b>Tekst</b>` pogrubia, a `<newline>` rozpoczyna nową linię. To zapis formatowania MiniMessage. Używaj go w treściach i nagłówkach; etykiety pól najlepiej zostawić jako zwykły tekst.

`{username}` w nagłówku logowania i rejestracji zostanie zastąpione nickiem gracza. Nie zakładaj, że zadziała w dowolnym innym komunikacie, ani nie dopisuj wymyślonych zmiennych.

## Zapis i sprawdzenie

Zrób kopię, edytuj teksty przy wyłączonym serwerze i uruchom go ponownie. Sprawdź rejestrację, logowanie, błędne hasło i zmianę hasła. Nie usuwaj nazw wpisów po lewej stronie dwukropka.

Komunikat o błędzie powinien nadal mówić prawdę. Przy nieudanym logowaniu premium pozostaw informację o potrzebie zalogowania się na właściwe konto w launcherze.

Velocity ma osobny plik `lang/messages_pl.yml` w swoim folderze danych. Zmiana tekstów Paper nie zmienia automatycznie komunikatów proxy.
