undefined
## Zmiana Velocity 4.3.0 (2026-10-20)

- [x] Użyto `InboundConnection.sessionId` (UUID) zamiast adresu źródłowego i nicku do klucza decyzji `PreLoginEvent -> LoginEvent`.
- [x] Zablokowano nadpisywanie oczekującej decyzji dla jednego session ID.
- [x] Dodano odrębny deadline 20 s do weryfikacji admission, zapobiegający zajęciu semafora przez zawieszony lookup (limit Velocity domyślnie 30 s).
- [x] Dodano ograniczony, czyszczony podczas disconnect/shutdown rejestr sesji proxy z ostatnim backendem i publiczny odczyt w adapterze Velocity.
- [x] Potwierdzenia CraftConnect są powiązane również z identyfikatorem sesji proxy; nadal wymagają odpowiedzi od bieżącego backendu.
- [ ] Weryfikacja end-to-end na działającej sieci Velocity 4.3.0 + Paper, w tym testy slow-login i przełączenia backendu.

Bezpieczeństwo: sesja proxy nie jest dowodem ukończenia autoryzacji offline.
Identyfikator UUID nie jest hasłem ani tokenem i nie wolno go użyć do
pominięcia kontroli PRE_AUTH. Rzeczywisty login offline nadal potwierdza Paper.
Limity protokołu, 30-sekundowy timeout i ochrona pamięci przed floodem należą
do Velocity 4.3.0, a nie do AuthGatewayX.

- [x] Dodano chronioną `/authgatewayx session <nick>` (`authgatewayx.admin.session`), dostępną wyłącznie po ponownej weryfikacji administratora.
- [x] Przy potwierdzeniu staff-proof porównywany jest także UUID sesji proxy; odpowiedź z poprzedniej sesji nie jest akceptowana.
- [x] Ograniczono koszt czyszczenia wpisów oczekujących przy normalnym ruchu: nie skanujemy całej mapy przy każdym logowaniu.
