// Scarica le liste di protezione e crea i motori di blocco inclusi nel browser:
// resources/shields.bin (pubblicità e tracker, come Brave), resources/cookies.bin (banner dei cookie)
// e resources/threats.bin (truffe e malware).
import { mkdirSync, writeFileSync } from 'node:fs';
import https from 'node:https';
import { createRequire } from 'node:module';
import { FiltersEngine } from '@ghostery/adblocker';

const require = createRequire(import.meta.url);
const {
  TRACKER_LISTS, COOKIE_LISTS, THREAT_LISTS, RESOURCES, COSMETIC_CONFIG, NETWORK_CONFIG,
} = require('../src/lists.js');

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

async function download(mirrors, minSize = 1000) {
  for (const url of mirrors) {
    try {
      const text = await get(url);
      if (text.length > minSize) { console.log(`✓ ${url}`); return text; }
      console.log(`✗ ${url}: lista troppo corta`);
    } catch (err) {
      console.log(`✗ ${url}: ${err.message}`);
    }
  }
  return null;
}

async function build(lists, config, withResources, file, label) {
  const texts = [];
  for (const { mirrors, optional } of lists) {
    const text = await download(mirrors);
    if (text) texts.push(text);
    else if (!optional) throw new Error(`Nessun mirror raggiungibile per ${mirrors[0]}`);
  }
  const engine = FiltersEngine.parse(texts.join('\n'), config);
  if (withResources) {
    const resources = await download(RESOURCES);
    if (!resources) throw new Error('Risorse di uBlock Origin non raggiungibili');
    engine.updateResources(resources, String(resources.length));
  }
  const f = engine.getFilters();
  const rules = f.networkFilters.length + f.cosmeticFilters.length;
  mkdirSync(new URL('../resources/', import.meta.url), { recursive: true });
  const data = engine.serialize();
  writeFileSync(new URL(`../resources/${file}`, import.meta.url), data);
  writeFileSync(new URL(`../resources/${file}.json`, import.meta.url), JSON.stringify({ rules }));
  console.log(`${label}: ${f.networkFilters.length} regole di rete, ${f.cosmeticFilters.length} cosmetiche, ${(data.length / 1024 / 1024).toFixed(1)} MB`);
}

let failed = false;
for (const [lists, config, withResources, file, label] of [
  [TRACKER_LISTS, COSMETIC_CONFIG, true, 'shields.bin', 'Pubblicità e tracker'],
  [COOKIE_LISTS, COSMETIC_CONFIG, true, 'cookies.bin', 'Banner dei cookie'],
  [THREAT_LISTS, NETWORK_CONFIG, false, 'threats.bin', 'Truffe e malware'],
]) {
  try { await build(lists, config, withResources, file, label); } catch (err) { console.error(String(err)); failed = true; }
}
if (failed) process.exit(1);
