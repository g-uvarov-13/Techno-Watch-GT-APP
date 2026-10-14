package app.gtdial.lab;

import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Explanations from observed records, with explicit eligibility rather than invented daily totals. */
final class InsightEngine {
    static final class Insight {
        final String id,title,detail,metric;
        final LocalDate from,to;
        Insight(String id,String title,String detail,String metric,LocalDate from,LocalDate to){this.id=id;this.title=title;this.detail=detail;this.metric=metric;this.from=from;this.to=to;}
    }
    static List<Insight> calculate(LocalDate end,ZoneId zone,List<ActivityHistoryEntry> activity,
            List<ExpansionModels.HealthRecord> sleep,List<JSONObject> workouts,List<JSONObject> plans) {
        LocalDate from=end.minusDays(14),mid=end.minusDays(7);
        List<Insight> out=new ArrayList<>();
        Map<LocalDate,Long> nights=new TreeMap<>();
        for(ExpansionModels.HealthRecord r:sleep){
            if(!r.validity.startsWith("VALID")||r.data.optLong("unknownSeconds")>0||!r.data.has("exit"))continue;
            LocalDate date=Instant.ofEpochSecond(r.data.optLong("exit")).atZone(zone).toLocalDate();
            if(date.isBefore(from)||!date.isBefore(end))continue;
            // One longest observed interval per end-date; daytime naps do not manufacture a night total.
            long seconds=r.data.optLong("asleepSeconds");Long old=nights.get(date);if(old==null||seconds>old)nights.put(date,seconds);
        }
        List<Long> first=new ArrayList<>(),second=new ArrayList<>();
        for(Map.Entry<LocalDate,Long> e:nights.entrySet())(e.getKey().isBefore(mid)?first:second).add(e.getValue());
        String eligibility="Даты окончания: "+from+" — "+mid.minusDays(1)+" и "+mid+" — "+end.minusDays(1)+". Пригодных дней: "+first.size()+" / "+second.size()+". Сравниваются медианы самого длинного полученного интервала за день; это не доказательство полной ночи. Неизвестные стадии исключены. Пригодные даты: "+nights.keySet()+".";
        String id="sleep:"+end;
        if(first.size()>=5&&second.size()>=5){double a=median(first),b=median(second);String delta=String.format(java.util.Locale.getDefault(),"%+.0f",(b-a)/60.0);out.add(new Insight(id,"Наблюдаемые интервалы сна: "+delta+" мин", "Медиана: "+Math.round(a/60)+" → "+Math.round(b/60)+" мин. "+eligibility,"sleep",from,end));}
        else out.add(new Insight(id,"Для сравнения сна мало данных",eligibility+" Нужно минимум 5 пригодных дней в каждом окне.","sleep",from,end));
        Map<String,ActivityHistoryEntry> days=new TreeMap<>();
        for(ActivityHistoryEntry r:activity){LocalDate date=LocalDate.parse(r.phoneDay);if(!date.isBefore(from)&&date.isBefore(end)){ActivityHistoryEntry old=days.get(r.phoneDay);if(old==null||r.receivedAt>old.receivedAt)days.put(r.phoneDay,r);}}
        out.add(new Insight("activity:"+end,"Наблюдения активности: "+days.size()+" дней","Последний накопленный счётчик каждого дня сохраняется отдельно. Эти наблюдения не подтверждают полный день, поэтому недельное изменение шагов не вычисляется. Пропуски не заменяются нулём.","steps",from,end));
        LocalDate baselineStart=end.minusDays(28);Map<LocalDate,ExpansionModels.HealthRecord> baseline=new TreeMap<>();
        for(ExpansionModels.HealthRecord r:sleep){if(!r.validity.startsWith("VALID")||r.data.optLong("unknownSeconds")>0||!r.data.has("enter")||!r.data.has("exit"))continue;LocalDate d=Instant.ofEpochSecond(r.data.optLong("exit")).atZone(zone).toLocalDate();if(d.isBefore(baselineStart)||!d.isBefore(end))continue;ExpansionModels.HealthRecord old=baseline.get(d);if(old==null||r.data.optLong("asleepSeconds")>old.data.optLong("asleepSeconds"))baseline.put(d,r);}
        String baselineCoverage="За "+baselineStart+" — "+end.minusDays(1)+" пригодных дат окончания: "+baseline.size()+" / 28. Берётся самый длинный полученный интервал дня; неизвестные стадии исключены, полнота ночи не доказана. Пригодные даты: "+baseline.keySet()+".";
        if(baseline.size()>=14){List<Long> lengths=new ArrayList<>();double sin=0,cos=0;for(ExpansionModels.HealthRecord r:baseline.values()){lengths.add(r.data.optLong("asleepSeconds"));int secondOfDay=Instant.ofEpochSecond(r.data.optLong("enter")).atZone(zone).toLocalTime().toSecondOfDay();double angle=2*Math.PI*secondOfDay/86400.0;sin+=Math.sin(angle);cos+=Math.cos(angle);}Collections.sort(lengths);long q1=lengths.get((lengths.size()-1)/4),q3=lengths.get(3*(lengths.size()-1)/4);
            out.add(new Insight("sleep_baseline:"+end,"Обычный диапазон полученных интервалов сна", "Медиана "+Math.round(median(lengths)/60)+" мин; центральные 50%: "+Math.round(q1/60.0)+"–"+Math.round(q3/60.0)+" мин (квартили по индексам). "+baselineCoverage,"sleep",baselineStart,end));
            double resultant=Math.hypot(sin,cos)/baseline.size();if(resultant>1e-6){double angle=Math.atan2(sin,cos);if(angle<0)angle+=2*Math.PI;int meanSecondOfDay=((int)Math.round(angle*86400/(2*Math.PI)))%86400;double deviation=Math.sqrt(Math.max(0,-2*Math.log(Math.min(1,resultant))))*86400/(2*Math.PI)/60;out.add(new Insight("sleep_regular:"+end,"Время начала полученных интервалов сна","Круговое среднее "+java.time.LocalTime.ofSecondOfDay(meanSecondOfDay).withSecond(0)+"; круговое стандартное отклонение "+Math.round(deviation)+" мин. Переход через полночь учтён через sin/cos времени суток, без советов о правильном часе сна. "+baselineCoverage,"sleep",baselineStart,end));}
            else out.add(new Insight("sleep_regular:"+end,"Время начала сна не имеет устойчивого среднего","Круговые направления почти взаимно компенсируются; время среднего не публикуется. "+baselineCoverage,"sleep",baselineStart,end));
        }else{out.add(new Insight("sleep_baseline:"+end,"Для обычного диапазона сна мало данных",baselineCoverage+" Нужно минимум 14 пригодных дат за 28 дней.","sleep",baselineStart,end));out.add(new Insight("sleep_regular:"+end,"Для регулярности сна мало данных",baselineCoverage+" Нужно минимум 14 пригодных дат; круговой расчёт ещё не публикуется.","sleep",baselineStart,end));}
        long duration=0;int completed=0,planned=0,done=0;
        for(JSONObject r:workouts){long at=r.optLong("startedAt");LocalDate date=Instant.ofEpochMilli(at).atZone(zone).toLocalDate();if("SAVED".equals(r.optString("status"))&&!date.isBefore(mid)&&date.isBefore(end)&&ExpansionModels.PHONE.equals(r.optString("source"))){duration+=r.optLong("elapsedActiveMillis");completed++;}}
        for(JSONObject p:plans){LocalDate date=LocalDate.parse(p.optString("localDate"));if(!date.isBefore(mid)&&date.isBefore(end)){planned++;if("DONE".equals(p.optString("status")))done++;}}
        out.add(new Insight("workouts:"+end,"Занятия телефона: "+completed,"За "+mid+" — "+end.minusDays(1)+": активное время "+WorkoutRecorder.duration(duration)+". Отмечено планов: "+done+" из "+planned+". Записи часов не складываются с записями телефона; неизвестные интервалы после перезагрузки исключены.","workouts",mid,end));
        return Collections.unmodifiableList(out);
    }
    private static double median(List<Long> values){List<Long> sorted=new ArrayList<>(values);Collections.sort(sorted);int n=sorted.size();return n%2==1?sorted.get(n/2):(sorted.get(n/2-1)/2.0+sorted.get(n/2)/2.0);}
    private InsightEngine(){ }
}
