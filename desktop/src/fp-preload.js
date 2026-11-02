'use strict';
// Riduzione dell'impronta del browser, eseguita in ogni pagina e riquadro prima degli script del sito.
// Grafica (canvas, WebGL) e audio ricevono un disturbo minimo, stabile sullo stesso sito e diverso
// tra siti e tra sessioni; scheda video e hardware si presentano come quelli più comuni.
// Non tocca schermo, caratteri, lingua e fuso orario: cambiarli romperebbe i siti.
const { contextBridge } = require('electron');

const PREFIX = '--angerona-fp=';
const arg = process.argv.find((a) => a.startsWith(PREFIX));
if (arg) {
  try {
    contextBridge.executeInMainWorld({ func: install, args: [arg.slice(PREFIX.length)] });
  } catch { /* pagina senza contesto JavaScript */ }
}

// Viene serializzata ed eseguita nel mondo della pagina: non può usare nulla di esterno.
// `w` è la finestra da proteggere: la pagina stessa o un suo riquadro vuoto (about:blank) creato
// da uno script, che altrimenti offrirebbe funzioni originali senza protezione.
function install(seed, w) {
  w = w || window;
  const MARK = Symbol.for(`angerona-${seed.slice(0, 12)}`);
  if (Object.prototype.hasOwnProperty.call(w, MARK)) return;
  Object.defineProperty(w, MARK, { value: true });

  // Numeri pseudocasuali ripetibili: stesso sito + stessa sessione = stesso disturbo.
  function hash(str) {
    let h1 = 0xdeadbeef;
    let h2 = 0x41c6ce57;
    for (let i = 0; i < str.length; i++) {
      const c = str.charCodeAt(i);
      h1 = Math.imul(h1 ^ c, 2654435761);
      h2 = Math.imul(h2 ^ c, 1597334677);
    }
    h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
    return h1 >>> 0;
  }
  const origin = (() => { try { return String(w.origin); } catch { return 'null'; } })();
  function rng(extra) {
    let a = hash(`${seed}|${origin}|${extra}`);
    return () => {
      a = (a + 0x6d2b79f5) >>> 0;
      let t = a;
      t = Math.imul(t ^ (t >>> 15), t | 1);
      t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  // Le funzioni sostituite restano "native" per chi le ispeziona con toString.
  function patch(proto, name, wrap) {
    if (!proto) return;
    const desc = Object.getOwnPropertyDescriptor(proto, name);
    if (!desc || typeof desc.value !== 'function') return;
    const replacement = new Proxy(desc.value, { apply: (target, self, args) => wrap(target, self, args) });
    Object.defineProperty(proto, name, { ...desc, value: replacement });
  }
  function patchGetter(proto, name, getValue) {
    if (!proto) return;
    const desc = Object.getOwnPropertyDescriptor(proto, name);
    if (!desc || typeof desc.get !== 'function') return;
    const get = new Proxy(desc.get, { apply: (target, self) => getValue(target, self) });
    Object.defineProperty(proto, name, { ...desc, get });
  }

  // ---------- Riquadri vuoti creati dagli script ----------
  // Un about:blank appena creato non riceve subito la protezione: la si applica appena la pagina lo tocca.
  function protectFrame(win) {
    try { if (win && win.HTMLCanvasElement) install(seed, win); } catch { /* riquadro di un altro sito: ha la sua protezione */ }
  }
  for (const C of [w.HTMLIFrameElement, w.HTMLFrameElement, w.HTMLObjectElement]) {
    if (!C) continue;
    patchGetter(C.prototype, 'contentWindow', (get, el) => { const win = Reflect.apply(get, el, []); protectFrame(win); return win; });
    patchGetter(C.prototype, 'contentDocument', (get, el) => {
      const doc = Reflect.apply(get, el, []);
      if (doc) protectFrame(doc.defaultView);
      return doc;
    });
  }

  // ---------- Canvas ----------
  const MAX_PIXELS = 16 * 1024 * 1024;
  // Cambia il bit meno significativo di pochi pixel opachi: invisibile, ma l'impronta cambia.
  function disturb(data, width, height) {
    const pixels = width * height;
    if (!pixels || pixels > MAX_PIXELS) return;
    const rand = rng(`canvas|${width}x${height}`);
    const wanted = Math.min(10 + Math.floor(pixels / 2000), 200);
    let done = 0;
    for (let tries = 0; tries < wanted * 8 && done < wanted; tries++) {
      const i = Math.floor(rand() * pixels) * 4;
      if (data[i + 3] !== 255) continue;
      data[i + Math.floor(rand() * 3)] ^= 1;
      done++;
    }
  }

  // Originali salvati prima di qualsiasi sostituzione, per non applicare il disturbo due volte.
  const get2d = w.HTMLCanvasElement.prototype.getContext;
  const getImageData2d = w.CanvasRenderingContext2D.prototype.getImageData;
  const putImageData2d = w.CanvasRenderingContext2D.prototype.putImageData;
  const hasOffscreen = typeof w.OffscreenCanvas !== 'undefined' && typeof w.OffscreenCanvasRenderingContext2D !== 'undefined';
  const getOff = hasOffscreen ? w.OffscreenCanvas.prototype.getContext : null;
  const getImageDataOff = hasOffscreen ? w.OffscreenCanvasRenderingContext2D.prototype.getImageData : null;
  const putImageDataOff = hasOffscreen ? w.OffscreenCanvasRenderingContext2D.prototype.putImageData : null;

  function disturbedCopy(canvas) {
    const { width, height } = canvas;
    if (!width || !height || width * height > MAX_PIXELS) return null;
    const copy = w.document.createElement('canvas');
    copy.width = width;
    copy.height = height;
    const ctx = get2d.call(copy, '2d', { willReadFrequently: true });
    if (!ctx) return null;
    ctx.drawImage(canvas, 0, 0);
    const img = getImageData2d.call(ctx, 0, 0, width, height);
    disturb(img.data, width, height);
    putImageData2d.call(ctx, img, 0, 0);
    return copy;
  }
  for (const name of ['toDataURL', 'toBlob']) {
    patch(w.HTMLCanvasElement.prototype, name, (orig, canvas, args) => {
      let copy = null;
      try { copy = disturbedCopy(canvas); } catch { /* canvas non leggibile */ }
      return Reflect.apply(orig, copy || canvas, args);
    });
  }
  const disturbImage = (orig, ctx, args) => {
    const img = Reflect.apply(orig, ctx, args);
    try { disturb(img.data, img.width, img.height); } catch { /* ignorato */ }
    return img;
  };
  patch(w.CanvasRenderingContext2D.prototype, 'getImageData', disturbImage);
  if (hasOffscreen) {
    patch(w.OffscreenCanvasRenderingContext2D.prototype, 'getImageData', disturbImage);
    patch(w.OffscreenCanvas.prototype, 'convertToBlob', (orig, canvas, args) => {
      let copy = null;
      try {
        const { width, height } = canvas;
        if (width && height && width * height <= MAX_PIXELS) {
          copy = new w.OffscreenCanvas(width, height);
          const ctx = getOff.call(copy, '2d');
          ctx.drawImage(canvas, 0, 0);
          const img = getImageDataOff.call(ctx, 0, 0, width, height);
          disturb(img.data, width, height);
          putImageDataOff.call(ctx, img, 0, 0);
        }
      } catch { copy = null; }
      return Reflect.apply(orig, copy || canvas, args);
    });
  }

  // ---------- WebGL ----------
  const VENDOR = 'Google Inc. (Intel)';
  const RENDERER = 'ANGLE (Intel, Intel(R) UHD Graphics 620 (0x00003EA0) Direct3D11 vs_5_0 ps_5_0, D3D11)';
  for (const C of [w.WebGLRenderingContext, w.WebGL2RenderingContext]) {
    if (!C) continue;
    patch(C.prototype, 'getParameter', (orig, gl, args) => {
      if (args[0] === 0x9245) return VENDOR; // UNMASKED_VENDOR_WEBGL
      if (args[0] === 0x9246) return RENDERER; // UNMASKED_RENDERER_WEBGL
      return Reflect.apply(orig, gl, args);
    });
    patch(C.prototype, 'readPixels', (orig, gl, args) => {
      const out = Reflect.apply(orig, gl, args);
      try {
        const [, , width, height, , type, pixels] = args;
        if (pixels && ArrayBuffer.isView(pixels) && type === 0x1401) disturb(pixels, width, height); // UNSIGNED_BYTE
      } catch { /* ignorato */ }
      return out;
    });
  }

  // ---------- Audio ----------
  const disturbedChannels = new WeakMap();
  function disturbChannel(buffer, channel, data) {
    let set = disturbedChannels.get(buffer);
    if (!set) { set = new Set(); disturbedChannels.set(buffer, set); }
    if (set.has(channel)) return;
    set.add(channel);
    if (data.length > 2000000) return;
    const rand = rng(`audio|${buffer.length}|${buffer.sampleRate}|${channel}`);
    for (let i = 0; i < data.length; i++) data[i] += (rand() - 0.5) * 2e-7;
  }
  if (w.AudioBuffer) {
    const getChannelData = w.AudioBuffer.prototype.getChannelData;
    patch(w.AudioBuffer.prototype, 'getChannelData', (orig, buffer, args) => {
      const data = Reflect.apply(orig, buffer, args);
      try { disturbChannel(buffer, args[0], data); } catch { /* ignorato */ }
      return data;
    });
    patch(w.AudioBuffer.prototype, 'copyFromChannel', (orig, buffer, args) => {
      try { disturbChannel(buffer, args[1], getChannelData.call(buffer, args[1])); } catch { /* ignorato */ }
      return Reflect.apply(orig, buffer, args);
    });
  }
  if (w.AnalyserNode) {
    patch(w.AnalyserNode.prototype, 'getFloatFrequencyData', (orig, node, args) => {
      const out = Reflect.apply(orig, node, args);
      try {
        const arr = args[0];
        const rand = rng(`analyser|${arr.length}`);
        for (let i = 0; i < arr.length; i++) arr[i] += (rand() - 0.5) * 1e-4;
      } catch { /* ignorato */ }
      return out;
    });
  }

  // ---------- Hardware e versione ----------
  patchGetter(w.Navigator.prototype, 'hardwareConcurrency', () => 8);
  patchGetter(w.Navigator.prototype, 'deviceMemory', () => 8);
  if (w.NavigatorUAData) {
    patch(w.NavigatorUAData.prototype, 'getHighEntropyValues', (orig, data, args) => Reflect.apply(orig, data, args).then((v) => {
      const out = { ...v };
      const major = (version) => String(version || '').split('.')[0];
      if ('platformVersion' in out) out.platformVersion = '10.0.0';
      if ('uaFullVersion' in out) out.uaFullVersion = `${major(out.uaFullVersion)}.0.0.0`;
      if (Array.isArray(out.fullVersionList)) out.fullVersionList = out.fullVersionList.map((b) => ({ brand: b.brand, version: `${major(b.version)}.0.0.0` }));
      if ('architecture' in out) out.architecture = 'x86';
      if ('bitness' in out) out.bitness = '64';
      if ('model' in out) out.model = '';
      if ('wow64' in out) out.wow64 = false;
      return out;
    }));
  }
}
