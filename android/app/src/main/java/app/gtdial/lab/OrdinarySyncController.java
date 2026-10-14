package app.gtdial.lab;

import android.content.SharedPreferences;

/** Session-thread cadence. Attempts survive restarts; failures never cause catch-up reads. */
final class OrdinarySyncController {
    static final String[] PROFILES={"ACTIVITY","HEART_LAST","SPO2_PAGE","SLEEP_PAGE","WEATHER_GET"};
    private static final long[] INTERVALS={300_000L,300_000L,300_000L,1_800_000L,1_800_000L};
    private final SharedPreferences preferences;
    OrdinarySyncController(SharedPreferences preferences){this.preferences=preferences;}
    private String key(String address,String profile){return "ordinary.attempt."+address+"."+profile;}
    boolean attempted(String address,String profile,long now){return preferences.edit().putLong(key(address,profile),now).commit();}
    String due(String address,long now){
        for(int i=0;i<PROFILES.length;i++)if(delay(address,i,now)==0)return PROFILES[i];
        return null;
    }
    long nextDelay(String address,long now){
        long next=Long.MAX_VALUE;for(int i=0;i<PROFILES.length;i++)next=Math.min(next,delay(address,i,now));return next;
    }
    private long delay(String address,int i,long now){
        long last=preferences.getLong(key(address,PROFILES[i]),0);
        // Clock rollback gets one normal interval, rather than an unbounded pause.
        return last==0?0:Math.max(0,Math.min(INTERVALS[i],last+INTERVALS[i]-now));
    }
}
