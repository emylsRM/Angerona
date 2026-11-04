# Angerona — note per chi mantiene il progetto

Browser privato dell'utente per Windows (`desktop/`, Electron) e Android (`android/`, Java + GeckoView). L'utente non è uno sviluppatore: scrivigli in italiano, con parole semplici.

## Principi
- Privacy e sicurezza prima di tutto. Ogni errore di rete deve chiudere, mai aprire: niente ripiego in chiaro se il DNS cifrato o il proxy non rispondono.
- Interfaccia in italiano, tema "Notte" (viola scuro, accento #7F77DD). Non cambiare aspetto o funzioni non richieste.
- Leggerezza: nessuna dipendenza nuova senza un motivo forte.
- Le modifiche arrivano su `main` solo tramite pull request approvate dall'utente.

## Desktop (Electron)
- `src/main.js` finestra, schede (WebContentsView), scorciatoie, IPC con controllo del mittente.
- `src/privacy.js` DoH con `app.configureHostResolver` in modalità `secure`, proxy `fixed_servers` senza bypass, scudi (@ghostery/adblocker con filtri di rete, cosmetici e scriptlet), cookie di terze parti, HTTPS-only.
- `src/settings.js` provider DNS, motori di ricerca, validazione. `src/pages.js` pagine interne `angerona://` senza script.
- `ui/` interfaccia; `ui/preload.js` espone solo canali in whitelist.
- Verifica: `npm ci`, `npm run check`, prova con `xvfb-run` + Playwright `_electron.launch` (`--no-sandbox`).
- Scudi «come Brave»: tre motori in `src/lists.js` → `resources/shields.bin` (EasyList, EasyPrivacy, Peter Lowe, filtri di uBlock Origin dal mirror di Ghostery, con `resources.json` per scriptlet e script sostitutivi), `cookies.bin` (EasyList Cookie, uBO annoyances-cookies), `threats.bin` (URLhaus, Phishing Filter). `npm run filters` li rigenera; nel browser si aggiornano ogni 2 giorni (siti pericolosi ogni 12 ore) in un worker thread. Se cambia il formato del motore, cambia il nome del file.
- Gli script sostitutivi (`$redirect`, es. Google Analytics innocuo) si servono da `angerona-res://`: Chromium vieta i reindirizzamenti verso `data:` (ERR_UNSAFE_REDIRECT).
- Pop-up: serve un gesto dell'utente (evento `input-event` negli ultimi 5 s, uno per finestra), e con gli scudi attivi l'indirizzo della finestra non deve corrispondere alle liste. Lo scudo nella barra apre un menu nativo per spegnere le protezioni su un sito (`settings.shieldsOff`, per dominio).
- `src/urlclean.js`: debouncing dei link di tracciamento e rimozione degli identificativi di clic (fbclid, gclid…) sulla navigazione principale; i parametri utm_* restano.
- L'interfaccia è servita dal protocollo `angerona-ui://` (sessione `angerona-ui`, senza rete): con il fusibile `GrantFileProtocolExtraPrivileges` spento `file://` non legge dentro `app.asar`. Non tornare a `loadFile`.
- Fusibili Electron in `package.json` → `build.electronFuses` (niente RunAsNode, NODE_OPTIONS, `--inspect`; integrità dell'asar; solo asar). Playwright non può collegarsi all'app compilata: le prove si fanno su `electron .` in sviluppo.
- `ANGERONA_SELFTEST=<file>` avvia, controlla interfaccia e pagina iniziale, scrive `ok` ed esce: lo usa la «Prova d'avvio» su GitHub. `ANGERONA_TEST_MINUTE_MS` accorcia i minuti della cancellazione automatica nelle prove.
- `src/page-preload.js` gira in ogni pagina e riquadro: chiede al processo principale le regole cosmetiche (`shields:page`, sincrono, poi `shields:dom` per classi/id/link) ed esegue gli scriptlet con `contextBridge.executeInMainWorld` (niente CSP del sito); poi riduce l'impronta (anche negli about:blank creati dagli script). Se aggiungi un'API da proteggere, salva l'originale prima di sostituirla per non applicare il disturbo due volte.
- Rete locale: le pagine su Internet non possono chiedere risorse a indirizzi privati (`isPrivateHost` in `privacy.js`); la navigazione principale verso il router resta libera.

## Android (GeckoView)
- Versione motore in `android/gradle.properties` (`geckoviewVersion=NNN.+`). AGP, Gradle e compileSdk vanno allineati a quelli del branch `release` di mozilla-firefox/firefox (GeckoView 159 richiede compileSdk 37.2).
- Preferenze di Firefox impostate da `Engine.hardeningPrefs` con `GeckoPreferenceController` (ramo utente, riapplicate a ogni avvio e salvataggio). La modalità blindata spegne i JIT subito; i processi isolati (`isolatedProcessEnabled`) cambiano solo a processo nuovo: «Cancella tutto ed esci» termina il processo apposta.
- uBlock Origin (`Adblock.java`): installato da AMO (`latest.xpi`, firmato Mozilla) solo dopo che il proxy è attivo, consentito in privato, aggiornato una volta al giorno; il PromptDelegate accetta solo l'id `uBlock0@raymondhill.net`. `MainActivity` fa da `Adblock.Host` (pannello del pulsante in un Dialog con GeckoView, schede aperte/chiuse dall'estensione) e segnala la scheda attiva con `setTabActive`.
- Blocco con impronta/PIN (`AppLock`, BiometricPrompt di sistema) e cancellazione automatica sono gestiti in `MainActivity` contando le attività avviate: l'app «esce» quando nessuna è visibile; le app esterne aperte per un risultato (scelta file, PIN) non contano.
- Proxy tramite estensione interna `app/src/main/assets/extensions/proxy`: blocca tutto finché l'app non invia la configurazione; `Engine.canBrowse()` ferma i caricamenti se il proxy non è confermato.
- Gli stili `Angerona.*` ereditano dallo stile base `Angerona` in `themes.xml`: non rimuoverlo.
- La compilazione vera avviene su GitHub Actions (`.github/workflows/main.yml`); in ambienti senza accesso a Maven Google/Mozilla controlla almeno i tipi con `android.jar` e stub generati da `mobile/android/geckoview/api.txt`.

## GitHub
- Le azioni nel workflow sono bloccate per hash di commit (commento con la versione): aggiornale cambiando hash e commento insieme, o lascia fare a Dependabot (`.github/dependabot.yml`, mensile e raggruppato).
- Permessi minimi: i job di compilazione leggono soltanto; scrive solo «Pubblica la versione», che allega anche l'attestato di provenienza (`gh attestation verify FILE -R emylsRM/Angerona`).
- Su `main` valgono regole del repository: niente push diretti né forzati, servono una pull request e le compilazioni «APK Android» e «Installer Windows» riuscite.
