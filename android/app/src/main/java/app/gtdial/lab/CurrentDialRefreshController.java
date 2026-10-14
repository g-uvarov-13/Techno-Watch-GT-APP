package app.gtdial.lab;

/** Allows one deferred read after an exclusive operation and clears it on disconnect. */
final class CurrentDialRefreshController {
    private boolean pending;

    void request() { pending = true; }
    boolean isPending() { return pending; }

    boolean takeIfIdle(boolean idle) {
        if (!pending || !idle) return false;
        pending = false;
        return true;
    }

    void onDisconnected() { pending = false; }
}
