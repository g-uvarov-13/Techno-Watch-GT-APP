package app.gtdial.lab;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Explicit internal alarm only. Work runs on the existing serial storage executor. */
public final class WorkoutIdleReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){if(intent==null||!"app.gtdial.lab.STRENGTH_IDLE".equals(intent.getAction()))return;PendingResult pending=goAsync();Context app=context.getApplicationContext();ExpansionStore store=ExpansionStore.get(app);store.execute(()->{try{org.json.JSONObject r=new WorkoutRecorder(app,store).current();if(r==null)WorkoutInactivityController.cancel(app);}catch(RuntimeException e){OperationJournal.get(app).stage("","strength_idle","alarm","FAILED","FAILED","LOCAL_RECONCILIATION",0,0,0);}finally{pending.finish();}});}
}
