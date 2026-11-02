"use strict";
// Proxy di Angerona. Finché l'app non invia la configurazione ogni richiesta va verso
// un proxy inesistente: nessuna connessione parte "in chiaro" all'avvio.
const BLOCKED = { type: "http", host: "127.0.0.1", port: 9, failoverTimeout: 1 };
let config = null;

function proxyFor() {
  if (!config) return BLOCKED;
  if (config.mode === "none") return { type: "direct" };
  const info = {
    type: config.mode === "socks5" ? "socks" : config.mode,
    host: String(config.host),
    port: Number(config.port),
    failoverTimeout: 5,
  };
  if (config.mode === "socks5") {
    info.proxyDNS = true; // anche il DNS passa dal proxy
    if (config.user) {
      info.username = String(config.user);
      info.password = String(config.pass || "");
    }
  }
  return info;
}

browser.proxy.onRequest.addListener(() => proxyFor(), { urls: ["<all_urls>"] });
browser.proxy.onError.addListener((err) => console.warn("Angerona proxy:", err && err.message));

// WebRTC può rivelare l'indirizzo IP reale anche con un proxy: per questo è spento di base.
// Se l'utente lo attiva, con un proxy passa solo dal proxy; senza, mostra solo l'interfaccia pubblica.
async function applyWebRTC(cfg) {
  const net = browser.privacy && browser.privacy.network;
  if (!net) return "non disponibile";
  try {
    const enabled = Boolean(cfg.webrtc);
    await net.peerConnectionEnabled.set({ value: enabled });
    await net.webRTCIPHandlingPolicy.set({
      value: cfg.mode !== "none" ? "proxy_only" : "default_public_interface_only",
    });
    return enabled ? "limitato" : "spento";
  } catch (e) {
    console.warn("Angerona WebRTC:", e && e.message);
    return "errore";
  }
}

function connect() {
  let port;
  try {
    port = browser.runtime.connectNative("angerona");
  } catch (e) {
    setTimeout(connect, 1000);
    return;
  }
  port.onMessage.addListener(async (msg) => {
    if (msg && msg.type === "config" && msg.config) {
      config = msg.config;
      const webrtc = await applyWebRTC(config);
      port.postMessage({ type: "applied", webrtc });
    }
  });
  port.onDisconnect.addListener(() => setTimeout(connect, 1000));
}
connect();
