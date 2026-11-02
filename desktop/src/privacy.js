'use strict';
// Protezioni di rete: DNS cifrato, proxy, blocco tracker, cookie di terze parti, HTTPS-only.
const fs = require('node:fs');
const path = require('node:path');
const { app, session } = require('electron');
const { getDomain, parse } = require('tldts');
const { FiltersEngine, Request } = require('@ghostery/adblocker');
const { dohTemplate } = require('./settings');

const FILTER_LISTS = [
  ['https://easylist.to/easylist/easylist.txt', 'https://raw.githubusercontent.com/easylist/easylist/gh-pages/easylist.txt'],
  ['https://easylist.to/easylist/easyprivacy.txt', 'https://raw.githubusercontent.com/easylist/easylist/gh-pages/easyprivacy.txt'],
];
const ENGINE_CONFIG = {
  loadNetworkFilters: true,
  loadCosmeticFilters: false,
  loadCSPFilters: false,
  enableHtmlFiltering: false,
  enableCompression: true,
};
const FILTER_MAX_AGE = 7 * 24 * 3600 * 1000;

const RESOURCE_TYPES = {
  mainFrame: 'main_frame', subFrame: 'sub_frame', stylesheet: 'stylesheet', script: 'script',
  image: 'image', font: 'font', object: 'object', xhr: 'xmlhttprequest', ping: 'ping',
  cspReport: 'csp_report', media: 'media', webSocket: 'websocket', other: 'other',
};
const ALLOWED_PERMISSIONS = new Set(['fullscreen', 'clipboard-sanitized-write', 'pointerLock']);

