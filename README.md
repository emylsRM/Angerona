# Angerona

Browser privato e leggero per **Windows** e **Android**, con il nome della dea romana che custodiva il nome segreto di Roma e veniva raffigurata con il dito sulle labbra sigillate.

- **DNS sicuro da menù a tendina**: Quad9, Cloudflare, Mullvad, AdGuard, NextDNS (con il tuo ID) o un indirizzo DoH personalizzato. Se il DNS scelto non risponde, i siti non si aprono: nessun ripiego in chiaro.
- **Proxy e VPN**: SOCKS5, HTTP o HTTPS. Se il proxy cade la navigazione si ferma invece di uscire senza protezione. Con una VPN attiva sul sistema non serve configurare nulla.
- **Ricerca privata**: DuckDuckGo, Startpage, Brave Search, Mojeek, Qwant o la tua istanza SearXNG.
- **Nessuna traccia**: tutte le schede sono private. Cookie, cache e cronologia restano solo in memoria e spariscono alla chiusura.
- **Punteggio privacy**: nelle impostazioni un valore da 0 a 100 che cambia mentre modifichi le opzioni, con la spiegazione di cosa vedono i siti, il tuo operatore, il servizio DNS e il proxy. Da lì apri anche i test indipendenti (Mullvad, EFF Cover Your Tracks, BrowserLeaks).
- **Aggiornamenti**: all'apertura avvisa se su GitHub c'è una versione nuova e la installa con un tocco, verificandone l'integrità.
- **Protezioni**: blocco di tracker e pubblicità, cookie di terze parti bloccati, solo HTTPS, Global Privacy Control, nessun permesso a fotocamera, microfono, posizione e notifiche.
- **Sicurezza**: siti di truffa e malware bloccati, rete di casa irraggiungibile dalle pagine web, impronta del browser ridotta, pulsante con la fiamma per cancellare tutto subito. In più, a scelta: modalità blindata, cancellazione automatica, blocco con impronta o PIN (Android), rifiuto dei certificati aggiunti al computer (Windows).

| | Windows | Android |
|---|---|---|
| Motore | Chromium (Electron 45) | Firefox (GeckoView 159) |
| Blocco tracker | EasyList + EasyPrivacy, aggiornate ogni settimana | Protezione antitracciamento rigorosa di Firefox |
| Fughe di IP via WebRTC | bloccate | WebRTC spento (attivabile, passa solo dal proxy) |
| Siti di truffa e malware | URLhaus + Phishing Filter, aggiornate ogni 12 ore | Safe Browsing di Firefox |
| Rete locale (router, app sul dispositivo) | non raggiungibile dalle pagine web | non raggiungibile dalle pagine web |
| Impronta del browser | disturbo su grafica e audio, hardware generico | protezione di Firefox rafforzata |
| Modalità blindata | JavaScript senza JIT né WebAssembly | JavaScript senza JIT + processi isolati di Android |
| Password del proxy | cifrata con Windows (DPAPI) | cifrata con il Keystore di Android |

---

## Windows

1. Apri `Angerona-Setup-1.0.0.exe` (installazione) oppure `Angerona-Portable-1.0.0.exe` (nessuna installazione, utile su chiavetta).
2. Windows mostrerà «Windows ha protetto il PC» perché il file non è firmato da un'azienda: clicca **Ulteriori informazioni → Esegui comunque**.
3. Le impostazioni sono nel pulsante in alto a destra (o `Ctrl+,`).

Scorciatoie: `Ctrl+T` nuova scheda · `Ctrl+W` chiudi · `Ctrl+L` barra indirizzi · `Ctrl+Tab` scheda successiva · `Ctrl+F` trova · `F5` ricarica · `Alt+←/→` indietro/avanti · `Ctrl +/-/0` zoom · `F11` schermo intero.

**Verifica connessione** (nelle impostazioni) mostra l'indirizzo IP con cui i siti ti vedono: utile per controllare che proxy o VPN funzionino.

## Android

L'APK si compila gratis su GitHub (servono solo un account e pochi minuti):

1. Crea un repository **privato** su github.com e carica tutto il contenuto di questa cartella (dal computer: «Add file → Upload files», trascinando le cartelle).
2. Il caricamento dal browser salta la cartella nascosta `.github`: nella scheda **Actions** clicca **set up a workflow yourself**, incolla il contenuto di `.github/workflows/build.yml` e fai **Commit changes**. La compilazione parte da sola; in seguito puoi rilanciarla con **Run workflow**.
3. Dopo circa 10 minuti, nella pagina dell'esecuzione trovi l'artefatto **Angerona-Android**: dentro c'è un APK per tipo di processore. Per quasi tutti i telefoni recenti va bene **arm64-v8a**.
4. Installa l'APK sul telefono (Android ti chiederà di consentire l'installazione da questa fonte).

In alternativa apri la cartella `android` con Android Studio e usa **Build → Build APK(s)**.

