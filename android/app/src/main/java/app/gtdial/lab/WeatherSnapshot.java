package app.gtdial.lab;

final class WeatherSnapshot {
    static final class ForecastDay {
        final java.time.LocalDate date;final double lowC,highC;final int wmoCode;
        ForecastDay(java.time.LocalDate date,double lowC,double highC,int wmoCode){this.date=date;this.lowC=lowC;this.highC=highC;this.wmoCode=wmoCode;}
    }
    final double latitude, longitude, temperatureC;
    final int wmoCode;
    final long fetchedAtMs, providerTimeUnix,modelIssuedAtUnix,forecastValidAtUnix,evaluatedAtMs;
    final String rangeBasis;
    final String source;
    final double dailyMinC,dailyMaxC;
    final java.util.List<ForecastDay> forecast;
    final String forecastZoneId,forecastBaseDate;
    WeatherSnapshot(double latitude,double longitude,double temperatureC,int wmoCode,long fetchedAtMs,String source){this(latitude,longitude,temperatureC,wmoCode,fetchedAtMs,0,Double.NaN,Double.NaN,source);}
    WeatherSnapshot(double latitude,double longitude,double temperatureC,int wmoCode,long fetchedAtMs,long providerTimeUnix,double dailyMinC,double dailyMaxC,String source){this(latitude,longitude,temperatureC,wmoCode,fetchedAtMs,providerTimeUnix,dailyMinC,dailyMaxC,source,java.util.Collections.emptyList(),"","");}
    WeatherSnapshot(double latitude,double longitude,double temperatureC,int wmoCode,long fetchedAtMs,long providerTimeUnix,double dailyMinC,double dailyMaxC,String source,java.util.List<ForecastDay> forecast,String zone,String date){this(latitude,longitude,temperatureC,wmoCode,fetchedAtMs,providerTimeUnix,dailyMinC,dailyMaxC,source,forecast,zone,date,0,0,fetchedAtMs,"provider daily");}
    WeatherSnapshot(double latitude,double longitude,double temperatureC,int wmoCode,long fetchedAtMs,long providerTimeUnix,double dailyMinC,double dailyMaxC,String source,java.util.List<ForecastDay> forecast,String zone,String date,long modelIssuedAtUnix,long forecastValidAtUnix,long evaluatedAtMs,String rangeBasis){this.modelIssuedAtUnix=modelIssuedAtUnix;this.forecastValidAtUnix=forecastValidAtUnix;this.evaluatedAtMs=evaluatedAtMs;this.rangeBasis=rangeBasis;this.latitude=latitude;this.longitude=longitude;this.temperatureC=temperatureC;this.wmoCode=wmoCode;this.fetchedAtMs=fetchedAtMs;this.providerTimeUnix=providerTimeUnix;this.dailyMinC=dailyMinC;this.dailyMaxC=dailyMaxC;this.source=source;this.forecast=java.util.Collections.unmodifiableList(new java.util.ArrayList<>(forecast));forecastZoneId=zone;forecastBaseDate=date;}
    String forecastJson(){try{org.json.JSONArray days=new org.json.JSONArray();for(ForecastDay day:forecast)days.put(new org.json.JSONObject().put("date",day.date.toString()).put("low",day.lowC).put("high",day.highC).put("wmo",day.wmoCode));return new org.json.JSONObject().put("zone",forecastZoneId).put("date",forecastBaseDate).put("days",days).put("modelIssuedAtUnix",modelIssuedAtUnix).put("forecastValidAtUnix",forecastValidAtUnix).put("evaluatedAtMs",evaluatedAtMs).put("rangeBasis",rangeBasis).toString();}catch(Exception e){throw new IllegalArgumentException("forecast serialization",e);}}
    static WeatherSnapshot withCachedForecast(WeatherSnapshot today,String json){
        if(json==null||json.isEmpty()||json.length()>8192)return today;
        try{org.json.JSONObject saved=new org.json.JSONObject(json);org.json.JSONArray days=saved.getJSONArray("days");if(days.length()<1||days.length()>6)return today;java.util.List<ForecastDay> values=new java.util.ArrayList<>();for(int i=0;i<days.length();i++){org.json.JSONObject row=days.getJSONObject(i);values.add(new ForecastDay(java.time.LocalDate.parse(row.getString("date")),row.getDouble("low"),row.getDouble("high"),row.getInt("wmo")));}WeatherSnapshot result=new WeatherSnapshot(today.latitude,today.longitude,today.temperatureC,today.wmoCode,today.fetchedAtMs,today.providerTimeUnix,today.dailyMinC,today.dailyMaxC,today.source,values,saved.getString("zone"),saved.getString("date"),saved.optLong("modelIssuedAtUnix",0),saved.optLong("forecastValidAtUnix",0),saved.optLong("evaluatedAtMs",today.fetchedAtMs),saved.optString("rangeBasis","provider daily"));return new WeatherPayload(result).validForecast()?result:today;}catch(Exception e){return today;}
    }
}
