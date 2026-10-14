package app.gtdial.lab;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.provider.AlarmClock;
import org.json.JSONObject;
import java.time.Instant;
import java.time.ZoneId;

/** Public next occurrence only: Android exposes neither full HiOS schedules nor repeat labels. */
final class AndroidAlarmBridge {
    private final Activity activity;
    private final Runnable changed;
    private boolean registered;
    private final BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){changed.run();}};
    AndroidAlarmBridge(Activity activity,Runnable changed){this.activity=activity;this.changed=changed;}
    void resume(){if(!registered){IntentFilter filter=new IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);if(Build.VERSION.SDK_INT>=33)activity.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED);else activity.registerReceiver(receiver,filter);registered=true;}}
    void pause(){if(registered){activity.unregisterReceiver(receiver);registered=false;}}
    AlarmManager.AlarmClockInfo next(){return ((AlarmManager)activity.getSystemService(Context.ALARM_SERVICE)).getNextAlarmClock();}
    JSONObject draftFromNext(){AlarmManager.AlarmClockInfo info=next();if(info==null)throw new IllegalStateException("ANDROID_NEXT_ALARM_ABSENT");java.time.ZonedDateTime time=Instant.ofEpochMilli(info.getTriggerTime()).atZone(ZoneId.systemDefault());JSONObject draft=ExpansionModels.record("ANDROID_NEXT_ALARM");ExpansionModels.put(draft,"origin","ANDROID_NEXT_ALARM");ExpansionModels.put(draft,"hour",time.getHour());ExpansionModels.put(draft,"minute",time.getMinute());ExpansionModels.put(draft,"selectedNextTrigger",info.getTriggerTime());ExpansionModels.put(draft,"recurrence","UNKNOWN");ExpansionModels.put(draft,"weekdays",new org.json.JSONArray());ExpansionModels.put(draft,"enabled",true);ExpansionModels.put(draft,"label","");ExpansionModels.put(draft,"state","LOCAL_DRAFT");return draft;}
    void open(){AlarmManager.AlarmClockInfo info=next();if(info!=null&&info.getShowIntent()!=null){try{info.getShowIntent().send();return;}catch(PendingIntent.CanceledException ignored){}}Intent intent=new Intent(AlarmClock.ACTION_SHOW_ALARMS);activity.startActivity(intent);}
    void reminder(JSONObject plan){String time=plan.optString("startLocalTime","");if(time.isEmpty())throw new IllegalArgumentException("PLAN_TIME_REQUIRED");java.time.LocalDate date=java.time.LocalDate.parse(plan.optString("localDate"));java.time.ZoneId zone=java.time.ZoneId.of(plan.optString("zoneId"));java.time.LocalTime local=java.time.LocalTime.parse(time);Intent intent=new Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR,local.getHour()).putExtra(AlarmClock.EXTRA_MINUTES,local.getMinute()).putExtra(AlarmClock.EXTRA_MESSAGE,"Часовой: "+WorkoutCatalog.name(plan.optString("sportId"))+" · "+date+" · "+zone.getId()+" (дату проверьте вручную)").putExtra(AlarmClock.EXTRA_SKIP_UI,false);activity.startActivity(intent);}
}
