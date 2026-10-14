package app.gtdial.lab;
import java.io.*;
import java.net.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;
/** Independent weather source. WWO codes are translated explicitly, never treated as WMO. */
final class WttrWeatherProvider {
 WeatherSnapshot fetch(double lat,double lon,String city,long now)throws IOException {
  if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)throw new IOException("Invalid coordinates");
  HttpsURLConnection c=(HttpsURLConnection)new URL("https://wttr.in/"+lat+","+lon+"?format=j1&m").openConnection();
  try {c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(10000);c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","Chasovoy/0.9.22");
   if(c.getResponseCode()!=200)throw new IOException("wttr.in HTTP "+c.getResponseCode());
   try(InputStream in=c.getInputStream()) {return parse(OpenMeteoWeatherProvider.readBounded(in),lat,lon,city,now);}
  }finally{c.disconnect();}
 }
 static WeatherSnapshot parse(byte[] bytes,double lat,double lon,String city,long now)throws IOException {
  try {JSONObject root=new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));if(root.has("data"))root=root.getJSONObject("data");
   JSONObject current=root.getJSONArray("current_condition").getJSONObject(0),day=root.getJSONArray("weather").getJSONObject(0);
   double temp=current.getDouble("temp_C"),min=day.getDouble("mintempC"),max=day.getDouble("maxtempC");
   if(!Double.isFinite(temp)||!Double.isFinite(min)||!Double.isFinite(max)||min>max)throw new IOException("Invalid wttr.in temperature");
   // observation_time has no date or zone: do not invent an observation timestamp.
   java.time.ZoneId zone=java.time.ZoneId.systemDefault();java.time.LocalDate today=java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate();java.util.List<WeatherSnapshot.ForecastDay> forecast=new java.util.ArrayList<>();
   try{
    JSONArray weather=root.getJSONArray("weather");if(weather.length()<2||weather.length()>7)throw new IllegalArgumentException("forecast day count");java.time.LocalDate previous=null;
    for(int i=0;i<weather.length();i++){
     JSONObject row=weather.getJSONObject(i);java.time.LocalDate date=java.time.LocalDate.parse(row.getString("date"));if(previous!=null&&!date.equals(previous.plusDays(1)))throw new IllegalArgumentException("forecast dates");previous=date;
     if(!date.isAfter(today))continue;
     JSONArray hours=row.getJSONArray("hourly");JSONObject noon=null;for(int h=0;h<hours.length();h++){JSONObject hour=hours.getJSONObject(h);if("1200".equals(hour.getString("time"))){if(noon!=null)throw new IllegalArgumentException("duplicate noon");noon=hour;}}
     if(noon==null)throw new IllegalArgumentException("missing noon");String rawCode=noon.getString("weatherCode");if(!rawCode.matches("[0-9]{1,4}"))throw new IllegalArgumentException("noon WWO code");
     forecast.add(new WeatherSnapshot.ForecastDay(date,row.getDouble("mintempC"),row.getDouble("maxtempC"),wmo(Integer.parseInt(rawCode))));
    }
    WeatherSnapshot checked=new WeatherSnapshot(lat,lon,temp,wmo(current.getInt("weatherCode")),now,0,min,max,"wttr.in; city="+city,forecast,zone.getId(),today.toString());if(!new WeatherPayload(checked).validForecast())forecast.clear();
   }catch(Exception unavailable){forecast.clear();}
   // Noon is the representative condition, not a claimed daily aggregate.
   return new WeatherSnapshot(lat,lon,temp,wmo(current.getInt("weatherCode")),now,0,min,max,"wttr.in; city="+city,forecast,zone.getId(),today.toString());
  }catch(JSONException e){throw new IOException("Invalid wttr.in JSON",e);}
 }
 private static int wmo(int code)throws IOException {
  switch(code){
   case 113:return 0;case 116:return 2;case 119:case 122:return 3;
   case 143:case 248:return 45;case 260:return 48;
   case 263:case 266:return 51;case 281:case 284:return 56;
   case 176:case 353:return 80;case 299:case 305:case 356:return 81;case 359:return 82;
   case 293:case 296:return 61;case 302:case 308:return 65;
   case 185:case 311:case 314:return 66;
   // Mixed rain/snow uses the existing coarse rain category; 68 is not an approved WMO code.
   case 179:case 182:case 317:case 362:case 365:return 61;
   case 227:case 320:return 71;case 230:case 329:case 332:case 338:return 75;
   case 323:case 326:case 368:return 85;case 335:case 371:case 395:return 86;
   case 350:case 374:case 377:return 77;
   case 200:case 386:case 389:case 392:return 95;
   default:throw new IOException("Unsupported wttr.in condition "+code);
  }
 }
}
