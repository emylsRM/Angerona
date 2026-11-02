'use strict';
// Aggiornamenti: controlla l'ultimo rilascio su GitHub passando dalla sessione protetta
// (DNS cifrato e proxy), scarica l'installer, ne verifica l'impronta SHA-256 e lo avvia.
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { app, shell } = require('electron');

const REPO = 'emylsRM/Angerona';
const LATEST = `https://api.github.com/repos/${REPO}/releases/latest`;

function parseVersion(v) {
  const m = String(v || '').replace(/^v/, '').match(/^(\d+)\.(\d+)\.(\d+)/);
  return m ? m.slice(1, 4).map(Number) : null;
}

function isNewer(remote, local) {
  const a = parseVersion(remote);
  const b = parseVersion(local);
  if (!a || !b) return false;
  for (let i = 0; i < 3; i++) {
    if (a[i] !== b[i]) return a[i] > b[i];
  }
  return false;
}

// La versione portable non si installa: si scarica il nuovo file dalla pagina del rilascio.
const isPortable = () => Boolean(process.env.PORTABLE_EXECUTABLE_FILE);

class Updater {
  constructor({ ses, onAvailable, onStatus, openTab }) {
    this.ses = ses;
    this.onAvailable = onAvailable;
    this.onStatus = onStatus;
    this.openTab = openTab;
    this.release = null;
    this.pending = null; // { url, name, sha256 }
  }

  async #json(url) {
    const res = await this.ses.fetch(url, {
      credentials: 'omit',
      cache: 'no-store',
      headers: { Accept: 'application/vnd.github+json' },
      signal: AbortSignal.timeout(15000),
    });
    if (!res.ok) throw new Error(`GitHub ha risposto ${res.status}`);
    return res.json();
  }

  /** Restituisce { available, version, current } oppure lancia un errore. */
  async check() {
    const rel = await this.#json(LATEST);
    const version = String(rel.tag_name || '').replace(/^v/, '');
    const current = app.getVersion();
    if (!isNewer(version, current)) return { available: false, version, current };
    const assets = Array.isArray(rel.assets) ? rel.assets : [];
    const pattern = isPortable() ? /^Angerona-Portable-.*\.exe$/ : /^Angerona-Setup-.*\.exe$/;
    const exe = assets.find((a) => pattern.test(a.name));
    const sums = assets.find((a) => a.name === 'SHA256SUMS.txt');
    this.release = {
      version,
      page: String(rel.html_url || `https://github.com/${REPO}/releases/latest`),
      exe: exe ? { name: exe.name, url: exe.browser_download_url } : null,
      sums: sums ? sums.browser_download_url : null,
    };
    this.onAvailable({ version, current, portable: isPortable() });
    return { available: true, version, current };
  }

  async install() {
    const rel = this.release;
    if (!rel) return;
    if (isPortable() || process.platform !== 'win32' || !rel.exe || !rel.sums) {
      this.openTab(rel.page);
      return;
    }
    const sumsRes = await this.ses.fetch(rel.sums, { credentials: 'omit', cache: 'no-store', signal: AbortSignal.timeout(15000) });
    if (!sumsRes.ok) throw new Error('Impronte del rilascio non disponibili');
    const line = (await sumsRes.text()).split('\n').find((l) => l.trim().endsWith(rel.exe.name));
    const sha256 = line ? line.trim().split(/\s+/)[0].toLowerCase() : '';
    if (!/^[0-9a-f]{64}$/.test(sha256)) throw new Error("Impronta dell'installer mancante");
    this.pending = { url: rel.exe.url, name: rel.exe.name, sha256 };
    this.onStatus('Download dell\'aggiornamento…');
    this.ses.downloadURL(rel.exe.url);
  }

  /** Da chiamare da will-download: true se il download è dell'aggiornamento e viene gestito qui. */
  handleDownload(item) {
    const p = this.pending;
    if (!p) return false;
    const chain = item.getURLChain();
    if (!chain.length || chain[0] !== p.url) return false;
    this.pending = null;
    const file = path.join(app.getPath('temp'), p.name);
    item.setSavePath(file);
    let retried = false;
    item.on('updated', (_e, state) => {
      if (state === 'interrupted') {
        // Un tentativo di ripresa, poi si rinuncia: meglio nessun aggiornamento che un file a metà.
        if (!retried && item.canResume()) { retried = true; item.resume(); } else { item.cancel(); }
        return;
      }
      const total = item.getTotalBytes();
      if (total > 0) this.onStatus(`Download dell'aggiornamento ${Math.round((100 * item.getReceivedBytes()) / total)}%`);
    });
    item.once('done', (_e, state) => {
      if (state !== 'completed') { this.onStatus('Download dell\'aggiornamento non riuscito'); return; }
      const hash = crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
      if (hash !== p.sha256) {
        try { fs.unlinkSync(file); } catch { /* ignorato */ }
        this.onStatus('Aggiornamento scartato: il file non corrisponde a quello pubblicato');
        return;
      }
      this.onStatus('Avvio dell\'installazione…');
      shell.openPath(file).then((err) => {
        if (err) this.onStatus(`Impossibile avviare l'installer: ${err}`);
        else setTimeout(() => app.quit(), 1500);
      });
    });
    return true;
  }
}

module.exports = { Updater, isNewer, parseVersion };
