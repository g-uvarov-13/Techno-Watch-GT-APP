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
        {"leg_raise","leg_raise","Подъём ног в висе","Корпус","Турник","BODYWEIGHT","raise"},
        {"shoulder_machine","press","Жим на плечи в тренажёре","Плечи","Тренажёр","MACHINE","press"},
        {"lateral_cable","raise","Разведение в стороны на плечи в тренажёре","Плечи","Регулируемые блоки","MACHINE","raise"},
        {"hip_adduction","hip_adduction","Сведение ног в тренажёре","Ноги","Тренажёр","MACHINE","raise"},
        {"hip_abduction","hip_abduction","Разведение ног в тренажёре","Ноги","Тренажёр","MACHINE","raise"},
        {"overhead_db","triceps","Гантель за голову","Руки","Гантель: вес одной","DUMBBELL_EACH","press"},
        {"curl_machine","curl","Сгибание рук в тренажёре на бицепс","Руки","Тренажёр","MACHINE","curl"},
        {"dip_chest","dip_chest","Брусья на грудь","Грудь","Собственный вес","BODYWEIGHT","press"},
        {"french_press","triceps","Французский жим","Руки","Штанга","BARBELL_TOTAL","press"},
        {"back_extension","back_extension","Гиперэкстензия","Спина","Собственный вес","BODYWEIGHT","row"},
        {"pullover_machine","pullover","Пуловер на спину в тренажёре","Спина","Тренажёр","MACHINE","pull"},
        {"row_machine","row_supported","Тяга штанги на спину в тренажёре","Спина","Тренажёр с упором грудью","BARBELL_TOTAL","row"},
        {"walk_timed","walking","Шагаю с тренировки или на тренировку","Ходьба","Время, шаги и пульс","TIMED","walking"}
    };
    static List<JSONObject> list(){List<JSONObject> result=new ArrayList<>();for(String[] row:ROWS){JSONObject j=new JSONObject();String[] keys={"id","familyId","name","group","equipment","loadMode","illustration"};for(int i=0;i<keys.length;i++)ExpansionModels.put(j,keys[i],row[i]);if("walk_timed".equals(row[0]))ExpansionModels.put(j,"entryKind","TIMED_WALK");result.add(j);}return result;}
    static JSONObject find(String id){for(JSONObject row:list())if(id.equals(row.optString("id")))return row;return null;}
    static boolean loadMode(String mode){return "BARBELL_TOTAL".equals(mode)||"DUMBBELL_EACH".equals(mode)||"MACHINE".equals(mode)||"BODYWEIGHT".equals(mode)||"BODYWEIGHT_ADDED".equals(mode)||"ASSISTANCE".equals(mode);}
    /** Only explicit user-provided names are aliases; historical entries keep their IDs and load semantics. */
    static String migrationTarget(String originalName){
        String name=originalName==null?"":originalName.trim().toLowerCase(java.util.Locale.ROOT).replace('ё','е').replaceAll("\\s+"," ");
        switch(name){
            case "жим на плечи в тренажере":return "shoulder_machine";
            case "разведение в стороны на плече в диковинном тренажере":
            case "разведение в стороны на плечи в диковинном тренажере":return "lateral_cable";
            case "сведение ног в тренажерк":return "hip_abduction";
            case "сведение ног в тренажере":return "hip_adduction";
            case "гантеля затголову":case "гантеля за голову":return "overhead_db";
            case "сгибание рук в тренажере на бицепс":return "curl_machine";
            case "брусья на грудь":return "dip_chest";
            case "французский жим":return "french_press";
            case "гиперэкстензия":return "back_extension";
            case "пуловер на спину в тренажере":return "pullover_machine";
            case "тяга штанги на спину в тренажере":return "row_machine";
            case "шагаю с тренировки/или на тренировку":return "walk_timed";
            default:return "";
        }
    }
    static String navigationGroup(JSONObject exercise){
        if("TIMED_WALK".equals(exercise.optString("entryKind")))return "Ходьба";
        String group=exercise.optString("group").trim().toLowerCase(java.util.Locale.ROOT).replace('ё','е');
        switch(group){case "грудь":case "грудные":return "Грудь";
            case "спина":case "спину":return "Спина";
            case "ноги":case "ягодицы":case "икры":return "Ноги";
            case "плечи":case "плече":case "дельты":return "Плечи";
            case "руки":case "бицепс":case "трицепс":case "предплечья":return "Руки";
            case "корпус":case "пресс":return "Корпус";
            case "ходьба":return "Ходьба";default:return "Другие";}
    }
    private StrengthCatalog(){}
}
