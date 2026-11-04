'use strict';
// Protezioni di rete: DNS cifrato, proxy, scudi contro pubblicità e tracker (come Brave), siti
// pericolosi, rete locale, cookie di terze parti, parametri di tracciamento, HTTPS-only, certificati.
const fs = require('node:fs');
const path = require('node:path');
const { Worker } = require('node:worker_threads');
const { app, session } = require('electron');
const { getDomain, parse } = require('tldts');
const { FiltersEngine, Request } = require('@ghostery/adblocker');
const { dohTemplate } = require('./settings');
const {
  TRACKER_LISTS, COOKIE_LISTS, THREAT_LISTS, RESOURCES, COSMETIC_CONFIG, NETWORK_CONFIG,
} = require('./lists');
const { cleanNavigation } = require('./urlclean');

// Tre motori separati, ciascuno con il suo file e la sua frequenza di aggiornamento.
// I nomi dei file cambiano quando cambia il formato (versioni precedenti: filters.bin senza cosmetici).
const DAY = 24 * 3600 * 1000;
const KINDS = {
  trackers: { file: 'shields.bin', lists: TRACKER_LISTS, resources: true, config: COSMETIC_CONFIG, maxAge: 2 * DAY, minSize: 1000 },
  cookies: { file: 'cookies.bin', lists: COOKIE_LISTS, resources: true, config: COSMETIC_CONFIG, maxAge: 2 * DAY, minSize: 1000 },
  threats: { file: 'threats.bin', lists: THREAT_LISTS, resources: false, config: NETWORK_CONFIG, maxAge: DAY / 2, minSize: 1000 },
};
const AD_KINDS = ['trackers', 'cookies'];

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
    this.engines = { trackers: null, cookies: null, threats: null };
    this.info = {
      trackers: { rules: 0, updated: null, source: 'nessuna' },
      cookies: { rules: 0, updated: null, source: 'nessuna' },
      threats: { rules: 0, updated: null, source: 'nessuna' },
    };
    this.shieldsOff = new Set(settings.shieldsOff || []); // siti con le protezioni spente
    this.surrogates = new Map(); // id -> contenuto sostitutivo
    this.httpAllowed = new Set(); // host per cui l'utente ha accettato HTTP in questa sessione
    this.threatAllowed = new Set(); // host segnalati che l'utente ha voluto aprire comunque
    this.upgraded = new Map(); // webContentsId -> URL http originale riscritto in https
    this.dangerous = new Map(); // webContentsId -> URL bloccato perché pericoloso
    this.untrustedRoots = new Set(); // host con certificato emesso da un'autorità aggiunta al computer
  }

  get engine() { return this.engines.trackers; }
  get filtersInfo() { return this.info.trackers; }
  get threatsInfo() { return this.info.threats; }
  get cookiesInfo() { return this.info.cookies; }

  /** Le protezioni contro pubblicità e tracker valgono per questa pagina? (false = spente dall'utente) */
  shieldsActiveFor(url) {
    return Boolean(this.settings.blockTrackers) && !this.shieldsOff.has(siteOf(url));
  }

  #adKinds() {
    return AD_KINDS.filter((k) => this.engines[k] && (k !== 'cookies' || this.settings.blockCookieBanners));
  }

  /** Dimentica le scelte fatte in questa sessione (HTTP accettato, siti segnalati aperti comunque). */
  forgetSessionChoices() {
    this.httpAllowed.clear();
    this.threatAllowed.clear();
    this.dangerous.clear();
    this.untrustedRoots.clear();
  }

  // ---------- Liste di blocco ----------
  #cacheFile(kind) { return path.join(this.dataDir, KINDS[kind].file); }

  // Usa la copia più recente tra quella scaricata e quella inclusa nel browser.
  #load(kind) {
    const bundled = path.join(__dirname, '..', 'resources', KINDS[kind].file);
    const candidates = [[this.#cacheFile(kind), 'aggiornate'], [bundled, 'incluse nel browser']]
      .map(([file, source]) => {
        try { return { file, source, mtime: fs.statSync(file).mtimeMs }; } catch { return null; }
      })
      .filter(Boolean)
      .sort((a, b) => b.mtime - a.mtime);
    for (const { file, source, mtime } of candidates) {
      try {
        const engine = FiltersEngine.deserialize(new Uint8Array(fs.readFileSync(file)));
        this.engines[kind] = engine;
        this.info[kind] = { rules: readRuleCount(file) ?? countRules(engine), updated: new Date(mtime).toISOString(), source };
        return true;
      } catch { /* prova il successivo */ }
    }
    return false;
  }

  loadFilters() {
    // Le versioni precedenti salvavano le liste dei tracker in filters.bin (senza filtri cosmetici).
    for (const old of ['filters.bin', 'filters.bin.json']) {
      try { fs.rmSync(path.join(this.dataDir, old), { force: true }); } catch { /* ignorato */ }
    }
    const trackers = this.#load('trackers');
    this.#load('cookies');
    this.#load('threats');
    return trackers;
  }

  needsFilterUpdate(kind = 'trackers') {
    const updated = Date.parse(this.info[kind].updated || '');
    return !Number.isFinite(updated) || Date.now() - updated > KINDS[kind].maxAge;
  }

  // Scarica le liste passando dalla sessione del browser, quindi da DNS cifrato e proxy.
  async #download(mirrors, minSize) {
    for (const url of mirrors) {
      try {
        const res = await this.ses.fetch(url, { credentials: 'omit', cache: 'no-store', signal: AbortSignal.timeout(30000) });
        if (res.ok) {
          const text = await res.text();
          if (text.length > minSize) return text;
        }
      } catch { /* prova il mirror successivo */ }
    }
    return null;
  }

  async updateFilters(kind = 'trackers') {
    const { lists, minSize, resources, config } = KINDS[kind];
    const texts = [];
    for (const { mirrors, optional } of lists) {
      const text = await this.#download(mirrors, minSize);
      if (text) texts.push(text);
      else if (!optional) throw new Error('Liste di protezione non raggiungibili');
    }
    let res = null;
    if (resources) {
      res = await this.#download(RESOURCES, 1000);
      if (!res) throw new Error('Risorse di uBlock Origin non raggiungibili');
    }
    const { data, rules } = await buildEngine(texts.join('\n'), config, res);
    const file = this.#cacheFile(kind);
    fs.writeFileSync(`${file}.tmp`, data);
    fs.renameSync(`${file}.tmp`, file);
    try { fs.writeFileSync(`${file}.json`, JSON.stringify({ rules })); } catch { /* solo informativo */ }
    this.engines[kind] = FiltersEngine.deserialize(data);
    this.info[kind] = { rules, updated: new Date().toISOString(), source: 'aggiornate' };
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
    this.shieldsOff = new Set(s.shieldsOff || []);
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

  #request(d) {
    const top = this.#topUrl(d);
    return Request.fromRawDetails({
      url: d.url,
      sourceUrl: /^https?:/.test(top) ? top : '',
      type: RESOURCE_TYPES[d.resourceType] || 'other',
    });
  }

  #matches(engine, d) {
    return engine.match(this.#request(d)).match;
  }

  /** Pubblicità e tracker: blocco, oppure sostituzione con uno script innocuo (come uBlock Origin). */
  #matchAds(d) {
    const req = this.#request(d);
    for (const kind of this.#adKinds()) {
      const { match, redirect } = this.engines[kind].match(req);
      if (redirect && redirect.body !== undefined) return { redirectURL: this.#surrogateUrl(redirect) };
      if (match) return { cancel: true };
    }
    return null;
  }

  // I contenuti sostitutivi (pochi, piccoli) restano in memoria e si servono da angerona-res://r/<id>.
  #surrogateUrl(redirect) {
    const id = require('node:crypto').createHash('sha256').update(redirect.dataUrl || redirect.body).digest('hex').slice(0, 24);
    if (!this.surrogates.has(id)) {
      if (this.surrogates.size > 500) this.surrogates.clear();
      this.surrogates.set(id, { body: redirect.body, type: redirect.contentType || 'text/plain' });
    }
    return `angerona-res://r/${id}`;
  }

  serveSurrogate(url) {
    let id = '';
    try { id = new URL(url).pathname.slice(1); } catch { /* indirizzo non valido */ }
    const item = this.surrogates.get(id);
    if (!item) return new Response('', { status: 404 });
    // Le risorse binarie (immagini, video vuoti) arrivano in base64.
    const [type, encoding] = item.type.split(';');
    const body = encoding === 'base64' ? Buffer.from(item.body, 'base64') : item.body;
    return new Response(body, {
      headers: { 'content-type': type, 'access-control-allow-origin': '*', 'cache-control': 'no-store' },
    });
  }

  /**
   * Una finestra pop-up verso un indirizzo di pubblicità o tracciamento (i «popunder»)? Le regole
   * per dominio delle liste valgono anche per le finestre nuove, come in uBlock Origin.
   */
  isPopupBlocked(url, openerUrl) {
    if (!this.shieldsActiveFor(openerUrl)) return false;
    let req;
    try {
      req = Request.fromRawDetails({ url, sourceUrl: /^https?:/.test(openerUrl) ? openerUrl : '', type: 'main_frame' });
    } catch { return false; }
    return this.#adKinds().some((kind) => this.engines[kind].match(req).match);
  }

  /**
   * Regole cosmetiche per una pagina o un riquadro: stili che nascondono annunci e banner, e
   * scriptlet da eseguire prima degli script del sito. Con `dom` (classi, id e link trovati nella
   * pagina) restituisce solo gli stili generici che li riguardano.
   */
  cosmeticsFor(url, topUrl, dom = null) {
    const empty = { styles: '', scripts: [] };
    if (!/^https?:/.test(url) || !this.shieldsActiveFor(topUrl || url)) return empty;
    const info = parse(url, { allowPrivateDomains: true });
    const hostname = info.hostname || '';
    const domain = info.domain || hostname;
    const query = dom
      ? {
        classes: dom.classes, ids: dom.ids, hrefs: dom.hrefs,
        getBaseRules: false, getInjectionRules: false, getExtendedRules: false, getRulesFromHostname: false, getRulesFromDOM: true,
      }
      : { getBaseRules: true, getInjectionRules: true, getExtendedRules: false, getRulesFromHostname: true, getRulesFromDOM: false };
    let styles = '';
    const scripts = [];
    for (const kind of this.#adKinds()) {
      try {
        const r = this.engines[kind].getCosmeticsFilters({ url, hostname, domain, ...query });
        if (r.styles) styles += `${r.styles}\n`;
        scripts.push(...r.scripts);
      } catch { /* lista non utilizzabile per questa pagina */ }
    }
    return { styles, scripts };
  }

  #beforeRequest(d) {
    let u;
    try { u = new URL(d.url); } catch { return {}; }
    const web = u.protocol === 'http:' || u.protocol === 'https:';
    if (!web && u.protocol !== 'ws:' && u.protocol !== 'wss:') return {};
    const mainFrame = d.resourceType === 'mainFrame';

    // Link di tracciamento: si salta l'intermediario (google.com/url?q=…, l.facebook.com/l.php?u=…)
    // e si tolgono gli identificativi di clic (fbclid, gclid…), come fa Brave.
    if (mainFrame && web && this.shieldsActiveFor(d.url)) {
      const clean = cleanNavigation(d.url);
      if (clean) return { redirectURL: clean };
    }

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

    if (!mainFrame && this.shieldsActiveFor(this.#topUrl(d) || d.url)) {
      const blocked = this.#matchAds(d);
      if (blocked) {
        if (d.webContentsId) this.onBlocked(d.webContentsId);
        return blocked;
      }
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
  try {
    const f = engine.getFilters();
    return f.networkFilters.length + f.cosmeticFilters.length;
  } catch { return 0; }
}

function readRuleCount(file) {
  try {
    const n = JSON.parse(fs.readFileSync(`${file}.json`, 'utf8')).rules;
    return Number.isInteger(n) && n >= 0 ? n : null;
  } catch { return null; }
}

// Le liste contano centinaia di migliaia di regole: prepararle richiede qualche secondo, quindi
// lo si fa in un thread separato per non bloccare il browser. Se il thread non parte, si ripiega
// sul thread principale.
const ADBLOCKER_PATH = require.resolve('@ghostery/adblocker');
const WORKER_CODE = `
  const { parentPort, workerData } = require('node:worker_threads');
  const { FiltersEngine } = require(workerData.lib);
  const engine = FiltersEngine.parse(workerData.text, workerData.config);
  if (workerData.resources) engine.updateResources(workerData.resources, String(workerData.resources.length));
  const data = engine.serialize();
  const f = engine.getFilters();
  parentPort.postMessage({ data, rules: f.networkFilters.length + f.cosmeticFilters.length });
`;

function buildEngineInline(text, config, resources) {
  const engine = FiltersEngine.parse(text, config);
  if (resources) engine.updateResources(resources, String(resources.length));
  return { data: engine.serialize(), rules: countRules(engine), worker: false };
}

function buildEngine(text, config = NETWORK_CONFIG, resources = null) {
  return new Promise((resolve) => {
    let worker;
    try {
      worker = new Worker(WORKER_CODE, { eval: true, workerData: { lib: ADBLOCKER_PATH, text, config, resources } });
    } catch {
      resolve(buildEngineInline(text, config, resources));
      return;
    }
    let settled = false;
    const finish = (value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      worker.terminate().catch(() => {});
      resolve(value || buildEngineInline(text, config, resources));
    };
    const timer = setTimeout(() => finish(null), 120000);
    worker.once('message', (m) => finish({ data: new Uint8Array(m.data), rules: m.rules, worker: true }));
    worker.once('error', () => finish(null));
    worker.once('exit', () => finish(null));
  });
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

module.exports = {
  Privacy, inputToUrl, isPrivateHost, isPublicWebUrl, buildEngine, siteOf,
};