Ogni compilazione su `main` (e ogni lunedì, con l'ultimo motore Firefox) pubblica da sola una nuova versione nella sezione **Releases**, numerata `1.0.N`, con APK, installer Windows e impronte SHA-256.

### Aggiornamenti automatici

All'apertura Angerona chiede a GitHub se c'è una versione più recente, passando dal DNS cifrato e dal proxy scelti, e propone l'aggiornamento:
- **Windows**: scarica l'installer, controlla che l'impronta SHA-256 sia quella pubblicata e lo avvia.
- **Android**: scarica l'APK adatto al processore del telefono e apre l'installer di sistema (la prima volta Android chiede di consentire l'installazione da Angerona).

Si può disattivare nelle impostazioni, dove c'è anche il pulsante **Controlla ora**. Perché funzioni il repository deve essere pubblico.

### Firma stabile (consigliata)

Senza una chiave tua ogni compilazione è firmata con una chiave temporanea: Android rifiuta l'aggiornamento e bisogna disinstallare l'app prima. Con la chiave stabile gli aggiornamenti si installano sopra la versione esistente, mantenendo le impostazioni. Per crearla una volta sola:

```
keytool -genkeypair -v -keystore angerona.jks -alias angerona -keyalg RSA -keysize 4096 -validity 10000
```

Poi, nel repository GitHub, **Settings → Secrets and variables → Actions**, aggiungi:

- `ANGERONA_KEYSTORE_BASE64`: il file `angerona.jks` convertito in base64 (`base64 -w0 angerona.jks`, su Windows `certutil -encode`)
- `ANGERONA_KEYSTORE_PASSWORD`, `ANGERONA_KEY_ALIAS` (`angerona`), `ANGERONA_KEY_PASSWORD`

Conserva `angerona.jks` e le password in un posto sicuro: senza di essi non potrai più aggiornare l'app installata.

---

## Manutenzione e richieste

Ogni lunedì mattina un agente controlla aggiornamenti di sicurezza dei motori (Electron e GeckoView), liste anti-tracker, bug e compilazioni fallite. Le modifiche arrivano come **pull request**: GitHub le compila e tu le approvi con **Merge pull request**.

Per chiedere una modifica o una nuova funzione apri una **issue** (scheda Issues → New issue) e descrivila con parole semplici: verrà realizzata al giro successivo in una pull request.

## Proxy e VPN: esempi

| Cosa usi | Tipo | Indirizzo | Porta |
|---|---|---|---|
| App VPN sul sistema (WireGuard, Mullvad, Proton…) | Nessuno | — | — |
| Solo il browser dentro Mullvad VPN | SOCKS5 | 10.64.0.1 | 1080 |
| Tor con Orbot (Android) | SOCKS5 | 127.0.0.1 | 9050 |
| Tor Browser aperto (Windows) | SOCKS5 | 127.0.0.1 | 9150 |
| Proxy aziendale con accesso | HTTP | indirizzo del proxy | porta del proxy |

Su Windows il motore Chromium non supporta SOCKS5 con nome utente e password: per un proxy con accesso usa il tipo HTTP. Su Android SOCKS5 con password funziona.

## Sicurezza: cosa fa Angerona e cosa puoi attivare

Sempre attivo:
- **Siti pericolosi**: gli indirizzi segnalati per truffe o malware si fermano su una pagina di avviso, con «Apri comunque» per chi sa cosa sta facendo. Il controllo avviene sul dispositivo: nessun indirizzo visitato viene inviato a terzi (su Android con Safe Browsing di Firefox, che usa impronte parziali).
- **Rete di casa protetta**: una pagina su Internet non può contattare il router, la stampante o i programmi in ascolto sul dispositivo. È il trucco usato nel 2025 da Meta e Yandex per collegare la navigazione alle loro app.
- **Indirizzi truccati** (Android): domini che imitano siti noti con lettere straniere sono mostrati come `xn--…`.
- **Niente collegamenti in chiaro o anticipati**: in modalità Solo HTTPS nessuna richiesta di prova non cifrata, nessuna connessione preparata verso link non aperti.
- **App difficile da manipolare** (Windows): niente modalità debug né esecuzione come Node, file dell'app controllati, pagine salvate sul disco che non leggono altri file, download segnalati a SmartScreen.

Da attivare nelle impostazioni, perché hanno un prezzo:
- **Modalità blindata**: JavaScript senza compilatore veloce, da cui passa la maggior parte degli attacchi ai browser (su Android anche processi isolati). Siti pesanti più lenti, alcuni giochi o editor online non funzionano.
- **Cancellazione automatica**: dopo 5, 15 o 30 minuti senza usare Angerona chiude tutte le schede e cancella i dati.
- **Blocco con impronta o PIN** (Android): al ritorno nell'app serve sbloccarla; le app recenti non mostrano le schede.
- **Rifiuta i certificati aggiunti al computer** (Windows): blocca chi intercetta l'HTTPS (reti aziendali, programmi dannosi, alcuni antivirus).

## Cosa sapere, onestamente

- **Nessun browser è inattaccabile.** Angerona riduce molto il tracciamento e le fughe di dati, ma la sicurezza dipende anche dagli aggiornamenti: ricompila o reinstalla periodicamente. Su Windows il motore Chromium di Electron arriva con qualche giorno di ritardo rispetto a Chrome.
- **Anonimato vero** contro chi ti osserva attivamente: usa Tor Browser o Mullvad Browser, che uniformano anche l'impronta del browser.
- **Leggero, con un limite**: su Windows il pacchetto pesa circa 100 MB perché include un motore completo; su Android si scarica un solo APK per tipo di processore, quindi solo il motore che serve al tuo telefono.
- Videochiamate e siti che chiedono fotocamera, microfono o posizione non funzionano: quei permessi sono negati per scelta.
- Le estensioni del browser non sono supportate.

## Struttura

```
desktop/   app Windows (Electron): src/ processo principale, ui/ interfaccia, resources/ filtri anti-tracker
android/   app Android (Java + GeckoView), con l'estensione interna per il proxy in app/src/main/assets
.github/   compilazione automatica di APK e installer Windows
```

Comandi utili in `desktop/`: `npm install`, `npm start` (prova), `npm run filters` (aggiorna liste), `npm run dist:win` (installer).
