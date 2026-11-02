package app.angerona.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Patterns;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Impostazioni di Angerona: valori, validazione, salvataggio (password del proxy cifrata). */
public final class Settings {

    /** id, etichetta, indirizzo DoH (null = calcolato o nessuno). */
    public static final String[][] DNS = {
        {"quad9", "Quad9 — blocco malware, Svizzera", "https://dns.quad9.net/dns-query"},
        {"cloudflare", "Cloudflare 1.1.1.1", "https://cloudflare-dns.com/dns-query"},
        {"cloudflare-security", "Cloudflare — blocco malware", "https://security.cloudflare-dns.com/dns-query"},
        {"mullvad", "Mullvad DNS", "https://dns.mullvad.net/dns-query"},
        {"mullvad-adblock", "Mullvad DNS — blocco pubblicità", "https://adblock.dns.mullvad.net/dns-query"},
        {"adguard", "AdGuard DNS — blocco pubblicità", "https://dns.adguard-dns.com/dns-query"},
        {"nextdns", "NextDNS — con il tuo ID", null},
        {"custom", "Personalizzato (indirizzo DoH)", null},
        {"system", "DNS di Android o della VPN", null},
    };

    public static final String[][] SEARCH = {
        {"duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s"},
        {"startpage", "Startpage", "https://www.startpage.com/sp/search?query=%s"},
        {"brave", "Brave Search", "https://search.brave.com/search?q=%s"},
        {"mojeek", "Mojeek", "https://www.mojeek.com/search?q=%s"},
        {"qwant", "Qwant", "https://www.qwant.com/?q=%s"},
        {"searxng", "SearXNG — la tua istanza", null},
    };

    public static final String[][] PROXY = {
        {"none", "Nessuno (diretto o app VPN)"},
        {"socks5", "SOCKS5"},
        {"http", "HTTP"},
        {"https", "HTTPS"},
    };

    public String dns = "quad9";
    public String nextdnsId = "";
    public String customDoh = "";
    public String proxyMode = "none";
    public String proxyHost = "";
    public String proxyPort = "";
    public String proxyUser = "";
    public String proxyPass = "";
    public String search = "duckduckgo";
    public String searxngUrl = "";
    public boolean httpsOnly = true;
    public boolean blockTrackers = true;
    public boolean blockThirdPartyCookies = true;
    public boolean javascript = true;
    public boolean secureScreen = false;

    private static final String PREFS = "angerona";
    private static final String KEY_ALIAS = "angerona-proxy";

    public Settings copy() {
        Settings s = new Settings();
        s.dns = dns; s.nextdnsId = nextdnsId; s.customDoh = customDoh;
        s.proxyMode = proxyMode; s.proxyHost = proxyHost; s.proxyPort = proxyPort;
        s.proxyUser = proxyUser; s.proxyPass = proxyPass;
        s.search = search; s.searxngUrl = searxngUrl;
        s.httpsOnly = httpsOnly; s.blockTrackers = blockTrackers;
        s.blockThirdPartyCookies = blockThirdPartyCookies; s.javascript = javascript;
        s.secureScreen = secureScreen;
        return s;
    }

    public boolean proxyEnabled() {
        return !"none".equals(proxyMode);
    }

    static int indexOf(String[][] table, String id) {
        for (int i = 0; i < table.length; i++) if (table[i][0].equals(id)) return i;
        return 0;
    }

    static String[] labels(String[][] table) {
        String[] out = new String[table.length];
        for (int i = 0; i < table.length; i++) out[i] = table[i][1];
        return out;
    }

    static String label(String[][] table, String id) {
        return table[indexOf(table, id)][1];
    }

    /** Indirizzo DoH da usare, oppure null per il DNS di sistema. */
    public String dohUrl() {
        if ("system".equals(dns)) return null;
        if ("nextdns".equals(dns)) return "https://dns.nextdns.io/" + nextdnsId;
        if ("custom".equals(dns)) return customDoh;
        return DNS[indexOf(DNS, dns)][2];
    }

    public String searchUrl(String query) {
        String q = Uri.encode(query);
        if ("searxng".equals(search)) return searxngUrl + "/search?q=" + q;
        return SEARCH[indexOf(SEARCH, search)][2].replace("%s", q);
    }

    /** Testo della barra indirizzi: un indirizzo da aprire oppure una ricerca. */
    public String toUrl(String input) {
        String t = input == null ? "" : input.trim();
        if (t.isEmpty()) return null;
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://") || lower.startsWith("http://") || lower.equals("about:blank")) return t;
        if (!t.contains(" ")) {
            String host = t.split("[/?#]", 2)[0].replaceFirst(":\\d{1,5}$", "");
            boolean ip = Patterns.IP_ADDRESS.matcher(host).matches();
            boolean domain = host.contains(".") && Patterns.DOMAIN_NAME.matcher(host).matches()
                && Patterns.WEB_URL.matcher(t).matches();
            if (ip || domain || "localhost".equalsIgnoreCase(host)) return "https://" + t;
        }
        return searchUrl(t);
    }

