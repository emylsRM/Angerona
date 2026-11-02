package app.angerona.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.BasicSelectionActionDelegate;
import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.StorageController;
import org.mozilla.geckoview.WebRequestError;
import org.mozilla.geckoview.WebResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Schermata principale: schede, barra indirizzi in basso, menu. */
public class MainActivity extends Activity {
    private static final int REQUEST_SETTINGS = 4202;
    static final String EXTRA_ACTION = "angerona.action";
    static final String EXTRA_URL = "angerona.url";
    static final String CHECK_URL = "https://mullvad.net/it/check";

    private Engine engine;
    private Prompts prompts;
    private Updater updater;
    private GeckoView geckoView;
    private EditText address;
    private ImageView siteState;
    private TextView shield;
    private TextView burn;
    /** Tracker bloccati da quando l'app è aperta (o dall'ultima cancellazione). */
    private int sessionBlocked;
    private TextView tabCount;
    private ProgressBar progress;
    private View toolbar;
    private View findBar;
    private EditText findInput;
    private TextView findCount;

    private final List<Tab> tabs = new ArrayList<>();
    private Tab current;
    private boolean ready;
    private boolean fullscreen;
    private String pendingUrl;
    private Object backCallback;
    private boolean backRegistered;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        geckoView = findViewById(R.id.gecko);
        address = findViewById(R.id.address);
        siteState = findViewById(R.id.site_state);
        shield = findViewById(R.id.shield);
        burn = findViewById(R.id.burn);
        tabCount = findViewById(R.id.tab_count);
        progress = findViewById(R.id.progress);
        toolbar = findViewById(R.id.toolbar);
        findBar = findViewById(R.id.findbar);
        findInput = findViewById(R.id.find_input);
        findCount = findViewById(R.id.find_count);

        setupWindow();
        setupToolbar();
        setupFindBar();
        setupBack();

