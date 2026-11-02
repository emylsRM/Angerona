'use strict';
// Pagine interne angerona:// (pagina iniziale ed errori). Nessuno script, CSP rigida.
const fs = require('node:fs');
const path = require('node:path');
const { DNS_PROVIDERS, SEARCH_ENGINES, PROXY_MODES } = require('./settings');

const ICON = `data:image/svg+xml;base64,${fs.readFileSync(path.join(__dirname, '..', 'build', 'icon.svg')).toString('base64')}`;
const CSP = "default-src 'none'; style-src 'unsafe-inline'; img-src data:; form-action https: http:; base-uri 'none'";

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const STYLE = `
:root { color-scheme: dark; }
* { box-sizing: border-box; }
body { margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;
  background: #16132F; color: #EEEDFE; font: 15px/1.6 "Segoe UI Variable Text", "Segoe UI", system-ui, sans-serif; }
main { width: min(620px, 92vw); text-align: center; padding: 48px 0; }
h1 { font-weight: 500; font-size: 26px; margin: 18px 0 6px; letter-spacing: 0.02em; }
p { color: #CECBF6; margin: 0 0 8px; }
.sub { color: #AFA9EC; font-size: 13px; }
form { display: flex; margin: 28px 0 22px; background: #26215C; border: 1px solid #3C3489; border-radius: 14px; overflow: hidden; }
form:focus-within { border-color: #7F77DD; }
input { flex: 1; min-width: 0; background: transparent; border: 0; outline: 0; color: #EEEDFE; font: inherit; font-size: 16px; padding: 14px 18px; }
input::placeholder { color: #8b85d4; }
button, .btn { background: #534AB7; color: #EEEDFE; border: 0; font: inherit; padding: 0 20px; cursor: pointer; text-decoration: none; }
button:hover, .btn:hover { background: #7F77DD; }
.chips { display: flex; flex-wrap: wrap; gap: 8px; justify-content: center; }
.chip { background: #26215C; border: 1px solid #3C3489; border-radius: 999px; padding: 4px 12px; font-size: 12.5px; color: #CECBF6; }
.chip b { font-weight: 500; color: #EEEDFE; }
.warn { border-color: #993C1D; }
.actions { display: flex; gap: 10px; justify-content: center; margin-top: 24px; flex-wrap: wrap; }
.actions .btn { padding: 10px 18px; border-radius: 10px; display: inline-block; }
.actions .ghost { background: transparent; border: 1px solid #3C3489; }
.url { font-family: ui-monospace, Consolas, monospace; font-size: 12.5px; color: #AFA9EC; word-break: break-all; margin-top: 10px; }
`;

function page(title, body) {
  return `<!doctype html><html lang="it"><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="${CSP}"><meta name="viewport" content="width=device-width"><title>${esc(title)}</title><style>${STYLE}</style></head><body><main>${body}</main></body></html>`;
}

function startPage(s, privacy) {
  const engine = SEARCH_ENGINES.find((e) => e.id === s.search);
  let action;
  let param;
  const hidden = [];
  if (s.search === 'searxng') {
    action = `${s.searxngUrl}/search`;
    param = 'q';
  } else {
    const u = new URL(engine.url.replace('%s', '__Q__'));
    action = `${u.origin}${u.pathname}`;
    for (const [k, v] of u.searchParams) {
      if (v === '__Q__') param = k; else hidden.push(`<input type="hidden" name="${esc(k)}" value="${esc(v)}">`);
    }
  }
  const dns = DNS_PROVIDERS.find((p) => p.id === s.dns);
  const dnsLabel = s.dns === 'system' ? 'di sistema' : `${dns.label.split(' — ')[0]}, cifrato`;
  const proxy = s.proxy.mode === 'none' ? 'nessuno' : `${PROXY_MODES.find((m) => m.id === s.proxy.mode).label} attivo`;
  const rules = privacy.filtersInfo.rules ? `${privacy.filtersInfo.rules.toLocaleString('it-IT')} regole` : 'liste in arrivo';
  return page('Nuova scheda', `
    <img src="${ICON}" width="88" height="88" alt="">
    <h1>Angerona</h1>
    <p class="sub">Navigazione privata. Alla chiusura non resta nulla.</p>
    <form method="get" action="${esc(action)}" autocomplete="off">
      ${hidden.join('')}
      <input name="${esc(param)}" placeholder="Cerca con ${esc(engine.label.split(' — ')[0])}" autofocus aria-label="Cerca">
      <button type="submit">Cerca</button>
    </form>
    <div class="chips">
      <span class="chip ${s.dns === 'system' ? 'warn' : ''}">DNS <b>${esc(dnsLabel)}</b></span>
      <span class="chip">Proxy <b>${esc(proxy)}</b></span>
      <span class="chip">Tracker <b>${s.blockTrackers ? esc(rules) : 'non bloccati'}</b></span>
      <span class="chip">HTTPS <b>${s.httpsOnly ? 'obbligatorio' : 'facoltativo'}</b></span>
    </div>`);
}

