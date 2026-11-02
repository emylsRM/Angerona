'use strict';
// Angerona — processo principale: finestra, schede, scorciatoie, comunicazione con l'interfaccia.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {
  app, BrowserWindow, WebContentsView, ipcMain, Menu, clipboard, session, protocol,
} = require('electron');
const {
  SettingsStore, sanitize, searchUrl, DNS_PROVIDERS, SEARCH_ENGINES, PROXY_MODES,
} = require('./settings');
const { Privacy, inputToUrl } = require('./privacy');
const pages = require('./pages');
const report = require('./report');

const PARTITION = 'angerona-private'; // senza "persist:" la sessione vive solo in memoria
const HOME = 'angerona://start';
const THEME = { strip: '#1C1846', bar: '#26215C', page: '#16132F', text: '#CECBF6' };
const TABSTRIP_HEIGHT = 40;
// Siti indipendenti per verificare la privacy della connessione.
const PRIVACY_TESTS = {
  mullvad: 'https://mullvad.net/it/check',
  eff: 'https://coveryourtracks.eff.org/',
  browserleaks: 'https://browserleaks.com/',
};

app.setName('Angerona');
const settingsDir = app.getPath('userData');

// ---------- Avvio: una sola istanza, dati temporanei cancellati alla chiusura ----------
if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  start();
}

function purgeOldTempDirs() {
  for (const name of fs.readdirSync(os.tmpdir())) {
    if (!name.startsWith('angerona-')) continue;
    try { fs.rmSync(path.join(os.tmpdir(), name), { recursive: true, force: true }); } catch { /* in uso */ }
  }
}

function start() {
  try { purgeOldTempDirs(); } catch { /* ignorato */ }
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'angerona-'));
  app.setPath('userData', tempDir);
  app.setPath('sessionData', tempDir);
  app.on('will-quit', () => {
    try { fs.rmSync(tempDir, { recursive: true, force: true }); } catch { /* verrà pulita al prossimo avvio */ }
  });

  app.commandLine.appendSwitch('force-webrtc-ip-handling-policy', 'disable_non_proxied_udp');
  app.commandLine.appendSwitch('disable-features', 'SpareRendererForSitePerProcess,MediaRouter');
  protocol.registerSchemesAsPrivileged([{ scheme: 'angerona', privileges: { standard: true, secure: true } }]);
  Menu.setApplicationMenu(null);

  app.whenReady().then(init).catch((err) => {
    console.error(err);
    app.exit(1);
  });
  app.on('window-all-closed', () => app.quit());
  app.on('second-instance', () => {
    if (!win) return;
    if (win.isMinimized()) win.restore();
    win.focus();
    createTab(HOME);
  });
  // Rifiuta qualunque certificato non valido (comportamento esplicito).
  app.on('certificate-error', (e, _wc, _url, _err, _cert, cb) => { e.preventDefault(); cb(false); });
  app.on('login', onLogin);
  app.on('web-contents-created', (_e, wc) => {
    wc.on('will-attach-webview', (ev) => ev.preventDefault());
  });
}

let win = null;
let ses = null;
let store = null;
let privacy = null;
const tabs = new Map();
let order = [];
let activeId = null;
let chromeHeight = 84;
let panelOpen = false;
let htmlFullscreen = false;

function userAgent() {
  const major = process.versions.chrome.split('.')[0];
  const platform = process.platform === 'win32' ? 'Windows NT 10.0; Win64; x64'
    : process.platform === 'darwin' ? 'Macintosh; Intel Mac OS X 10_15_7' : 'X11; Linux x86_64';
  return `Mozilla/5.0 (${platform}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${major}.0.0.0 Safari/537.36`;
}

async function init() {
  store = new SettingsStore(settingsDir);
  ses = session.fromPartition(PARTITION);
  privacy = new Privacy({
    ses, settings: store.value, dataDir: settingsDir, onBlocked: countBlocked,
  });
  privacy.harden(userAgent());
  session.defaultSession.setUserAgent(userAgent());
  privacy.loadFilters();
  await privacy.applyNetwork(store.value);

  ses.protocol.handle('angerona', (req) => pages.render(req.url, store.value, privacy));
  ses.on('will-download', onDownload);

  createWindow();
  createTab(HOME);

  if (store.value.blockTrackers && privacy.needsFilterUpdate()) {
    setTimeout(() => {
      privacy.updateFilters().then(() => sendSettings()).catch(() => { /* riprova al prossimo avvio */ });
    }, 4000);
  }
}

