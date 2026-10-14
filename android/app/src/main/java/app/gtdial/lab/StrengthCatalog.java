package app.gtdial.lab;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Local stable movement variants. A dumbbell load always means one dumbbell. */
final class StrengthCatalog {
    private static final String[][] ROWS={
        {"bench_bar","bench","Жим штанги лёжа","Грудь","Штанга","BARBELL_TOTAL","bench"},
        {"bench_db","bench","Жим гантелей лёжа","Грудь","Гантели","DUMBBELL_EACH","bench"},
        {"incline_bar","incline","Жим штанги на наклонной скамье","Грудь","Штанга","BARBELL_TOTAL","bench"},
        {"incline_db","incline","Жим гантелей на наклонной скамье","Грудь","Гантели","DUMBBELL_EACH","bench"},
        {"chest_machine","bench","Жим в тренажёре","Грудь","Тренажёр","MACHINE","bench"},
        {"fly_db","fly","Разведение гантелей лёжа","Грудь","Гантели","DUMBBELL_EACH","raise"},
        {"fly_machine","fly","Сведение рук в тренажёре","Грудь","Тренажёр","MACHINE","raise"},
        {"pushup","pushup","Отжимания от пола","Грудь","Свой вес","BODYWEIGHT","bench"},
        {"row_bar","row","Тяга штанги в наклоне","Спина","Штанга","BARBELL_TOTAL","row"},
        {"row_db","row","Тяга гантели одной рукой","Спина","Гантели","DUMBBELL_EACH","row"},
        {"row_cable","row","Тяга горизонтального блока","Спина","Тренажёр","MACHINE","row"},
        {"pulldown","pull","Тяга верхнего блока","Спина","Тренажёр","MACHINE","pull"},
        {"pullup","pull","Подтягивания","Спина","Турник","BODYWEIGHT","pull"},
        {"pullup_added","pull","Подтягивания с дополнительным весом","Спина","Турник","BODYWEIGHT_ADDED","pull"},
        {"pullup_assisted","pull","Подтягивания с помощью тренажёра","Спина","Тренажёр","ASSISTANCE","pull"},
        {"deadlift","deadlift","Становая тяга","Спина","Штанга","BARBELL_TOTAL","squat"},
        {"squat_bar","squat","Приседания со штангой","Ноги","Штанга","BARBELL_TOTAL","squat"},
        {"goblet","squat","Приседания с гантелью","Ноги","Гантели","DUMBBELL_EACH","squat"},
        {"leg_press","leg_press","Жим ногами","Ноги","Тренажёр","MACHINE","squat"},
        {"lunge_db","lunge","Выпады с гантелями","Ноги","Гантели","DUMBBELL_EACH","squat"},
        {"romanian_bar","romanian","Румынская тяга со штангой","Ноги","Штанга","BARBELL_TOTAL","squat"},
        {"romanian_db","romanian","Румынская тяга с гантелями","Ноги","Гантели","DUMBBELL_EACH","squat"},
        {"leg_extension","extension","Разгибание ног","Ноги","Тренажёр","MACHINE","raise"},
        {"leg_curl","leg_curl","Сгибание ног","Ноги","Тренажёр","MACHINE","curl"},
        {"calf_machine","calf","Подъём на носки в тренажёре","Ноги","Тренажёр","MACHINE","raise"},
        {"hip_thrust","hip","Ягодичный мост со штангой","Ноги","Штанга","BARBELL_TOTAL","bench"},
        {"press_bar","press","Жим штанги стоя","Плечи","Штанга","BARBELL_TOTAL","press"},
        {"press_db","press","Жим гантелей сидя","Плечи","Гантели","DUMBBELL_EACH","press"},
        {"raise_db","raise","Подъём гантелей в стороны","Плечи","Гантели","DUMBBELL_EACH","raise"},
        {"rear_db","rear","Разведение гантелей в наклоне","Плечи","Гантели","DUMBBELL_EACH","raise"},
        {"curl_bar","curl","Сгибание рук со штангой","Руки","Штанга","BARBELL_TOTAL","curl"},
        {"curl_db","curl","Сгибание рук с гантелями","Руки","Гантели","DUMBBELL_EACH","curl"},
        {"hammer_db","hammer","Молотковые сгибания","Руки","Гантели","DUMBBELL_EACH","curl"},
        {"triceps_cable","triceps","Разгибание рук на блоке","Руки","Тренажёр","MACHINE","press"},
        {"triceps_db","triceps","Разгибание рук с гантелью","Руки","Гантели","DUMBBELL_EACH","press"},
        {"dip","dip","Отжимания на брусьях","Руки","Брусья","BODYWEIGHT","press"},
        {"crunch","crunch","Скручивания","Корпус","Свой вес","BODYWEIGHT","plank"},
        {"leg_raise","leg_raise","Подъём ног в висе","Корпус","Турник","BODYWEIGHT","raise"}
    };
    static List<JSONObject> list(){List<JSONObject> result=new ArrayList<>();for(String[] row:ROWS){JSONObject j=new JSONObject();String[] keys={"id","familyId","name","group","equipment","loadMode","illustration"};for(int i=0;i<keys.length;i++)ExpansionModels.put(j,keys[i],row[i]);result.add(j);}return result;}
    static JSONObject find(String id){for(JSONObject row:list())if(id.equals(row.optString("id")))return row;return null;}
    static boolean loadMode(String mode){return "BARBELL_TOTAL".equals(mode)||"DUMBBELL_EACH".equals(mode)||"MACHINE".equals(mode)||"BODYWEIGHT".equals(mode)||"BODYWEIGHT_ADDED".equals(mode)||"ASSISTANCE".equals(mode);}
    private StrengthCatalog(){}
}
