package app.gtdial.lab;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.List;

final class NutritionSummary {
    static JSONObject day(List<JSONObject> entries,JSONObject declared){long kcal=0,protein=0,fat=0,carbs=0;int count=0,items=0,known=0,photo=0,manual=0;boolean p=true,f=true,c=true;
        for(JSONObject entry:entries){if(!"CONFIRMED".equals(entry.optString("state")))continue;count++;JSONArray a=entry.optJSONArray("items");for(int i=0;i<a.length();i++){JSONObject item=a.optJSONObject(i);items++;kcal+=item.optLong("kcalMilli");boolean kp=!item.isNull("proteinMg"),kf=!item.isNull("fatMg"),kc=!item.isNull("carbohydrateMg");p&=kp;f&=kf;c&=kc;if(kp)protein+=item.optLong("proteinMg");if(kf)fat+=item.optLong("fatMg");if(kc)carbs+=item.optLong("carbohydrateMg");if(kp&&kf&&kc)known++;String kind=item.optJSONObject("provenance").optString("kind");if("PHOTO_ESTIMATE".equals(kind))photo++;if("MANUAL_ESTIMATE".equals(kind))manual++;}}
        boolean complete=declared!=null&&declared.optBoolean("completeDayDeclared");Long goal=declared==null||declared.isNull("goalKcalMilli")?null:declared.optLong("goalKcalMilli");boolean observed=count>0||complete;
        return NutritionModels.object("confirmedEntryCount",count,"confirmedItemCount",items,"kcalMilli",observed?kcal:null,"proteinMg",observed&&p?protein:null,"fatMg",observed&&f?fat:null,"carbohydrateMg",observed&&c?carbs:null,"macrosKnownItems",known,"photoEstimatedItems",photo,"manualEstimatedItems",manual,"completeDayDeclared",complete,"goalKcalMilli",goal,"differenceToUserGoalKcalMilli",observed&&goal!=null?kcal-goal:null);
    }
    private NutritionSummary(){}
}
