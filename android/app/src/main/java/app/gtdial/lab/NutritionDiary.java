package app.gtdial.lab;
import org.json.JSONObject;
import java.time.LocalDate;
import java.util.List;

/** Storage executor caller owns this facade; all edits use compare-and-swap revisions. */
final class NutritionDiary {
    private final ExpansionStore store;
    NutritionDiary(ExpansionStore store){this.store=store;}
    List<JSONObject> entries(LocalDate day){return store.nutritionRows("nutrition_entries",day.toString());}
    JSONObject find(String id){return store.nutritionFind("nutrition_entries",id);}
    JSONObject create(LocalDate day){JSONObject value=NutritionModels.draft(day);store.nutritionWrite("nutrition_entries",value,-1);return value;}
    JSONObject save(JSONObject edited){JSONObject old=find(edited.optString("id"));if(old==null)throw new IllegalStateException("ENTRY_MISSING");long revision=NutritionModels.integer(edited,"revision",1,Long.MAX_VALUE-1);JSONObject next=NutritionModels.copy(edited);NutritionModels.put(next,"revision",revision+1);NutritionModels.put(next,"state","DRAFT");NutritionModels.put(next,"removedFrom",null);store.nutritionWrite("nutrition_entries",next,revision);return next;}
    JSONObject state(String id,long revision,String action){JSONObject old=find(id);if(old==null||old.optLong("revision")!=revision)throw new IllegalStateException("NUTRITION_REVISION_CHANGED");JSONObject next=NutritionModels.copy(old);if("CONFIRM".equals(action)){if(!"DRAFT".equals(old.optString("state")))throw new IllegalStateException("ENTRY_NOT_DRAFT");NutritionModels.put(next,"state","CONFIRMED");}else if("REMOVE".equals(action)){if("REMOVED".equals(old.optString("state")))throw new IllegalStateException("ENTRY_REMOVED");NutritionModels.put(next,"removedFrom",old.optString("state"));NutritionModels.put(next,"state","REMOVED");}else if("UNDO".equals(action)){if(!"REMOVED".equals(old.optString("state")))throw new IllegalStateException("ENTRY_NOT_REMOVED");NutritionModels.put(next,"state",old.optString("removedFrom"));NutritionModels.put(next,"removedFrom",null);}else throw new IllegalArgumentException("ENTRY_ACTION");NutritionModels.put(next,"revision",revision+1);store.nutritionWrite("nutrition_entries",next,revision);return next;}
    void deleteEntry(String id,long revision){store.deleteNutritionEntry(id,revision);}
    JSONObject day(LocalDate day){JSONObject saved=store.nutritionFind("nutrition_days",day.toString());return saved==null?NutritionModels.day(day):saved;}
    JSONObject saveDay(JSONObject day){JSONObject previous=store.nutritionFind("nutrition_days",day.optString("localDay"));long revision=day.optLong("revision");JSONObject next=NutritionModels.copy(day);NutritionModels.put(next,"revision",previous==null?1:revision+1);store.nutritionWrite("nutrition_days",next,previous==null?-1:revision);return next;}
    JSONObject saveFood(JSONObject item){NutritionModels.validateItem(item);JSONObject food=NutritionModels.object("schemaVersion",1,"id",NutritionModels.uuid(),"revision",1L,"state","ACTIVE","item",NutritionModels.copy(item));store.nutritionWrite("nutrition_foods",food,-1);return food;}
    void removeFood(JSONObject food){store.deleteNutritionFood(food.optString("id"),food.optLong("revision"));}
    JSONObject updateFood(JSONObject food,JSONObject item){NutritionModels.validateItem(item);JSONObject next=NutritionModels.copy(food);NutritionModels.put(next,"item",NutritionModels.copy(item));NutritionModels.put(next,"revision",food.optLong("revision")+1);store.nutritionWrite("nutrition_foods",next,food.optLong("revision"));return next;}
}
