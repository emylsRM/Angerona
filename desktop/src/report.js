'use strict';
// Valutazione della privacy a partire dalle impostazioni: punteggio, chi vede cosa, come migliorare.
// La stessa logica esiste in android/.../PrivacyReport.java: se cambi qui, cambia anche là.
const { DNS_PROVIDERS } = require('./settings');
const { isPrivateHost } = require('./privacy');

const NO_LOG_DNS = new Set(['quad9', 'mullvad', 'mullvad-adblock', 'adguard']);

function level(score) {
  if (score >= 90) return 'Massima';
  if (score >= 75) return 'Alta';
  if (score >= 55) return 'Buona';
  return 'Base';
}

function evaluate(s) {
  const proxy = s.proxy || { mode: 'none', host: '' };
  const hasProxy = proxy.mode !== 'none';
  // Un proxy SOCKS5/HTTP sul computer o dentro una VPN (Tor, Mullvad) non espone nulla in rete;
  // uno remoto riceve le richieste senza cifratura del collegamento.
  const host = String(proxy.host || '').trim();
  const proxyLocal = hasProxy && host !== '' && isPrivateHost(host);
  const proxyEncrypted = proxy.mode === 'https' || proxyLocal;
  const doh = s.dns !== 'system';
  const dnsName = (DNS_PROVIDERS.find((p) => p.id === s.dns) || DNS_PROVIDERS[0]).label.split(' — ')[0];

  const parts = [
    { id: 'base', value: 10, max: 10 },
    { id: 'dns', value: !doh ? 0 : NO_LOG_DNS.has(s.dns) ? 20 : 15, max: 20 },
    { id: 'proxy', value: !hasProxy ? 0 : proxyEncrypted ? 25 : 15, max: 25 },
    { id: 'https', value: s.httpsOnly ? 15 : 0, max: 15 },
    { id: 'trackers', value: s.blockTrackers ? 15 : 0, max: 15 },
    { id: 'cookies', value: s.blockThirdPartyCookies ? 10 : 0, max: 10 },
    { id: 'javascript', value: s.javascript ? 0 : 5, max: 5 },
  ];
  const score = parts.reduce((n, p) => n + p.value, 0);
  const got = Object.fromEntries(parts.map((p) => [p.id, p.value]));

  const sites = [
    hasProxy
      ? "Vedono l'indirizzo IP del proxy, non il tuo."
      : 'Vedono il tuo indirizzo IP e la zona da cui ti colleghi, a meno che sul computer sia attiva una VPN.',
    s.javascript
      ? 'Vedono il tipo di browser, la lingua, il fuso orario e la risoluzione dello schermo.'
      : 'Vedono solo il tipo di browser e la lingua: senza JavaScript non leggono schermo e altri dettagli.',
    s.blockTrackers
      ? 'I tracker pubblicitari conosciuti non vengono caricati.'
      : 'I tracker pubblicitari presenti nella pagina possono registrare la tua visita.',
    s.blockThirdPartyCookies
      ? 'I servizi esterni incorporati (pulsanti social, video) non ricevono i loro cookie.'
      : 'I servizi esterni incorporati possono riconoscerti tra siti diversi finché il browser resta aperto.',
    'WebRTC non può rivelare il tuo indirizzo IP: le connessioni dirette passano solo da un proxy, se c\'è.',
    "Possono calcolare un'impronta del browser (grafica, schermo, hardware): su Windows Angerona non la maschera ancora.",
    'Non trovano cronologia precedente: cookie e dati spariscono alla chiusura.',
  ];

  const isp = [];
  if (hasProxy && proxyEncrypted) {
    isp.push('Vede solo che usi un proxy e quanto traffico scambi, non i siti.');
  } else if (hasProxy) {
    isp.push('Vede i nomi dei siti: il collegamento a un proxy SOCKS5 o HTTP remoto non è cifrato.');
  } else if (doh) {
    isp.push('Vede i nomi dei siti a cui ti colleghi, ma non le pagine che apri né i contenuti.');
  } else {
    isp.push('Vede i nomi di tutti i siti, anche dalle richieste DNS in chiaro, ma non i contenuti delle pagine HTTPS.');
  }
  if (!s.httpsOnly) isp.push('Può leggere le pagine dei siti che non usano HTTPS.');

  const dns = [];
  if (hasProxy && proxy.mode === 'socks5') {
    dns.push('Con SOCKS5 i nomi dei siti li risolve il proxy: il servizio DNS non li riceve.');
  } else if (doh) {
    dns.push(`${dnsName} riceve, cifrati, i nomi dei siti che visiti.`);
    if (NO_LOG_DNS.has(s.dns)) dns.push('Dichiara di non conservare il tuo indirizzo IP.');
  } else {
    dns.push("Il DNS del tuo operatore o della VPN riceve, senza cifratura, i nomi di tutti i siti.");
  }

  const observers = [
    { who: 'I siti che visiti', sees: sites },
    { who: 'Il tuo operatore e la rete Wi-Fi', sees: isp },
    { who: 'Il servizio DNS', sees: dns },
  ];
  if (hasProxy) {
    observers.push({
      who: 'Il gestore del proxy',
      sees: [
        'Vede il tuo indirizzo IP reale e i nomi dei siti che visiti, non il contenuto delle pagine HTTPS.',
        ...(s.httpsOnly ? [] : ['Può leggere le pagine non cifrate.']),
      ],
    });
  }

  const tips = [];
  if (!hasProxy) tips.push({ text: 'Usa una VPN o un proxy SOCKS5 per nascondere il tuo IP ai siti.', gain: 25 });
  else if (!proxyEncrypted) tips.push({ text: 'Usa un proxy HTTPS, o SOCKS5 dentro una VPN, per cifrare anche il collegamento al proxy.', gain: 10 });
  if (!doh) tips.push({ text: 'Scegli un DNS cifrato, per esempio Quad9 o Mullvad.', gain: 20 });
  else if (got.dns < 20) tips.push({ text: 'Quad9, Mullvad e AdGuard dichiarano di non conservare il tuo IP.', gain: 20 - got.dns });
  if (!s.httpsOnly) tips.push({ text: 'Attiva «Solo HTTPS».', gain: 15 });
  if (!s.blockTrackers) tips.push({ text: 'Attiva il blocco di tracker e pubblicità.', gain: 15 });
  if (!s.blockThirdPartyCookies) tips.push({ text: 'Blocca i cookie di terze parti.', gain: 10 });
  if (s.javascript) tips.push({ text: 'Disattivare JavaScript riduce ancora le informazioni sul dispositivo, ma molti siti smettono di funzionare.', gain: 5 });

  return { score, level: level(score), parts, observers, tips, vpnNote: !hasProxy };
}

module.exports = { evaluate, level };