function isPrivateHost(host) {
  const h = host.replace(/^\[|\]$/g, '').toLowerCase();
  if (h === 'localhost' || h.endsWith('.localhost') || h.endsWith('.local') || h.endsWith('.lan')
    || h.endsWith('.internal') || h.endsWith('.home.arpa') || !h.includes('.') && !h.includes(':')) return true;
  if (h === '::1' || h.startsWith('fe80:') || h.startsWith('fc') || h.startsWith('fd')) return h.includes(':');
  const m = h.match(/^(\d+)\.(\d+)\.\d+\.\d+$/);
  if (!m) return false;
  const [a, b] = [Number(m[1]), Number(m[2])];
  return a === 10 || a === 127 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 169 && b === 254);
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
    this.engine = null;
    this.filtersInfo = { rules: 0, updated: null, source: 'nessuna' };
    this.httpAllowed = new Set(); // host per cui l'utente ha accettato HTTP in questa sessione
    this.upgraded = new Map(); // webContentsId -> URL http originale riscritto in https
  }

  get cacheFile() { return path.join(this.dataDir, 'filters.bin'); }

  // ---------- Filtri anti-tracciamento ----------
  loadFilters() {
    const bundled = path.join(__dirname, '..', 'resources', 'filters.bin');
    const candidates = [[this.cacheFile, 'aggiornati'], [bundled, 'inclusi nel browser']];
    for (const [file, source] of candidates) {
      try {
        const buf = fs.readFileSync(file);
        this.engine = FiltersEngine.deserialize(new Uint8Array(buf));
        this.filtersInfo = {
          rules: this.#countRules(), updated: fs.statSync(file).mtime.toISOString(), source,
        };
        return true;
      } catch { /* prova il successivo */ }
    }
    return false;
  }

  #countRules() {
    try { return this.engine.getFilters().networkFilters.length; } catch { return 0; }
  }

  needsFilterUpdate() {
    try { return Date.now() - fs.statSync(this.cacheFile).mtimeMs > FILTER_MAX_AGE; } catch { return true; }
  }

  // Scarica le liste passando dalla sessione del browser, quindi da DNS cifrato e proxy.
  async updateFilters() {
    const texts = [];
    for (const mirrors of FILTER_LISTS) {
      let text = null;
      for (const url of mirrors) {
        try {
          const res = await this.ses.fetch(url, { credentials: 'omit', cache: 'no-store', signal: AbortSignal.timeout(30000) });
          if (res.ok) { text = await res.text(); if (text.length > 10000) break; }
        } catch { /* prova il mirror successivo */ }
      }
      if (!text) throw new Error('Liste dei filtri non raggiungibili');
      texts.push(text);
    }
    const engine = FiltersEngine.parse(texts.join('\n'), ENGINE_CONFIG);
    const tmp = `${this.cacheFile}.tmp`;
    fs.writeFileSync(tmp, engine.serialize());
    fs.renameSync(tmp, this.cacheFile);
    this.engine = engine;
    this.filtersInfo = { rules: this.#countRules(), updated: new Date().toISOString(), source: 'aggiornati' };
    return this.filtersInfo;
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

  updateSettings(s) { this.settings = s; }

  // ---------- Indurimento della sessione ----------
  harden(userAgent) {
    const ses = this.ses;
    ses.setUserAgent(userAgent);
    ses.setSpellCheckerEnabled(false);
    ses.setPermissionRequestHandler((_wc, perm, cb) => cb(ALLOWED_PERMISSIONS.has(perm)));
    ses.setPermissionCheckHandler((_wc, perm) => ALLOWED_PERMISSIONS.has(perm));
    ses.setDevicePermissionHandler(() => false);
    ses.setDisplayMediaRequestHandler((_req, cb) => cb({}));

    ses.webRequest.onBeforeRequest((d, cb) => cb(this.#beforeRequest(d)));
    ses.webRequest.onBeforeSendHeaders((d, cb) => cb(this.#beforeSendHeaders(d)));
    ses.webRequest.onHeadersReceived((d, cb) => cb(this.#headersReceived(d)));
  }

  #topUrl(d) {
    if (d.resourceType === 'mainFrame') return d.url;
    try { if (d.frame && d.frame.top && d.frame.top.url) return d.frame.top.url; } catch { /* frame chiuso */ }
    try { if (d.webContents) return d.webContents.getURL(); } catch { /* ignorato */ }
    return '';
  }

  #isThirdParty(d) {
    if (d.resourceType === 'mainFrame') return false;
    const top = this.#topUrl(d);
    if (!/^https?:/.test(top)) return false;
    return siteOf(d.url) !== siteOf(top);
  }

  #beforeRequest(d) {
    let u;
    try { u = new URL(d.url); } catch { return {}; }
    const web = u.protocol === 'http:' || u.protocol === 'https:';
    if (!web && u.protocol !== 'ws:' && u.protocol !== 'wss:') return {};

    if (u.protocol === 'http:' && this.settings.httpsOnly && !isPrivateHost(u.hostname)
        && !this.httpAllowed.has(u.hostname)) {
      if (d.resourceType === 'mainFrame' && d.webContentsId) this.upgraded.set(d.webContentsId, d.url);
      u.protocol = 'https:';
      if (u.port === '80') u.port = '';
      return { redirectURL: u.toString() };
    }

    if (this.settings.blockTrackers && this.engine && d.resourceType !== 'mainFrame') {
      const top = this.#topUrl(d);
      const { match } = this.engine.match(Request.fromRawDetails({
        url: d.url,
        sourceUrl: /^https?:/.test(top) ? top : '',
        type: RESOURCE_TYPES[d.resourceType] || 'other',
      }));
      if (match) {
        if (d.webContentsId) this.onBlocked(d.webContentsId);
        return { cancel: true };
      }
    }
    return {};
  }

  #beforeSendHeaders(d) {
    const headers = { ...d.requestHeaders, 'Sec-GPC': '1' };
    if (this.settings.blockThirdPartyCookies && this.#isThirdParty(d)) {
      for (const k of Object.keys(headers)) if (k.toLowerCase() === 'cookie') delete headers[k];
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

module.exports = { Privacy, inputToUrl, isPrivateHost };