// ---------- Finestra ----------
function createWindow() {
  win = new BrowserWindow({
    width: 1280,
    height: 820,
    minWidth: 560,
    minHeight: 420,
    title: 'Angerona',
    icon: path.join(__dirname, '..', 'build', 'icon.png'),
    backgroundColor: THEME.page,
    show: false,
    titleBarStyle: 'hidden',
    titleBarOverlay: { color: THEME.strip, symbolColor: THEME.text, height: TABSTRIP_HEIGHT },
    webPreferences: {
      preload: path.join(__dirname, '..', 'ui', 'preload.js'),
      partition: 'angerona-ui',
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      spellcheck: false,
    },
  });
  const ui = win.webContents;
  ui.on('will-navigate', (e) => e.preventDefault());
  ui.setWindowOpenHandler(() => ({ action: 'deny' }));
  ui.on('before-input-event', (e, input) => { if (handleShortcut(input)) e.preventDefault(); });
  win.loadFile(path.join(__dirname, '..', 'ui', 'index.html'));
  win.once('ready-to-show', () => win.show());
  win.on('resize', layout);
  win.on('enter-full-screen', layout);
  win.on('leave-full-screen', layout);
  win.on('closed', () => { win = null; });
}

function layout() {
  if (!win) return;
  const [width, height] = win.getContentSize();
  const top = htmlFullscreen ? 0 : chromeHeight;
  for (const tab of tabs.values()) {
    tab.view.setBounds({ x: 0, y: top, width, height: Math.max(0, height - top) });
  }
}

// ---------- Schede ----------
function viewPreferences() {
  return {
    partition: PARTITION,
    sandbox: true,
    contextIsolation: true,
    nodeIntegration: false,
    nodeIntegrationInSubFrames: false,
    webSecurity: true,
    allowRunningInsecureContent: false,
    javascript: store.value.javascript,
    spellcheck: false,
    navigateOnDragDrop: false,
    safeDialogs: true,
    enableWebSQL: false,
    autoplayPolicy: 'document-user-activation-required',
  };
}

function createTab(url, { activate = true, after = null } = {}) {
  const view = new WebContentsView({ webPreferences: viewPreferences() });
  view.setBackgroundColor(THEME.page);
  const wc = view.webContents;
  wc.setWebRTCIPHandlingPolicy('disable_non_proxied_udp');
  const tab = {
    id: wc.id, view, title: 'Nuova scheda', url: '', favicon: null, loading: true, blocked: 0,
  };
  tabs.set(tab.id, tab);
  const pos = after !== null ? order.indexOf(after) : -1;
  if (pos >= 0) order.splice(pos + 1, 0, tab.id); else order.push(tab.id);

  wireTab(tab);
  win.contentView.addChildView(view);
  view.setVisible(false);
  layout();
  if (activate) activateTab(tab.id);
  wc.loadURL(url || HOME).catch(() => { /* gestito da did-fail-load */ });
  sendTabs();
  return tab;
}

