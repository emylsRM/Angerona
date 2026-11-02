package app.angerona.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Map;

/** Impostazioni: DNS da menu a tendina, proxy, ricerca, protezioni. */
public class SettingsActivity extends Activity {
    private Spinner dns;
    private Spinner proxy;
    private Spinner search;
    private EditText nextdns;
    private EditText doh;
    private EditText host;
    private EditText port;
    private EditText user;
    private EditText pass;
    private EditText searxng;
    private Switch https;
    private Switch trackers;
    private Switch cookies;
    private Switch js;
    private Switch secure;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        View root = findViewById(R.id.settings_root);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                return WindowInsets.CONSUMED;
            });
        } else {
            root.setFitsSystemWindows(true);
        }

        dns = findViewById(R.id.spinner_dns);
        proxy = findViewById(R.id.spinner_proxy);
        search = findViewById(R.id.spinner_search);
        nextdns = findViewById(R.id.input_nextdns);
        doh = findViewById(R.id.input_doh);
        host = findViewById(R.id.input_host);
        port = findViewById(R.id.input_port);
        user = findViewById(R.id.input_user);
        pass = findViewById(R.id.input_pass);
        searxng = findViewById(R.id.input_searxng);
        https = findViewById(R.id.switch_https);
        trackers = findViewById(R.id.switch_trackers);
        cookies = findViewById(R.id.switch_cookies);
        js = findViewById(R.id.switch_js);
        secure = findViewById(R.id.switch_secure);

        dns.setAdapter(adapter(Settings.labels(Settings.DNS)));
        proxy.setAdapter(adapter(Settings.labels(Settings.PROXY)));
        search.setAdapter(adapter(Settings.labels(Settings.SEARCH)));

        Settings s = Engine.get(this).settings();
        dns.setSelection(Settings.indexOf(Settings.DNS, s.dns));
        proxy.setSelection(Settings.indexOf(Settings.PROXY, s.proxyMode));
        search.setSelection(Settings.indexOf(Settings.SEARCH, s.search));
        nextdns.setText(s.nextdnsId);
        doh.setText(s.customDoh);
        host.setText(s.proxyHost);
        port.setText(s.proxyPort);
        user.setText(s.proxyUser);
        pass.setText(s.proxyPass);
        searxng.setText(s.searxngUrl);
        https.setChecked(s.httpsOnly);
        trackers.setChecked(s.blockTrackers);
        cookies.setChecked(s.blockThirdPartyCookies);
        js.setChecked(s.javascript);
        secure.setChecked(s.secureScreen);

        AdapterView.OnItemSelectedListener refresh = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { updateVisibility(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        };
        dns.setOnItemSelectedListener(refresh);
        proxy.setOnItemSelectedListener(refresh);
        search.setOnItemSelectedListener(refresh);
        updateVisibility();

        ((TextView) findViewById(R.id.about)).setText("Angerona 1.0.0 · motore Firefox (GeckoView)");

        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_save).setOnClickListener(v -> {
            if (save()) {
                setResult(RESULT_OK);
                finish();
            }
        });
        findViewById(R.id.btn_check).setOnClickListener(v -> {
            if (save()) {
                setResult(RESULT_OK, new Intent().putExtra(MainActivity.EXTRA_ACTION, "check"));
                finish();
            }
        });
        findViewById(R.id.btn_clear).setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("Cancellare tutto?")
            .setMessage("Si chiudono tutte le schede e si cancellano cookie, cache e dati dei siti.")
            .setPositiveButton("Cancella", (d, w) -> {
                setResult(RESULT_OK, new Intent().putExtra(MainActivity.EXTRA_ACTION, "clear"));
                finish();
            })
            .setNegativeButton("Annulla", null)
            .show());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Engine.get(this).settings().secureScreen) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private ArrayAdapter<String> adapter(String[] items) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    private String selected(Spinner spinner, String[][] table) {
        int i = spinner.getSelectedItemPosition();
        return table[i < 0 || i >= table.length ? 0 : i][0];
    }

    private void updateVisibility() {
        String d = selected(dns, Settings.DNS);
        String p = selected(proxy, Settings.PROXY);
        findViewById(R.id.group_nextdns).setVisibility("nextdns".equals(d) ? View.VISIBLE : View.GONE);
        findViewById(R.id.group_doh).setVisibility("custom".equals(d) ? View.VISIBLE : View.GONE);
        findViewById(R.id.group_proxy).setVisibility("none".equals(p) ? View.GONE : View.VISIBLE);
        findViewById(R.id.group_searxng).setVisibility("searxng".equals(selected(search, Settings.SEARCH)) ? View.VISIBLE : View.GONE);
    }

    private static String text(EditText e) {
        return e.getText().toString().trim();
    }

    private boolean save() {
        Engine engine = Engine.get(this);
        Settings s = engine.settings();
        s.dns = selected(dns, Settings.DNS);
        s.nextdnsId = text(nextdns);
        s.customDoh = text(doh);
        s.proxyMode = selected(proxy, Settings.PROXY);
        s.proxyHost = text(host).replaceAll("^\\[|\\]$", "");
        s.proxyPort = text(port);
        s.proxyUser = text(user);
        s.proxyPass = pass.getText().toString();
        s.search = selected(search, Settings.SEARCH);
        s.searxngUrl = text(searxng).replaceAll("/+$", "");
        s.httpsOnly = https.isChecked();
        s.blockTrackers = trackers.isChecked();
        s.blockThirdPartyCookies = cookies.isChecked();
        s.javascript = js.isChecked();
        s.secureScreen = secure.isChecked();

        Map<String, String> errors = s.validate();
        nextdns.setError(errors.get("nextdns"));
        doh.setError(errors.get("doh"));
        host.setError(errors.get("host"));
        port.setError(errors.get("port"));
        searxng.setError(errors.get("searxng"));
        if (!errors.isEmpty()) {
            Toast.makeText(this, errors.values().iterator().next(), Toast.LENGTH_LONG).show();
            return false;
        }
        s.save(this);
        engine.apply(s);
        Toast.makeText(this, "Impostazioni applicate", Toast.LENGTH_SHORT).show();
        return true;
    }
}
