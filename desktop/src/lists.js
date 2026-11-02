'use strict';
// Liste di blocco usate dal browser e da scripts/build-filters.mjs (ogni voce: mirror in ordine di preferenza).

// Tracker e pubblicità.
const TRACKER_LISTS = [
  ['https://easylist.to/easylist/easylist.txt', 'https://raw.githubusercontent.com/easylist/easylist/gh-pages/easylist.txt'],
  ['https://easylist.to/easylist/easyprivacy.txt', 'https://raw.githubusercontent.com/easylist/easylist/gh-pages/easyprivacy.txt'],
];

// Siti di truffa (phishing) e indirizzi che distribuiscono malware, aggiornati due volte al giorno.
// URLhaus (abuse.ch, CC0) e Phishing Filter (PhishTank, OpenPhish; CC BY-SA 4.0), progetto malware-filter.
const THREAT_LISTS = [
  [
    'https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-online.txt',
    'https://curbengh.github.io/malware-filter/urlhaus-filter-online.txt',
    'https://malware-filter.pages.dev/urlhaus-filter-online.txt',
  ],
  [
    'https://malware-filter.gitlab.io/malware-filter/phishing-filter.txt',
    'https://curbengh.github.io/malware-filter/phishing-filter.txt',
    'https://malware-filter.pages.dev/phishing-filter.txt',
  ],
];

const ENGINE_CONFIG = {
  loadNetworkFilters: true,
  loadCosmeticFilters: false,
  loadCSPFilters: false,
  enableHtmlFiltering: false,
  enableCompression: true,
};

module.exports = { TRACKER_LISTS, THREAT_LISTS, ENGINE_CONFIG };
