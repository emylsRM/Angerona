'use strict';
// Impostazioni di Angerona: valori predefiniti, validazione e salvataggio su disco.
const fs = require('node:fs');
const path = require('node:path');
const { safeStorage } = require('electron');

const DNS_PROVIDERS = [
  { id: 'quad9', label: 'Quad9 — blocco malware, Svizzera', url: 'https://dns.quad9.net/dns-query' },
  { id: 'cloudflare', label: 'Cloudflare 1.1.1.1', url: 'https://cloudflare-dns.com/dns-query' },
  { id: 'cloudflare-security', label: 'Cloudflare — blocco malware', url: 'https://security.cloudflare-dns.com/dns-query' },
  { id: 'mullvad', label: 'Mullvad DNS', url: 'https://dns.mullvad.net/dns-query' },
  { id: 'mullvad-adblock', label: 'Mullvad DNS — blocco pubblicità', url: 'https://adblock.dns.mullvad.net/dns-query' },
  { id: 'adguard', label: 'AdGuard DNS — blocco pubblicità', url: 'https://dns.adguard-dns.com/dns-query' },
  { id: 'nextdns', label: 'NextDNS — con il tuo ID', url: null },
  { id: 'custom', label: 'Personalizzato (indirizzo DoH)', url: null },
  { id: 'system', label: 'DNS di sistema o della VPN (non cifrato dal browser)', url: null },
];

const SEARCH_ENGINES = [
  { id: 'duckduckgo', label: 'DuckDuckGo', url: 'https://duckduckgo.com/?q=%s' },
  { id: 'startpage', label: 'Startpage', url: 'https://www.startpage.com/sp/search?query=%s' },
  { id: 'brave', label: 'Brave Search', url: 'https://search.brave.com/search?q=%s' },
  { id: 'mojeek', label: 'Mojeek', url: 'https://www.mojeek.com/search?q=%s' },
  { id: 'qwant', label: 'Qwant', url: 'https://www.qwant.com/?q=%s' },
  { id: 'searxng', label: 'SearXNG — la tua istanza', url: null },
];

const PROXY_MODES = [
  { id: 'none', label: 'Nessuno (connessione diretta o VPN di sistema)' },
  { id: 'socks5', label: 'SOCKS5' },
  { id: 'http', label: 'HTTP' },
  { id: 'https', label: 'HTTPS' },
];

const DEFAULTS = Object.freeze({
  dns: 'quad9',
  nextdnsId: '',
  customDoh: '',
  proxy: { mode: 'none', host: '', port: '', user: '', pass: '' },
  search: 'duckduckgo',
  searxngUrl: '',
  httpsOnly: true,
  blockTrackers: true,
  blockThirdPartyCookies: true,
  javascript: true,
  checkUpdates: true,
});

const clone = (o) => JSON.parse(JSON.stringify(o));
const str = (v, max = 300) => (typeof v === 'string' ? v.trim().slice(0, max) : '');
const bool = (v, d) => (typeof v === 'boolean' ? v : d);

function isHttpsUrl(v) {
  try { return new URL(v).protocol === 'https:'; } catch { return false; }
}
function isWebUrl(v) {
  try { return ['https:', 'http:'].includes(new URL(v).protocol); } catch { return false; }
}

