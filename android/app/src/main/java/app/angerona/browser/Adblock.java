package app.angerona.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * uBlock Origin dentro il motore Firefox: blocca pubblicità, tracker, pop-up e banner con le stesse
 * liste usate da Brave, più filtri cosmetici e scriptlet. Si installa dal sito dei componenti
 * aggiuntivi di Mozilla (file firmato da Mozilla, verificato dal motore), passando dal proxy e dal
 * DNS scelti; poi si aggiorna da solo una volta al giorno. Funziona anche nelle schede private.
 */
final class Adblock {
    static final String ID = "uBlock0@raymondhill.net";
    private static final String TAG = "Angerona";
    private static final String INSTALL_URL = "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi";
    private static final long UPDATE_EVERY_MS = 24L * 3600 * 1000;
    private static final String PREFS = "angerona";
    private static final String KEY_UPDATED = "adblockCheckedAt";

    /** Ciò che serve dall'interfaccia: finestra di uBlock Origin e schede aperte o chiuse dall'estensione. */
    interface Host {
        GeckoSession openPopup();
        GeckoSession openTab(boolean active);
        void openUrl(String url);
        void closeTab(GeckoSession session);
        void selectTab(GeckoSession session);
    }

    private final Context context;
    private final WebExtensionController controller;
    private final List<Consumer<WebExtension>> listeners = new ArrayList<>();
    private WebExtension extension;
    private WebExtension.Action action;
    private Host host;
    private boolean wanted;
    private boolean started;
    private boolean installing;
    private String status = "In attesa della connessione…";

    Adblock(Context context, GeckoRuntime runtime, boolean enabled) {
        this.context = context.getApplicationContext();
        this.controller = runtime.getWebExtensionController();
        this.wanted = enabled;
        // Si accettano installazione e aggiornamenti solo di uBlock Origin; nessun'altra estensione.
        controller.setPromptDelegate(new WebExtensionController.PromptDelegate() {
            @Override
            public GeckoResult<WebExtension.PermissionPromptResponse> onInstallPromptRequest(
                    WebExtension ext, String[] permissions, String[] origins, String[] dataCollection) {
                boolean ok = ID.equals(ext.id);
                return GeckoResult.fromValue(new WebExtension.PermissionPromptResponse(ok, ok, false));
            }

            @Override
            public GeckoResult<AllowOrDeny> onUpdatePrompt(WebExtension ext, String[] permissions, String[] origins, String[] dataCollection) {
                return GeckoResult.fromValue(ID.equals(ext.id) ? AllowOrDeny.ALLOW : AllowOrDeny.DENY);
            }

            @Override
            public GeckoResult<AllowOrDeny> onOptionalPrompt(WebExtension ext, String[] permissions, String[] origins, String[] dataCollection) {
                return GeckoResult.fromValue(AllowOrDeny.DENY);
            }
        });
    }

    String status() {
        return status;
    }

    boolean ready() {
        return extension != null && wanted;
    }

    /** Pagina delle impostazioni di uBlock Origin (liste dei filtri, siti esclusi), o null. */
    String optionsUrl() {
        return extension == null ? null : extension.metaData.optionsPageUrl;
    }

    void setHost(Host h) {
        host = h;
    }

    /** Avvisato subito se uBlock Origin è già pronto, poi a ogni installazione o aggiornamento. */
    void addListener(Consumer<WebExtension> l) {
        listeners.add(l);
        if (extension != null) l.accept(extension);
    }

    void removeListener(Consumer<WebExtension> l) {
        listeners.remove(l);
    }

    /** Collega una scheda: uBlock Origin può chiudere i pop-up pubblicitari e cambiare scheda. */
    void attach(GeckoSession session) {
        if (extension == null) return;
        session.getWebExtensionController().setTabDelegate(extension, sessionTabs);
    }

    /** Apre la finestra di uBlock Origin per la scheda attiva (accendere o spegnere il blocco sul sito). */
    boolean openPanel() {
        if (!ready() || action == null) return false;
        action.click();
        return true;
    }

    /** Da chiamare quando il proxy è attivo: da lì in poi installazione e aggiornamenti passano dal proxy. */
    void start() {
        if (started) return;
        started = true;
        controller.list().accept(list -> {
            WebExtension found = null;
            if (list != null) for (WebExtension e : list) if (ID.equals(e.id)) found = e;
            if (found != null) {
                configure(found);
                checkForUpdate();
            } else if (wanted) {
                install();
            } else {
                status = "Spento";
            }
        }, e -> {
            Log.e(TAG, "Elenco delle estensioni non disponibile", e);
            status = "Non disponibile: riprovo al prossimo avvio";
        });
    }

