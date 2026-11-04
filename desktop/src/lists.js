'use strict';
// Liste di blocco usate dal browser e da scripts/build-filters.mjs.
// Ogni lista: { mirrors: [indirizzi in ordine di preferenza], optional: true se può mancare }.

// Copie delle liste di uBlock Origin ed EasyList mantenute da Ghostery (licenze GPLv3 e CC BY-SA),
// aggiornate ogni giorno; jsDelivr serve gli stessi file come riserva.
const GHOSTERY = 'https://raw.githubusercontent.com/ghostery/adblocker/master/packages/adblocker/assets';
const JSDELIVR = 'https://cdn.jsdelivr.net/gh/ghostery/adblocker@master/packages/adblocker/assets';
const mirrored = (file, extra = []) => [`${GHOSTERY}/${file}`, `${JSDELIVR}/${file}`, ...extra];

// uBlock Origin divide i filtri per anno: da 2020 all'anno in corso (quello nuovo può non esistere ancora).
function yearlyFilters() {
  const out = [];
  for (let y = 2020; y <= new Date().getFullYear() + 1; y++) {
    out.push({ mirrors: mirrored(`ublock-origin/filters-${y}.txt`), optional: true });
  }
  return out;
}

// Pubblicità e tracker: lo stesso insieme che Brave e uBlock Origin usano di base.
const TRACKER_LISTS = [
  { mirrors: mirrored('easylist/easylist.txt', ['https://easylist.to/easylist/easylist.txt']) },
  { mirrors: mirrored('easylist/easyprivacy.txt', ['https://easylist.to/easylist/easyprivacy.txt']) },
  { mirrors: mirrored('peter-lowe/serverlist.txt') },
  { mirrors: mirrored('ublock-origin/filters.txt') },
  ...yearlyFilters(),
  { mirrors: mirrored('ublock-origin/badware.txt') },
  { mirrors: mirrored('ublock-origin/privacy.txt') },
  { mirrors: mirrored('ublock-origin/quick-fixes.txt') },
  { mirrors: mirrored('ublock-origin/resource-abuse.txt') },
  { mirrors: mirrored('ublock-origin/unbreak.txt') },
];

// Avvisi «Accetta i cookie»: come l'opzione di Brave «Blocca gli avvisi di consenso ai cookie».
const COOKIE_LISTS = [
  { mirrors: mirrored('easylist/easylist-cookie.txt') },
  { mirrors: mirrored('ublock-origin/annoyances-cookies.txt') },
];

// Script sostitutivi e «scriptlet» di uBlock Origin (disinnescano anti-adblock, popup, banner).
const RESOURCES = mirrored('ublock-origin/resources.json');

// Siti di truffa (phishing) e indirizzi che distribuiscono malware, aggiornati due volte al giorno.
// URLhaus (abuse.ch, CC0) e Phishing Filter (PhishTank, OpenPhish; CC BY-SA 4.0), progetto malware-filter.
const THREAT_LISTS = [
  {
    mirrors: [
      'https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-online.txt',
      'https://curbengh.github.io/malware-filter/urlhaus-filter-online.txt',
      'https://malware-filter.pages.dev/urlhaus-filter-online.txt',
    ],
  },
  {
    mirrors: [
      'https://malware-filter.gitlab.io/malware-filter/phishing-filter.txt',
      'https://curbengh.github.io/malware-filter/phishing-filter.txt',
      'https://malware-filter.pages.dev/phishing-filter.txt',
    ],
  },
];

const BASE_CONFIG = {
  loadNetworkFilters: true,
  loadCSPFilters: false,
  enableHtmlFiltering: false,
  enableCompression: true,
};
// Tracker e cookie: anche filtri cosmetici (nascondono riquadri e banner) e scriptlet.
const COSMETIC_CONFIG = { ...BASE_CONFIG, loadCosmeticFilters: true };
// Siti pericolosi: bastano i filtri di rete.
const NETWORK_CONFIG = { ...BASE_CONFIG, loadCosmeticFilters: false };

module.exports = {
  TRACKER_LISTS, COOKIE_LISTS, THREAT_LISTS, RESOURCES, COSMETIC_CONFIG, NETWORK_CONFIG,
};
