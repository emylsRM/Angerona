package app.angerona.browser;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.URLUtil;
import android.widget.Toast;

import org.mozilla.geckoview.WebResponse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/** Salva i file scaricati nella cartella Download (Android 10+) o nella cartella dell'app. */
final class Downloads {
    private Downloads() { }

    private static String header(WebResponse r, String name) {
        if (r.headers == null) return null;
        for (Map.Entry<String, String> e : r.headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    static void save(Activity activity, WebResponse response) {
        if (response.body == null) {
            Toast.makeText(activity, "Download non disponibile", Toast.LENGTH_SHORT).show();
            return;
        }
        String disposition = header(response, "Content-Disposition");
        String mime = header(response, "Content-Type");
        if (mime != null) mime = mime.split(";")[0].trim();
        if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
        final String type = mime;
        final String name = URLUtil.guessFileName(response.uri, disposition, mime).replaceAll("[\\\\/:*?\"<>|]", "_");
        response.setReadTimeoutMillis(5 * 60 * 1000);
        Toast.makeText(activity, "Download di " + name + "…", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            Uri uri = null;
            File file = null;
            ContentResolver resolver = activity.getContentResolver();
            try (InputStream in = response.body) {
                OutputStream out;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    cv.put(MediaStore.Downloads.MIME_TYPE, type);
                    cv.put(MediaStore.Downloads.IS_PENDING, 1);
                    uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) throw new IllegalStateException("cartella Download non disponibile");
                    out = resolver.openOutputStream(uri);
                } else {
                    File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) throw new IllegalStateException("memoria non disponibile");
                    file = unique(dir, name);
                    out = new FileOutputStream(file);
                }
                if (out == null) throw new IllegalStateException("impossibile scrivere il file");
                try (OutputStream o = out) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) o.write(buf, 0, n);
                }
                if (uri != null) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Downloads.IS_PENDING, 0);
                    resolver.update(uri, done, null, null);
                }
                String where = uri != null ? "Download" : "la cartella di Angerona";
                activity.runOnUiThread(() ->
                    Toast.makeText(activity, "Salvato in " + where + ": " + name, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                if (uri != null) resolver.delete(uri, null, null);
                if (file != null && file.exists() && !file.delete()) file.deleteOnExit();
                activity.runOnUiThread(() ->
                    Toast.makeText(activity, "Download non riuscito: " + name, Toast.LENGTH_LONG).show());
            }
        }, "angerona-download").start();
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; f.exists(); i++) f = new File(dir, base + " (" + i + ")" + ext);
        return f;
    }
}
