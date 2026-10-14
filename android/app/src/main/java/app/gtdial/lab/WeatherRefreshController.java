package app.gtdial.lab;

final class WeatherRefreshController {
    static final long FRESH_MS=30*60*1000L, STALE_MS=90*60*1000L;
    static boolean isFresh(WeatherSnapshot s,long now){return s!=null&&now-s.fetchedAtMs<=FRESH_MS;}
    static boolean isScheduledDue(WeatherSnapshot s,long now){return s==null||now-s.fetchedAtMs>=FRESH_MS;}
    static boolean needsRefresh(WeatherSnapshot s,long now){return s==null||now-s.fetchedAtMs>=STALE_MS;}
}
