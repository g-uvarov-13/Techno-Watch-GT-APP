package app.gtdial.lab;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Phone templates only: local IDs never enter the GT01 protocol. */
final class WorkoutCatalog {
    static final class Sport {
        final String id,name,category,aliases;
        final boolean gps;
        Sport(String id,String name,String category,boolean gps,String aliases){this.id=id;this.name=name;this.category=category;this.gps=gps;this.aliases=aliases;}
    }
    static final List<Sport> ALL=Collections.unmodifiableList(Arrays.asList(
        s("walk","Ходьба","На улице",true,"прогулка шаги walking"),s("run","Бег","На улице",true,"пробежка running"),
        s("cycle","Велосипед","На улице",true,"велопрогулка cycling"),s("hike","Поход","На улице",true,"треккинг hiking"),
        s("nordic","Скандинавская ходьба","На улице",true,"палки"),s("ski","Лыжи","На улице",true,"лыжный спорт"),
        s("treadmill","Беговая дорожка","В зале",false,"бег зал"),s("strength","Силовая тренировка","В зале",false,"гантели штанга мышцы gym"),
        s("elliptical","Эллиптический тренажёр","В зале",false,"эллипс"),s("rowing","Гребной тренажёр","В зале",false,"гребля"),
        s("indoor_cycle","Велотренажёр","В зале",false,"сайкл"),s("interval","Интервальная тренировка","В зале",false,"hiit интервалы"),
        s("swim","Плавание","Вода",false,"бассейн swimming"),s("aqua","Аквааэробика","Вода",false,"вода"),
        s("yoga","Йога","Спокойные",false,"yoga"),s("pilates","Пилатес","Спокойные",false,"pilates"),
        s("stretch","Растяжка","Спокойные",false,"стретчинг stretching"),s("breathing","Дыхательная практика","Спокойные",false,"дыхание"),
        s("football","Футбол","Игры",false,"football"),s("basketball","Баскетбол","Игры",false,"basketball"),
        s("tennis","Теннис","Игры",false,"ракетка"),s("dance","Танцы","Другие",false,"dancing"),
        s("boxing","Бокс","Другие",false,"единоборства"),s("other","Другое занятие","Другие",false,"спорт workout"),
        s("rest","Отдых","Спокойные",false,"день отдыха восстановление")
    ));
    static List<Sport> search(String query,String category){String needle=query==null?"":query.toLowerCase(Locale.ROOT).trim();ArrayList<Sport> out=new ArrayList<>();for(Sport sport:ALL)if((category==null||category.isEmpty()||category.equals(sport.category))&&(sport.name+" "+sport.aliases).toLowerCase(Locale.ROOT).contains(needle))out.add(sport);return out;}
    static Sport find(String id){for(Sport s:ALL)if(s.id.equals(id))return s;return null;}
    static String name(String id){Sport sport=find(id);return sport==null?"Неизвестный местный вид: "+id:sport.name;}
    static String name(org.json.JSONObject workout){String source=workout.optString("source");return "WATCH_WORKOUT".equals(source)?"Вид часов: код "+workout.optString("rawWatchType","неизвестен"):name(workout.optString("typeId"));}
    private static Sport s(String id,String name,String category,boolean gps,String aliases){return new Sport(id,name,category,gps,aliases);}
    private WorkoutCatalog(){ }
}
