package app.angerona.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.PromptDelegate;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Finestre dei siti (avvisi, conferme, menu a tendina, file, date, accessi). */
final class Prompts implements PromptDelegate {
    static final int REQUEST_FILE = 4201;

    private final Activity activity;
    private GeckoResult<PromptResponse> pendingFile;
    private FilePrompt pendingFilePrompt;

    Prompts(Activity activity) {
        this.activity = activity;
    }

    /** Completa una sola volta, anche se il dialogo viene chiuso in più modi. */
    private static final class Once {
        final GeckoResult<PromptResponse> result = new GeckoResult<>();
        private final AtomicBoolean done = new AtomicBoolean();

        void complete(PromptResponse r) {
            if (done.compareAndSet(false, true)) result.complete(r);
        }
    }

    private AlertDialog.Builder dialog(BasePrompt prompt, Once once) {
        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        if (prompt.title != null && !prompt.title.isEmpty()) b.setTitle(prompt.title);
        b.setOnCancelListener(d -> once.complete(prompt.dismiss()));
        return b;
    }

    private EditText input(String value, int type) {
        EditText e = new EditText(activity);
        e.setInputType(type);
        if (value != null) e.setText(value);
        e.setSelectAllOnFocus(true);
        return e;
    }

    private LinearLayout padded(android.view.View... views) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        for (android.view.View v : views) {
            box.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return box;
    }

    @Override
    public GeckoResult<PromptResponse> onAlertPrompt(GeckoSession session, AlertPrompt prompt) {
        Once once = new Once();
        dialog(prompt, once)
            .setMessage(prompt.message)
            .setPositiveButton("OK", (d, w) -> once.complete(prompt.dismiss()))
            .show();
        return once.result;
    }

    @Override
    public GeckoResult<PromptResponse> onButtonPrompt(GeckoSession session, ButtonPrompt prompt) {
        Once once = new Once();
        dialog(prompt, once)
            .setMessage(prompt.message)
            .setPositiveButton("OK", (d, w) -> once.complete(prompt.confirm(ButtonPrompt.Type.POSITIVE)))
            .setNegativeButton("Annulla", (d, w) -> once.complete(prompt.confirm(ButtonPrompt.Type.NEGATIVE)))
            .show();
        return once.result;
    }

    @Override
    public GeckoResult<PromptResponse> onTextPrompt(GeckoSession session, TextPrompt prompt) {
        Once once = new Once();
        EditText field = input(prompt.defaultValue, InputType.TYPE_CLASS_TEXT);
        dialog(prompt, once)
            .setMessage(prompt.message)
            .setView(padded(field))
            .setPositiveButton("OK", (d, w) -> once.complete(prompt.confirm(field.getText().toString())))
            .setNegativeButton("Annulla", (d, w) -> once.complete(prompt.dismiss()))
            .show();
        return once.result;
    }

