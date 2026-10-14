package app.gtdial.lab;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;

/** Own foods plus immutable recent item snapshots; no invented external food database. */
final class FoodSearchController {
    private final ExpansionStore store;
    FoodSearchController(ExpansionStore store){this.store=store;}
    List<JSONObject> search(String query){String term=query.trim().toLowerCase(Locale.ROOT);LinkedHashMap<String,JSONObject> found=new LinkedHashMap<>();for(JSONObject food:store.nutritionRows("nutrition_foods",null))if("ACTIVE".equals(food.optString("state"))){JSONObject item=food.optJSONObject("item");if(item.optString("name").toLowerCase(Locale.ROOT).contains(term))found.put("own:"+food.optString("id"),NutritionModels.object("item",NutritionModels.copy(item),"food",food,"origin","Свой продукт"));}for(JSONObject entry:store.nutritionRows("nutrition_entries",null)){if("REMOVED".equals(entry.optString("state")))continue;JSONArray items=entry.optJSONArray("items");for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);if(!item.optString("name").toLowerCase(Locale.ROOT).contains(term))continue;String signature=item.optString("name").toLowerCase(Locale.ROOT)+"|"+item.optString("calculation")+"|"+item.opt("basisKcalMilli")+"|"+item.opt("kcalMilli");found.putIfAbsent(signature,NutritionModels.object("item",NutritionModels.copy(item),"food",null,"origin","Недавний"));}}return new ArrayList<>(found.values());}
}
