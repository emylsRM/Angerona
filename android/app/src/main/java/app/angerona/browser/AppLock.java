package app.angerona.browser;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;

/**
 * Blocco di Angerona con impronta, volto o PIN del telefono, senza librerie esterne.
 * Da Android 10 usa la finestra di sistema BiometricPrompt; su Android 8 e 9 la schermata di
 * conferma del blocco schermo, il cui esito arriva in onActivityResult con REQUEST_UNLOCK.
 */
final class AppLock {
    static final int REQUEST_UNLOCK = 4210;
    private static final String TITLE = "Sblocca Angerona";

    interface Result {
        void done(boolean unlocked);
    }

    private AppLock() { }

    /** Il telefono ha un blocco schermo (PIN, sequenza, password o biometria)? Senza, non c'è nulla da chiedere. */
    static boolean available(Context c) {
        KeyguardManager km = c.getSystemService(KeyguardManager.class);
        return km != null && km.isDeviceSecure();
    }

    @SuppressWarnings("deprecation")
    static void authenticate(Activity activity, Result result) {
        if (!available(activity)) {
            result.done(true);
            return;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            BiometricPrompt.Builder b = new BiometricPrompt.Builder(activity)
                .setTitle(TITLE)
                .setSubtitle("Usa l'impronta, il volto o il PIN del telefono");
            if (Build.VERSION.SDK_INT >= 30) {
                b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            } else {
                b.setDeviceCredentialAllowed(true);
            }
            try {
                b.build().authenticate(new CancellationSignal(), activity.getMainExecutor(),
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                            result.done(true);
                        }

                        @Override
                        public void onAuthenticationError(int code, CharSequence message) {
                            result.done(false);
                        }
                    });
            } catch (RuntimeException e) {
                result.done(false);
            }
            return;
        }
        KeyguardManager km = activity.getSystemService(KeyguardManager.class);
        Intent intent = km.createConfirmDeviceCredentialIntent(TITLE, "Usa il PIN, la sequenza o l'impronta del telefono");
        if (intent == null) {
            result.done(true);
            return;
        }
        activity.startActivityForResult(intent, REQUEST_UNLOCK);
    }
}
