package app.angerona.browser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Valutazione della privacy a partire dalle impostazioni: punteggio, chi vede cosa, come migliorare.
 * La base è la stessa di desktop/src/report.js; qui in più contano impronta del browser e WebRTC,
 * che su Android sono configurabili.
 */
final class PrivacyReport {
    static final class Observer {
        final String who;
        final List<String> sees = new ArrayList<>();
        Observer(String who) { this.who = who; }
    }

    static final class Tip {
        final String text;
        final int gain;
        Tip(String text, int gain) { this.text = text; this.gain = gain; }
    }

    int score;
    String level;
    boolean vpnNote;
    final List<Observer> observers = new ArrayList<>();
    final List<Tip> tips = new ArrayList<>();

    private static final Set<String> NO_LOG_DNS =
        new HashSet<>(Arrays.asList("quad9", "mullvad", "mullvad-adblock", "adguard"));

    static final String[][] TESTS = {
        {"Mullvad: IP, DNS e WebRTC", "https://mullvad.net/it/check"},
        {"EFF Cover Your Tracks: impronta del browser", "https://coveryourtracks.eff.org/"},
        {"BrowserLeaks: tutti i dettagli", "https://browserleaks.com/"},
    };

    static String level(int score) {
        if (score >= 90) return "Massima";
        if (score >= 75) return "Alta";
        if (score >= 55) return "Buona";
        return "Base";
    }

