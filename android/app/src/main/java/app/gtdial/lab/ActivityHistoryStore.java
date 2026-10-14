package app.gtdial.lab;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Phone receipt-day observations only; never sums cumulative watch values. */
final class ActivityHistoryStore extends SQLiteOpenHelper {
    static final String SOURCE="svc_dcm_get_target_v1";
    private static final String TABLE="activity_observations";
    private static final String META="activity_history_settings";
    private static final String[] COLUMNS={"id","device_address","received_at_ms","phone_zone_id",
            "phone_day","steps","calorie_raw","distance_raw","source"};
    private boolean closed;
    ActivityHistoryStore(Context context){super(context.getApplicationContext(),"activity_history.db",null,1);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE "+TABLE+" (id INTEGER PRIMARY KEY AUTOINCREMENT, device_address TEXT NOT NULL, received_at_ms INTEGER NOT NULL, phone_zone_id TEXT NOT NULL, phone_day TEXT NOT NULL, steps INTEGER NOT NULL, calorie_raw INTEGER NOT NULL, distance_raw INTEGER NOT NULL, source TEXT NOT NULL CHECK(source='svc_dcm_get_target_v1'), UNIQUE(device_address,received_at_ms))");
        db.execSQL("CREATE INDEX activity_observations_day ON "+TABLE+" (device_address,phone_day,received_at_ms)");
        createSettings(db);
    }
    @Override public void onOpen(SQLiteDatabase db){super.onOpen(db);if(!db.isReadOnly())createSettings(db);}
    private static void createSettings(SQLiteDatabase db){db.execSQL("CREATE TABLE IF NOT EXISTS "+META+" (id TEXT PRIMARY KEY,value_ms INTEGER NOT NULL)");}
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){throw new IllegalStateException("Unsupported activity history schema migration");}
    synchronized void record(String device,ActivitySnapshot snapshot,ZoneId phoneZone){
        requireOpen();requireDevice(device);
        if(snapshot==null||phoneZone==null)throw new IllegalArgumentException("activity observation missing");
        ContentValues values=new ContentValues();values.put("device_address",device);
        values.put("received_at_ms",snapshot.receivedAt);values.put("phone_zone_id",phoneZone.getId());
        values.put("phone_day",Instant.ofEpochMilli(snapshot.receivedAt).atZone(phoneZone).toLocalDate().toString());
        values.put("steps",snapshot.steps);values.put("calorie_raw",snapshot.calorie);
        values.put("distance_raw",snapshot.distance);values.put("source",SOURCE);
        // Duplicate receipt replay is harmless; other SQLite failures remain visible to the caller.
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try{
        // The database lock covers both helper instances: a late receipt cannot undo deletion.
        long cutoff=deletionCutoff(db);if(snapshot.receivedAt<=cutoff){db.setTransactionSuccessful();return;}
        if(db.insertWithOnConflict(TABLE,null,values,SQLiteDatabase.CONFLICT_IGNORE)==-1){
            try(Cursor c=db.query(TABLE,new String[]{"id"},"device_address=? AND received_at_ms=?",
                    new String[]{device,Long.toString(snapshot.receivedAt)},null,null,null,"1")){
                if(!c.moveToFirst())throw new IllegalStateException("Activity observation was not stored");
            }
        }
        db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    synchronized ActivityHistoryEntry latestByDay(String device,String day){
        requireOpen();requireDevice(device);requireDay(day);
        try(Cursor c=getReadableDatabase().query(TABLE,COLUMNS,"device_address=? AND phone_day=?",
                new String[]{device,day},null,null,"received_at_ms DESC,id DESC","1")){
            return c.moveToFirst()?entry(c):null;
        }
    }
    synchronized List<String> observedDays(String device){
        requireOpen();requireDevice(device);ArrayList<String> days=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query(true,TABLE,new String[]{"phone_day"},"device_address=?",
                new String[]{device},null,null,"phone_day DESC","366")){
            while(c.moveToNext())days.add(c.getString(0));
        }
        return Collections.unmodifiableList(days);
    }
    synchronized List<String> devices(){
        requireOpen();ArrayList<String> values=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query(true,TABLE,new String[]{"device_address"},null,null,null,null,"device_address","128")){while(c.moveToNext())values.add(c.getString(0));}
        return Collections.unmodifiableList(values);
    }
    synchronized List<ActivityHistoryEntry> observationsForDay(String device,String day){
        requireOpen();requireDevice(device);requireDay(day);ArrayList<ActivityHistoryEntry> records=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query(TABLE,COLUMNS,"device_address=? AND phone_day=?",
                new String[]{device,day},null,null,"received_at_ms DESC,id DESC","4096")){
            while(c.moveToNext())records.add(entry(c));
        }
        Collections.reverse(records);return Collections.unmodifiableList(records);
    }
    synchronized List<ActivityHistoryEntry> measurementsForExport(){
        requireOpen();ArrayList<ActivityHistoryEntry> records=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query(TABLE,COLUMNS,null,null,null,null,"received_at_ms ASC,id ASC")){
            while(c.moveToNext()){
                if(records.size()>=50000)throw new IllegalStateException("MEASUREMENT_EXPORT_LIMIT");
                records.add(entry(c));
            }
        }
        return Collections.unmodifiableList(records);
    }
    synchronized void clearMeasurements(long deletedAt){
        requireOpen();if(deletedAt<=0)throw new IllegalArgumentException("MEASUREMENT_DELETE_TIME");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{ContentValues value=new ContentValues();value.put("id","measurements_deleted_at");value.put("value_ms",Math.max(deletedAt,deletionCutoff(db)));
            if(db.insertWithOnConflict(META,null,value,SQLiteDatabase.CONFLICT_REPLACE)==-1)throw new IllegalStateException("ACTIVITY_DELETE_CUTOFF");
            db.delete(TABLE,null,null);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    private static long deletionCutoff(SQLiteDatabase db){try(Cursor c=db.query(META,new String[]{"value_ms"},"id=?",new String[]{"measurements_deleted_at"},null,null,null,"1")){return c.moveToFirst()?c.getLong(0):0;}}
    private static ActivityHistoryEntry entry(Cursor c){return new ActivityHistoryEntry(c.getLong(0),c.getString(1),c.getLong(2),c.getString(3),c.getString(4),c.getInt(5),c.getInt(6),c.getInt(7),c.getString(8));}
    private static void requireDevice(String device){if(device==null||device.isBlank())throw new IllegalArgumentException("activity device missing");}
    private static void requireDay(String day){if(day==null||!LocalDate.parse(day).toString().equals(day))throw new IllegalArgumentException("activity phone day");}
    private void requireOpen(){if(closed)throw new IllegalStateException("Activity history closed");}
    @Override public synchronized void close(){if(!closed){closed=true;super.close();}}
}