    static boolean isHttpsUrl(String v) {
        try {
            Uri u = Uri.parse(v);
            return "https".equals(u.getScheme()) && u.getHost() != null && !u.getHost().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isWebUrl(String v) {
        try {
            Uri u = Uri.parse(v);
            String s = u.getScheme();
            return ("https".equals(s) || "http".equals(s)) && u.getHost() != null && !u.getHost().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** Errori per campo (chiave = nome del campo). Vuoto se tutto è valido. */
    public Map<String, String> validate() {
        Map<String, String> e = new LinkedHashMap<>();
        if ("nextdns".equals(dns) && !nextdnsId.matches("[A-Za-z0-9]{4,20}")) {
            e.put("nextdns", "Inserisci il tuo ID NextDNS (lo trovi su my.nextdns.io)");
        }
        if ("custom".equals(dns) && !isHttpsUrl(customDoh)) {
            e.put("doh", "Serve un indirizzo che inizi con https://");
        }
        if (proxyEnabled()) {
            if (!proxyHost.matches("[A-Za-z0-9.\\-:]+")) e.put("host", "Indirizzo non valido");
            int port = -1;
            try { port = Integer.parseInt(proxyPort); } catch (NumberFormatException ignored) { }
            if (port < 1 || port > 65535) e.put("port", "La porta va da 1 a 65535");
        }
        if ("searxng".equals(search) && !isWebUrl(searxngUrl)) {
            e.put("searxng", "Inserisci l'indirizzo della tua istanza");
        }
        return e;
    }

    // ---------- Salvataggio ----------

    public static Settings load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Settings s = new Settings();
        s.dns = p.getString("dns", s.dns);
        s.nextdnsId = p.getString("nextdnsId", s.nextdnsId);
        s.customDoh = p.getString("customDoh", s.customDoh);
        s.proxyMode = p.getString("proxyMode", s.proxyMode);
        s.proxyHost = p.getString("proxyHost", s.proxyHost);
        s.proxyPort = p.getString("proxyPort", s.proxyPort);
        s.proxyUser = p.getString("proxyUser", s.proxyUser);
        s.proxyPass = decrypt(p.getString("proxyPassEnc", ""));
        s.search = p.getString("search", s.search);
        s.searxngUrl = p.getString("searxngUrl", s.searxngUrl);
        s.httpsOnly = p.getBoolean("httpsOnly", s.httpsOnly);
        s.blockTrackers = p.getBoolean("blockTrackers", s.blockTrackers);
        s.blockThirdPartyCookies = p.getBoolean("blockThirdPartyCookies", s.blockThirdPartyCookies);
        s.javascript = p.getBoolean("javascript", s.javascript);
        s.secureScreen = p.getBoolean("secureScreen", s.secureScreen);
        // Valori sconosciuti (versioni future o file alterati) tornano ai predefiniti.
        if (!s.dns.equals(DNS[indexOf(DNS, s.dns)][0])) s.dns = "quad9";
        if (!s.search.equals(SEARCH[indexOf(SEARCH, s.search)][0])) s.search = "duckduckgo";
        if (!s.proxyMode.equals(PROXY[indexOf(PROXY, s.proxyMode)][0])) s.proxyMode = "none";
        if (!s.validate().isEmpty()) {
            if (s.validate().containsKey("nextdns") || s.validate().containsKey("doh")) s.dns = "quad9";
            if (s.validate().containsKey("searxng")) s.search = "duckduckgo";
        }
        return s;
    }

    public void save(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("dns", dns)
            .putString("nextdnsId", nextdnsId)
            .putString("customDoh", customDoh)
            .putString("proxyMode", proxyMode)
            .putString("proxyHost", proxyHost)
            .putString("proxyPort", proxyPort)
            .putString("proxyUser", proxyUser)
            .putString("proxyPassEnc", encrypt(proxyPass))
            .putString("search", search)
            .putString("searxngUrl", searxngUrl)
            .putBoolean("httpsOnly", httpsOnly)
            .putBoolean("blockTrackers", blockTrackers)
            .putBoolean("blockThirdPartyCookies", blockThirdPartyCookies)
            .putBoolean("javascript", javascript)
            .putBoolean("secureScreen", secureScreen)
            .commit();
    }

    // ---------- Cifratura con il Keystore di Android ----------

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        return kg.generateKey();
    }

    private static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
        } catch (Exception e) {
            return ""; // se il Keystore non è disponibile la password non viene salvata
        }
    }

    private static String decrypt(String stored) {
        if (stored == null || !stored.contains(":")) return "";
        try {
            String[] parts = stored.split(":", 2);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
