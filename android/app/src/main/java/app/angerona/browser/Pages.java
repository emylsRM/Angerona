package app.angerona.browser;

import android.net.Uri;
import android.util.Base64;

import org.mozilla.geckoview.WebRequestError;

import java.nio.charset.StandardCharsets;

/** Pagine interne (iniziale ed errori), senza script. */
final class Pages {
    private Pages() { }

    static final String ICON_SVG =
        "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 512 512' width='%d' height='%d'>"
        + "<rect width='512' height='512' rx='112' fill='#26215C'/>"
        + "<circle cx='256' cy='262' r='192' fill='none' stroke='#3C3489' stroke-width='8'/>"
        + "<path d='M116 300C146 268 178 238 212 236C232 235 246 248 256 256C266 248 280 235 300 236C334 238 366 268 396 300C356 346 304 372 256 372C208 372 156 346 116 300Z' fill='#CECBF6'/>"
        + "<path d='M140 302C196 318 316 318 372 302' fill='none' stroke='#26215C' stroke-width='12' stroke-linecap='round'/>"
        + "<line x1='256' y1='116' x2='256' y2='408' stroke='#26215C' stroke-width='58' stroke-linecap='round'/>"
        + "<line x1='256' y1='116' x2='256' y2='408' stroke='#7F77DD' stroke-width='30' stroke-linecap='round'/></svg>";

    private static final String CSP =
        "default-src 'none'; style-src 'unsafe-inline'; img-src data:; form-action https: http:; base-uri 'none'";

    private static final String STYLE =
        ":root{color-scheme:dark}*{box-sizing:border-box}"
        + "body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#16132F;color:#EEEDFE;"
        + "font:16px/1.55 system-ui,-apple-system,Roboto,sans-serif;padding:24px}"
        + "main{width:100%;max-width:560px;text-align:center}"
        + "h1{font-weight:500;font-size:24px;margin:16px 0 4px}"
        + "p{color:#CECBF6;margin:0 0 8px}.sub{color:#AFA9EC;font-size:14px}"
        + "form{display:flex;margin:24px 0 18px;background:#26215C;border:1px solid #3C3489;border-radius:16px;overflow:hidden}"
        + "form:focus-within{border-color:#7F77DD}"
        + "input{flex:1;min-width:0;background:transparent;border:0;outline:0;color:#EEEDFE;font:inherit;padding:14px 16px}"
        + "input::placeholder{color:#8b85d4}"
        + "button,.btn{background:#534AB7;color:#EEEDFE;border:0;font:inherit;padding:0 18px;text-decoration:none}"
        + ".chips{display:flex;flex-wrap:wrap;gap:8px;justify-content:center}"
        + ".chip{background:#26215C;border:1px solid #3C3489;border-radius:999px;padding:4px 12px;font-size:13px;color:#CECBF6}"
        + ".chip b{font-weight:500;color:#EEEDFE}.warn{border-color:#993C1D}"
        + ".actions{margin-top:22px}.actions .btn{display:inline-block;padding:12px 20px;border-radius:12px}"
        + ".url{font-family:monospace;font-size:13px;color:#AFA9EC;word-break:break-all;margin-top:10px}";

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String page(String title, String body) {
        return "<!doctype html><html lang='it'><head><meta charset='utf-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<meta http-equiv='Content-Security-Policy' content=\"" + CSP + "\">"
            + "<title>" + esc(title) + "</title><style>" + STYLE + "</style></head><body><main>"
            + body + "</main></body></html>";
    }

    private static String icon(int size) {
        return String.format(java.util.Locale.ROOT, ICON_SVG, size, size);
    }

    static String start(Settings s) {
        String action;
        String param = "q";
        StringBuilder hidden = new StringBuilder();
        if ("searxng".equals(s.search)) {
            action = s.searxngUrl + "/search";
        } else {
            Uri u = Uri.parse(Settings.SEARCH[Settings.indexOf(Settings.SEARCH, s.search)][2].replace("%s", "__Q__"));
            action = u.getScheme() + "://" + u.getAuthority() + (u.getPath() == null ? "/" : u.getPath());
            for (String k : u.getQueryParameterNames()) {
                String v = u.getQueryParameter(k);
                if ("__Q__".equals(v)) param = k;
                else hidden.append("<input type='hidden' name='").append(esc(k)).append("' value='").append(esc(v)).append("'>");
            }
        }
        String engine = Settings.label(Settings.SEARCH, s.search).split(" — ")[0];
        String dns = "system".equals(s.dns) ? "di Android" : Settings.label(Settings.DNS, s.dns).split(" — ")[0] + ", cifrato";
        String proxy = s.proxyEnabled() ? Settings.label(Settings.PROXY, s.proxyMode) + " attivo" : "nessuno";
        return page("Nuova scheda",
            icon(84)
            + "<h1>Angerona</h1><p class='sub'>Navigazione privata. Alla chiusura non resta nulla.</p>"
            + "<form method='get' action='" + esc(action) + "' autocomplete='off'>" + hidden
            + "<input name='" + esc(param) + "' placeholder='Cerca con " + esc(engine) + "' aria-label='Cerca' enterkeyhint='search'>"
            + "<button type='submit'>Cerca</button></form>"
            + "<div class='chips'>"
            + "<span class='chip" + ("system".equals(s.dns) ? " warn" : "") + "'>DNS <b>" + esc(dns) + "</b></span>"
            + "<span class='chip'>Proxy <b>" + esc(proxy) + "</b></span>"
            + "<span class='chip'>Tracker <b>" + (s.blockTrackers ? "protezione rigorosa" : "protezione standard") + "</b></span>"
            + "<span class='chip'>HTTPS <b>" + (s.httpsOnly ? "obbligatorio" : "facoltativo") + "</b></span>"
            + "</div>");
    }

