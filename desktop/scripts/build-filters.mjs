// Scarica EasyList ed EasyPrivacy e crea il motore di blocco incluso nel browser (resources/filters.bin).
import { mkdirSync, writeFileSync } from 'node:fs';
import { FiltersEngine } from '@ghostery/adblocker';

const LISTS = [
  ['https://easylist.to/easylist/easylist.txt', 'https://raw.githubusercontent.com/easylist/easylist/gh-pages/easylist.txt'],
  ['https://easylist.to/easylist/easyprivacy.txt', 'https://raw.githubusercontent.com/easylist/easylist/gh-pages/easyprivacy.txt'],
];

async function download(mirrors) {
  for (const url of mirrors) {
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(60000) });
      if (res.ok) {
        const text = await res.text();
        if (text.length > 10000) { console.log(`✓ ${url}`); return text; }
      }
    } catch { /* prova il mirror successivo */ }
    console.log(`✗ ${url}`);
  }
  throw new Error(`Nessun mirror raggiungibile per ${mirrors[0]}`);
}

const texts = [];
for (const mirrors of LISTS) texts.push(await download(mirrors));
const engine = FiltersEngine.parse(texts.join('\n'), {
  loadNetworkFilters: true,
  loadCosmeticFilters: false,
  loadCSPFilters: false,
  enableHtmlFiltering: false,
  enableCompression: true,
});
mkdirSync(new URL('../resources/', import.meta.url), { recursive: true });
const data = engine.serialize();
writeFileSync(new URL('../resources/filters.bin', import.meta.url), data);
console.log(`Filtri: ${engine.getFilters().networkFilters.length} regole, ${(data.length / 1024 / 1024).toFixed(1)} MB`);
