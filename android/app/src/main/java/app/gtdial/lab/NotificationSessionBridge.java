package app.gtdial.lab;

import java.util.concurrent.atomic.AtomicReference;

/** Process-local handoff from NotificationListenerService to the existing BLE session. */
final class NotificationSessionBridge {
    private static final AtomicReference<Runnable> target=new AtomicReference<>();
    private NotificationSessionBridge() { }
    static void install(Runnable signal) { target.set(signal); }
    static void clear(Runnable signal) { target.compareAndSet(signal,null); }
    static boolean signal() {
        Runnable current=target.get();
        if(current==null)return false;
        current.run();
        return true;
    }
}
