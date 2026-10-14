package app.gtdial.lab;

/** Owns only the cadence policy; BLE dispatch remains in the session thread. */
final class ActivityPollController {
    // Recorded long sessions repeatedly replayed the handshake; retain manual refresh and reduce idle traffic.
    private static final long PERIOD_MS=300_000L;
    private long generation;
    private boolean scheduled;
    private boolean initial;
    boolean onReady(){ initial=true; scheduled=true; return true; }
    boolean onSuccess(){ initial=false; scheduled=true; return true; }
    boolean onFailure(){ scheduled=false; generation++; return false; }
    void onExclusiveStart(){ scheduled=false; generation++; }
    void onDisconnected(){ scheduled=false; generation++; }
    boolean isScheduled(){ return scheduled; }
    long nextDelayMs(){ return scheduled ? (initial ? 0L : PERIOD_MS) : -1L; }
    long generation(){ return generation; }
}
