package app.angerona.browser;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Motore Firefox (GeckoView) condiviso da tutte le schede: DNS cifrato, HTTPS-only,
 * protezione dai tracker e proxy tramite un'estensione interna che blocca tutto finché
 * non ha ricevuto la configurazione (nessuna fuga all'avvio).
 */
public final class Engine {
    private static final String TAG = "Angerona";
    private static final String EXT_URI = "resource://android/assets/extensions/proxy/";
    private static final String EXT_ID = "proxy@angerona.app";
    private static final String NATIVE_APP = "angerona";
    private static final long READY_TIMEOUT_MS = 20000;

    private static Engine instance;

    public final GeckoRuntime runtime;
    private Settings settings;
    private WebExtension.Port port;
    private boolean proxyApplied;
    private boolean extensionFailed;
    private final List<Consumer<Boolean>> waiters = new ArrayList<>();
    private final List<Runnable> lateListeners = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    public static synchronized Engine get(Context ctx) {
        if (instance == null) instance = new Engine(ctx.getApplicationContext());
        return instance;
    }

    private Engine(Context ctx) {
        settings = Settings.load(ctx);
        GeckoRuntimeSettings.Builder b = new GeckoRuntimeSettings.Builder()
            .contentBlocking(contentBlocking(settings))
            .allowInsecureConnections(settings.httpsOnly ? GeckoRuntimeSettings.HTTPS_ONLY : GeckoRuntimeSettings.ALLOW_ALL)
            .javaScriptEnabled(settings.javascript)
            .globalPrivacyControlEnabled(true)
            .loginAutofillEnabled(false)
            .aboutConfigEnabled(false)
            .remoteDebuggingEnabled(false)
            .consoleOutput(false)
            .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM);
        String doh = settings.dohUrl();
        if (doh != null) {
            b.trustedRecursiveResolverUri(doh).trustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_ONLY);
        } else {
            b.trustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_DISABLED);
        }
        runtime = GeckoRuntime.create(ctx, b.build());
        runtime.getSettings().setFingerprintingProtection(true);
        runtime.getSettings().setFingerprintingProtectionPrivateBrowsing(true);
        runtime.getSettings().setFingerprintingProtectionOverrides(fingerprintOverrides(settings));
        runtime.getSettings().setLocales(locales(settings));
        installProxyExtension();
    }

    public Settings settings() {
        return settings.copy();
    }

    // Protezioni aggiuntive dall'impronta (nomi dei bersagli in toolkit/components/resistfingerprinting/RFPTargets.inc).
    // Scelte per non rompere i siti: niente blocco del canvas, niente finestre a dimensione fissa, colori e tema intatti.
    // Niente ScreenRect/ScreenAvailRect/CSSDeviceSize: su Android danno uno schermo 0x0, raro (quindi identificante)
    // e capace di rompere le pagine. La scheda video si presenta come "Mozilla", uguale per tutti.
    private static final String FP_RAFFORZATA = "+CanvasRandomization,+EfficientCanvasRandomization,+WebGLRandomization,"
        + "+WebGLRenderInfo,+WebGLVendorConstant,+WebGLRendererConstant,+AudioContext,+AudioSampleRate,"
        + "+FontVisibilityBaseSystem,+FontVisibilityLangPack,+NavigatorHWConcurrencyTiered,+MaxTouchPoints,"
        + "+MediaDevices,+MediaCapabilities,+NetworkConnection,+DeviceSensors,+ScreenPixelDepth,+CSSColorInfo,"
        + "+SpeechSynthesis,+Gamepad,+WebGPULimits,+DiskStorageLimit,+NavigatorOscpu,+NavigatorBuildID,"
        + "+NavigatorAppVersion,+NavigatorPlatform";
    // In più, come Tor Browser: fuso orario UTC, formati inglesi, timer meno precisi, processore generico.
    private static final String FP_MASSIMA = FP_RAFFORZATA + ",+JSDateTimeUTC,+JSLocale,+ReduceTimerPrecision,+NavigatorHWConcurrency";

    /**
     * User agent con la versione di Android bloccata a 10, come fa Firefox sui telefoni più vecchi:
     * "Android 16" con l'ultimo Firefox è raro e quindi riconoscibile. Null = quello predefinito.
     */
    static String userAgent(Settings s) {
        if ("standard".equals(s.fingerprint)) return null;
        String ua = GeckoSession.getDefaultUserAgent();
        return ua.replaceFirst("\\(Android [0-9.]+; Mobile;", "(Android 10; Mobile;");
    }

    /** Lingue chieste ai siti: con «Massima» solo inglese, come Tor Browser; altrimenti quelle del telefono. */
    static String[] locales(Settings s) {
        if ("massima".equals(s.fingerprint)) return new String[] {"en-US", "en"};
        return android.os.LocaleList.getDefault().toLanguageTags().split(",");
    }

    static String fingerprintOverrides(Settings s) {
        if ("massima".equals(s.fingerprint)) return FP_MASSIMA;
        if ("rafforzata".equals(s.fingerprint)) return FP_RAFFORZATA;
        return "";
    }

    private static int cookieBehavior(Settings s) {
        return s.blockThirdPartyCookies
            ? ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY
            : ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS;
    }

    private static ContentBlocking.Settings contentBlocking(Settings s) {
        int cookies = cookieBehavior(s);
        return new ContentBlocking.Settings.Builder()
            .antiTracking(s.blockTrackers ? ContentBlocking.AntiTracking.STRICT : ContentBlocking.AntiTracking.DEFAULT)
            .enhancedTrackingProtectionLevel(s.blockTrackers ? ContentBlocking.EtpLevel.STRICT : ContentBlocking.EtpLevel.DEFAULT)
            .strictSocialTrackingProtection(s.blockTrackers)
            .cookieBehavior(cookies)
            .cookieBehaviorPrivateMode(cookies)
            .queryParameterStrippingEnabled(true)
            .queryParameterStrippingPrivateBrowsingEnabled(true)
            .build();
    }

    /** Applica subito le nuove impostazioni a tutte le schede. */
    public void apply(Settings s) {
        settings = s.copy();
        GeckoRuntimeSettings rs = runtime.getSettings();
        String doh = s.dohUrl();
        if (doh != null) {
            rs.setTrustedRecursiveResolverUri(doh);
            rs.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_ONLY);
        } else {
            rs.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_DISABLED);
        }
        rs.setAllowInsecureConnections(s.httpsOnly ? GeckoRuntimeSettings.HTTPS_ONLY : GeckoRuntimeSettings.ALLOW_ALL);
        rs.setJavaScriptEnabled(s.javascript);
        rs.setFingerprintingProtectionOverrides(fingerprintOverrides(s));
        rs.setLocales(locales(s));
        int cookies = cookieBehavior(s);
        ContentBlocking.Settings cb = rs.getContentBlocking();
        cb.setAntiTracking(s.blockTrackers ? ContentBlocking.AntiTracking.STRICT : ContentBlocking.AntiTracking.DEFAULT);
        cb.setEnhancedTrackingProtectionLevel(s.blockTrackers ? ContentBlocking.EtpLevel.STRICT : ContentBlocking.EtpLevel.DEFAULT);
        cb.setStrictSocialTrackingProtection(s.blockTrackers);
        cb.setCookieBehavior(cookies);
        cb.setCookieBehaviorPrivateMode(cookies);
        sendProxyConfig();
    }

    // ---------- Proxy tramite estensione interna ----------

    private void installProxyExtension() {
        final WebExtensionController controller = runtime.getWebExtensionController();
        controller.ensureBuiltIn(EXT_URI, EXT_ID)
            .then(ext -> controller.setAllowedInPrivateBrowsing(ext, true))
            .accept(ext -> {
                if (ext == null) { fail(null); return; }
                ext.setMessageDelegate(messageDelegate, NATIVE_APP);
            }, this::fail);
        main.postDelayed(() -> {
            if (!proxyApplied && !extensionFailed) fail(new IllegalStateException("estensione proxy in ritardo"));
        }, READY_TIMEOUT_MS);
    }

    private void fail(Throwable t) {
        Log.e(TAG, "Estensione proxy non disponibile", t);
        extensionFailed = true;
        notifyWaiters(false);
    }

    private final WebExtension.MessageDelegate messageDelegate = new WebExtension.MessageDelegate() {
        @Override
        public void onConnect(WebExtension.Port p) {
            port = p;
            p.setDelegate(new WebExtension.PortDelegate() {
                @Override
                public void onPortMessage(Object message, WebExtension.Port from) {
                    if (message instanceof JSONObject && "applied".equals(((JSONObject) message).optString("type"))) {
                        proxyApplied = true;
                        extensionFailed = false;
                        notifyWaiters(true);
                        List<Runnable> late = new ArrayList<>(lateListeners);
                        lateListeners.clear();
                        for (Runnable r : late) r.run();
                    }
                }

                @Override
                public void onDisconnect(WebExtension.Port from) {
                    if (port == from) port = null;
                }
            });
            sendProxyConfig();
        }
    };

    private void sendProxyConfig() {
        if (port == null) return;
        try {
            JSONObject cfg = new JSONObject()
                .put("mode", settings.proxyMode)
                .put("host", settings.proxyHost)
                .put("port", settings.proxyEnabled() ? Integer.parseInt(settings.proxyPort) : 0)
                .put("user", settings.proxyUser)
                .put("pass", settings.proxyPass)
                .put("webrtc", settings.webrtc);
            port.postMessage(new JSONObject().put("type", "config").put("config", cfg));
        } catch (JSONException | NumberFormatException e) {
            Log.e(TAG, "Configurazione proxy non valida", e);
        }
    }

    /**
     * Chiama cb(true) quando il proxy (o la sua assenza) è applicato, cb(false) se l'estensione
     * non risponde. Prima di allora nessuna pagina va caricata.
     */
    public void whenReady(Consumer<Boolean> cb) {
        if (proxyApplied) { cb.accept(true); return; }
        if (extensionFailed) { cb.accept(false); return; }
        waiters.add(cb);
    }

    /** true quando l'estensione ha confermato la configurazione del proxy. */
    public boolean isProxyApplied() {
        return proxyApplied;
    }

    /** Eseguito appena il proxy risulta applicato (subito se lo è già). */
    public void onLateReady(Runnable r) {
        if (proxyApplied) r.run(); else lateListeners.add(r);
    }

    /** Si può navigare? Con un proxy impostato serve la conferma dell'estensione. */
    public boolean canBrowse() {
        return proxyApplied || !settings.proxyEnabled();
    }

    private void notifyWaiters(boolean ok) {
        List<Consumer<Boolean>> list = new ArrayList<>(waiters);
        waiters.clear();
        for (Consumer<Boolean> cb : list) cb.accept(ok);
    }
}
