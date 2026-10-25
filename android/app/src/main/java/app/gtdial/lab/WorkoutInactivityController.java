package app.gtdial.lab;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import org.json.JSONObject;

/** Best-effort elapsed alarm plus reconciliation; Android may defer alarms or stop the app. */
final class WorkoutInactivityController {
    private static final String CHANNEL="strength_inactivity";
    private static final java.util.concurrent.atomic.AtomicBoolean BUSY=new java.util.concurrent.atomic.AtomicBoolean();
    static void reconcile(Context context){if(!BUSY.compareAndSet(false,true))return;Context app=context.getApplicationContext();ExpansionStore store=ExpansionStore.get(app);store.execute(()->{try{JSONObject current=new WorkoutRecorder(app,store).current();if(current==null)cancel(app);}catch(RuntimeException e){OperationJournal.get(app).stage("","strength_idle","reconcile","FAILED","FAILED","LOCAL_RECONCILIATION",0,0,0);}finally{BUSY.set(false);}});}
    private static PendingIntent pending(Context c){return PendingIntent.getBroadcast(c,71,new Intent(c,WorkoutIdleReceiver.class).setAction("app.gtdial.lab.STRENGTH_IDLE"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    static void schedule(Context c,JSONObject r){AlarmManager alarm=c.getSystemService(AlarmManager.class);if(alarm==null)return;if(r==null||r.optBoolean("activeTimedWalk")||!WorkoutRecorder.isActive(r)||!"strength".equals(r.optString("typeId"))||!WorkoutRecorder.bootIdentity(c).equals(r.optString("lastStrengthActionBootIdentity"))){cancel(c);return;}long anchor=r.optLong("lastStrengthActionElapsed",-1);if(anchor<0){cancel(c);return;}long now=SystemClock.elapsedRealtime(),deadline=anchor+(now-anchor<3000000?3000000:3600000);if(deadline<=now)deadline=now+60000;try{alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,deadline,pending(c));}catch(RuntimeException ignored){/* Reconciliation and foreground tick remain available. */}}
    static void cancel(Context c){AlarmManager alarm=c.getSystemService(AlarmManager.class);if(alarm!=null)alarm.cancel(pending(c));}
    static void notifyStatus(Context c,JSONObject r,String state){try{notifyStatusSafely(c,r,state);}catch(RuntimeException e){OperationJournal.get(c).stage("","strength_idle","notification","FAILED","FAILED","NOTIFICATION_UNAVAILABLE",0,0,0);}}
    private static void notifyStatusSafely(Context c,JSONObject r,String state){if(Build.VERSION.SDK_INT>=33&&c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;NotificationManager manager=c.getSystemService(NotificationManager.class);if(manager==null)return;manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Силовая тренировка",NotificationManager.IMPORTANCE_DEFAULT));Intent open=new Intent(c,MainActivity.class).putExtra("strengthWorkoutId",r.optString("id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);PendingIntent content=PendingIntent.getActivity(c,72,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);String text="AUTO_FINISHED".equals(state)?"Занятие завершено временем последнего действия. Время можно исправить.":"50 минут без записи. Через 10 минут занятие завершится временем последнего действия.";manager.notify(72,new Notification.Builder(c,CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Силовая тренировка").setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(content).setAutoCancel(true).build());}
}