function wireTab(tab) {
  const wc = tab.view.webContents;
  const update = () => sendTabs();

  wc.setWindowOpenHandler(({ url }) => {
    if (/^(https?|angerona):/.test(url)) createTab(url, { after: tab.id });
    return { action: 'deny' };
  });
  wc.on('will-navigate', (e) => onWillNavigate(e, tab));
  wc.on('page-title-updated', (_e, title) => { tab.title = title || tab.url; update(); });
  wc.on('page-favicon-updated', (_e, icons) => fetchFavicon(tab, icons && icons[0]));
  wc.on('did-start-loading', () => { tab.loading = true; update(); });
  wc.on('did-stop-loading', () => { tab.loading = false; update(); });
  wc.on('did-start-navigation', (d) => {
    if (!d.isMainFrame || d.isSameDocument) return;
    tab.blocked = 0;
    tab.favicon = null;
    // Sfondo scuro solo per le pagine interne; i siti senza sfondo restano bianchi e leggibili.
    tab.view.setBackgroundColor(d.url.startsWith('angerona:') ? THEME.page : '#FFFFFF');
    update();
  });
  wc.on('did-navigate', (_e, url) => {
    tab.url = url;
    if (/^https:/.test(url)) privacy.upgraded.delete(wc.id);
    update();
  });
  wc.on('did-navigate-in-page', (_e, url, isMainFrame) => { if (isMainFrame) { tab.url = url; update(); } });
  wc.on('did-fail-load', (_e, code, _desc, url, isMainFrame) => {
    if (!isMainFrame || code === -3) return; // -3: navigazione annullata
    const http = privacy.upgraded.get(wc.id);
    const wasUpgraded = Boolean(http) && http.replace(/^http:/, 'https:').split('#')[0] === url.split('#')[0];
    const q = new URLSearchParams({ code: String(code), url, http: wasUpgraded ? '1' : '0' });
    tab.url = url;
    wc.loadURL(`angerona://error?${q}`).catch(() => {});
  });
  wc.on('render-process-gone', (_e, details) => {
    if (details.reason === 'clean-exit') return;
    const q = new URLSearchParams({ url: tab.url });
    setTimeout(() => wc.loadURL(`angerona://crash?${q}`).catch(() => {}), 50);
  });
  wc.on('found-in-page', (_e, r) => { if (tab.id === activeId) sendUi('find', { active: r.activeMatchOrdinal, total: r.matches }); });
  wc.on('context-menu', (_e, p) => contextMenu(tab, p));
  wc.on('before-input-event', (e, input) => { if (handleShortcut(input)) e.preventDefault(); });
  wc.on('enter-html-full-screen', () => { htmlFullscreen = true; sendUi('fullscreen', true); layout(); });
  wc.on('leave-html-full-screen', () => { htmlFullscreen = false; sendUi('fullscreen', false); layout(); });
}

function onWillNavigate(e, tab) {
  const wc = tab.view.webContents;
  let target;
  try { target = new URL(e.url); } catch { e.preventDefault(); return; }
  const fromInternal = wc.getURL().startsWith('angerona://');
  if (target.protocol === 'angerona:') {
    e.preventDefault();
    if (!fromInternal) return; // le pagine web non possono aprire pagine interne
    if (target.hostname === 'allow-http') {
      const httpUrl = target.searchParams.get('url') || '';
      if (/^http:\/\//.test(httpUrl)) {
        privacy.httpAllowed.add(new URL(httpUrl).hostname);
        wc.loadURL(httpUrl).catch(() => {});
      }
    } else {
      wc.loadURL(e.url).catch(() => {});
    }
    return;
  }
  if (!['http:', 'https:'].includes(target.protocol)) e.preventDefault(); // niente mailto:, file:, app esterne
}

function activateTab(id) {
  const next = tabs.get(id);
  if (!next) return;
  const prev = tabs.get(activeId);
  if (prev && prev !== next) prev.view.setVisible(false);
  activeId = id;
  next.view.setVisible(!panelOpen);
  if (!panelOpen) next.view.webContents.focus();
  sendUi('find', null);
  sendTabs();
}

function closeTab(id) {
  const tab = tabs.get(id);
  if (!tab) return;
  const idx = order.indexOf(id);
  order = order.filter((t) => t !== id);
  tabs.delete(id);
  privacy.upgraded.delete(id);
  if (win) win.contentView.removeChildView(tab.view);
  tab.view.webContents.close();
  if (order.length === 0) {
    createTab(HOME);
  } else if (activeId === id) {
    activateTab(order[Math.min(idx, order.length - 1)]);
  }
  sendTabs();
}

function activeWc() {
  const t = tabs.get(activeId);
  return t ? t.view.webContents : null;
}

function cycleTab(delta) {
  if (order.length < 2) return;
  const i = order.indexOf(activeId);
  activateTab(order[(i + delta + order.length) % order.length]);
}

function navigate(input) {
  const url = inputToUrl(input, (q) => searchUrl(store.value, q));
  const wc = activeWc();
  if (!url || !wc) return;
  wc.loadURL(url).catch(() => {});
  wc.focus();
}

function countBlocked(wcId) {
  const tab = tabs.get(wcId);
  if (!tab) return;
  tab.blocked += 1;
  sendTabs();
}