    void setEnabled(boolean on) {
        wanted = on;
        if (extension != null) {
            syncEnabled();
        } else if (on && started && !installing) {
            install();
        } else if (!on) {
            status = "Spento";
        }
    }

    private void install() {
        installing = true;
        status = "Installazione di uBlock Origin…";
        controller.install(INSTALL_URL, WebExtensionController.INSTALLATION_METHOD_MANAGER).accept(ext -> {
            installing = false;
            if (ext == null) {
                status = "Non installato: riprovo al prossimo avvio";
                return;
            }
            prefs().edit().putLong(KEY_UPDATED, System.currentTimeMillis()).apply();
            configure(ext);
        }, e -> {
            installing = false;
            Log.e(TAG, "Installazione di uBlock Origin non riuscita", e);
            status = "Non installato: controlla la connessione, riprovo al prossimo avvio";
        });
    }

    private void configure(WebExtension ext) {
        controller.setAllowedInPrivateBrowsing(ext, true).accept(
            allowed -> use(allowed != null ? allowed : ext),
            e -> use(ext));
    }

    private void use(WebExtension ext) {
        extension = ext;
        ext.setActionDelegate(actions);
        ext.setTabDelegate(tabs);
        syncEnabled();
        for (Consumer<WebExtension> l : new ArrayList<>(listeners)) l.accept(ext);
    }

    private void syncEnabled() {
        WebExtension ext = extension;
        if (ext == null) return;
        if (wanted && !ext.metaData.enabled) {
            controller.enable(ext, WebExtensionController.EnableSource.APP).accept(e -> { if (e != null) use(e); }, e -> { });
        } else if (!wanted && ext.metaData.enabled) {
            controller.disable(ext, WebExtensionController.EnableSource.APP).accept(e -> { if (e != null) use(e); }, e -> { });
        }
        status = wanted ? "Attivo · uBlock Origin " + ext.metaData.version : "Spento";
    }

    private void checkForUpdate() {
        long last = prefs().getLong(KEY_UPDATED, 0);
        if (extension == null || System.currentTimeMillis() - last < UPDATE_EVERY_MS) return;
        controller.update(extension).accept(updated -> {
            prefs().edit().putLong(KEY_UPDATED, System.currentTimeMillis()).apply();
            if (updated != null) configure(updated);
        }, e -> Log.w(TAG, "Aggiornamento di uBlock Origin non riuscito", e));
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------- Delegati dell'estensione ----------

    private final WebExtension.ActionDelegate actions = new WebExtension.ActionDelegate() {
        @Override
        public void onBrowserAction(WebExtension ext, GeckoSession session, WebExtension.Action a) {
            if (session == null) action = a; // azione predefinita: apre la finestra per la scheda attiva
        }

        @Override
        public GeckoResult<GeckoSession> onTogglePopup(WebExtension ext, WebExtension.Action a) {
            return popup();
        }

        @Override
        public GeckoResult<GeckoSession> onOpenPopup(WebExtension ext, WebExtension.Action a) {
            return popup();
        }

        private GeckoResult<GeckoSession> popup() {
            GeckoSession s = host == null ? null : host.openPopup();
            return s == null ? null : GeckoResult.fromValue(s);
        }
    };

    private final WebExtension.TabDelegate tabs = new WebExtension.TabDelegate() {
        @Override
        public GeckoResult<GeckoSession> onNewTab(WebExtension ext, WebExtension.CreateTabDetails details) {
            GeckoSession s = host == null ? null : host.openTab(details.active == null || details.active);
            return GeckoResult.fromValue(s);
        }

        @Override
        public void onOpenOptionsPage(WebExtension ext) {
            String url = ext.metaData.optionsPageUrl;
            if (host != null && url != null) host.openUrl(url);
        }
    };

    private final WebExtension.SessionTabDelegate sessionTabs = new WebExtension.SessionTabDelegate() {
        @Override
        public GeckoResult<AllowOrDeny> onCloseTab(WebExtension ext, GeckoSession session) {
            if (host == null) return GeckoResult.fromValue(AllowOrDeny.DENY);
            host.closeTab(session);
            return GeckoResult.fromValue(AllowOrDeny.ALLOW);
        }

        @Override
        public GeckoResult<AllowOrDeny> onUpdateTab(WebExtension ext, GeckoSession session, WebExtension.UpdateTabDetails details) {
            if (host == null) return GeckoResult.fromValue(AllowOrDeny.DENY);
            if (Boolean.TRUE.equals(details.active)) host.selectTab(session);
            return GeckoResult.fromValue(AllowOrDeny.ALLOW);
        }
    };
}
