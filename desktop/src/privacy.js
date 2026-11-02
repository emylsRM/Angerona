'use strict';
// Protezioni di rete: DNS cifrato, proxy, blocco di tracker e siti pericolosi, rete locale,
// cookie di terze parti, HTTPS-only, certificati.
const fs = require('node:fs');
const path = require('node:path');
const { app, session } = require('electron');
const { getDomain, parse } = require('tldts');
const { FiltersEngine, Request } = require('@ghostery/adblocker');
const { dohTemplate } = require('./settings');
const { TRACKER_LISTS, THREAT_LISTS, ENGINE_CONFIG } = require('./lists');

// Due motori separati: tracker (aggiornati ogni settimana) e siti pericolosi (ogni 12 ore).
const KINDS = {
  trackers: { file: 'filters.bin', lists: TRACKER_LISTS, maxAge: 7 * 24 * 3600 * 1000, minSize: 10000 },
  threats: { file: 'threats.bin', lists: THREAT_LISTS, maxAge: 12 * 3600 * 1000, minSize: 1000 },
};

const RESOURCE_TYPES = {
  mainFrame: 'main_frame', subFrame: 'sub_frame', stylesheet: 'stylesheet', script: 'script',
  image: 'image', font: 'font', object: 'object', xhr: 'xmlhttprequest', ping: 'ping',
  cspReport: 'csp_report', media: 'media', webSocket: 'websocket', other: 'other',
};
const ALLOWED_PERMISSIONS = new Set(['fullscreen', 'clipboard-sanitized-write', 'pointerLock']);
// Client Hints che rivelano versione esatta di Windows, processore e modello: non vengono inviati.
const HIGH_ENTROPY_HINTS = new Set([
  'sec-ch-ua-full-version-list', 'sec-ch-ua-full-version', 'sec-ch-ua-platform-version', 'sec-ch-ua-arch',
  'sec-ch-ua-bitness', 'sec-ch-ua-model', 'sec-ch-ua-wow64', 'sec-ch-ua-form-factors',
]);
const ERR_CERT_AUTHORITY_INVALID = -202;
const USE_CHROMIUM_RESULT = -3;

/** Indirizzo del computer stesso o della rete locale (casa, ufficio, VPN come Tailscale). */
function isPrivateHost(host) {
  const h = String(host || '').replace(/^\[|\]$/g, '').replace(/\.$/, '').toLowerCase();
  if (!h) return false;
  if (h === 'localhost' || h.endsWith('.localhost') || h.endsWith('.local') || h.endsWith('.lan')
    || h.endsWith('.internal') || h.endsWith('.home.arpa') || !h.includes('.') && !h.includes(':')) return true;
  if (h.includes(':')) {
    return h === '::' || h === '::1' || h.startsWith('::ffff:') || h.startsWith('fe80:') || h.startsWith('fc') || h.startsWith('fd');
  }
  const m = h.match(/^(\d+)\.(\d+)\.\d+\.\d+$/);
  if (!m) return false;
  const [a, b] = [Number(m[1]), Number(m[2])];
  return a === 0 || a === 10 || a === 127 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168)
    || (a === 169 && b === 254) || (a === 100 && b >= 64 && b <= 127);
}

/** Pagina web su Internet (non sul computer né in rete locale). */
function isPublicWebUrl(url) {
  try {
    const u = new URL(url);
    return (u.protocol === 'http:' || u.protocol === 'https:') && !isPrivateHost(u.hostname);
  } catch { return false; }
}

function siteOf(url) {
  try {
    const host = new URL(url).hostname;
    return getDomain(host, { allowPrivateDomains: true }) || host;
  } catch { return ''; }
}

