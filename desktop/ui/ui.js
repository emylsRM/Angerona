'use strict';
// Interfaccia di Angerona: schede, barra indirizzi, ricerca nella pagina, impostazioni.
(() => {
  const api = window.angerona;
  const $ = (sel) => document.querySelector(sel);

  const ICON = {
    plus: '<path d="M12 5v14M5 12h14"/>',
    x: '<path d="M18 6 6 18M6 6l12 12"/>',
    back: '<path d="M5 12h14M5 12l6 6M5 12l6-6"/>',
    forward: '<path d="M5 12h14M13 18l6-6M13 6l6 6"/>',
    reload: '<path d="M20 11a8.1 8.1 0 0 0-15.5-2M4 5v4h4"/><path d="M4 13a8.1 8.1 0 0 0 15.5 2M20 19v-4h-4"/>',
    home: '<path d="M5 12H3l9-9 9 9h-2"/><path d="M5 12v7a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-7"/><path d="M10 21v-5h4v5"/>',
    lock: '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 1 1 8 0v4"/>',
    alert: '<path d="M12 9v4M12 17h.01"/><path d="M10.24 3.96 2.39 17.08a2 2 0 0 0 1.7 3h15.82a2 2 0 0 0 1.7-3L13.76 3.96a2 2 0 0 0-3.52 0z"/>',
    shield: '<path d="M12 3a12 12 0 0 0 8.5 3A12 12 0 0 1 12 21 12 12 0 0 1 3.5 6 12 12 0 0 0 12 3"/>',
    shieldCheck: '<path d="M12 3a12 12 0 0 0 8.5 3A12 12 0 0 1 12 21 12 12 0 0 1 3.5 6 12 12 0 0 0 12 3"/><path d="m9 12 2 2 4-4"/>',
    settings: '<circle cx="14" cy="6" r="2"/><path d="M4 6h8M16 6h4"/><circle cx="8" cy="12" r="2"/><path d="M4 12h2M10 12h10"/><circle cx="17" cy="18" r="2"/><path d="M4 18h11M19 18h1"/>',
    up: '<path d="m6 15 6-6 6 6"/>',
    down: '<path d="m6 9 6 6 6-6"/>',
    globe: '<circle cx="12" cy="12" r="9"/><path d="M3.6 9h16.8M3.6 15h16.8M11.5 3a17 17 0 0 0 0 18M12.5 3a17 17 0 0 1 0 18"/>',
    search: '<circle cx="10" cy="10" r="7"/><path d="m21 21-6-6"/>',
  };
  const svg = (name, size = 18) => `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICON[name]}</svg>`;

  const setIcon = (sel, name, size) => { $(sel).innerHTML = svg(name, size); };
  setIcon('#new-tab', 'plus');
  setIcon('#back', 'back');
  setIcon('#forward', 'forward');
  setIcon('#reload', 'reload');
  setIcon('#home', 'home');
  setIcon('#open-settings', 'settings');
  setIcon('#find-prev', 'up');
  setIcon('#find-next', 'down');
  setIcon('#find-close', 'x', 16);
  setIcon('#close-settings', 'x', 20);

  let tabs = [];
  let active = null;
  let lastActiveId = null;
  let payload = null;

  // ---------- Altezza dell'interfaccia: il contenuto web parte da qui ----------
  const chrome = $('#chrome');
  new ResizeObserver(() => {
    const h = chrome.offsetHeight;
    $('#panel').style.top = `${h}px`;
    api.invoke('ui:chrome-height', h);
  }).observe(chrome);

  // ---------- Avvisi brevi ----------
  let noticeTimer = null;
  function notice(text) {
    const el = $('#notice');
    el.textContent = text;
    el.classList.add('show');
    clearTimeout(noticeTimer);
    noticeTimer = setTimeout(() => el.classList.remove('show'), 3800);
  }
  api.on('toast', (t) => notice(String(t || '')));

  // ---------- Schede ----------
  function tabIcon(t) {
    if (t.loading) return '<span class="spinner" aria-hidden="true"></span>';
    if (t.favicon) {
      const img = document.createElement('img');
      img.className = 'fav';
      img.alt = '';
      img.src = t.favicon;
      return img;
    }
    if (t.internal) return '<img class="fav" src="icon.png" alt="">';
    return `<span class="fav-dot">${svg('globe', 14)}</span>`;
  }

  function renderTabs() {
    const box = $('#tabs');
    box.textContent = '';
    for (const t of tabs) {
      const el = document.createElement('div');
      el.className = `tab${t.active ? ' active' : ''}`;
      el.setAttribute('role', 'tab');
      el.setAttribute('aria-selected', String(t.active));
      el.title = t.url ? `${t.title}\n${t.url}` : t.title;
      const icon = tabIcon(t);
      if (typeof icon === 'string') el.insertAdjacentHTML('beforeend', icon); else el.append(icon);
      const title = document.createElement('span');
      title.className = 'title';
      title.textContent = t.title || 'Nuova scheda';
      const close = document.createElement('button');
      close.className = 'close';
      close.setAttribute('aria-label', 'Chiudi scheda');
      close.innerHTML = svg('x', 14);
      close.addEventListener('click', (e) => { e.stopPropagation(); api.invoke('tab:close', t.id); });
      el.append(title, close);
      el.addEventListener('mousedown', (e) => { if (e.button === 1) e.preventDefault(); });
      el.addEventListener('auxclick', (e) => { if (e.button === 1) api.invoke('tab:close', t.id); });
      el.addEventListener('click', () => { closePanel(); api.invoke('tab:activate', t.id); });
      box.append(el);
    }
    requestAnimationFrame(() => {
      for (const el of box.children) el.classList.toggle('narrow', el.offsetWidth < 92);
    });
  }

  function renderToolbar() {
    if (!active) return;
    $('#back').disabled = !active.canBack;
    $('#forward').disabled = !active.canForward;
    const reload = $('#reload');
    reload.innerHTML = svg(active.loading ? 'x' : 'reload', active.loading ? 16 : 18);
    reload.title = active.loading ? 'Interrompi' : 'Ricarica (F5)';
    reload.setAttribute('aria-label', active.loading ? 'Interrompi' : 'Ricarica');

    const addr = $('#address');
    if (document.activeElement !== addr || active.id !== lastActiveId) addr.value = active.url;
    lastActiveId = active.id;

    const state = $('#site-state');
    state.className = '';
    if (!active.url && active.internal) { state.innerHTML = svg('search', 16); state.classList.add('internal'); state.title = ''; }
    else if (active.internal) { state.innerHTML = svg('shield', 16); state.classList.add('internal'); state.title = 'Pagina interna di Angerona'; }
    else if (active.secure) { state.innerHTML = svg('lock', 16); state.title = 'Connessione cifrata (HTTPS)'; }
    else { state.innerHTML = svg('alert', 16); state.classList.add('insecure'); state.title = active.error ? 'Pagina non caricata' : 'Connessione non cifrata'; }

    const zoom = $('#zoom');
    zoom.hidden = active.zoom === 100;
    zoom.textContent = `${active.zoom}%`;

    const shield = $('#shield');
    const off = payload && !payload.settings.blockTrackers;
    shield.className = `shield${off ? ' off' : active.blocked ? ' active' : ''}`;
    shield.innerHTML = svg(off ? 'shield' : 'shieldCheck', 16) + (active.blocked && !off ? `<span>${active.blocked}</span>` : '');
    shield.title = off ? 'Blocco tracker disattivato'
      : active.blocked === 1 ? '1 tracker bloccato in questa pagina' : `${active.blocked} tracker bloccati in questa pagina`;

    document.title = active.title ? `${active.title} — Angerona` : 'Angerona';
  }

  api.on('tabs', (list) => {
    tabs = list;
    active = list.find((t) => t.active) || null;
    renderTabs();
    renderToolbar();
  });

  $('#new-tab').addEventListener('click', () => { closePanel(); api.invoke('tab:new'); focusAddress(); });
  $('#back').addEventListener('click', () => api.invoke('tab:back'));
  $('#forward').addEventListener('click', () => api.invoke('tab:forward'));
  $('#reload').addEventListener('click', () => api.invoke(active && active.loading ? 'tab:stop' : 'tab:reload'));
  $('#home').addEventListener('click', () => { closePanel(); api.invoke('tab:home'); });
  $('#zoom').addEventListener('click', () => api.invoke('tab:zoom-reset'));

  // ---------- Barra indirizzi ----------
  const addr = $('#address');
  function focusAddress() {
    closePanel();
    setTimeout(() => { addr.focus(); addr.select(); }, 30);
  }
  addr.addEventListener('focus', () => setTimeout(() => addr.select(), 0));
  addr.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      const value = addr.value.trim();
      if (!value) return;
      closePanel();
      api.invoke('tab:navigate', value);
      addr.blur();
    } else if (e.key === 'Escape') {
      addr.value = active ? active.url : '';
      addr.blur();
    }
  });
  api.on('focus-address', focusAddress);

  // ---------- Trova nella pagina ----------
  const findbar = $('#findbar');
  const findInput = $('#find-input');
  function openFind() {
    closePanel();
    findbar.hidden = false;
    findInput.focus();
    findInput.select();
    if (findInput.value) api.invoke('find:start', { text: findInput.value });
  }
  function closeFind() {
    if (findbar.hidden) return;
    findbar.hidden = true;
    $('#find-count').textContent = '';
    api.invoke('find:stop');
  }
  const findNext = (forward) => api.invoke('find:start', { text: findInput.value, forward, next: true });
  findInput.addEventListener('input', () => api.invoke('find:start', { text: findInput.value }));
  findInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); findNext(!e.shiftKey); }
    if (e.key === 'Escape') closeFind();
  });
  $('#find-next').addEventListener('click', () => findNext(true));
  $('#find-prev').addEventListener('click', () => findNext(false));
  $('#find-close').addEventListener('click', closeFind);
  api.on('open-find', openFind);
  api.on('find', (r) => {
    if (r === null) { closeFind(); return; }
    $('#find-count').textContent = r.total ? `${r.active} di ${r.total}` : (findInput.value ? 'Nessun risultato' : '');
  });

  // ---------- Impostazioni ----------
  const panel = $('#panel');
  const form = $('#settings-form');
  let savedSnapshot = '';

  function fillSelect(name, items) {
    const sel = form.elements[name];
    sel.textContent = '';
    for (const it of items) sel.append(new Option(it.label, it.id));
  }

  function applyShowRules() {
    const values = { dns: form.elements.dns.value, proxyMode: form.elements.proxyMode.value, search: form.elements.search.value };
    for (const el of form.querySelectorAll('[data-show]')) {
      const rule = el.dataset.show;
      const neg = rule.includes('!=');
      const [key, list] = rule.split(neg ? '!=' : '=');
      const hit = list.split(',').includes(values[key]);
      el.hidden = neg ? hit : !hit;
    }
  }

  function filtersText(f) {
    if (!f || !f.rules) return 'Liste EasyList ed EasyPrivacy: in attesa del primo aggiornamento.';
    const when = f.updated ? new Date(f.updated).toLocaleDateString('it-IT', { day: 'numeric', month: 'long', year: 'numeric' }) : '';
    return `EasyList ed EasyPrivacy: ${f.rules.toLocaleString('it-IT')} regole, ${f.source}${when ? ` il ${when}` : ''}.`;
  }

  function fillForm(p) {
    payload = p;
    const s = p.settings;
    fillSelect('dns', p.dnsProviders);
    fillSelect('proxyMode', p.proxyModes);
    fillSelect('search', p.searchEngines);
    const el = form.elements;
    el.dns.value = s.dns;
    el.nextdnsId.value = s.nextdnsId;
    el.customDoh.value = s.customDoh;
    el.proxyMode.value = s.proxy.mode;
    el.proxyHost.value = s.proxy.host;
    el.proxyPort.value = s.proxy.port;
    el.proxyUser.value = s.proxy.user;
    el.proxyPass.value = s.proxy.pass;
    el.search.value = s.search;
    el.searxngUrl.value = s.searxngUrl;
    for (const k of ['httpsOnly', 'blockTrackers', 'blockThirdPartyCookies', 'javascript']) el[k].checked = s[k];
    $('#filters-info').textContent = filtersText(p.filters);
    $('#about').textContent = `Angerona ${p.version} · ${p.engine}`;
    clearErrors();
    applyShowRules();
    savedSnapshot = JSON.stringify(collect());
  }

  function collect() {
    const el = form.elements;
    return {
      dns: el.dns.value,
      nextdnsId: el.nextdnsId.value,
      customDoh: el.customDoh.value,
      proxy: {
        mode: el.proxyMode.value,
        host: el.proxyHost.value,
        port: el.proxyPort.value,
        user: el.proxyUser.value,
        pass: el.proxyPass.value,
      },
      search: el.search.value,
      searxngUrl: el.searxngUrl.value,
      httpsOnly: el.httpsOnly.checked,
      blockTrackers: el.blockTrackers.checked,
      blockThirdPartyCookies: el.blockThirdPartyCookies.checked,
      javascript: el.javascript.checked,
    };
  }

  function clearErrors() {
    for (const em of form.querySelectorAll('[data-error]')) {
      em.textContent = '';
      em.closest('.field').classList.remove('invalid');
    }
  }

  function showErrors(errors) {
    clearErrors();
    let first = null;
    for (const [key, msg] of Object.entries(errors)) {
      const em = form.querySelector(`[data-error="${key}"]`);
      if (!em) continue;
      em.textContent = msg;
      const field = em.closest('.field');
      field.classList.add('invalid');
      if (!first) first = field;
    }
    if (first) { first.scrollIntoView({ block: 'center', behavior: 'smooth' }); first.querySelector('input')?.focus(); }
  }

  async function save() {
    const res = await api.invoke('settings:set', collect());
    if (!res.ok) { showErrors(res.errors); return false; }
    fillForm(res.payload);
    renderToolbar();
    notice(res.jsChanged ? 'Salvato. JavaScript cambia nelle nuove schede.' : 'Impostazioni applicate');
    return true;
  }

  async function openPanel() {
    closeFind();
    fillForm(await api.invoke('settings:get'));
    $('#net-result').textContent = '';
    panel.hidden = false;
    await api.invoke('ui:panel', true);
    panel.scrollTop = 0;
    form.elements.dns.focus();
  }
  function closePanel() {
    if (panel.hidden) return;
    panel.hidden = true;
    api.invoke('ui:panel', false);
  }

  $('#open-settings').addEventListener('click', () => (panel.hidden ? openPanel() : closePanel()));
  $('#close-settings').addEventListener('click', closePanel);
  api.on('open-panel', () => (panel.hidden ? openPanel() : closePanel()));
  api.on('settings', (p) => {
    payload = p;
    $('#filters-info').textContent = filtersText(p.filters);
    renderToolbar();
  });
  form.addEventListener('change', applyShowRules);
  form.addEventListener('input', (e) => {
    const field = e.target.closest('.field');
    if (field && field.classList.contains('invalid')) {
      field.classList.remove('invalid');
      const em = field.querySelector('[data-error]');
      if (em) em.textContent = '';
    }
  });
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const btn = form.querySelector('.btn.primary');
    btn.setAttribute('aria-busy', 'true');
    try { await save(); } finally { btn.removeAttribute('aria-busy'); }
  });

  async function busy(button, fn) {
    button.setAttribute('aria-busy', 'true');
    try { return await fn(); } finally { button.removeAttribute('aria-busy'); }
  }

  $('#check-net').addEventListener('click', (e) => busy(e.currentTarget, async () => {
    const out = $('#net-result');
    if (JSON.stringify(collect()) !== savedSnapshot && !(await save())) return;
    out.className = 'muted small';
    out.textContent = 'Verifica in corso…';
    const r = await api.invoke('net:check');
    if (r.ok) {
      const place = [r.city, r.country].filter(Boolean).join(', ');
      out.className = 'small net-ok';
      out.textContent = `Il web ti vede come ${r.ip}${place ? ` · ${place}` : ''}${r.mullvad ? ' · uscita Mullvad VPN' : ''}`;
    } else {
      out.className = 'small net-bad';
      out.textContent = 'Nessuna risposta: controlla proxy, VPN o DNS. Il browser non esce senza protezione.';
    }
  }));

  $('#update-filters').addEventListener('click', (e) => busy(e.currentTarget, async () => {
    notice('Aggiornamento delle liste…');
    const r = await api.invoke('filters:update');
    if (r.ok) {
      $('#filters-info').textContent = filtersText(r.filters);
      notice('Liste dei tracker aggiornate');
    } else {
      notice('Liste non raggiungibili, riprova più tardi');
    }
  }));

  $('#clear-data').addEventListener('click', async () => {
    if (!window.confirm('Chiudere tutte le schede e cancellare cookie, cache e dati dei siti?')) return;
    await api.invoke('data:clear');
    closePanel();
    notice('Dati di navigazione cancellati');
  });

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !panel.hidden) closePanel();
  });

  api.invoke('settings:get').then((p) => { payload = p; renderToolbar(); });
})();