// Controlla e ripulisce un oggetto impostazioni. Restituisce { settings, errors }.
function sanitize(input, previous = DEFAULTS) {
  const s = clone(previous);
  const errors = {};
  const i = input && typeof input === 'object' ? input : {};

  if (DNS_PROVIDERS.some((p) => p.id === i.dns)) s.dns = i.dns;
  if ('nextdnsId' in i) s.nextdnsId = str(i.nextdnsId, 64);
  if ('customDoh' in i) s.customDoh = str(i.customDoh, 500);
  if (s.dns === 'nextdns' && !/^[a-z0-9]{4,20}$/i.test(s.nextdnsId)) {
    errors.nextdnsId = 'Inserisci il tuo ID NextDNS (lo trovi su my.nextdns.io, es. a1b2c3).';
  }
  if (s.dns === 'custom' && !isHttpsUrl(s.customDoh)) {
    errors.customDoh = "Serve un indirizzo che inizi con https://, es. https://dns.example/dns-query";
  }

  if (i.proxy && typeof i.proxy === 'object') {
    const p = i.proxy;
    if (PROXY_MODES.some((m) => m.id === p.mode)) s.proxy.mode = p.mode;
    if ('host' in p) s.proxy.host = str(p.host, 255).replace(/^\[|\]$/g, '');
    if ('port' in p) s.proxy.port = str(String(p.port ?? ''), 5);
    if ('user' in p) s.proxy.user = str(p.user, 200);
    if ('pass' in p) s.proxy.pass = typeof p.pass === 'string' ? p.pass.slice(0, 200) : '';
  }
  if (s.proxy.mode !== 'none') {
    if (!/^[a-z0-9.\-:]+$/i.test(s.proxy.host)) errors.proxyHost = 'Inserisci un indirizzo valido, es. 127.0.0.1 o proxy.example.com';
    const port = Number(s.proxy.port);
    if (!Number.isInteger(port) || port < 1 || port > 65535) errors.proxyPort = 'La porta va da 1 a 65535.';
  }

  if (SEARCH_ENGINES.some((e) => e.id === i.search)) s.search = i.search;
  if ('searxngUrl' in i) s.searxngUrl = str(i.searxngUrl, 300).replace(/\/+$/, '');
  if (s.search === 'searxng' && !isWebUrl(s.searxngUrl)) {
    errors.searxngUrl = "Inserisci l'indirizzo della tua istanza, es. https://searx.example.org";
  }

  s.httpsOnly = bool(i.httpsOnly, s.httpsOnly);
  s.blockTrackers = bool(i.blockTrackers, s.blockTrackers);
  s.blockThirdPartyCookies = bool(i.blockThirdPartyCookies, s.blockThirdPartyCookies);
  s.javascript = bool(i.javascript, s.javascript);
  s.checkUpdates = bool(i.checkUpdates, s.checkUpdates);
  return { settings: s, errors };
}

function dohTemplate(s) {
  if (s.dns === 'system') return null;
  if (s.dns === 'nextdns') return `https://dns.nextdns.io/${s.nextdnsId}`;
  if (s.dns === 'custom') return s.customDoh;
  return DNS_PROVIDERS.find((p) => p.id === s.dns).url;
}

function searchUrl(s, query) {
  const q = encodeURIComponent(query);
  if (s.search === 'searxng') return `${s.searxngUrl}/search?q=${q}`;
  return SEARCH_ENGINES.find((e) => e.id === s.search).url.replace('%s', q);
}

class SettingsStore {
  constructor(dir) {
    this.file = path.join(dir, 'settings.json');
    fs.mkdirSync(dir, { recursive: true });
    this.value = this.#load();
  }

  #load() {
    try {
      const raw = JSON.parse(fs.readFileSync(this.file, 'utf8'));
      if (raw.proxy && raw.proxy.passEnc) {
        try {
          raw.proxy.pass = safeStorage.decryptString(Buffer.from(raw.proxy.passEnc, 'base64'));
        } catch { raw.proxy.pass = ''; }
      }
      return sanitize(raw, DEFAULTS).settings;
    } catch {
      return clone(DEFAULTS);
    }
  }

  save(next) {
    this.value = next;
    const out = clone(next);
    const pass = out.proxy.pass;
    delete out.proxy.pass;
    // La password del proxy viene salvata solo se il sistema può cifrarla (su Windows sempre).
    if (pass && safeStorage.isEncryptionAvailable()) {
      out.proxy.passEnc = safeStorage.encryptString(pass).toString('base64');
    }
    const tmp = `${this.file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(out, null, 2), { mode: 0o600 });
    fs.renameSync(tmp, this.file);
  }
}

module.exports = {
  DNS_PROVIDERS, SEARCH_ENGINES, PROXY_MODES, DEFAULTS,
  sanitize, dohTemplate, searchUrl, SettingsStore,
};
