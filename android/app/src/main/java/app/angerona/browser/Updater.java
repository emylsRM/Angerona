package app.angerona.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoWebExecutor;
import org.mozilla.geckoview.WebRequest;
import org.mozilla.geckoview.WebResponse;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Controlla l'ultimo rilascio su GitHub e propone l'aggiornamento. Le richieste passano dal motore
 * Firefox, quindi usano il DNS cifrato e il proxy scelti, senza cookie.
 */
final class Updater {
    private static final String REPO = "emylsRM/Angerona";
    private static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final int FLAGS = GeckoWebExecutor.FETCH_FLAGS_ANONYMOUS | GeckoWebExecutor.FETCH_FLAGS_PRIVATE;

    private static final class Release {
        String version;
        long build;
        String apkUrl;
        String page;
    }

    private final Activity activity;
    private final Engine engine;
    private final Consumer<String> openTab;
    private final GeckoWebExecutor executor;
    private boolean busy;

    Updater(Activity activity, Engine engine, Consumer<String> openTab) {
        this.activity = activity;
        this.engine = engine;
        this.openTab = openTab;
        this.executor = new GeckoWebExecutor(engine.runtime);
    }

    @SuppressWarnings("deprecation")
    static long installedBuild(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static String installedVersion(Context c) {
        try {
            String v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return v == null ? "?" : v;
        } catch (Exception e) {
            return "?";
        }
    }

    private void toast(String text) {
        activity.runOnUiThread(() -> Toast.makeText(activity, text, Toast.LENGTH_LONG).show());
    }

    private static String header(WebResponse r, String name) {
        if (r.headers == null) return null;
        for (Map.Entry<String, String> e : r.headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    private WebRequest request(String url, String accept) {
        WebRequest.Builder b = new WebRequest.Builder(url);
        b.header("Accept", accept);
        return b.cacheMode(WebRequest.CACHE_MODE_NO_STORE).build();
    }

    /** manual = true quando l'utente ha premuto "Controlla ora": in quel caso si risponde sempre. */
    void check(boolean manual) {
        if (busy) return;
        if (!engine.canBrowse()) {
            if (manual) toast("Proxy non ancora attivo: riprova tra poco");
            return;
        }
        busy = true;
        executor.fetch(request(LATEST, "application/vnd.github+json"), FLAGS).accept(res -> {
            new Thread(() -> {
                Release rel = null;
                try (InputStream in = res.body) {
                    if (res.statusCode == 200 && in != null) rel = parse(readAll(in));
                } catch (Exception ignored) { }
                final Release found = rel;
                activity.runOnUiThread(() -> {
                    busy = false;
                    if (found == null) {
                        if (manual) toast("Nessun rilascio trovato su GitHub");
                    } else if (found.build > installedBuild(activity)) {
                        offer(found);
                    } else if (manual) {
                        toast("Hai già l'ultima versione (" + installedVersion(activity) + ")");
                    }
                });
            }, "angerona-update-check").start();
        }, err -> {
            busy = false;
            if (manual) toast("GitHub non raggiungibile: controlla connessione, proxy o DNS");
        });
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            if (out.size() > 2 * 1024 * 1024) throw new IllegalStateException("risposta troppo grande");
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Release parse(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        String tag = o.optString("tag_name", "");
        String[] parts = tag.replaceFirst("^v", "").split("\\.");
        Release r = new Release();
        r.version = tag.replaceFirst("^v", "");
        r.build = Long.parseLong(parts[parts.length - 1]);
        r.page = o.optString("html_url", "https://github.com/" + REPO + "/releases/latest");
        JSONArray assets = o.optJSONArray("assets");
        if (assets != null) {
            for (String abi : Build.SUPPORTED_ABIS) {
                String wanted = "Angerona-android-" + abi + ".apk";
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (wanted.equals(a.optString("name"))) {
                        r.apkUrl = a.optString("browser_download_url");
                        break;
                    }
                }
                if (r.apkUrl != null) break;
            }
        }
        String host = r.apkUrl == null ? null : Uri.parse(r.apkUrl).getHost();
        if (host != null && !host.equals("github.com")) r.apkUrl = null; // solo file pubblicati nel repository
        return r;
    }

    private void offer(Release r) {
        if (activity.isFinishing()) return;
        new AlertDialog.Builder(activity)
            .setTitle("Aggiornamento disponibile")
            .setMessage("È pronta Angerona " + r.version + " (hai la " + installedVersion(activity) + "). Vuoi aggiornare adesso?")
            .setPositiveButton("Aggiorna", (d, w) -> download(r))
            .setNegativeButton("Più tardi", null)
            .show();
    }

    private void download(Release r) {
        if (Build.VERSION.SDK_INT < 29 || r.apkUrl == null) {
            openTab.accept(r.page);
            toast("Scarica l'APK per il tuo telefono e aprilo dalla cartella Download");
            return;
        }
        AlertDialog progress = new AlertDialog.Builder(activity)
            .setTitle("Aggiornamento")
            .setMessage("Download in corso…")
            .setCancelable(false)
            .show();
        executor.fetch(request(r.apkUrl, "application/octet-stream"), FLAGS).accept(res -> {
            res.setReadTimeoutMillis(5 * 60 * 1000);
            new Thread(() -> save(r, res, progress), "angerona-update-download").start();
        }, err -> {
            progress.dismiss();
            toast("Download non riuscito: riprova più tardi");
        });
    }

    private void save(Release r, WebResponse res, AlertDialog progress) {
        ContentResolver resolver = activity.getContentResolver();
        Uri uri = null;
        try (InputStream in = res.body) {
            if (res.statusCode != 200 || in == null) throw new IllegalStateException("risposta " + res.statusCode);
            long total = -1;
            try { total = Long.parseLong(header(res, "Content-Length")); } catch (Exception ignored) { }
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, "Angerona-" + r.version + ".apk");
            cv.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive");
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IllegalStateException("cartella Download non disponibile");
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("impossibile scrivere il file");
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int lastPct = -1;
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        int pct = (int) (100 * done / total);
                        if (pct != lastPct) {
                            lastPct = pct;
                            activity.runOnUiThread(() -> progress.setMessage("Download in corso… " + pct + "%"));
                        }
                    }
                }
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, ready, null, null);
            final Uri apk = uri;
            activity.runOnUiThread(() -> {
                progress.dismiss();
                install(apk);
            });
        } catch (Exception e) {
            if (uri != null) resolver.delete(uri, null, null);
            activity.runOnUiThread(progress::dismiss);
            toast("Download non riuscito: riprova più tardi");
        }
    }

    private void install(Uri apk) {
        Intent i = new Intent(Intent.ACTION_VIEW)
            .setDataAndType(apk, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(i);
        } catch (RuntimeException e) {
            toast("Apri l'APK dalla cartella Download per installarlo");
        }
    }
}
