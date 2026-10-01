# Natywne potwierdzenie logowania dla aplikacji — Paper i Velocity

AuthGatewayX udostępnia kanał plugin messaging `authgatewayx:auth` oraz zgodny
alias `craftconnect:auth`. Do tej integracji nie jest potrzebny CraftConnectBridge.
Aktualny CraftConnect już używa aliasu i rozumie format odpowiedzi.

## Paper/Purpur/Folia

Klient rejestruje kanał i wysyła subskrypcję z losowym nonce bieżącego połączenia.
AuthGatewayX sprawdza własny `AuthenticationStatusProvider` raz na sekundę na
EntityScheduler gracza. Odpowiedź jest wysyłana dopiero przy READY, ACTIVE,
zgodnym Minecraft UUID i niewygasłej sesji. Dotyczy to logowania hasłem/dialogiem,
zweryfikowanego premium i zaufanej sesji. PRE_AUTH, błędne hasło, niegotowy runtime
i niedokończona migracja nie dają potwierdzenia.

Nie ma dodatkowego pluginu, heurystyki tekstu ani timera ogłaszającego login.
Zapytanie nie zmienia stanu sesji, świata, ekwipunku ani uprawnień. Obsługa tej
ściśle ograniczonej subskrypcji przed auth jest wyjątkiem wyłącznie dla odczytu
statusu; inne plugin messaging nie otrzymuje przez to zwolnienia z PRE_AUTH.

## Velocity

W obecnej architekturze AGX proxy wykonuje admission i weryfikację Mojang, a hasło
offline oraz stan ACTIVE należą do backendu Paper. Velocity nie udaje, że samo
`LoginEvent` oznacza zakończenie logowania gracza na backendzie.

Proxy konsumuje subskrypcję klienta, wiąże ją z aktualnym `ServerConnection`
tego gracza i przekazuje do backendu. Konsumuje także odpowiedzi; akceptuje tylko
`authenticated/AuthGatewayX` z tego samego, nadal aktualnego backendu, dla tego
samego gracza, kanału i nonce. Następnie wysyła wiadomość do klienta. Nie przyjmuje
potwierdzeń od klienta ani nie forwarduje ich na backend.

W sieci należy zaktualizować AGX na proxy **i na backendzie Paper**. Sam Velocity,
bez AGX na backendzie, nie pozna sukcesu offline `/login`. Także klient premium
czeka na backend ACTIVE, aby nie ominąć wymaganej migracji lub izolacji. Nie jest
potrzebny nowy shared secret: ta informacyjna odpowiedź ufa aktualnemu backendowi
kontrolowanemu przez administratora. Nie zastępuje podpisanego `StaffSessionProof`
i nigdy nie przyznaje dostępu do administracji proxy.

## Format wiadomości v1

Payload to `DataOutputStream` / `DataInputStream`:

| Pole | Typ | Subskrypcja | Potwierdzenie |
| --- | --- | --- | --- |
| wersja | unsigned byte | `1` | `1` |
| rodzaj | Java modified UTF (`writeUTF`) | `subscribe` | `authenticated` |
| nonce | Java modified UTF | losowy UUID, 36 znaków | nonce subskrypcji |
| provider | Java modified UTF | pusty tekst | `AuthGatewayX` |

Nie ma UUID gracza, adresów, nicka, hasła ani rekordu konta w payloadzie. Tożsamość
nadawcy wynika z połączenia. Limit payloadu: 256 bajtów; każde pole: najwyżej
64 bajty przed alokacją stringa. Nieznana wersja, nadmiarowe dane i błędny nonce
są odrzucane. Klient musi sprawdzić nonce i nie używać odpowiedzi starej sesji.

Nonce wiąże odpowiedź z połączeniem, nie stanowi podpisu kryptograficznego ani
tokenu autoryzacji. Serwer może kłamać klientowi; aplikacja ufa serwerowi, z którym
się połączyła. Potwierdzenie oznacza snapshot zakończonego auth, nie bezterminowe
uprawnienie i nie gwarancję niewidzialności ani ochrony ekwipunku.

## Limity i lifecycle

- Najwyżej jedna subskrypcja/task na połączonego gracza, limit 5000 na platformę.
- Powtórzone subskrypcje nie tworzą nowych tasków; oczekiwanie wygasa po 5 minutach.
- Paper odczytuje tylko pamięć; proxy ponawia zapytanie najwyżej raz na 2 sekundy.
- Jedno potwierdzenie na subskrypcję; potem task zostaje anulowany.
- Disconnect, shutdown i zmiana backendu na proxy unieważniają oczekiwanie.
- Brak odpowiedzi nie jest interpretowany jako sukces. Klient może ponowić
  subskrypcję, jeśli przed auth plugin message został zablokowany.

Paper posługuje się EntityScheduler i bezpiecznym, nieblokującym API stanu sesji;
Velocity używa własnego schedulera i synchronizuje rejestr. Kanał nie wykonuje
JDBC, HTTP, Argon2 ani teleportów. Błąd kanału nie zmienia wyniku uwierzytelnienia.

## Testy i stan weryfikacji

Testy automatyczne obejmują wire format CraftConnect, zły nonce, spoof klienta,
zły backend, brak READY, oczekiwanie na ACTIVE, duplikaty, deadline, disconnect
i shutdown. Testy API stanu sesji dodatkowo obejmują PRE_AUTH i wygaśnięcie sesji.
Testy na uruchomionych Paper/Folia/Velocity z aplikacją nadal są wymagane;
nie zamyka to checklisty całego API, publicznych eventów ani ochrony PRE_AUTH.
