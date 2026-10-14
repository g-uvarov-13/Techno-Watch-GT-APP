package app.gtdial.lab;

import org.json.JSONObject;

/** Builds the approved official-format TodayWeather payload from fresh phone weather. */
final class WeatherPayload {
    final WeatherSnapshot snapshot;
    WeatherPayload(WeatherSnapshot snapshot){this.snapshot=snapshot;}
    int forecastDays(){return snapshot==null?0:snapshot.forecast.size();}
    boolean validForecast(){try{
        if(snapshot==null||snapshot.forecast.size()<1||snapshot.forecast.size()>6)return false;
        java.time.ZoneId zone=java.time.ZoneId.of(snapshot.forecastZoneId);java.time.LocalDate date=java.time.LocalDate.parse(snapshot.forecastBaseDate).plusDays(1);
        for(WeatherSnapshot.ForecastDay day:snapshot.forecast){if(!date.equals(day.date)||!validTemperature(day.lowC)||!validTemperature(day.highC)||day.lowC>day.highC)return false;WeatherCodeMapper.qcDescribe(day.wmoCode);long ts=day.date.atStartOfDay(zone).toEpochSecond();if(ts<=0||ts>Integer.MAX_VALUE)return false;date=date.plusDays(1);}return true;
    }catch(Exception e){return false;}}
    boolean readyForWeekly(){return forecastDays()==6&&readyForForecast();}
    boolean readyForForecast(){return readyForBle()&&validForecast()&&java.time.ZoneId.systemDefault().getId().equals(snapshot.forecastZoneId)&&java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString().equals(snapshot.forecastBaseDate);}
    org.json.JSONArray createForecast()throws Exception{
        if(!readyForWeekly())throw new IllegalArgumentException("forecast expired, unavailable, or phone date/zone changed");org.json.JSONArray rows=new org.json.JSONArray();java.time.ZoneId zone=java.time.ZoneId.of(snapshot.forecastZoneId);
        for(WeatherSnapshot.ForecastDay day:snapshot.forecast)rows.put(new JSONObject().put("describe",WeatherCodeMapper.qcDescribe(day.wmoCode)).put("maxTemperature",asInt(day.highC,"forecast high")).put("minTemperature",asInt(day.lowC,"forecast low")).put("timestamp",(int)day.date.atStartOfDay(zone).toEpochSecond()).put("moonPhase",0));return rows;
    }
    boolean readyForBle(){try{
        if(snapshot==null)return false;
        WeatherCodeMapper.qcDescribe(snapshot.wmoCode);
        long now=System.currentTimeMillis();
        boolean providerFresh;
        if(snapshot.providerTimeUnix>0){
            providerFresh=snapshot.providerTimeUnix<=Integer.MAX_VALUE&&fresh(snapshot.providerTimeUnix*1000L,now);
        }else if(snapshot.providerTimeUnix==0&&snapshot.source!=null&&snapshot.source.startsWith("MET Norway; city=")){
            providerFresh=snapshot.modelIssuedAtUnix>=now/1000L-43_200L&&snapshot.modelIssuedAtUnix<=now/1000L+300L&&snapshot.forecastValidAtUnix>0&&snapshot.forecastValidAtUnix>=snapshot.evaluatedAtMs/1000L-5400L&&snapshot.forecastValidAtUnix<=snapshot.evaluatedAtMs/1000L+5400L&&fresh(snapshot.evaluatedAtMs,now)&&snapshot.fetchedAtMs>0&&snapshot.fetchedAtMs<=now+300_000L;
        }else if(snapshot.providerTimeUnix==0){
            String prefix=snapshot.source!=null&&snapshot.source.startsWith("Yandex Weather; city=")?"Yandex Weather; city=":"wttr.in; city=";
            // This provider has no observation timestamp: only phone fetch freshness is known.
            // Keep that absence truthful; the official payload updateTime is phone send time.
            providerFresh=snapshot.source!=null&&snapshot.source.startsWith(prefix)
                    &&!snapshot.source.substring(prefix.length()).trim().isEmpty();
        }else providerFresh=false;
        return validTemperature(snapshot.temperatureC)&&validTemperature(snapshot.dailyMinC)&&validTemperature(snapshot.dailyMaxC)
                &&snapshot.dailyMinC<=snapshot.dailyMaxC&&providerFresh
                &&(snapshot.source!=null&&snapshot.source.startsWith("MET Norway; city=")?providerFresh:fresh(snapshot.fetchedAtMs,now));
    }catch(Exception ignored){return false;}}
    private static boolean fresh(long time,long now){return time>0&&time>=now-30*60_000L&&time<=now+5*60_000L;}
    private static boolean validTemperature(double value){return Double.isFinite(value)&&value>=-90&&value<=60;}
    String blockedReason(){return "Weather snapshot is outside the approved WMO-to-QC mapping or has invalid values";}
    JSONObject applyTo(JSONObject prior,String city) throws Exception {
        if(prior!=null)WeatherSessionProtocol.requireKnownTodayWeather(prior);
        if(!readyForBle())throw new IllegalArgumentException(blockedReason());
        if(city==null||city.trim().isEmpty()||city.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>64)throw new IllegalArgumentException("city");
        for(int i=0;i<city.length();i++)if(Character.isISOControl(city.charAt(i)))throw new IllegalArgumentException("city control character");
        JSONObject candidate=prior==null?new JSONObject():new JSONObject(prior.toString());
        candidate.put("city",watchCity(city.trim()));
        candidate.put("currentTemperature",asInt(snapshot.temperatureC,"temperature"));
        candidate.put("updateTime",(int)(System.currentTimeMillis()/1000L));
        candidate.put("describe",WeatherCodeMapper.qcDescribe(snapshot.wmoCode));
        // This is the exact (counterintuitive) official adapter ordering: low -> max, high -> min.
        candidate.put("maxTemperature",asInt(snapshot.dailyMinC,"daily low"));
        candidate.put("minTemperature",asInt(snapshot.dailyMaxC,"daily high"));
        candidate.put("pm25",0);candidate.put("aqi",0);candidate.put("humidity",0);candidate.put("ultraviolet",0);candidate.put("unit",1);
        return candidate;
    }
    // GT01 returns U+FFFF for the Cyrillic city even when JSON escapes are used.
    // Only the watch label uses Russian transliteration; the app keeps the original.
    // Orthographic soft/hard signs are conventionally omitted; sign-only names fail.
    static String watchCity(String city){
        String alphabet="абвгдеёжзийклмнопрстуфхцчшщъыьэюя";
        String[] latin={"a","b","v","g","d","e","yo","zh","z","i","y","k","l","m","n","o","p","r","s","t","u","f","kh","ts","ch","sh","shch","","y","","e","yu","ya"};
        StringBuilder result=new StringBuilder();
        for(int i=0;i<city.length();i++){
            char c=city.charAt(i);
            if(c>=0x20&&c<=0x7e){result.append(c);continue;}
            int index=alphabet.indexOf(Character.toLowerCase(c));
            if(index<0)throw new IllegalArgumentException("unsupported watch city character");
            String replacement=latin[index];
            if(Character.isUpperCase(c)&&!replacement.isEmpty())result.append(Character.toUpperCase(replacement.charAt(0))).append(replacement.substring(1));
            else result.append(replacement);
        }
        String value=result.toString().trim();
        if(value.isEmpty()||value.length()>64)throw new IllegalArgumentException("watch city ASCII length");
        return value;
    }
    JSONObject create(String city)throws Exception{return applyTo(null,city);}
    private static int asInt(double v,String field){long rounded=Math.round(v);if(!validTemperature(v)||rounded<-90||rounded>60)throw new IllegalArgumentException(field);return (int)rounded;}
}