class Privacy {
  constructor({ ses, settings, dataDir, onBlocked }) {
    this.ses = ses;
    this.settings = settings;
    this.dataDir = dataDir;
    this.onBlocked = onBlocked;
    this.engines = { trackers: null, threats: null };
    this.info = {
      trackers: { rules: 0, updated: null, source: 'nessuna' },
      threats: { rules: 0, updated: null, source: 'nessuna' },
    };
    this.httpAllowed = new Set(); // host per cui l'utente ha accettato HTTP in questa sessione
    this.threatAllowed = new Set(); // host segnalati che l'utente ha voluto aprire comunque
    this.upgraded = new Map(); // webContentsId -> URL http originale riscritto in https
    this.dangerous = new Map(); // webContentsId -> URL bloccato perché pericoloso
    this.untrustedRoots = new Set(); // host con certificato emesso da un'autorità aggiunta al computer
  }

  get engine() { return this.engines.trackers; }
  get filtersInfo() { return this.info.trackers; }
  get threatsInfo() { return this.info.threats; }

  /** Dimentica le scelte fatte in questa sessione (HTTP accettato, siti segnalati aperti comunque). */
  forgetSessionChoices() {
    this.httpAllowed.clear();
    this.threatAllowed.clear();
    this.dangerous.clear();
    this.untrustedRoots.clear();
  }

  // ---------- Liste di blocco ----------
  #cacheFile(kind) { return path.join(this.dataDir, KINDS[kind].file); }