// Indirizzo da mostrare nella barra: per le pagine di errore, quello del sito che ha fallito.
function displayUrl(url) {
  if (url.startsWith(HOME)) return '';
  if (/^angerona:\/\/(error|crash)/.test(url)) {
    try { return new URL(url).searchParams.get('url') || ''; } catch { return ''; }
  }
  return url;
}

async function fetchFavicon(tab, url) {
  if (!url || !/^(https?|data):/.test(url)) return;
  try {
    let dataUrl = url;
    if (!url.startsWith('data:')) {
      const res = await ses.fetch(url, { credentials: 'omit', signal: AbortSignal.timeout(8000) });
      if (!res.ok) return;
      const type = (res.headers.get('content-type') || '').split(';')[0].trim();
      const buf = Buffer.from(await res.arrayBuffer());
      if (buf.length > 200 * 1024) return;
      const mime = type.startsWith('image/') ? type : 'image/x-icon';
      dataUrl = `data:${mime};base64,${buf.toString('base64')}`;
    } else if (!/^data:image\//.test(url) || url.length > 300000) {
      return;
    }
    if (tabs.has(tab.id)) { tab.favicon = dataUrl; sendTabs(); }
  } catch { /* nessuna icona */ }
}

// ---------- Menu contestuale ----------
function contextMenu(tab, p) {
  const wc = tab.view.webContents;
  const sep = { type: 'separator' };
  const t = [];
  if (p.linkURL && /^https?:/.test(p.linkURL)) {
    t.push(
      { label: 'Apri link in una nuova scheda', click: () => createTab(p.linkURL, { activate: false, after: tab.id }) },
      { label: 'Copia indirizzo del link', click: () => clipboard.writeText(p.linkURL) },
      sep,
    );
  }
  if (p.mediaType === 'image' && p.srcURL) {
    t.push(
      { label: 'Apri immagine in una nuova scheda', click: () => createTab(p.srcURL, { after: tab.id }) },
      { label: 'Copia immagine', click: () => wc.copyImageAt(p.x, p.y) },
      { label: 'Salva immagine con nome…', click: () => wc.downloadURL(p.srcURL) },
      sep,
    );
  }
  if (p.isEditable) {
    t.push(
      { label: 'Annulla', role: 'undo' }, { label: 'Ripeti', role: 'redo' }, sep,
      { label: 'Taglia', role: 'cut' }, { label: 'Copia', role: 'copy' }, { label: 'Incolla', role: 'paste' },
      { label: 'Seleziona tutto', role: 'selectAll' },
    );
  } else if (p.selectionText && p.selectionText.trim()) {
    const q = p.selectionText.trim();
    const short = q.length > 28 ? `${q.slice(0, 28)}…` : q;
    t.push(
      { label: 'Copia', role: 'copy' },
      { label: `Cerca «${short}»`, click: () => createTab(searchUrl(store.value, q), { after: tab.id }) },
    );
  } else if (!t.length) {
    const h = wc.navigationHistory;
    t.push(
      { label: 'Indietro', enabled: h.canGoBack(), click: () => h.goBack() },
      { label: 'Avanti', enabled: h.canGoForward(), click: () => h.goForward() },
      { label: 'Ricarica', click: () => wc.reload() },
      sep,
      { label: 'Seleziona tutto', role: 'selectAll' },
    );
  }
  if (t.length && t[t.length - 1].type === 'separator') t.pop();
  t.push(sep, { label: 'Ispeziona elemento', click: () => wc.inspectElement(p.x, p.y) });
  Menu.buildFromTemplate(t).popup({ window: win });
}