    @Override
    public GeckoResult<PromptResponse> onAuthPrompt(GeckoSession session, AuthPrompt prompt) {
        int flags = prompt.authOptions.flags;
        Settings s = Engine.get(activity).settings();
        boolean proxy = (flags & AuthPrompt.AuthOptions.Flags.PROXY) != 0;
        boolean failed = (flags & AuthPrompt.AuthOptions.Flags.PREVIOUS_FAILED) != 0;
        if (proxy && !failed && !s.proxyUser.isEmpty()) {
            return GeckoResult.fromValue(prompt.confirm(s.proxyUser, s.proxyPass));
        }
        boolean onlyPassword = (flags & AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD) != 0;
        Once once = new Once();
        EditText user = input(prompt.authOptions.username, InputType.TYPE_CLASS_TEXT);
        user.setHint("Nome utente");
        EditText pass = input(prompt.authOptions.password, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setHint("Password");
        AlertDialog.Builder b = dialog(prompt, once);
        if (prompt.title == null || prompt.title.isEmpty()) b.setTitle(proxy ? "Accesso al proxy" : "Accesso richiesto");
        b.setMessage(prompt.message)
            .setView(onlyPassword ? padded(pass) : padded(user, pass))
            .setPositiveButton("Accedi", (d, w) -> once.complete(onlyPassword
                ? prompt.confirm(pass.getText().toString())
                : prompt.confirm(user.getText().toString(), pass.getText().toString())))
            .setNegativeButton("Annulla", (d, w) -> once.complete(prompt.dismiss()))
            .show();
        return once.result;
    }

    @Override
    public GeckoResult<PromptResponse> onChoicePrompt(GeckoSession session, ChoicePrompt prompt) {
        List<ChoicePrompt.Choice> flat = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (ChoicePrompt.Choice c : prompt.choices) {
            if (c.items != null) {
                for (ChoicePrompt.Choice sub : c.items) {
                    if (sub.separator) continue;
                    flat.add(sub);
                    labels.add((c.label == null ? "" : c.label + " › ") + sub.label);
                }
            } else if (!c.separator) {
                flat.add(c);
                labels.add(c.label);
            }
        }
        Once once = new Once();
        if (flat.isEmpty()) return GeckoResult.fromValue(prompt.dismiss());
        String[] items = labels.toArray(new String[0]);
        AlertDialog.Builder b = dialog(prompt, once);
        if (prompt.message != null && !prompt.message.isEmpty()) b.setTitle(prompt.message);

        if (prompt.type == ChoicePrompt.Type.MULTIPLE) {
            boolean[] checked = new boolean[flat.size()];
            for (int i = 0; i < flat.size(); i++) checked[i] = flat.get(i).selected;
            b.setMultiChoiceItems(items, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("OK", (d, w) -> {
                    List<ChoicePrompt.Choice> out = new ArrayList<>();
                    for (int i = 0; i < flat.size(); i++) if (checked[i] && !flat.get(i).disabled) out.add(flat.get(i));
                    once.complete(prompt.confirm(out.toArray(new ChoicePrompt.Choice[0])));
                })
                .setNegativeButton("Annulla", (d, w) -> once.complete(prompt.dismiss()));
        } else {
            int selected = -1;
            for (int i = 0; i < flat.size(); i++) if (flat.get(i).selected) selected = i;
            b.setSingleChoiceItems(items, selected, (d, which) -> {
                ChoicePrompt.Choice choice = flat.get(which);
                if (choice.disabled) return;
                once.complete(prompt.confirm(choice));
                d.dismiss();
            });
        }
        b.show();
        return once.result;
    }

    @Override
    public GeckoResult<PromptResponse> onDateTimePrompt(GeckoSession session, DateTimePrompt prompt) {
        Once once = new Once();
        Calendar cal = Calendar.getInstance();
        String def = prompt.defaultValue == null ? "" : prompt.defaultValue;
        if (prompt.type == DateTimePrompt.Type.DATE) {
            try {
                String[] p = def.split("-");
                cal.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
            } catch (Exception ignored) { }
            DatePickerDialog d = new DatePickerDialog(activity, (v, y, m, day) ->
                once.complete(prompt.confirm(String.format(Locale.ROOT, "%04d-%02d-%02d", y, m + 1, day))),
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
            d.setOnCancelListener(x -> once.complete(prompt.dismiss()));
            d.show();
            return once.result;
        }
        if (prompt.type == DateTimePrompt.Type.TIME) {
            int h = cal.get(Calendar.HOUR_OF_DAY);
            int min = cal.get(Calendar.MINUTE);
            try {
                String[] p = def.split(":");
                h = Integer.parseInt(p[0]);
                min = Integer.parseInt(p[1]);
            } catch (Exception ignored) { }
            TimePickerDialog d = new TimePickerDialog(activity, (v, hh, mm) ->
                once.complete(prompt.confirm(String.format(Locale.ROOT, "%02d:%02d", hh, mm))), h, min, true);
            d.setOnCancelListener(x -> once.complete(prompt.dismiss()));
            d.show();
            return once.result;
        }
        String hint = prompt.type == DateTimePrompt.Type.MONTH ? "AAAA-MM"
            : prompt.type == DateTimePrompt.Type.WEEK ? "AAAA-Wss" : "AAAA-MM-GGThh:mm";
        EditText field = input(def, InputType.TYPE_CLASS_TEXT);
        field.setHint(hint);
        dialog(prompt, once)
            .setView(padded(field))
            .setPositiveButton("OK", (d, w) -> once.complete(prompt.confirm(field.getText().toString().trim())))
            .setNegativeButton("Annulla", (d, w) -> once.complete(prompt.dismiss()))
            .show();
        return once.result;
    }

    @Override
    public GeckoResult<PromptResponse> onColorPrompt(GeckoSession session, ColorPrompt prompt) {
        Once once = new Once();
        EditText field = input(prompt.defaultValue, InputType.TYPE_CLASS_TEXT);
        field.setHint("#7f77dd");
        dialog(prompt, once)
            .setView(padded(field))
            .setPositiveButton("OK", (d, w) -> {
                String v = field.getText().toString().trim();
                once.complete(v.matches("#[0-9a-fA-F]{6}") ? prompt.confirm(v.toLowerCase(Locale.ROOT)) : prompt.dismiss());
            })
            .setNegativeButton("Annulla", (d, w) -> once.complete(prompt.dismiss()))
            .show();
        return once.result;
    }

    @Override
    public GeckoResult<PromptResponse> onFilePrompt(GeckoSession session, FilePrompt prompt) {
        if (pendingFile != null) return GeckoResult.fromValue(prompt.dismiss());
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        String[] mimes = prompt.mimeTypes;
        if (mimes != null && mimes.length == 1 && mimes[0] != null && mimes[0].contains("/")) {
            intent.setType(mimes[0]);
        } else {
            intent.setType("*/*");
            if (mimes != null && mimes.length > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, mimes);
        }
        if (prompt.type == FilePrompt.Type.MULTIPLE) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        pendingFile = new GeckoResult<>();
        pendingFilePrompt = prompt;
        GeckoResult<PromptResponse> result = pendingFile;
        try {
            activity.startActivityForResult(Intent.createChooser(intent, "Scegli un file"), REQUEST_FILE);
        } catch (ActivityNotFoundException e) {
            pendingFile = null;
            pendingFilePrompt = null;
            Toast.makeText(activity, "Nessuna app per scegliere i file", Toast.LENGTH_SHORT).show();
            return GeckoResult.fromValue(prompt.dismiss());
        }
        return result;
    }

    /** Da chiamare in onActivityResult della schermata principale. */
    void onFileResult(int resultCode, Intent data) {
        GeckoResult<PromptResponse> result = pendingFile;
        FilePrompt prompt = pendingFilePrompt;
        pendingFile = null;
        pendingFilePrompt = null;
        if (result == null || prompt == null) return;
        if (resultCode != Activity.RESULT_OK || data == null) {
            result.complete(prompt.dismiss());
            return;
        }
        if (data.getClipData() != null && prompt.type == FilePrompt.Type.MULTIPLE) {
            int n = data.getClipData().getItemCount();
            Uri[] uris = new Uri[n];
            for (int i = 0; i < n; i++) uris[i] = data.getClipData().getItemAt(i).getUri();
            result.complete(prompt.confirm(activity, uris));
        } else if (data.getData() != null) {
            result.complete(prompt.confirm(activity, data.getData()));
        } else {
            result.complete(prompt.dismiss());
        }
    }

    @Override
    public GeckoResult<PromptResponse> onPopupPrompt(GeckoSession session, PopupPrompt prompt) {
        Toast.makeText(activity, "Finestra pop-up bloccata", Toast.LENGTH_SHORT).show();
        return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY));
    }

    @Override
    public GeckoResult<PromptResponse> onBeforeUnloadPrompt(GeckoSession session, BeforeUnloadPrompt prompt) {
        Once once = new Once();
        dialog(prompt, once)
            .setTitle("Vuoi lasciare la pagina?")
            .setMessage("Le modifiche che hai fatto potrebbero andare perse.")
            .setPositiveButton("Lascia", (d, w) -> once.complete(prompt.confirm(AllowOrDeny.ALLOW)))
            .setNegativeButton("Resta", (d, w) -> once.complete(prompt.confirm(AllowOrDeny.DENY)))
            .show();
        return once.result;
    }

    @Override
    public GeckoResult<PromptResponse> onRepostConfirmPrompt(GeckoSession session, RepostConfirmPrompt prompt) {
        Once once = new Once();
        dialog(prompt, once)
            .setTitle("Inviare di nuovo il modulo?")
            .setMessage("La pagina reinvierà i dati che hai già inserito.")
            .setPositiveButton("Invia", (d, w) -> once.complete(prompt.confirm(AllowOrDeny.ALLOW)))
            .setNegativeButton("Annulla", (d, w) -> once.complete(prompt.confirm(AllowOrDeny.DENY)))
            .show();
        return once.result;
    }
}
