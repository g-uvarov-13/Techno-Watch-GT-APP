package app.gtdial.lab;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Exact app-private diagnostics only; never health data, databases or installation evidence. */
final class DiagnosticRetention {
    static final long WEEK=7L*24*60*60*1000;
    private static final Object GATE=new Object();
    private static final Set<String> ACTIVE_TEXT=new HashSet<>();
    private static int leases;private static boolean pending;
    interface IoAction {void run()throws IOException;}
    // All automatic deletion/rotation shares the lease gate; no check-then-delete race.
    static boolean whileUnleased(IoAction action)throws IOException{synchronized(GATE){if(leases>0)return false;action.run();return true;}}
    static final class Lease implements AutoCloseable {private boolean closed;public void close(){synchronized(GATE){if(!closed){closed=true;leases--;}}}}
    static Lease exportLease(){synchronized(GATE){leases++;return new Lease();}}
    static boolean leased(){synchronized(GATE){return leases>0;}}
    static void protect(File file){if(file!=null)synchronized(GATE){ACTIVE_TEXT.add(file.getAbsolutePath());}}
    static void release(File file){if(file!=null)synchronized(GATE){ACTIVE_TEXT.remove(file.getAbsolutePath());}}
    static boolean protectedText(File file){synchronized(GATE){return ACTIVE_TEXT.contains(file.getAbsolutePath());}}
    private static final class Stats {int files,failed;long bytes;boolean partial;}
    static void request(Context context){Context app=context.getApplicationContext();long now=System.currentTimeMillis();android.content.SharedPreferences prefs=app.getSharedPreferences("diagnostic_retention",Context.MODE_PRIVATE);synchronized(GATE){long last=prefs.getLong("lastSuccessfulAt",0),attempt=prefs.getLong("lastAttemptAt",0);if(pending||leases>0||(now-last<WEEK&&now>=last)||(now-attempt<3600000&&now>=attempt))return;pending=true;prefs.edit().putLong("lastAttemptAt",now).apply();}
        OperationJournal.get(app).maintainRetention(rotationOk->{Stats stats=new Stats();if(!rotationOk)stats.failed++;boolean[] ran={false};long started=android.os.SystemClock.elapsedRealtime();try{ran[0]=whileUnleased(()->{File root=app.getFilesDir().getCanonicalFile();File logs=exactDirectory(root,"logs"),reports=exactDirectory(root,"reports");long cutoff=System.currentTimeMillis()-WEEK;if(logs.exists()){age(logs,DiagnosticFiles.TEXT,cutoff,true,stats);age(logs,"operations-v1[.]jsonl[.][123]",cutoff,false,stats);age(logs,DiagnosticFiles.SNAPSHOT,cutoff,false,stats);}if(reports.exists())age(reports,DiagnosticFiles.REPORT,cutoff,false,stats);});}catch(IOException|RuntimeException e){stats.failed++;}finally{synchronized(GATE){if(ran[0]&&!stats.partial&&stats.failed==0)prefs.edit().putLong("lastSuccessfulAt",System.currentTimeMillis()).apply();pending=false;}String outcome=stats.failed>0?"FAILED":!ran[0]?"DEFERRED":stats.partial?"PARTIAL":"SUCCESS";OperationJournal.get(app).stage("","diagnostic_retention","weekly","COMPLETE",outcome,stats.failed>0?"BOUNDED_CLEANUP_FAILED":stats.partial?"CLEANUP_LIMIT":"",stats.files,stats.bytes,android.os.SystemClock.elapsedRealtime()-started);prefs.edit().putInt("lastRemovedFiles",stats.files).putLong("lastRemovedBytes",stats.bytes).putInt("lastFailedFiles",stats.failed).putString("lastOutcome",outcome).apply();}});
    }
    private static File exactDirectory(File root,String name)throws IOException{File target=new File(root,name);File canonical=target.getCanonicalFile();if(!canonical.equals(target.getAbsoluteFile())||!root.equals(canonical.getParentFile())||java.nio.file.Files.isSymbolicLink(target.toPath()))throw new IOException("RETENTION_DIRECTORY");return canonical;}
    private static void age(File directory,String pattern,long cutoff,boolean text,Stats stats)throws IOException{for(File file:DiagnosticFiles.matching(directory,pattern)){if(text&&ACTIVE_TEXT.contains(file.getAbsolutePath()))continue;if(file.lastModified()<=0||file.lastModified()>=cutoff)continue;if(stats.files+stats.failed>=128||stats.bytes+file.length()>32L*1024*1024){stats.partial=true;continue;}try{stats.bytes+=DiagnosticFiles.remove(file);stats.files++;}catch(IOException e){stats.failed++;}}}
}
