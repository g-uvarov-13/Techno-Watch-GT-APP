package app.gtdial.lab;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.List;
import java.util.Locale;

/** One foreground, user-requested lookup. No background location or BLE interaction. */
final class PhoneCityLocator implements LocationListener {
    interface Callback { void complete(String city, double latitude, double longitude, String error); }
    private final Context context;
    private final LocationManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Callback callback;
    private boolean finished, resolving;
    private final Runnable timeout = () -> finish(null, 0, 0, "timeout");

    PhoneCityLocator(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        this.callback = callback;
    }

    @SuppressLint("MissingPermission")
    void start() {
        main.postDelayed(timeout, 20_000);
        try {
            if (manager == null) { finish(null, 0, 0, "disabled"); return; }
            String provider = manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                    ? LocationManager.NETWORK_PROVIDER
                    : manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : null;
            if (provider == null) { finish(null, 0, 0, "disabled"); return; }
            Location cached = manager.getLastKnownLocation(provider);
            long age = cached == null ? Long.MAX_VALUE : SystemClock.elapsedRealtimeNanos() - cached.getElapsedRealtimeNanos();
            if (cached != null && age >= 0 && age <= 15L * 60 * 1_000_000_000) onLocationChanged(cached);
            else manager.requestLocationUpdates(provider, 0, 0, this, Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException e) { finish(null, 0, 0, "permission"); }
    }

    @Override public void onLocationChanged(Location location) {
        if (finished || resolving) return;
        double lat = location.getLatitude(), lon = location.getLongitude();
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat)>90 || Math.abs(lon)>180) return;
        resolving = true;
        stopUpdates();
        // Geocoder availability is optional: coordinates are sufficient for the weather provider.
        new Thread(() -> {
            String name = null;
            try {
                if (Geocoder.isPresent()) {
                    List<Address> addresses = new Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1);
                    if (addresses != null && !addresses.isEmpty()) {
                        Address a = addresses.get(0);
                        name = a.getLocality();
                        if (name == null || name.isEmpty()) name = a.getSubAdminArea();
                    }
                }
            } catch (Exception ignored) { /* Keep usable coordinates when reverse geocoding is unavailable. */ }
            String city = name;
            main.post(() -> finish(city, lat, lon, null));
        }, "weather-location-name").start();
        main.removeCallbacks(timeout);
        main.postDelayed(() -> finish(null, lat, lon, null), 5_000);
    }

    private void finish(String city, double lat, double lon, String error) {
        if (finished) return;
        finished = true;
        main.removeCallbacksAndMessages(null);
        stopUpdates();
        callback.complete(city, lat, lon, error);
    }

    @SuppressLint("MissingPermission")
    private void stopUpdates() { if (manager != null) try { manager.removeUpdates(this); } catch (SecurityException ignored) {} }
    void cancel() { finished = true; main.removeCallbacksAndMessages(null); stopUpdates(); }
    @Override public void onProviderDisabled(String provider) { if (!resolving) finish(null, 0, 0, "disabled"); }
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
}