        engine = Engine.get(this);
        prompts = new Prompts(this);
        updater = new Updater(this, engine, url -> newTab(url, true, true));
        pendingUrl = urlFromIntent(getIntent());
        // Nessuna pagina parte prima che il proxy (o la sua assenza) sia applicato.
        engine.whenReady(ok -> {
            if (isFinishing() || isDestroyed()) return;
            ready = true;
            if (!ok) {
                Tab t = newTab(null, true, false);
                t.session.load(new GeckoSession.Loader().data(Pages.extensionError(), "text/html"));
                // Se l'estensione risponde in ritardo, si riparte normalmente.
                engine.onLateReady(() -> {
                    if (!tabs.contains(t)) return;
                    if (pendingUrl != null) { t.session.loadUri(pendingUrl); pendingUrl = null; } else loadStart(t);
                });
                return;
            }
            newTab(pendingUrl, true, true);
            pendingUrl = null;
            if (engine.settings().checkUpdates) {
                geckoView.postDelayed(() -> { if (!isFinishing()) updater.check(false); }, 5000);
            }
        });
    }

    // ---------- Finestra, barre di sistema e tastiera ----------

    private void setupWindow() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        View root = findViewById(R.id.root);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                int bottom = Math.max(bars.bottom, ime.bottom);
                v.setPadding(bars.left, fullscreen ? 0 : bars.top, bars.right, fullscreen ? 0 : bottom);
                return WindowInsets.CONSUMED;
            });
        } else {
            root.setFitsSystemWindows(true);
        }
    }

    private void applyScreenProtection() {
        if (engine != null && engine.settings().secureScreen) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    private void setFullscreen(boolean on) {
        fullscreen = on;
        toolbar.setVisibility(on ? View.GONE : View.VISIBLE);
        if (on) closeFind();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                if (on) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    c.show(WindowInsets.Type.systemBars());
                }
            }
            findViewById(R.id.root).requestApplyInsets();
        } else {
            int flags = on
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                : View.SYSTEM_UI_FLAG_VISIBLE;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
        updateBack();
    }

    // ---------- Barra degli strumenti ----------

    private void setupToolbar() {
        address.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                navigate(address.getText().toString());
                return true;
            }
            return false;
        });
        address.setOnFocusChangeListener((v, focused) -> {
            if (!focused) render();
        });
        findViewById(R.id.address_box).setOnClickListener(v -> focusAddress());
        shield.setOnClickListener(v -> {
            if (current == null) return;
            String msg = engine.settings().blockTrackers
                ? (current.blocked == 1 ? "1 tracker bloccato in questa pagina" : current.blocked + " tracker bloccati in questa pagina")
                : "Protezione standard: attiva quella rigorosa nelle impostazioni";
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.tabs_button).setOnClickListener(v -> showTabs());
        burn.setOnClickListener(v -> showBurn());
        findViewById(R.id.menu).setOnClickListener(this::showMenu);
    }

    private void focusAddress() {
        address.requestFocus();
        address.selectAll();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(address, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        v.clearFocus();
    }

    private void navigate(String input) {
        String url = engine.settings().toUrl(input);
        hideKeyboard(address);
        if (url == null) { render(); return; }
        if (current == null) { newTab(url, true, true); return; }
        current.errorUrl = null;
        current.session.loadUri(url);
        geckoView.requestFocus();
    }

    private void render() {
        tabCount.setText(tabs.size() > 99 ? ":D" : String.valueOf(tabs.size()));
        burn.setText(sessionBlocked == 0 ? "" : sessionBlocked > 9999 ? "9999+" : String.valueOf(sessionBlocked));
        if (current == null) return;
        if (!address.hasFocus()) address.setText(current.displayUrl());

        if (current.errorUrl != null) {
            siteState.setImageResource(R.drawable.ic_alert);
            siteState.setColorFilter(getColor(R.color.warn));
        } else if (current.internal()) {
            siteState.setImageResource(R.drawable.ic_search);
            siteState.setColorFilter(getColor(R.color.accent));
        } else if (current.secure) {
            siteState.setImageResource(R.drawable.ic_lock);
            siteState.setColorFilter(getColor(R.color.ok));
        } else {
            siteState.setImageResource(R.drawable.ic_alert);
            siteState.setColorFilter(getColor(R.color.warn));
        }

        boolean strict = engine.settings().blockTrackers;
        shield.setText(current.blocked > 0 ? String.valueOf(current.blocked) : "");
        shield.setCompoundDrawablesRelativeWithIntrinsicBounds(strict ? R.drawable.ic_shield_check : R.drawable.ic_shield, 0, 0, 0);

        progress.setVisibility(current.loading && current.progress < 100 ? View.VISIBLE : View.GONE);
        progress.setProgress(Math.max(5, current.progress));
        updateBack();
    }

    // ---------- Schede ----------

    private Tab createTab() {
        GeckoSessionSettings.Builder b = new GeckoSessionSettings.Builder()
            .usePrivateMode(true)
            .useTrackingProtection(true)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE);
        String ua = Engine.userAgent(engine.settings());
        if (ua != null) b.userAgentOverride(ua);
        GeckoSession session = new GeckoSession(b.build());
        Tab tab = new Tab(session);
        attach(tab, session);
        return tab;
    }

    /** Crea una scheda, la apre e carica l'indirizzo (o la pagina iniziale se url è null). */
    private Tab newTab(String url, boolean activate, boolean load) {
        Tab tab = createTab();
        tab.session.open(engine.runtime);
        int pos = current == null ? tabs.size() : tabs.indexOf(current) + 1;
        tabs.add(Math.min(pos, tabs.size()), tab);
        if (activate) show(tab);
        if (load) {
            if (url == null) loadStart(tab); else tab.session.loadUri(url);
        }
        render();
        return tab;
    }

    private void loadStart(Tab tab) {
        tab.errorUrl = null;
        tab.session.load(new GeckoSession.Loader().data(Pages.start(engine.settings()), "text/html"));
    }

    private void show(Tab tab) {
        if (current == tab && geckoView.getSession() == tab.session) return;
        if (current != null && current != tab) current.session.setActive(false);
        if (geckoView.getSession() != null) geckoView.releaseSession();
        geckoView.setSession(tab.session);
        if (tab.session.isOpen()) tab.session.setActive(true);
        current = tab;
        closeFind();
        render();
    }

    private void closeTab(Tab tab) {
        int index = tabs.indexOf(tab);
        if (index < 0) return;
        tabs.remove(index);
        if (tab == current) {
            if (geckoView.getSession() != null) geckoView.releaseSession();
            current = null;
        }
        tab.session.close();
        if (tabs.isEmpty()) {
            newTab(null, true, true);
        } else if (current == null) {
            show(tabs.get(Math.min(index, tabs.size() - 1)));
        }
        render();
    }

    private void showTabs() {
        if (!ready) return;
        String[] items = new String[tabs.size()];
        for (int i = 0; i < tabs.size(); i++) {
            Tab t = tabs.get(i);
            String title = t.title == null || t.title.isEmpty() ? "Nuova scheda" : t.title;
            items[i] = (t == current ? "● " : "    ") + title;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(tabs.size() == 1 ? "1 scheda privata" : tabs.size() + " schede private")
            .setItems(items, (d, which) -> show(tabs.get(which)))
            .setPositiveButton("Nuova scheda", (d, w) -> newTab(null, true, true))
            .setNeutralButton("Chiudi questa", (d, w) -> { if (current != null) closeTab(current); })
            .create();
        dialog.show();
    }

    // ---------- Collegamento con il motore ----------

    private void attach(Tab tab, GeckoSession session) {
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onLocationChange(GeckoSession s, String url, List<GeckoSession.PermissionDelegate.ContentPermission> perms, Boolean hasUserGesture) {
                tab.url = url == null ? "" : url;
                if (!tab.internal()) tab.errorUrl = null;
                if (tab == current) render();
            }

            @Override
            public void onCanGoBack(GeckoSession s, boolean canGoBack) {
                tab.canBack = canGoBack;
                if (tab == current) updateBack();
            }

            @Override
            public void onCanGoForward(GeckoSession s, boolean canGoForward) {
                tab.canForward = canGoForward;
            }

            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s, LoadRequest request) {
                if (!allowedByProxy(request.uri)) return GeckoResult.fromValue(AllowOrDeny.DENY);
                return handleExternal(request) ? GeckoResult.fromValue(AllowOrDeny.DENY) : null;
            }

            @Override
            public GeckoResult<AllowOrDeny> onSubframeLoadRequest(GeckoSession s, LoadRequest request) {
                return allowedByProxy(request.uri) ? null : GeckoResult.fromValue(AllowOrDeny.DENY);
            }

            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession s, String uri) {
                Tab t = createTab();
                int pos = tabs.indexOf(tab) + 1;
                tabs.add(Math.max(0, Math.min(pos, tabs.size())), t);
                show(t);
                return GeckoResult.fromValue(t.session);
            }

            @Override
            public GeckoResult<String> onLoadError(GeckoSession s, String uri, WebRequestError error) {
                tab.errorUrl = uri;
                tab.loading = false;
                if (tab == current) render();
                return GeckoResult.fromValue(Pages.dataUri(Pages.error(uri, error)));
            }
        });

        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(GeckoSession s, String url) {
                tab.loading = true;
                tab.progress = 0;
                if (url != null && !url.startsWith("data:")) {
                    tab.blocked = 0;
                    tab.errorUrl = null;
                }
                if (tab == current) render();
            }

            @Override
            public void onPageStop(GeckoSession s, boolean success) {
                tab.loading = false;
                tab.progress = 100;
                if (tab == current) render();
            }

            @Override
            public void onProgressChange(GeckoSession s, int value) {
                tab.progress = value;
                if (tab == current) render();
            }

            @Override
            public void onSecurityChange(GeckoSession s, SecurityInformation info) {
                tab.secure = info != null && info.isSecure;
                if (tab == current) render();
            }
        });

        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onTitleChange(GeckoSession s, String title) {
                tab.title = title == null || title.isEmpty() ? tab.displayUrl() : title;
            }

            @Override
            public void onExternalResponse(GeckoSession s, WebResponse response) {
                Downloads.save(MainActivity.this, response);
            }

            @Override
            public void onCloseRequest(GeckoSession s) {
                closeTab(tab);
            }

            @Override
            public void onFullScreen(GeckoSession s, boolean full) {
                if (tab == current) setFullscreen(full);
            }

            @Override
            public void onContextMenu(GeckoSession s, int x, int y, ContextElement element) {
                showContextMenu(tab, element);
            }

            @Override
            public void onCrash(GeckoSession s) {
                recover(tab);
            }

            @Override
            public void onKill(GeckoSession s) {
                recover(tab);
            }
        });

        session.setContentBlockingDelegate(new ContentBlocking.Delegate() {
            @Override
            public void onContentBlocked(GeckoSession s, ContentBlocking.BlockEvent event) {
                tab.blocked++;
                sessionBlocked++;
                if (tab == current) render();
            }
        });

        // Nessun permesso a fotocamera, microfono, posizione, notifiche.
        session.setPermissionDelegate(new GeckoSession.PermissionDelegate() {
            @Override
            public void onAndroidPermissionsRequest(GeckoSession s, String[] permissions, Callback callback) {
                callback.reject();
            }

            @Override
            public GeckoResult<Integer> onContentPermissionRequest(GeckoSession s, ContentPermission perm) {
                return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
            }

            @Override
            public void onMediaPermissionRequest(GeckoSession s, String uri, MediaSource[] video, MediaSource[] audio, MediaCallback callback) {
                callback.reject();
            }
        });

        session.setPromptDelegate(prompts);
        session.setSelectionActionDelegate(new BasicSelectionActionDelegate(this));
    }

    /** Con un proxy impostato ma non ancora attivo, nessuna pagina web parte (niente fughe di IP). */
    private boolean allowedByProxy(String uri) {
        if (engine.canBrowse() || uri == null) return true;
        String lower = uri.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http:") && !lower.startsWith("https:") && !lower.startsWith("ws")) return true;
        Toast.makeText(this, "Proxy non ancora attivo: caricamento fermato", Toast.LENGTH_SHORT).show();
        return false;
    }

    /** Il processo della pagina si è chiuso: nuova sessione nella stessa scheda. */
    private void recover(Tab tab) {
        if (!tabs.contains(tab)) return;
        String url = tab.internal() ? null : tab.url;
        boolean wasCurrent = tab == current;
        if (wasCurrent && geckoView.getSession() != null) geckoView.releaseSession();
        if (tab.session.isOpen()) tab.session.close();
        Tab fresh = createTab();
        tab.session = fresh.session;
        attach(tab, tab.session);
        tab.session.open(engine.runtime);
        if (wasCurrent) {
            geckoView.setSession(tab.session);
            tab.session.setActive(true);
        }
        if (url == null) loadStart(tab); else tab.session.loadUri(url);
        Toast.makeText(this, "La pagina si era bloccata ed è stata ricaricata", Toast.LENGTH_SHORT).show();
    }

    /** Link verso altre app (telefono, mail, mappe…): solo con un tocco dell'utente e con conferma. */
    private boolean handleExternal(GeckoSession.NavigationDelegate.LoadRequest request) {
        Uri uri = Uri.parse(request.uri);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        switch (scheme) {
            case "http": case "https": case "data": case "about": case "blob": case "javascript": case "resource": case "moz-extension":
                return false;
            default:
                break;
        }
        if (!request.hasUserGesture) return true;
        final Intent intent;
        try {
            if ("intent".equals(scheme)) {
                intent = Intent.parseUri(request.uri, Intent.URI_INTENT_SCHEME);
                intent.setComponent(null);
                intent.setSelector(null);
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
            } else {
                intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
            }
        } catch (Exception e) {
            return true;
        }
        new AlertDialog.Builder(this)
            .setTitle("Aprire con un'altra app?")
            .setMessage("Il sito vuole aprire: " + (request.uri.length() > 120 ? request.uri.substring(0, 120) + "…" : request.uri))
            .setPositiveButton("Apri", (d, w) -> {
                try {
                    startActivity(intent);
                } catch (RuntimeException e) {
                    Toast.makeText(this, "Nessuna app può aprirlo", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Annulla", null)
            .show();
        return true;
    }

    private void showContextMenu(Tab tab, GeckoSession.ContentDelegate.ContextElement el) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        final String link = el.linkUri;
        final String src = el.srcUri;
        if (link != null && (link.startsWith("http://") || link.startsWith("https://"))) {
            labels.add("Apri in una nuova scheda");
            actions.add(() -> newTab(link, true, true));
            labels.add("Apri in background");
            actions.add(() -> {
                Tab bg = newTab(link, false, true);
                bg.session.setActive(false);
                Toast.makeText(this, "Aperto in una nuova scheda", Toast.LENGTH_SHORT).show();
            });
            labels.add("Copia link");
            actions.add(() -> copy(link));
            labels.add("Condividi link");
            actions.add(() -> share(link));
        }
        if (el.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE && src != null && src.startsWith("http")) {
            labels.add("Apri immagine");
            actions.add(() -> newTab(src, true, true));
            labels.add("Copia indirizzo immagine");
            actions.add(() -> copy(src));
        }
        if (labels.isEmpty()) return;
        String title = link != null ? link : src;
        new AlertDialog.Builder(this)
            .setTitle(title != null && title.length() > 80 ? title.substring(0, 80) + "…" : title)
            .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
            .show();
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("link", text));
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copiato", Toast.LENGTH_SHORT).show();
    }

    private void share(String text) {
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        try {
            startActivity(Intent.createChooser(send, "Condividi"));
        } catch (ActivityNotFoundException ignored) { }
    }

    // ---------- Menu ----------

    private static final int M_BACK = 1, M_FORWARD = 2, M_RELOAD = 3, M_NEW = 4, M_FIND = 5,
        M_HOME = 6, M_CHECK = 7, M_SETTINGS = 8, M_CLEAR_EXIT = 9;

    private void showMenu(View anchor) {
        if (!ready) return;
        PopupMenu menu = new PopupMenu(this, anchor);
        Menu m = menu.getMenu();
        m.add(0, M_BACK, 0, "Indietro").setEnabled(current != null && current.canBack);
        m.add(0, M_FORWARD, 1, "Avanti").setEnabled(current != null && current.canForward);
        m.add(0, M_RELOAD, 2, current != null && current.loading ? "Interrompi" : "Ricarica");
        m.add(0, M_NEW, 3, "Nuova scheda");
        m.add(0, M_HOME, 4, "Pagina iniziale");
        m.add(0, M_FIND, 5, "Trova nella pagina");
        m.add(0, M_CHECK, 6, "Test della privacy");
        m.add(0, M_SETTINGS, 7, "Impostazioni");
        m.add(0, M_CLEAR_EXIT, 8, "Cancella tutto ed esci");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case M_BACK: if (current != null) current.session.goBack(); return true;
                case M_FORWARD: if (current != null) current.session.goForward(); return true;
                case M_RELOAD:
                    if (current != null) {
                        if (current.loading) current.session.stop();
                        else if (current.errorUrl != null) current.session.loadUri(current.errorUrl);
                        else current.session.reload();
                    }
                    return true;
                case M_NEW: newTab(null, true, true); focusAddress(); return true;
                case M_HOME: if (current != null) loadStart(current); return true;
                case M_FIND: openFind(); return true;
                case M_CHECK: showPrivacyTests(); return true;
                case M_SETTINGS: startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_SETTINGS); return true;
                case M_CLEAR_EXIT: clearAll(true); return true;
                default: return false;
            }
        });
        menu.show();
    }

    private void showPrivacyTests() {
        String[] names = new String[PrivacyReport.TESTS.length];
        for (int i = 0; i < names.length; i++) names[i] = PrivacyReport.TESTS[i][0];
        int score = PrivacyReport.evaluate(engine.settings()).score;
        new AlertDialog.Builder(this)
            .setTitle("Test della privacy · punteggio " + score + "/100")
            .setItems(names, (d, which) -> newTab(PrivacyReport.TESTS[which][1], true, true))
            .show();
    }

    private static boolean isPrivacyTest(String url) {
        for (String[] t : PrivacyReport.TESTS) if (t[1].equals(url)) return true;
        return false;
    }

    /** Pulsante "brucia tutto": riepilogo dei tracker bloccati, poi cancella e chiude oppure continua. */
    private void showBurn() {
        if (!ready) return;
        String count = sessionBlocked == 1 ? "1 tracker" : String.format(Locale.ITALIAN, "%,d tracker", sessionBlocked);
        new AlertDialog.Builder(this)
            .setTitle("Cancella tutto")
            .setMessage("In questa sessione Angerona ha bloccato " + count + ".\n\n"
                + "Si chiudono tutte le schede e si cancellano cookie, cache, accessi e dati dei siti.")
            .setPositiveButton("Cancella e chiudi", (d, w) -> clearAll(true))
            .setNeutralButton("Cancella e continua", (d, w) -> clearAll(false))
            .setNegativeButton("Annulla", null)
            .show();
    }

    /** Chiude tutte le schede e cancella cookie, cache e dati dei siti. */
    private void clearAll(boolean exit) {
        sessionBlocked = 0;
        if (geckoView.getSession() != null) geckoView.releaseSession();
        for (Tab t : tabs) t.session.close();
        tabs.clear();
        current = null;
        engine.runtime.getStorageController().clearData(StorageController.ClearFlags.ALL).accept(v -> {
            if (exit) {
                finishAndRemoveTask();
            } else {
                newTab(null, true, true);
                Toast.makeText(this, "Dati di navigazione cancellati", Toast.LENGTH_SHORT).show();
            }
        }, e -> {
            if (exit) finishAndRemoveTask(); else newTab(null, true, true);
        });
    }

    // ---------- Trova nella pagina ----------

    private void setupFindBar() {
        findInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { find(false); }
        });
        findInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { find(false); return true; }
            return false;
        });
        findViewById(R.id.find_next).setOnClickListener(v -> find(false));
        findViewById(R.id.find_prev).setOnClickListener(v -> find(true));
        findViewById(R.id.find_close).setOnClickListener(v -> closeFind());
    }

    private void openFind() {
        if (current == null) return;
        findBar.setVisibility(View.VISIBLE);
        findInput.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT);
        updateBack();
    }

    private void find(boolean backwards) {
        if (current == null) return;
        String text = findInput.getText().toString();
        if (text.isEmpty()) {
            current.session.getFinder().clear();
            findCount.setText("");
            return;
        }
        int flags = backwards ? GeckoSession.FINDER_FIND_BACKWARDS : GeckoSession.FINDER_FIND_FORWARD;
        current.session.getFinder().find(text, flags).accept(r -> {
            if (r == null) return;
            findCount.setText(r.found ? r.current + " di " + r.total : "Nessun risultato");
        });
    }

    private void closeFind() {
        if (findBar.getVisibility() != View.VISIBLE) return;
        findBar.setVisibility(View.GONE);
        findCount.setText("");
        if (current != null) current.session.getFinder().clear();
        hideKeyboard(findInput);
        updateBack();
    }

    // ---------- Tasto indietro ----------

    private boolean wantsBack() {
        return findBar.getVisibility() == View.VISIBLE || fullscreen || (current != null && current.canBack);
    }

    private boolean handleBack() {
        if (findBar.getVisibility() == View.VISIBLE) { closeFind(); return true; }
        if (fullscreen && current != null) { current.session.exitFullScreen(); return true; }
        if (current != null && current.canBack) { current.session.goBack(); return true; }
        return false;
    }

    private void setupBack() {
        if (Build.VERSION.SDK_INT >= 33) backCallback = (OnBackInvokedCallback) this::handleBack;
    }

    private void updateBack() {
        if (Build.VERSION.SDK_INT < 33 || backCallback == null) return;
        boolean want = wantsBack();
        OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
        if (want && !backRegistered) {
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, (OnBackInvokedCallback) backCallback);
            backRegistered = true;
        } else if (!want && backRegistered) {
            dispatcher.unregisterOnBackInvokedCallback((OnBackInvokedCallback) backCallback);
            backRegistered = false;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!handleBack()) super.onBackPressed();
    }

    // ---------- Ciclo di vita ----------

    private String urlFromIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) return null;
        String scheme = intent.getData().getScheme();
        return "http".equals(scheme) || "https".equals(scheme) ? intent.getDataString() : null;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String url = urlFromIntent(intent);
        if (url == null) return;
        if (ready) newTab(url, true, true); else pendingUrl = url;
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyScreenProtection();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == Prompts.REQUEST_FILE) {
            prompts.onFileResult(resultCode, data);
        } else if (requestCode == REQUEST_SETTINGS) {
            applyScreenProtection();
            String action = data == null ? null : data.getStringExtra(EXTRA_ACTION);
            if ("check".equals(action)) {
                newTab(CHECK_URL, true, true);
            } else if ("open".equals(action) && isPrivacyTest(data.getStringExtra(EXTRA_URL))) {
                newTab(data.getStringExtra(EXTRA_URL), true, true);
            } else if ("update".equals(action)) {
                updater.check(true);
            } else if ("clear".equals(action)) {
                clearAll(false);
            } else if (resultCode == RESULT_OK) {
                // Aggiorna le pagine iniziali aperte con le nuove impostazioni.
                for (Tab t : tabs) if (t.internal() && t.errorUrl == null) loadStart(t);
                render();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            if (geckoView.getSession() != null) geckoView.releaseSession();
            for (Tab t : tabs) if (t.session.isOpen()) t.session.close();
            tabs.clear();
            current = null;
        }
        super.onDestroy();
    }
}
