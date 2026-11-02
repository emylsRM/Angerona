# Angerona — note per chi mantiene il progetto

Browser privato dell'utente per Windows (`desktop/`, Electron) e Android (`android/`, Java + GeckoView). L'utente non è uno sviluppatore: scrivigli in italiano, con parole semplici.

## Principi
- Privacy e sicurezza prima di tutto. Ogni errore di rete deve chiudere, mai aprire: niente ripiego in chiaro se il DNS cifrato o il proxy non rispondono.
- Interfaccia in italiano, tema "Notte" (viola scuro, accento #7F77DD). Non cambiare aspetto o funzioni non richieste.
- Leggerezza: nessuna dipendenza nuova senza un motivo forte.
- Le modifiche arrivano su `main` solo tramite pull request approvate dall'utente.

## Desktop (Electron)
- `src/main.js` finestra, schede (WebContentsView), scorciatoie, IPC con controllo del mittente.
- `src/privacy.js` DoH con `app.configureHostResolver` in modalità `secure`, proxy `fixed_servers` senza bypass, blocco tracker (@ghostery/adblocker, solo filtri di rete), cookie di terze parti, HTTPS-only.
- `src/settings.js` provider DNS, motori di ricerca, validazione. `src/pages.js` pagine interne `angerona://` senza script.
- `ui/` interfaccia; `ui/preload.js` espone solo canali in whitelist.
- Verifica: `npm ci`, `npm run check`, prova con `xvfb-run` + Playwright `_electron.launch` (`--no-sandbox`).
- `npm run filters` rigenera `resources/filters.bin` (EasyList, EasyPrivacy) e `resources/threats.bin` (URLhaus, Phishing Filter); le fonti sono in `src/lists.js`. Nel browser i tracker si aggiornano ogni settimana, i siti pericolosi ogni 12 ore.
- L'interfaccia è servita dal protocollo `angerona-ui://` (sessione `angerona-ui`, senza rete): con il fusibile `GrantFileProtocolExtraPrivileges` spento `file://` non legge dentro `app.asar`. Non tornare a `loadFile`.
- Fusibili Electron in `package.json` → `build.electronFuses` (niente RunAsNode, NODE_OPTIONS, `--inspect`; integrità dell'asar; solo asar). Playwright non può collegarsi all'app compilata: le prove si fanno su `electron .` in sviluppo.
- `ANGERONA_SELFTEST=<file>` avvia, controlla interfaccia e pagina iniziale, scrive `ok` ed esce: lo usa la «Prova d'avvio» su GitHub. `ANGERONA_TEST_MINUTE_MS` accorcia i minuti della cancellazione automatica nelle prove.
- `src/fp-preload.js` riduce l'impronta in ogni pagina e riquadro (anche about:blank creati dagli script): se aggiungi un'API da proteggere, salva l'originale prima di sostituirla per non applicare il disturbo due volte.
- Rete locale: le pagine su Internet non possono chiedere risorse a indirizzi privati (`isPrivateHost` in `privacy.js`); la navigazione principale verso il router resta libera.

## Android (GeckoView)
- Versione motore in `android/gradle.properties` (`geckoviewVersion=NNN.+`). AGP, Gradle e compileSdk vanno allineati a quelli del branch `release` di mozilla-firefox/firefox (GeckoView 159 richiede compileSdk 37.2).
- Proxy tramite estensione interna `app/src/main/assets/extensions/proxy`: blocca tutto finché l'app non invia la configurazione; `Engine.canBrowse()` ferma i caricamenti se il proxy non è confermato.
- Gli stili `Angerona.*` ereditano dallo stile base `Angerona` in `themes.xml`: non rimuoverlo.
- La compilazione vera avviene su GitHub Actions (`.github/workflows/main.yml`); in ambienti senza accesso a Maven Google/Mozilla controlla almeno i tipi con `android.jar` e stub generati da `mobile/android/geckoview/api.txt`.

## GitHub
- Le azioni nel workflow sono bloccate per hash di commit (commento con la versione): aggiornale cambiando hash e commento insieme, o lascia fare a Dependabot (`.github/dependabot.yml`, mensile e raggruppato).
- Permessi minimi: i job di compilazione leggono soltanto; scrive solo «Pubblica la versione», che allega anche l'attestato di provenienza (`gh attestation verify FILE -R emylsRM/Angerona`).
- Su `main` valgono regole del repository: niente push diretti né forzati, servono una pull request e le compilazioni «APK Android» e «Installer Windows» riuscite.