const ERRORS = {
  '-105': ['Indirizzo non trovato', 'Il DNS non conosce questo sito. Controlla di averlo scritto bene.'],
  '-137': ['Il DNS sicuro non risponde', 'Il server DNS cifrato scelto non è raggiungibile. Prova un altro DNS nelle impostazioni.'],
  '-106': ['Nessuna connessione', 'Il computer non è collegato a Internet.'],
  '-130': ['Il proxy non risponde', 'Per non esporre il tuo indirizzo, Angerona non si collega senza proxy. Controlla proxy o VPN.'],
  '-111': ['Il proxy non risponde', 'Per non esporre il tuo indirizzo, Angerona non si collega senza proxy. Controlla proxy o VPN.'],
  '-127': ['Il proxy chiede di accedere', 'Controlla nome utente e password del proxy nelle impostazioni.'],
  '-102': ['Connessione rifiutata', 'Il sito ha rifiutato la connessione.'],
  '-118': ['Tempo scaduto', 'Il sito non ha risposto in tempo.'],
  '-101': ['Connessione interrotta', 'La connessione si è chiusa all’improvviso.'],
  '-109': ['Sito irraggiungibile', 'Non è stato possibile raggiungere il sito.'],
  '-21': ['La rete è cambiata', 'La connessione di rete è cambiata durante il caricamento.'],
  '-20': ['Bloccato', 'Questa pagina è stata bloccata dalle protezioni di Angerona.'],
  '-201': ['Certificato scaduto', 'Il certificato di sicurezza del sito non è valido. La connessione è stata fermata.'],
};

function errorPage(params) {
  const code = String(params.get('code') || '');
  const url = params.get('url') || '';
  const upgraded = params.get('http') === '1';
  let [title, text] = ERRORS[code] || [];
  const n = Number(code);
  if (!title && n <= -200 && n > -300) [title, text] = ['Connessione non sicura', 'Il certificato del sito non è valido. Angerona ha fermato la connessione per proteggerti.'];
  if (!title) [title, text] = ['Pagina non disponibile', `Il caricamento non è riuscito (errore ${esc(code)}).`];
  if (upgraded && n > -200) text = 'Il sito non risponde in HTTPS. Puoi aprirlo in HTTP solo se ti fidi: i dati viaggeranno in chiaro.';
  const safeUrl = /^https?:\/\//.test(url) ? url : '';
  const httpUrl = safeUrl.replace(/^https:/, 'http:');
  return page(title, `
    <img src="${ICON}" width="64" height="64" alt="">
    <h1>${esc(title)}</h1>
    <p>${text}</p>
    ${safeUrl ? `<div class="url">${esc(safeUrl)}</div>` : ''}
    <div class="actions">
      ${safeUrl ? `<a class="btn" href="${esc(safeUrl)}">Riprova</a>` : ''}
      ${upgraded && safeUrl ? `<a class="btn ghost" href="angerona://allow-http?url=${encodeURIComponent(httpUrl)}">Apri in HTTP (non protetto)</a>` : ''}
    </div>`);
}

function crashPage(params) {
  const url = params.get('url') || '';
  const safeUrl = /^https?:\/\//.test(url) ? url : '';
  return page('Scheda interrotta', `
    <img src="${ICON}" width="64" height="64" alt="">
    <h1>La scheda si è chiusa</h1>
    <p>La pagina ha smesso di funzionare. Il resto del browser non è stato toccato.</p>
    <div class="actions">${safeUrl ? `<a class="btn" href="${esc(safeUrl)}">Ricarica</a>` : ''}</div>`);
}

function render(url, s, privacy) {
  const u = new URL(url);
  const body = u.hostname === 'start' ? startPage(s, privacy)
    : u.hostname === 'error' ? errorPage(u.searchParams)
      : u.hostname === 'crash' ? crashPage(u.searchParams)
        : null;
  if (!body) return new Response('Non trovato', { status: 404 });
  return new Response(body, {
    headers: {
      'content-type': 'text/html; charset=utf-8',
      'content-security-policy': CSP,
      'x-content-type-options': 'nosniff',
      'cache-control': 'no-store',
    },
  });
}

module.exports = { render };