  #load(kind) {
    const bundled = path.join(__dirname, '..', 'resources', KINDS[kind].file);
    for (const [file, source] of [[this.#cacheFile(kind), 'aggiornate'], [bundled, 'incluse nel browser']]) {
      try {
        const engine = FiltersEngine.deserialize(new Uint8Array(fs.readFileSync(file)));
        this.engines[kind] = engine;
        this.info[kind] = { rules: countRules(engine), updated: fs.statSync(file).mtime.toISOString(), source };
        return true;
      } catch { /* prova il successivo */ }
    }
    return false;
  }

  loadFilters() {
    const trackers = this.#load('trackers');
    this.#load('threats');
    return trackers;
  }

  needsFilterUpdate(kind = 'trackers') {
    try { return Date.now() - fs.statSync(this.#cacheFile(kind)).mtimeMs > KINDS[kind].maxAge; } catch { return true; }
  }

  // Scarica le liste passando dalla sessione del browser, quindi da DNS cifrato e proxy.
  async updateFilters(kind = 'trackers') {
    const { lists, minSize } = KINDS[kind];
    const texts = [];
    for (const mirrors of lists) {
      let text = null;
      for (const url of mirrors) {
        try {
          const res = await this.ses.fetch(url, { credentials: 'omit', cache: 'no-store', signal: AbortSignal.timeout(30000) });
          if (res.ok) { text = await res.text(); if (text.length > minSize) break; }
          text = null;
        } catch { /* prova il mirror successivo */ }
      }
      if (!text) throw new Error('Liste di protezione non raggiungibili');
      texts.push(text);
    }
    const engine = FiltersEngine.parse(texts.join('\n'), ENGINE_CONFIG);
    const file = this.#cacheFile(kind);
    fs.writeFileSync(`${file}.tmp`, engine.serialize());
    fs.renameSync(`${file}.tmp`, file);
    this.engines[kind] = engine;
    this.info[kind] = { rules: countRules(engine), updated: new Date().toISOString(), source: 'aggiornate' };
    return this.info[kind];
  }

  // ---------- Rete: DNS e proxy ----------
  async applyNetwork(s) {
    this.settings = s;
    const tpl = dohTemplate(s);
    app.configureHostResolver(tpl
      ? { enableBuiltInResolver: true, secureDnsMode: 'secure', secureDnsServers: [tpl] }
      : { enableBuiltInResolver: true, secureDnsMode: 'off', secureDnsServers: [] });

    const p = s.proxy;
    let config = { mode: 'direct' };
    if (p.mode !== 'none') {
      const host = p.host.includes(':') ? `[${p.host}]` : p.host;
      // Nessun ripiego su connessione diretta: se il proxy cade, la navigazione si ferma.
      config = { mode: 'fixed_servers', proxyRules: `${p.mode}://${host}:${Number(p.port)}`, proxyBypassRules: '' };
    }
    await Promise.all([this.ses.setProxy(config), session.defaultSession.setProxy(config)]);
    await Promise.all([this.ses.closeAllConnections(), session.defaultSession.closeAllConnections()]);
    await Promise.all([this.ses.clearHostResolverCache(), session.defaultSession.clearHostResolverCache()]);
  }

  updateSettings(s) {
    const strictChanged = Boolean(this.settings.strictCerts) !== Boolean(s.strictCerts);
    this.settings = s;
    // Il risultato della verifica dei certificati resta in memoria: reinstallare la verifica lo azzera.
    if (strictChanged) this.ses.setCertificateVerifyProc((req, cb) => cb(this.#verifyCert(req)));
  }

  // ---------- Indurimento della sessione ----------
  harden(userAgent) {
    const ses = this.ses;
    ses.setUserAgent(userAgent);
    ses.setSpellCheckerEnabled(false);
    ses.setPermissionRequestHandler((_wc, perm, cb) => cb(ALLOWED_PERMISSIONS.has(perm)));
    ses.setPermissionCheckHandler((_wc, perm) => ALLOWED_PERMISSIONS.has(perm));
    ses.setDevicePermissionHandler(() => false);
    ses.setDisplayMediaRequestHandler((_req, cb) => cb({}));
    // Nessun accesso a dispositivi USB, HID, porte seriali e Bluetooth: ogni richiesta viene annullata.
    ses.on('select-hid-device', (e, _details, cb) => { e.preventDefault(); cb(); });
    ses.on('select-usb-device', (e, _details, cb) => { e.preventDefault(); cb(); });
    ses.on('select-serial-port', (e, _ports, _wc, cb) => { e.preventDefault(); cb(''); });
    try { ses.setBluetoothPairingHandler((_details, cb) => cb({ confirmed: false })); } catch { /* non disponibile */ }
    ses.setCertificateVerifyProc((req, cb) => cb(this.#verifyCert(req)));

    ses.webRequest.onBeforeRequest((d, cb) => cb(this.#beforeRequest(d)));
    ses.webRequest.onBeforeSendHeaders((d, cb) => cb(this.#beforeSendHeaders(d)));
    ses.webRequest.onHeadersReceived((d, cb) => cb(this.#headersReceived(d)));
  }

  // Con «Rifiuta i certificati aggiunti al computer», vale solo la lista di autorità di Chromium:
  // niente antivirus o proxy aziendali (o programmi dannosi) che intercettano l'HTTPS.
  #verifyCert(req) {
    if (this.settings.strictCerts && req.errorCode === 0 && req.isIssuedByKnownRoot === false
        && !isPrivateHost(req.hostname)) {
      this.untrustedRoots.add(req.hostname);
      return ERR_CERT_AUTHORITY_INVALID;
    }
    return USE_CHROMIUM_RESULT;
  }

  #topUrl(d) {
    if (d.resourceType === 'mainFrame') return d.url;
    try { if (d.frame && d.frame.top && d.frame.top.url) return d.frame.top.url; } catch { /* frame chiuso */ }
    try { if (d.webContents) return d.webContents.getURL(); } catch { /* ignorato */ }
    return '';
  }

  /** La richiesta parte da una pagina su Internet (la pagina principale o il riquadro che la chiede)? */
  #fromPublicPage(d) {
    if (isPublicWebUrl(this.#topUrl(d))) return true;
    try { return Boolean(d.frame && d.frame.url && isPublicWebUrl(d.frame.url)); } catch { return false; }
  }

  #isThirdParty(d) {
    if (d.resourceType === 'mainFrame') return false;
    const top = this.#topUrl(d);
    if (!/^https?:/.test(top)) return false;
    return siteOf(d.url) !== siteOf(top);
  }

  #matches(engine, d) {
    const top = this.#topUrl(d);
    return engine.match(Request.fromRawDetails({
      url: d.url,
      sourceUrl: /^https?:/.test(top) ? top : '',
      type: RESOURCE_TYPES[d.resourceType] || 'other',
    })).match;
  }

  #beforeRequest(d) {
    let u;
    try { u = new URL(d.url); } catch { return {}; }
    const web = u.protocol === 'http:' || u.protocol === 'https:';
    if (!web && u.protocol !== 'ws:' && u.protocol !== 'wss:') return {};
    const mainFrame = d.resourceType === 'mainFrame';

    // Una pagina su Internet non può contattare il computer né i dispositivi della rete locale
    // (router, stampanti, programmi in ascolto): niente scansioni né tracciamento via localhost.
    if (!mainFrame && isPrivateHost(u.hostname) && this.#fromPublicPage(d)) {
      if (d.webContentsId) this.onBlocked(d.webContentsId);
      return { cancel: true };
    }

    // Siti di truffa e malware: la pagina viene fermata prima di qualsiasi collegamento.
    if (this.settings.blockThreats && this.engines.threats && !this.threatAllowed.has(u.hostname)
        && this.#matches(this.engines.threats, d)) {
      if (mainFrame && d.webContentsId) this.dangerous.set(d.webContentsId, d.url);
      else if (d.webContentsId) this.onBlocked(d.webContentsId);
      return { cancel: true };
    }

    if (u.protocol === 'http:' && this.settings.httpsOnly && !isPrivateHost(u.hostname)
        && !this.httpAllowed.has(u.hostname)) {
      if (mainFrame && d.webContentsId) this.upgraded.set(d.webContentsId, d.url);
      u.protocol = 'https:';
      if (u.port === '80') u.port = '';
      return { redirectURL: u.toString() };
    }

    if (this.settings.blockTrackers && this.engines.trackers && !mainFrame && this.#matches(this.engines.trackers, d)) {
      if (d.webContentsId) this.onBlocked(d.webContentsId);
      return { cancel: true };
    }
    return {};
  }

  #beforeSendHeaders(d) {
    const headers = { ...d.requestHeaders, 'Sec-GPC': '1' };
    const thirdParty = this.settings.blockThirdPartyCookies && this.#isThirdParty(d);
    for (const k of Object.keys(headers)) {
      const key = k.toLowerCase();
      if ((thirdParty && key === 'cookie') || (this.settings.reduceFingerprint && HIGH_ENTROPY_HINTS.has(key))) delete headers[k];
    }
    return { requestHeaders: headers };
  }

  #headersReceived(d) {
    if (!this.settings.blockThirdPartyCookies || !d.responseHeaders || !this.#isThirdParty(d)) return {};
    const headers = {};
    let changed = false;
    for (const [k, v] of Object.entries(d.responseHeaders)) {
      if (k.toLowerCase() === 'set-cookie') { changed = true; continue; }
      headers[k] = v;
    }
    return changed ? { responseHeaders: headers } : {};
  }
}

function countRules(engine) {
  try { return engine.getFilters().networkFilters.length; } catch { return 0; }
}

// Indirizzo digitato o ricerca? Restituisce un URL navigabile.
function inputToUrl(input, makeSearchUrl) {
  const t = String(input || '').trim();
  if (!t) return null;
  if (/^https?:\/\//i.test(t) || /^file:\/\//i.test(t) || /^about:blank$/i.test(t)) return t;
  if (!/\s/.test(t)) {
    const hostPart = t.split(/[/?#]/)[0].replace(/:\d+$/, '');
    const info = parse(hostPart, { allowPrivateDomains: true });
    if (info.isIp || hostPart.toLowerCase() === 'localhost'
        || (info.domain && (info.isIcann || info.isPrivate) && info.publicSuffix !== hostPart)) {
      return `https://${t}`;
    }
  }
  return makeSearchUrl(t);
}

module.exports = { Privacy, inputToUrl, isPrivateHost, isPublicWebUrl };
