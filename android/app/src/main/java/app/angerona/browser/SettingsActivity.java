package app.angerona.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
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
    private Spinner fingerprint;
    private Switch webrtc;
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
    private Switch updates;
    private TextView scoreValue;
    private TextView scoreLevel;
    private TextView tipsTitle;
    private TextView vpnNote;
    private LinearLayout observersBox;
    private LinearLayout tipsBox;

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
        fingerprint = findViewById(R.id.spinner_fingerprint);
        webrtc = findViewById(R.id.switch_webrtc);
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
        updates = findViewById(R.id.switch_updates);
        scoreValue = findViewById(R.id.score_value);
        scoreLevel = findViewById(R.id.score_level);
        tipsTitle = findViewById(R.id.tips_title);
        vpnNote = findViewById(R.id.vpn_note);
        observersBox = findViewById(R.id.observers_box);
        tipsBox = findViewById(R.id.tips_box);

        dns.setAdapter(adapter(Settings.labels(Settings.DNS)));
        proxy.setAdapter(adapter(Settings.labels(Settings.PROXY)));
        search.setAdapter(adapter(Settings.labels(Settings.SEARCH)));
        fingerprint.setAdapter(adapter(Settings.labels(Settings.FINGERPRINT)));

        Settings s = Engine.get(this).settings();
        dns.setSelection(Settings.indexOf(Settings.DNS, s.dns));
        proxy.setSelection(Settings.indexOf(Settings.PROXY, s.proxyMode));
        search.setSelection(Settings.indexOf(Settings.SEARCH, s.search));
        fingerprint.setSelection(Settings.indexOf(Settings.FINGERPRINT, s.fingerprint));
        webrtc.setChecked(s.webrtc);
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
        updates.setChecked(s.checkUpdates);

        AdapterView.OnItemSelectedListener refresh = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { updateVisibility(); updateReport(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        };
        dns.setOnItemSelectedListener(refresh);
        proxy.setOnItemSelectedListener(refresh);
        search.setOnItemSelectedListener(refresh);
        fingerprint.setOnItemSelectedListener(refresh);
        for (Switch sw : new Switch[] {https, trackers, cookies, js, webrtc}) {
            sw.setOnCheckedChangeListener((b, checked) -> updateReport());
        }
        host.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence t, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence t, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { updateReport(); }
        });
        updateVisibility();
        updateReport();

        ((TextView) findViewById(R.id.about)).setText("Angerona " + Updater.installedVersion(this) + " · motore Firefox (GeckoView)");
        findViewById(R.id.btn_update).setOnClickListener(v -> {
            if (save()) {
                setResult(RESULT_OK, new Intent().putExtra(MainActivity.EXTRA_ACTION, "update"));
                finish();
            }
        });

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
        findViewById(R.id.btn_tests).setOnClickListener(v -> {
            String[] names = new String[PrivacyReport.TESTS.length];
            for (int i = 0; i < names.length; i++) names[i] = PrivacyReport.TESTS[i][0];
            new AlertDialog.Builder(this)
                .setTitle("Test della privacy")
                .setItems(names, (d, which) -> {
                    if (save()) {
                        setResult(RESULT_OK, new Intent()
                            .putExtra(MainActivity.EXTRA_ACTION, "open")
                            .putExtra(MainActivity.EXTRA_URL, PrivacyReport.TESTS[which][1]));
                        finish();
                    }
                })
                .show();
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

    /** Impostazioni come sono ora nel modulo, anche se non ancora salvate. */
    private Settings fromForm() {
        Settings s = Engine.get(this).settings();
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
        s.checkUpdates = updates.isChecked();
        s.fingerprint = selected(fingerprint, Settings.FINGERPRINT);
        s.webrtc = webrtc.isChecked();
        return s;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView line(String text, int sizeSp, int color, boolean medium) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(getColor(color));
        if (medium) t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    private void updateReport() {
        if (scoreValue == null) return;
        PrivacyReport r = PrivacyReport.evaluate(fromForm());
        int color = "Massima".equals(r.level) ? R.color.ok
            : "Alta".equals(r.level) ? R.color.accent
            : "Buona".equals(r.level) ? R.color.amber : R.color.warn;
        scoreValue.setText(String.valueOf(r.score));
        scoreValue.setTextColor(getColor(color));
        scoreLevel.setText("La tua privacy: " + r.level.toLowerCase(java.util.Locale.ITALIAN));

        observersBox.removeAllViews();
        for (PrivacyReport.Observer o : r.observers) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackgroundResource(R.drawable.bg_observer);
            box.setPadding(dp(12), dp(10), dp(12), dp(10));
            box.addView(line(o.who, 14, R.color.text, true));
            for (String l : o.sees) box.addView(line("•  " + l, 13, R.color.text2, false));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            observersBox.addView(box, lp);
        }

        tipsBox.removeAllViews();
        for (PrivacyReport.Tip t : r.tips) tipsBox.addView(line("•  " + t.text + "  +" + t.gain, 13, R.color.text2, false));
        tipsTitle.setVisibility(r.tips.isEmpty() ? View.GONE : View.VISIBLE);
        vpnNote.setVisibility(r.vpnNote ? View.VISIBLE : View.GONE);
    }

    private boolean save() {
        Engine engine = Engine.get(this);
        Settings s = fromForm();

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
