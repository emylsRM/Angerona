// Scarica le liste di protezione e crea i motori di blocco inclusi nel browser:
// resources/filters.bin (tracker e pubblicità) e resources/threats.bin (truffe e malware).
import { mkdirSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { FiltersEngine } from '@ghostery/adblocker';

const require = createRequire(import.meta.url);
const { TRACKER_LISTS, THREAT_LISTS, ENGINE_CONFIG } = require('../src/lists.js');

async function download(mirrors, minSize) {
  for (const url of mirrors) {
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(60000) });
      if (res.ok) {
        const text = await res.text();
        if (text.length > minSize) { console.log(`✓ ${url}`); return text; }
      }
    } catch { /* prova il mirror successivo */ }
    console.log(`✗ ${url}`);
  }
  throw new Error(`Nessun mirror raggiungibile per ${mirrors[0]}`);
}

async function build(lists, minSize, file, label) {
  const texts = [];
  for (const mirrors of lists) texts.push(await download(mirrors, minSize));
  const engine = FiltersEngine.parse(texts.join('\n'), ENGINE_CONFIG);
  mkdirSync(new URL('../resources/', import.meta.url), { recursive: true });
  const data = engine.serialize();
  writeFileSync(new URL(`../resources/${file}`, import.meta.url), data);
  console.log(`${label}: ${engine.getFilters().networkFilters.length} regole, ${(data.length / 1024 / 1024).toFixed(1)} MB`);
}

let failed = false;
for (const [lists, minSize, file, label] of [
  [TRACKER_LISTS, 10000, 'filters.bin', 'Tracker'],
  [THREAT_LISTS, 1000, 'threats.bin', 'Truffe e malware'],
]) {
  try { await build(lists, minSize, file, label); } catch (err) { console.error(String(err)); failed = true; }
}
if (failed) process.exit(1);