    static String error(String uri, WebRequestError error) {
        String title;
        String text;
        int code = error == null ? WebRequestError.ERROR_UNKNOWN : error.code;
        int category = error == null ? WebRequestError.ERROR_CATEGORY_UNKNOWN : error.category;
        switch (code) {
            case WebRequestError.ERROR_UNKNOWN_HOST:
                title = "Indirizzo non trovato";
                text = "Il DNS non conosce questo sito, oppure il DNS cifrato scelto non risponde. Controlla l'indirizzo o prova un altro DNS.";
                break;
            case WebRequestError.ERROR_PROXY_CONNECTION_REFUSED:
            case WebRequestError.ERROR_UNKNOWN_PROXY_HOST:
                title = "Il proxy non risponde";
                text = "Per non esporre il tuo indirizzo, Angerona non si collega senza proxy. Controlla il proxy o l'app VPN.";
                break;
            case WebRequestError.ERROR_HTTPS_ONLY:
                title = "Il sito non offre HTTPS";
                text = "Questo sito non ha una versione cifrata. Puoi aprirlo disattivando «Solo HTTPS» nelle impostazioni, se ti fidi.";
                break;
            case WebRequestError.ERROR_OFFLINE:
                title = "Nessuna connessione";
                text = "Il telefono non è collegato a Internet.";
                break;
            case WebRequestError.ERROR_CONNECTION_REFUSED:
                title = "Connessione rifiutata";
                text = "Il sito ha rifiutato la connessione.";
                break;
            case WebRequestError.ERROR_NET_TIMEOUT:
                title = "Tempo scaduto";
                text = "Il sito non ha risposto in tempo.";
                break;
            case WebRequestError.ERROR_NET_RESET:
            case WebRequestError.ERROR_NET_INTERRUPT:
                title = "Connessione interrotta";
                text = "La connessione si è chiusa all'improvviso.";
                break;
            case WebRequestError.ERROR_SAFEBROWSING_MALWARE_URI:
            case WebRequestError.ERROR_SAFEBROWSING_PHISHING_URI:
            case WebRequestError.ERROR_SAFEBROWSING_UNWANTED_URI:
            case WebRequestError.ERROR_SAFEBROWSING_HARMFUL_URI:
                title = "Sito pericoloso";
                text = "Questo sito è segnalato per truffe o software dannoso. Angerona l'ha bloccato.";
                break;
            case WebRequestError.ERROR_UNKNOWN_PROTOCOL:
                title = "Indirizzo non supportato";
                text = "Angerona apre solo pagine web (http e https).";
                break;
            default:
                if (category == WebRequestError.ERROR_CATEGORY_SECURITY) {
                    title = "Connessione non sicura";
                    text = "Il certificato del sito non è valido. Angerona ha fermato la connessione per proteggerti.";
                } else if (category == WebRequestError.ERROR_CATEGORY_PROXY) {
                    title = "Il proxy non risponde";
                    text = "Per non esporre il tuo indirizzo, Angerona non si collega senza proxy.";
                } else {
                    title = "Pagina non disponibile";
                    text = "Il caricamento non è riuscito (errore " + code + ").";
                }
        }
        boolean web = uri != null && (uri.startsWith("https://") || uri.startsWith("http://"));
        return page(title,
            icon(60)
            + "<h1>" + esc(title) + "</h1><p>" + esc(text) + "</p>"
            + (web ? "<div class='url'>" + esc(uri) + "</div><div class='actions'><a class='btn' href='" + esc(uri) + "'>Riprova</a></div>" : ""));
    }

    static String extensionError() {
        return page("Proxy non pronto",
            icon(60)
            + "<h1>Protezione proxy non pronta</h1>"
            + "<p>Il componente che instrada il traffico non ha risposto. Per sicurezza nessuna pagina è stata aperta.</p>"
            + "<p class='sub'>Chiudi e riapri Angerona. Se succede ancora, imposta il proxy su «Nessuno».</p>");
    }

    static String dataUri(String html) {
        return "data:text/html;charset=utf-8;base64,"
            + Base64.encodeToString(html.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }
}
