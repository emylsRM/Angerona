// Scarica le liste di protezione e crea i motori di blocco inclusi nel browser:
// resources/filters.bin (tracker e pubblicità) e resources/threats.bin (truffe e malware).
import { mkdirSync, writeFileSync } from 'node:fs';
import https from 'node:https';
import { createRequire } from 'node:module';
import { FiltersEngine } from '@ghostery/adblocker';

const require = createRequire(import.meta.url);
const { TRACKER_LISTS, THREAT_LISTS, ENGINE_CONFIG } = require('../src/lists.js');

// node:https invece di fetch: il fetch di Node 22 (undici) va in crash su alcune risposte grandi
// (assert(!this.paused)) e il crash non si può intercettare.
function get(url, redirects = 5) {
  return new Promise((resolve, reject) => {
    const req = https.get(url, { headers: { 'user-agent': 'Angerona-build' }, timeout: 60000 }, (res) => {
      const { statusCode, headers } = res;
      if (statusCode >= 300 && statusCode < 400 && headers.location && redirects > 0) {
        res.resume();
        resolve(get(new URL(headers.location, url).toString(), redirects - 1));
        return;
      }
      if (statusCode !== 200) {
        res.resume();
        reject(new Error(`HTTP ${statusCode}`));
        return;
      }
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
      res.on('error', reject);
    });
    req.on('timeout', () => req.destroy(new Error('tempo scaduto')));
    req.on('error', reject);
  });
}

async function download(mirrors, minSize) {
  for (const url of mirrors) {
    try {
      const text = await get(url);
      if (text.length > minSize) { console.log(`✓ ${url}`); return text; }
    } catch (err) {
      console.log(`✗ ${url}: ${err.message}`);
      continue;
    }
    console.log(`✗ ${url}: lista troppo corta`);
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
