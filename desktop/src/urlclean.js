'use strict';
// Indirizzi ripuliti prima di aprirli, come fa Brave:
// - «debouncing»: i link che passano da un intermediario di tracciamento (google.com/url?q=…,
//   l.facebook.com/l.php?u=…) vanno direttamente alla destinazione;
// - gli identificativi di clic aggiunti agli indirizzi per seguirti tra siti (fbclid, gclid…)
//   vengono tolti. I parametri utm_* restano: descrivono la campagna, non la persona.

// Parametri usati solo per riconoscere chi ha cliccato (elenco di Brave e Firefox).
const TRACKING_PARAMS = new Set([
  '__hsfp', '__hssc', '__hstc', '__s', '_bta_c', '_bta_tid', '_hsenc', '_kx', '_openstat',
  'bsft_clkid', 'bsft_uid', 'dclid', 'ebisu_clid', 'epik', 'fbclid', 'gbraid', 'gclid', 'guce_referrer',
  'guce_referrer_sig', 'hsCtaTracking', 'igshid', 'irclickid', 'mc_eid', 'ml_subscriber', 'ml_subscriber_hash',
  'mkt_tok', 'msclkid', 'oft_c', 'oft_ck', 'oft_d', 'oft_id', 'oft_ids', 'oft_k', 'oft_lk', 'oft_sk',
  'oly_anon_id', 'oly_enc_id', 'rb_clickid', 's_cid', 'ss_email_id', 'trk_contact', 'trk_module', 'trk_msg',
  'trk_sid', 'ttclid', 'twclid', 'vero_conv', 'vero_id', 'wbraid', 'wickedid', 'yclid', 'ymclid', 'ysclid',
]);

// Intermediari noti: host (esatto o espressione), percorso, parametro con la destinazione.
const DEBOUNCE = [
  { host: /^(www\.)?google\.[a-z.]{2,6}$/, path: /^\/url$/, params: ['q', 'url'] },
  { host: /^(l|lm)\.facebook\.com$/, path: /^\/l\.php$/, params: ['u'] },
  { host: /^l\.messenger\.com$/, path: /^\/l\.php$/, params: ['u'] },
  { host: /^l\.instagram\.com$/, path: /^\/$/, params: ['u'] },
  { host: /^l\.threads\.(net|com)$/, path: /^\/$/, params: ['u'] },
  { host: /^(www\.|m\.)?youtube\.com$/, path: /^\/redirect$/, params: ['q'] },
  { host: /^out\.reddit\.com$/, path: /^\//, params: ['url'] },
  { host: /^steamcommunity\.com$/, path: /^\/linkfilter\/?$/, params: ['u', 'url'] },
  { host: /^(www\.)?linkedin\.com$/, path: /^\/redir\/redirect\/?$/, params: ['url'] },
  { host: /^(away\.)?vk\.com$/, path: /^\/away\.php$/, params: ['to'] },
  { host: /^go\.skimresources\.com$/, path: /^\/?$/, params: ['url'] },
  { host: /^go\.redirectingat\.com$/, path: /^\/?$/, params: ['url'] },
  { host: /^click\.linksynergy\.com$/, path: /^\/(deeplink|link)$/, params: ['murl'] },
  { host: /^(www\.)?awin1\.com$/, path: /^\/cread\.php$/, params: ['ued'] },
  { host: /^(www\.)?anrdoezrs\.net$|^(www\.)?dpbolvw\.net$|^(www\.)?jdoqocy\.com$|^(www\.)?kqzyfj\.com$|^(www\.)?tkqlhce\.com$/, path: /^\/click/, params: ['url'] },
  { host: /^slack-redir\.net$/, path: /^\/link$/, params: ['url'] },
  { host: /^exit\.sc$/, path: /^\/?$/, params: ['url'] },
  { host: /^(www\.)?tiktok\.com$/, path: /^\/link\/v2$/, params: ['target'] },
  { host: /^href\.li$/, path: /^\/?$/, params: [] }, // destinazione dopo il «?»
];

function destination(u) {
  for (const rule of DEBOUNCE) {
    if (!rule.host.test(u.hostname) || !rule.path.test(u.pathname)) continue;
    const candidates = rule.params.length ? rule.params.map((p) => u.searchParams.get(p)) : [decodeURIComponent(u.search.slice(1))];
    for (const c of candidates) {
      if (!c) continue;
      try {
        const target = new URL(c);
        if (target.protocol === 'http:' || target.protocol === 'https:') return target;
      } catch { /* non è un indirizzo completo */ }
    }
  }
  return null;
}

/** Indirizzo pulito da aprire al posto di `url`, oppure null se non c'è nulla da cambiare. */
function cleanNavigation(url) {
  let u;
  try { u = new URL(url); } catch { return null; }
  let changed = false;
  for (let hop = 0; hop < 3; hop++) {
    const next = destination(u);
    if (!next) break;
    u = next;
    changed = true;
  }
  if (u.search) {
    // Si tolgono solo i pezzi da eliminare: gli altri parametri restano scritti esattamente com'erano.
    const parts = u.search.slice(1).split('&');
    const keep = parts.filter((part) => {
      const key = part.split('=')[0];
      try { return !TRACKING_PARAMS.has(decodeURIComponent(key.replace(/\+/g, ' '))); } catch { return true; }
    });
    if (keep.length !== parts.length) {
      u.search = keep.length ? `?${keep.join('&')}` : '';
      changed = true;
    }
  }
  return changed && u.href !== url ? u.href : null;
}

module.exports = { cleanNavigation, TRACKING_PARAMS };
