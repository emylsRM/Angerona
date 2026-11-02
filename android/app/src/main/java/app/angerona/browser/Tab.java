package app.angerona.browser;

import org.mozilla.geckoview.GeckoSession;

/** Stato di una scheda. Ogni scheda è una sessione privata del motore Firefox. */
final class Tab {
    GeckoSession session;
    String title = "Nuova scheda";
    String url = "";
    String errorUrl = null;
    boolean secure;
    boolean loading;
    boolean canBack;
    boolean canForward;
    int progress;
    int blocked;
    /** Arresti del processo della pagina ravvicinati: oltre un limite non si ricarica più. */
    int crashes;
    long firstCrashAt;

    Tab(GeckoSession session) {
        this.session = session;
    }

    /** Pagina interna (iniziale o di errore): l'indirizzo reale non va mostrato. */
    boolean internal() {
        return url == null || url.isEmpty() || url.startsWith("data:") || url.equals("about:blank");
    }

    /** Testo per la barra indirizzi. */
    String displayUrl() {
        if (errorUrl != null) return errorUrl;
        return internal() ? "" : url;
    }
}
