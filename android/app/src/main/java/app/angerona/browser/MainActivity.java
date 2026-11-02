package app.angerona.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
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

    // ---------- Blocco e cancellazione automatica ----------
    private final Handler handler = new Handler(Looper.getMainLooper());
    private View lockOverlay;
    private boolean locked;
    /** Lo sblocco viene proposto da solo una volta per ritorno nell'app; poi c'è il pulsante. */
    private boolean autoPrompted;
    private boolean authenticating;
    /** Aperta un'app esterna per un risultato (scelta file, PIN di sistema): non conta come uscita. */
    private boolean awaitingExternal;
    /** Qualche sito è stato aperto dall'ultima cancellazione. */
    private boolean dirty;
    private long backgroundAt = -1;
    private int startedActivities;
    private String pendingNotice;
    private final Runnable autoClearTask = this::runAutoClear;
    private final Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityCreated(Activity a, Bundle b) { }
        @Override public void onActivityResumed(Activity a) { }
        @Override public void onActivityPaused(Activity a) { }
        @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
        @Override public void onActivityDestroyed(Activity a) { }

        @Override
        public void onActivityStarted(Activity a) {
            if (++startedActivities == 1) onAppForeground();
        }

        @Override
        public void onActivityStopped(Activity a) {
            if (--startedActivities == 0 && !a.isChangingConfigurations()) onAppBackground();
        }
    };

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
        lockOverlay = buildLockOverlay();
        if (engine.settings().appLock && AppLock.available(this)) setLocked(true);
        getApplication().registerActivityLifecycleCallbacks(lifecycle);
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
        // Con il blocco attivo le app recenti non mostrano l'anteprima delle schede.
        if (Build.VERSION.SDK_INT >= 33 && engine != null) setRecentsScreenshotEnabled(!engine.settings().appLock);
    }

    // ---------- Blocco con impronta o PIN ----------

    private View buildLockOverlay() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(getColor(R.color.page));
        box.setClickable(true);
        box.setFocusable(true);
        int pad = (int) (32 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        int size = (int) (88 * getResources().getDisplayMetrics().density);
        box.addView(icon, new LinearLayout.LayoutParams(size, size));

        TextView title = new TextView(this);
        title.setText("Angerona è bloccata");
        title.setTextSize(22);
        title.setTextColor(getColor(R.color.text));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, pad / 2, 0, pad / 4);
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Sbloccala con l'impronta, il volto o il PIN del telefono.");
        sub.setTextSize(14);
        sub.setTextColor(getColor(R.color.text2));
        sub.setGravity(Gravity.CENTER);
        box.addView(sub);

        Button unlock = new Button(this);
        unlock.setText("Sblocca");
        unlock.setAllCaps(false);
        unlock.setTextColor(getColor(R.color.text));
        unlock.setBackgroundResource(R.drawable.bg_button_primary);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, (int) (48 * getResources().getDisplayMetrics().density));
        lp.topMargin = pad;
        unlock.setMinWidth(size * 2);
        unlock.setOnClickListener(v -> unlock());
        box.addView(unlock, lp);

        box.setVisibility(View.GONE);
        addContentView(box, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return box;
    }

    private void setLocked(boolean on) {
        locked = on;
        lockOverlay.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            hideKeyboard(address);
            if (current != null && current.session.isOpen()) current.session.setActive(false);
        } else if (current != null && current.session.isOpen()) {
            current.session.setActive(true);
        }
    }

    private void unlock() {
        if (!locked || authenticating) return;
        authenticating = true;
        AppLock.authenticate(this, ok -> {
            authenticating = false;
            if (ok) setLocked(false);
        });
    }

    // ---------- Uscita dall'app: blocco e cancellazione automatica ----------

    private void onAppBackground() {
        if (awaitingExternal || isFinishing()) return;
        Settings s = engine.settings();
        backgroundAt = SystemClock.elapsedRealtime();
        if (s.appLock && AppLock.available(this)) setLocked(true);
        autoPrompted = false;
        handler.removeCallbacks(autoClearTask);
        if (s.autoClear > 0) handler.postDelayed(autoClearTask, s.autoClear * 60_000L);
    }

    private void onAppForeground() {
        handler.removeCallbacks(autoClearTask);
        Settings s = engine.settings();
        if (backgroundAt >= 0 && s.autoClear > 0 && SystemClock.elapsedRealtime() - backgroundAt >= s.autoClear * 60_000L) {
            runAutoClear();
        }
        backgroundAt = -1;
    }

    /** Scatta in background (o al ritorno, se Android ha ritardato il timer): niente avvisi su altre app. */
    private void runAutoClear() {
        int minutes = engine.settings().autoClear;
        if (!ready || !dirty || minutes <= 0) return;
        pendingNotice = "Tracce cancellate automaticamente dopo " + minutes + " minuti senza usare Angerona";
        clearAll(false, "");
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
        burn.setOnClickListener(v -> burnNow());
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
                if (tab.url.startsWith("http:") || tab.url.startsWith("https:")) dirty = true;
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
        // Più di 3 arresti in 30 secondi: si smette di ricaricare per non entrare in un ciclo infinito.
        long now = SystemClock.elapsedRealtime();
        if (now - tab.firstCrashAt > 30_000) { tab.firstCrashAt = now; tab.crashes = 0; }
        boolean loop = ++tab.crashes > 3;
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
        if (loop) {
            tab.errorUrl = url;
            tab.session.load(new GeckoSession.Loader().data(Pages.crashLoop(url), "text/html"));
            return;
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
                case M_CLEAR_EXIT: clearAll(true, null); return true;
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

    /** Pulsante "brucia tutto": senza domande chiude tutte le schede, cancella tutto e riparte da zero. */
    private void burnNow() {
        if (!ready) return;
        int n = sessionBlocked;
        String count = n == 1 ? "1 tracker bloccato" : String.format(Locale.ITALIAN, "%,d tracker bloccati", n);
        clearAll(false, "Tracce cancellate · " + count + " in sessione");
    }

    /** Chiude tutte le schede e cancella cookie, cache e dati dei siti. */
    private void clearAll(boolean exit, String message) {
        sessionBlocked = 0;
        dirty = false;
        if (geckoView.getSession() != null) geckoView.releaseSession();
        for (Tab t : tabs) t.session.close();
        tabs.clear();
        current = null;
        engine.runtime.getStorageController().clearData(StorageController.ClearFlags.ALL).accept(v -> {
            if (exit) {
                exitCompletely();
            } else {
                newTab(null, true, true);
                if (!"".equals(message)) {
                    Toast.makeText(this, message != null ? message : "Dati di navigazione cancellati", Toast.LENGTH_SHORT).show();
                }
            }
        }, e -> {
            if (exit) exitCompletely(); else newTab(null, true, true);
        });
    }

    /**
     * Chiude l'app e termina il processo: in memoria non resta nulla e al prossimo avvio il motore
     * riparte da zero (serve anche ad attivare o spegnere la gabbia della modalità blindata).
     */
    private void exitCompletely() {
        finishAndRemoveTask();
        handler.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 500);
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
        if (pendingNotice != null) {
            Toast.makeText(this, pendingNotice, Toast.LENGTH_LONG).show();
            pendingNotice = null;
        }
        if (locked) {
            lockOverlay.setVisibility(View.VISIBLE);
            if (!autoPrompted) { autoPrompted = true; unlock(); }
        } else {
            lockOverlay.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Con il blocco attivo il contenuto si copre subito, prima che Android ne faccia l'anteprima.
        if (engine != null && engine.settings().appLock && AppLock.available(this) && !awaitingExternal && !authenticating) {
            lockOverlay.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        boolean internal = intent.getComponent() != null && getPackageName().equals(intent.getComponent().getPackageName());
        if (!internal) awaitingExternal = true;
        super.startActivityForResult(intent, requestCode, options);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        awaitingExternal = false;
        if (requestCode == AppLock.REQUEST_UNLOCK) {
            authenticating = false;
            if (resultCode == RESULT_OK) setLocked(false);
            return;
        }
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
                clearAll(false, "Dati di navigazione cancellati");
            } else if (resultCode == RESULT_OK) {
                // Aggiorna le pagine iniziali aperte con le nuove impostazioni.
                for (Tab t : tabs) if (t.internal() && t.errorUrl == null) loadStart(t);
                render();
            }
        }
    }

    @Override
    protected void onDestroy() {
        getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
        handler.removeCallbacksAndMessages(null);
        if (isFinishing()) {
            if (geckoView.getSession() != null) geckoView.releaseSession();
            for (Tab t : tabs) if (t.session.isOpen()) t.session.close();
            tabs.clear();
            current = null;
        }
        super.onDestroy();
    }
}
