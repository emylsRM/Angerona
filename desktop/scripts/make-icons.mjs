// Genera icon.png (512) e icon.ico (16-256) partendo da build/icon.svg
import { readFileSync, writeFileSync } from 'node:fs';
import { Resvg } from '@resvg/resvg-js';
import pngToIco from 'png-to-ico';

const svg = readFileSync(new URL('../build/icon.svg', import.meta.url));
const render = (size) =>
  new Resvg(svg, { fitTo: { mode: 'width', value: size } }).render().asPng();

writeFileSync(new URL('../build/icon.png', import.meta.url), render(512));
writeFileSync(new URL('../ui/icon.png', import.meta.url), render(128));
const ico = await pngToIco([16, 24, 32, 48, 64, 128, 256].map(render));
writeFileSync(new URL('../build/icon.ico', import.meta.url), ico);
console.log('Icone generate');