    /** Indirizzo sul telefono o in rete locale/VPN: il collegamento al proxy non passa in chiaro su Internet. */
    static boolean isPrivateHost(String host) {
        String h = host == null ? "" : host.trim().toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        if (h.isEmpty()) return false;
        if (h.equals("localhost") || h.endsWith(".local") || h.endsWith(".lan") || h.equals("::1")) return true;
        if (h.contains(":")) return h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80:");
        String[] p = h.split("\\.");
        if (p.length != 4) return !h.contains(".");
        try {
            int a = Integer.parseInt(p[0]);
            int b = Integer.parseInt(p[1]);
            return a == 10 || a == 127 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static PrivacyReport evaluate(Settings s) {
        PrivacyReport r = new PrivacyReport();
        boolean hasProxy = s.proxyEnabled();
        boolean proxyLocal = hasProxy && isPrivateHost(s.proxyHost);
        boolean proxyEncrypted = "https".equals(s.proxyMode) || proxyLocal;
        boolean socks = "socks5".equals(s.proxyMode);
        boolean doh = !"system".equals(s.dns);
        String dnsName = Settings.label(Settings.DNS, s.dns).split(" — ")[0];

        int dnsPoints = !doh ? 0 : NO_LOG_DNS.contains(s.dns) ? 20 : 15;
        int proxyPoints = !hasProxy ? 0 : proxyEncrypted ? 25 : 15;
        int trackerPoints = s.blockTrackers ? 15 : 8; // la protezione standard di Firefox resta attiva
        int cookiePoints = s.blockThirdPartyCookies ? 10 : 6; // senza blocco i cookie restano isolati per sito
        int fingerprintPoints = "massima".equals(s.fingerprint) ? 5 : "rafforzata".equals(s.fingerprint) ? 3 : 0;
        r.score = 5 + dnsPoints + proxyPoints + (s.httpsOnly ? 15 : 0) + trackerPoints + cookiePoints
            + fingerprintPoints + (s.javascript ? 0 : 5);
        r.level = level(r.score);
        r.vpnNote = !hasProxy;

        Observer sites = new Observer("I siti che visiti");
        sites.sees.add(hasProxy
            ? "Vedono l'indirizzo IP del proxy, non il tuo."
            : "Vedono il tuo indirizzo IP e la zona da cui ti colleghi, a meno che sul telefono sia attiva un'app VPN.");
        sites.sees.add(s.javascript
            ? "Vedono il tipo di browser e la lingua."
            : "Vedono solo il tipo di browser e la lingua: senza JavaScript non leggono altri dettagli del telefono.");
        if ("massima".equals(s.fingerprint)) {
            sites.sees.add("Grafica, audio, schermo e caratteri sono mascherati e cambiano a ogni sessione; in più vedono ora UTC e formati inglesi, come chi usa Tor Browser.");
        } else if ("rafforzata".equals(s.fingerprint)) {
            sites.sees.add("Grafica, audio, schermo e caratteri sono mascherati e cambiano a ogni sessione: l'impronta non ti segue da una sessione all'altra.");
        } else {
            sites.sees.add("L'impronta del browser è protetta solo dalle difese standard di Firefox.");
        }
        sites.sees.add(!s.webrtc
            ? "WebRTC è spento: non può rivelare il tuo indirizzo IP."
            : hasProxy
                ? "WebRTC passa solo dal proxy: non rivela il tuo indirizzo IP."
                : "WebRTC può mostrare il tuo IP pubblico, che i siti vedono comunque senza proxy o VPN.");
        sites.sees.add(s.blockTrackers
            ? "Tracker, cryptominer e tracciamento social vengono bloccati in modo rigoroso."
            : "Vengono bloccati solo i tracker più noti (protezione standard).");
        sites.sees.add(s.blockThirdPartyCookies
            ? "I servizi esterni incorporati non ricevono cookie."
            : "I cookie dei servizi esterni restano isolati sito per sito: non possono seguirti tra siti diversi.");
        sites.sees.add("Non trovano cronologia precedente: le schede private si cancellano alla chiusura.");
        r.observers.add(sites);

        Observer isp = new Observer("Il tuo operatore e la rete Wi-Fi");
        if (hasProxy && proxyEncrypted) {
            isp.sees.add("Vede solo che usi un proxy e quanto traffico scambi, non i siti.");
        } else if (hasProxy) {
            isp.sees.add("Vede i nomi dei siti: il collegamento a un proxy SOCKS5 o HTTP remoto non è cifrato.");
        } else if (doh) {
            isp.sees.add("Vede i nomi dei siti a cui ti colleghi, ma non le pagine che apri né i contenuti.");
        } else {
            isp.sees.add("Vede i nomi di tutti i siti, anche dalle richieste DNS in chiaro, ma non i contenuti delle pagine HTTPS.");
        }
        if (!s.httpsOnly) isp.sees.add("Può leggere le pagine dei siti che non usano HTTPS.");
        r.observers.add(isp);

        Observer dns = new Observer("Il servizio DNS");
        if (socks) {
            dns.sees.add("Con SOCKS5 i nomi dei siti li risolve il proxy: il servizio DNS non li riceve.");
        } else if (doh) {
            dns.sees.add(dnsName + " riceve, cifrati, i nomi dei siti che visiti.");
            if (NO_LOG_DNS.contains(s.dns)) dns.sees.add("Dichiara di non conservare il tuo indirizzo IP.");
        } else {
            dns.sees.add("Il DNS di Android o della VPN riceve i nomi di tutti i siti, di solito senza cifratura.");
        }
        r.observers.add(dns);

        if (hasProxy) {
            Observer proxy = new Observer("Il gestore del proxy");
            proxy.sees.add("Vede il tuo indirizzo IP reale e i nomi dei siti che visiti, non il contenuto delle pagine HTTPS.");
            if (!s.httpsOnly) proxy.sees.add("Può leggere le pagine non cifrate.");
            r.observers.add(proxy);
        }

        if (!hasProxy) r.tips.add(new Tip("Usa un'app VPN o un proxy SOCKS5 (es. Orbot) per nascondere il tuo IP ai siti.", 25));
        else if (!proxyEncrypted) r.tips.add(new Tip("Usa un proxy HTTPS, o SOCKS5 dentro una VPN, per cifrare anche il collegamento al proxy.", 10));
        if (!doh) r.tips.add(new Tip("Scegli un DNS cifrato, per esempio Quad9 o Mullvad.", 20));
        else if (dnsPoints < 20) r.tips.add(new Tip("Quad9, Mullvad e AdGuard dichiarano di non conservare il tuo IP.", 20 - dnsPoints));
        if (!s.httpsOnly) r.tips.add(new Tip("Attiva «Solo HTTPS».", 15));
        if (!s.blockTrackers) r.tips.add(new Tip("Attiva la protezione rigorosa dai tracker.", 7));
        if (!s.blockThirdPartyCookies) r.tips.add(new Tip("Blocca del tutto i cookie di terze parti.", 4));
        if ("standard".equals(s.fingerprint)) r.tips.add(new Tip("Scegli la protezione dall'impronta «Rafforzata».", 3));
        else if ("rafforzata".equals(s.fingerprint)) r.tips.add(new Tip("Con l'impronta «Massima» sembri uguale a molti altri utenti (ora UTC, formati inglesi).", 2));
        if (s.javascript) r.tips.add(new Tip("Disattivare JavaScript riduce ancora le informazioni sul telefono, ma molti siti smettono di funzionare.", 5));
        return r;
    }
}