// ---------- Scorciatoie da tastiera ----------
function handleShortcut(input) {
  if (input.type !== 'keyDown') return false;
  const ctrl = input.control || input.meta;
  const key = input.key.length === 1 ? input.key.toLowerCase() : input.key;
  const wc = activeWc();
  const h = wc && wc.navigationHistory;

  if (ctrl && !input.shift && key === 't') { createTab(HOME); sendUi('focus-address'); return true; }
  if (ctrl && key === 'w') { closeTab(activeId); return true; }
  if ((ctrl && key === 'l') || key === 'F6' || (input.alt && key === 'd')) { sendUi('focus-address'); return true; }
  if (ctrl && (key === 'Tab' || key === 'PageDown' || key === 'PageUp')) {
    cycleTab(key === 'PageUp' || (key === 'Tab' && input.shift) ? -1 : 1); return true;
  }
  if (ctrl && /^[1-9]$/.test(key)) {
    const id = key === '9' ? order[order.length - 1] : order[Number(key) - 1];
    if (id) activateTab(id);
    return true;
  }
  if (!wc) return false;
  if ((ctrl && key === 'r') || key === 'F5') {
    if (input.shift || ctrl && key === 'F5') wc.reloadIgnoringCache(); else wc.reload();
    return true;
  }
  if (input.alt && key === 'ArrowLeft') { if (h.canGoBack()) h.goBack(); return true; }
  if (input.alt && key === 'ArrowRight') { if (h.canGoForward()) h.goForward(); return true; }
  if ((ctrl && key === 'f') || key === 'F3') { sendUi('open-find'); return true; }
  if (ctrl && (key === '+' || key === '=')) { wc.setZoomLevel(Math.min(wc.getZoomLevel() + 0.5, 5)); return true; }
  if (ctrl && key === '-') { wc.setZoomLevel(Math.max(wc.getZoomLevel() - 0.5, -4)); return true; }
  if (ctrl && key === '0') { wc.setZoomLevel(0); return true; }
  if (key === 'F11') { win.setFullScreen(!win.isFullScreen()); return true; }
  if (ctrl && key === ',') { sendUi('open-panel'); return true; }
  return false;
}

// ---------- Download ----------
function onDownload(_e, item) {
  const name = item.getFilename();
  item.setSaveDialogOptions({
    title: 'Salva file',
    defaultPath: path.join(app.getPath('downloads'), name),
  });
  sendUi('toast', `Download di ${name}…`);
  item.once('done', (_ev, state) => {
    if (state === 'completed') sendUi('toast', `Scaricato: ${path.basename(item.getSavePath())}`);
    else if (state === 'interrupted') sendUi('toast', `Download non riuscito: ${name}`);
  });
}

// ---------- Autenticazione proxy ----------
function onLogin(e, _wc, _details, authInfo, cb) {
  const p = store && store.value.proxy;
  if (authInfo.isProxy && p && p.user) {
    e.preventDefault();
    cb(p.user, p.pass);
  }
}

// ---------- Comunicazione con l'interfaccia ----------
function sendUi(channel, data) {
  if (win && !win.isDestroyed()) win.webContents.send(`ui:${channel}`, data);
}

let tabsPending = false;
function sendTabs() {
  if (tabsPending) return;
  tabsPending = true;
  setTimeout(() => {
    tabsPending = false;
    if (!win || win.isDestroyed()) return;
    const list = order.map((id) => {
      const t = tabs.get(id);
      const h = t.view.webContents.navigationHistory;
      const url = t.view.webContents.getURL() || t.url;
      const shown = displayUrl(url);
      const errorPage = /^angerona:\/\/(error|crash)/.test(url);
      return {
        id,
        title: t.title,
        url: shown,
        favicon: t.favicon,
        loading: t.loading,
        active: id === activeId,
        canBack: h.canGoBack(),
        canForward: h.canGoForward(),
        blocked: t.blocked,
        internal: url.startsWith('angerona:') && !errorPage,
        error: errorPage,
        secure: url.startsWith('https:') || (url.startsWith('angerona:') && !errorPage),
        zoom: Math.round(100 * 1.2 ** t.view.webContents.getZoomLevel()),
      };
    });
    win.webContents.send('ui:tabs', list);
  }, 30);
}

function settingsPayload() {
  return {
    settings: store.value,
    dnsProviders: DNS_PROVIDERS.map(({ id, label }) => ({ id, label })),
    searchEngines: SEARCH_ENGINES.map(({ id, label }) => ({ id, label })),
    proxyModes: PROXY_MODES,
    filters: privacy.filtersInfo,
    report: report.evaluate(store.value),
    version: app.getVersion(),
    engine: `Chromium ${process.versions.chrome}`,
  };
}
function sendSettings() { sendUi('settings', settingsPayload()); }

function guard(handler) {
  return (event, ...args) => {
    if (!win || event.sender !== win.webContents) throw new Error('Richiesta non autorizzata');
    return handler(...args);
  };
}

