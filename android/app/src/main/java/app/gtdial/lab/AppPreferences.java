package app.gtdial.lab;
import android.content.Context;
final class AppPreferences {
    private final android.content.SharedPreferences p;
    AppPreferences(Context c){p=c.getSharedPreferences("gt_dial_preferences",Context.MODE_PRIVATE);}
    String language(){String v=p.getString("language","ru");return "en".equals(v)?"en":"ru";} void language(String v){p.edit().putString("language","en".equals(v)?"en":"ru").apply();}
    String theme(){String v=p.getString("theme","default");return v!=null&&v.matches("default|midnight-blue|arctic|olive")?v:"default";} void theme(String v){if(v!=null&&v.matches("default|midnight-blue|arctic|olive"))p.edit().putString("theme",v).apply();}
    String currentDial(){return p.getString("current_dial","");} void currentDial(String id){p.edit().putString("current_dial",id==null?"":id).apply();}
    String palette(String faceId){return faceId==null?"":p.getString("watchface.palette."+faceId,"");}
    void palette(String faceId,String color){if(faceId!=null&&!faceId.isEmpty()&&color!=null)p.edit().putString("watchface.palette."+faceId,color).apply();}
    boolean notificationForwardingEnabled(){return p.getBoolean("notification.forwarding.enabled",false);}
    void notificationForwardingEnabled(boolean enabled){p.edit().putBoolean("notification.forwarding.enabled",enabled).apply();}
    java.util.Set<String> notificationPackages(){return new java.util.HashSet<>(p.getStringSet("notification.forwarding.packages",java.util.Collections.emptySet()));}
    void notificationPackages(java.util.Set<String> packages){p.edit().putStringSet("notification.forwarding.packages",new java.util.HashSet<>(packages)).apply();}
    String profileName(){return p.getString("profile.name","");}
    void profileName(String value){p.edit().putString("profile.name",ExpansionModels.text(value,80)).apply();}
    int stepsGoal(){return p.getInt("goal.steps",8000);}
    void stepsGoal(int value){if(value<100||value>200000)throw new IllegalArgumentException("STEPS_GOAL");p.edit().putInt("goal.steps",value).apply();}
    int sleepGoalMinutes(){return p.getInt("goal.sleep_minutes",480);}
    void sleepGoalMinutes(int value){if(value<60||value>1440)throw new IllegalArgumentException("SLEEP_GOAL");p.edit().putInt("goal.sleep_minutes",value).apply();}
    String sleepSchedule(){return p.getString("profile.sleep_schedule","23:00");}
    void sleepSchedule(String value){java.time.LocalTime time=java.time.LocalTime.parse(value);p.edit().putString("profile.sleep_schedule",time.toString()).apply();}
    String distanceUnit(){return p.getString("profile.distance_unit","km");}
    void distanceUnit(String value){if(!"km".equals(value)&&!"mi".equals(value))throw new IllegalArgumentException("DISTANCE_UNIT");p.edit().putString("profile.distance_unit",value).apply();}
    java.util.Set<String> homeCards(){return new java.util.HashSet<>(p.getStringSet("home.cards",new java.util.HashSet<>(java.util.Arrays.asList("steps","heart","sleep","weight"))));}
    void homeCards(java.util.Set<String> value){p.edit().putStringSet("home.cards",new java.util.HashSet<>(value)).apply();}
    java.util.Set<String> favoriteSports(){return new java.util.HashSet<>(p.getStringSet("workout.favorites",new java.util.HashSet<>(java.util.Arrays.asList("walk","run","strength"))));}
    void favoriteSports(java.util.Set<String> value){p.edit().putStringSet("workout.favorites",new java.util.HashSet<>(value)).apply();}
    String recentSports(){return p.getString("workout.recent","");}
    void recentSport(String id){java.util.LinkedHashSet<String> ids=new java.util.LinkedHashSet<>();ids.add(id);for(String old:recentSports().split(","))if(!old.isEmpty())ids.add(old);StringBuilder out=new StringBuilder();int count=0;for(String item:ids){if(count++==8)break;if(out.length()>0)out.append(',');out.append(item);}p.edit().putString("workout.recent",out.toString()).apply();}
    String evidenceDevice(){return p.getString("health.device","");}
    void evidenceDevice(String device){p.edit().putString("health.device",device==null?"":device).apply();}
    String savedDevice(){return p.getString("device.identity","");}
    void savedDevice(String device){p.edit().putString("device.identity",device==null?"":device).apply();}
    String savedDeviceName(){return p.getString("device.name","");}
    void savedDeviceName(String name){p.edit().putString("device.name",ExpansionModels.text(name,120)).apply();}
    long lastSourceAt(String source){return p.getLong("source.last."+source,0);}
    void lastSourceAt(String source,long millis){p.edit().putLong("source.last."+source,millis).apply();}
    String retention(){return p.getString("privacy.retention","KEEP");}
    void retention(String value){if(!value.matches("KEEP|DAYS_90|DAYS_365"))throw new IllegalArgumentException("RETENTION");p.edit().putString("privacy.retention",value).apply();}
    boolean exportSection(String key,boolean fallback){return p.getBoolean("export."+key,fallback);}
    void setExportSection(String key,boolean value){p.edit().putBoolean("export."+key,value).apply();}
}
