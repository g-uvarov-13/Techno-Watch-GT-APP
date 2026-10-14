package app.gtdial.lab;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Recomputed from saved working sets; a draft or warm-up never becomes progress. */
final class StrengthProgress {
    private final ExpansionStore store;
    StrengthProgress(ExpansionStore store){this.store=store;}

    JSONObject month(String yyyyMm,String zoneId){
        YearMonth selected=YearMonth.parse(yyyyMm),previous=selected.minusMonths(1);
        ZoneId zone=ZoneId.of(zoneId);LocalDate today=LocalDate.now(zone);
        boolean incomplete=selected.equals(YearMonth.from(today));
        int elapsedDays=incomplete?today.getDayOfMonth():selected.lengthOfMonth();
        JSONObject result=new JSONObject(),groups=new JSONObject();
        Map<String,JSONObject> exercises=new LinkedHashMap<>(),previousBest=new LinkedHashMap<>();
        Map<String,Set<String>> sessions=new LinkedHashMap<>();
        Set<String> months=new TreeSet<>(java.util.Collections.reverseOrder());
        int workouts=0,workingSets=0;long activeMillis=0;
        for(JSONObject workout:store.reportRows("workouts")){
            if(!"SAVED".equals(workout.optString("status"))||!"strength".equals(workout.optString("typeId"))||!ExpansionModels.PHONE.equals(workout.optString("source")))continue;
            LocalDate date=startDate(workout,zone);YearMonth period=YearMonth.from(date);months.add(period.toString());
            boolean current=period.equals(selected)&&(!incomplete||date.getDayOfMonth()<=elapsedDays),comparison=period.equals(previous)&&(!incomplete||date.getDayOfMonth()<=elapsedDays);
            if(!current&&!comparison)continue;
            if(current){workouts++;activeMillis+=Math.max(0,workout.optLong("elapsedActiveMillis"));}
            for(JSONObject entry:store.diaryRows("strength_entries",workout.optString("id"))){
                if(entry.optBoolean("removed"))continue;
                String mode=entry.optString("loadMode"),equipment=entry.optString("equipmentProfileId");
                boolean comparable=!("MACHINE".equals(mode)||"ASSISTANCE".equals(mode))||!equipment.isEmpty();
                // Empty machine identities are local to the entry; unlike equipment cannot merge.
                String key=entry.optString("variantId")+"|"+mode+"|"+entry.optString("repConvention","REPS_AS_ENTERED")+"|"+(comparable?equipment:entry.optString("id"));
                for(JSONObject set:store.diaryRows("strength_sets",entry.optString("id"))){
                    if(!"DONE".equals(set.optString("state"))||!"WORK".equals(set.optString("setKind")))continue;
                    int reps=set.optInt("reps");if(reps<1||reps>1000)continue;
                    if(comparison&&comparable){JSONObject best=previousBest.get(key);if(best==null){best=new JSONObject();previousBest.put(key,best);}best(best,reps,set,mode);}
                    if(!current)continue;
                    workingSets++;String group=entry.optString("group");put(groups,group,groups.optInt(group)+1);
                    JSONObject exercise=exercises.get(key);
                    if(exercise==null){exercise=new JSONObject();put(exercise,"key",key);put(exercise,"name",entry.optString("nameSnapshot"));put(exercise,"loadMode",mode);put(exercise,"equipmentProfileId",equipment);put(exercise,"comparable",comparable);put(exercise,"bestByReps",new JSONObject());put(exercise,"sets",0);put(exercise,"reps",0);exercises.put(key,exercise);sessions.put(key,new java.util.HashSet<>());}
                    put(exercise,"sets",exercise.optInt("sets")+1);put(exercise,"reps",exercise.optLong("reps")+reps);sessions.get(key).add(workout.optString("id"));
                    best(exercise.optJSONObject("bestByReps"),reps,set,mode);
                }
            }
        }
        JSONArray rows=new JSONArray();for(Map.Entry<String,JSONObject> item:exercises.entrySet()){JSONObject row=item.getValue();put(row,"sessions",sessions.get(item.getKey()).size());put(row,"previousMonthBestByReps",previousBest.containsKey(item.getKey())?previousBest.get(item.getKey()):new JSONObject());rows.put(row);}
        months.add(selected.toString());JSONArray choices=new JSONArray();for(String month:months)choices.put(month);
        put(result,"month",selected.toString());put(result,"months",choices);put(result,"previousMonth",previous.toString());put(result,"incomplete",incomplete);put(result,"comparedThroughDay",incomplete?elapsedDays:null);
        put(result,"comparisonLabel",incomplete?"Текущий месяц не завершён; предыдущий месяц до того же числа ("+elapsedDays+")":"Полные календарные месяцы");
        put(result,"workouts",workouts);put(result,"workingSets",workingSets);put(result,"activeMillis",activeMillis);put(result,"groups",groups);put(result,"exercises",rows);return result;
    }
    private static LocalDate startDate(JSONObject workout,ZoneId fallback){
        String saved=workout.optString("localDateAtStart");if(!saved.isEmpty())return LocalDate.parse(saved);
        String storedZone=workout.optString("zoneIdAtStart",workout.optString("zoneIdAtImport",fallback.getId()));
        return Instant.ofEpochMilli(workout.optLong("startedAt")).atZone(ZoneId.of(storedZone)).toLocalDate();
    }
    private static void best(JSONObject map,int reps,JSONObject set,String mode){
        if(set.isNull("loadGrams"))return;long grams=set.optLong("loadGrams",-1);if(grams<0||grams>2000000)return;
        String key=Integer.toString(reps);boolean assistance="ASSISTANCE".equals(mode);
        if(!map.has(key)||(assistance?grams<map.optLong(key):grams>map.optLong(key)))put(map,key,grams);
    }
    private static void put(JSONObject row,String key,Object value){ExpansionModels.put(row,key,value);}
}