ipcMain.handle('tab:new', guard(() => { createTab(HOME); }));
ipcMain.handle('tab:close', guard((id) => closeTab(Number(id))));
ipcMain.handle('tab:activate', guard((id) => activateTab(Number(id))));
ipcMain.handle('tab:navigate', guard((input) => navigate(String(input || '').slice(0, 8000))));
ipcMain.handle('tab:back', guard(() => { const h = activeWc()?.navigationHistory; if (h && h.canGoBack()) h.goBack(); }));
ipcMain.handle('tab:forward', guard(() => { const h = activeWc()?.navigationHistory; if (h && h.canGoForward()) h.goForward(); }));
ipcMain.handle('tab:reload', guard(() => activeWc()?.reload()));
ipcMain.handle('tab:stop', guard(() => activeWc()?.stop()));
ipcMain.handle('tab:home', guard(() => activeWc()?.loadURL(HOME).catch(() => {})));
ipcMain.handle('tab:zoom-reset', guard(() => activeWc()?.setZoomLevel(0)));
ipcMain.handle('ui:chrome-height', guard((h) => {
  const n = Math.round(Number(h));
  if (Number.isFinite(n) && n >= 40 && n <= 200 && n !== chromeHeight) { chromeHeight = n; layout(); }
}));
ipcMain.handle('ui:panel', guard((open) => {
  panelOpen = Boolean(open);
  const t = tabs.get(activeId);
  if (t) { t.view.setVisible(!panelOpen); if (!panelOpen) t.view.webContents.focus(); }
}));
ipcMain.handle('find:start', guard((opts) => {
  const wc = activeWc();
  const text = String(opts && opts.text || '').slice(0, 500);
  if (!wc) return;
  if (!text) { wc.stopFindInPage('clearSelection'); sendUi('find', { active: 0, total: 0 }); return; }
  wc.findInPage(text, { forward: opts.forward !== false, findNext: Boolean(opts.next) });
}));
ipcMain.handle('find:stop', guard(() => activeWc()?.stopFindInPage('clearSelection')));
ipcMain.handle('settings:get', guard(() => settingsPayload()));
ipcMain.handle('settings:set', guard(async (input) => {
  const prev = store.value;
  const { settings, errors } = sanitize(input, prev);
  if (Object.keys(errors).length) return { ok: false, errors };
  store.save(settings);
  privacy.updateSettings(settings);
  await privacy.applyNetwork(settings);
  if (settings.blockTrackers && !privacy.engine) privacy.updateFilters().then(sendSettings).catch(() => {});
  const jsChanged = prev.javascript !== settings.javascript;
  for (const t of tabs.values()) {
    if (t.view.webContents.getURL().startsWith(HOME)) t.view.webContents.reload();
  }
  return { ok: true, payload: settingsPayload(), jsChanged };
}));
ipcMain.handle('privacy:evaluate', guard((input) => report.evaluate(sanitize(input, store.value).settings)));
ipcMain.handle('tab:open-test', guard((id) => {
  const url = PRIVACY_TESTS[String(id)];
  if (url) createTab(url);
}));
ipcMain.handle('net:check', guard(async () => {
  try {
    const res = await ses.fetch('https://am.i.mullvad.net/json', {
      credentials: 'omit', cache: 'no-store', signal: AbortSignal.timeout(12000),
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const j = await res.json();
    return {
      ok: true,
      ip: String(j.ip || ''),
      country: String(j.country || ''),
      city: String(j.city || ''),
      org: String(j.organization || ''),
      mullvad: Boolean(j.mullvad_exit_ip),
    };
  } catch (err) {
    return { ok: false, error: String(err && err.message || err) };
  }
}));
ipcMain.handle('filters:update', guard(async () => {
  try { return { ok: true, filters: await privacy.updateFilters() }; } catch (err) { return { ok: false, error: err.message }; }
}));
ipcMain.handle('data:clear', guard(async () => {
  await ses.clearStorageData();
  await ses.clearCache();
  await ses.clearAuthCache();
  await ses.clearHostResolverCache();
  privacy.httpAllowed.clear();
  for (const id of [...order]) closeTab(id);
  return true;
}));
