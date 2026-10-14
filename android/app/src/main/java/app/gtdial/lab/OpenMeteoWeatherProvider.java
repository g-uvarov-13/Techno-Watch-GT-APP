package app.gtdial.lab;

import java.io.*;import java.net.*;import javax.net.ssl.HttpsURLConnection;import org.json.*;

final class OpenMeteoWeatherProvider {
    static final int TIMEOUT_MS=10_000, MAX_BYTES=256*1024;
    WeatherSnapshot fetch(double latitude,double longitude,String city,long now) throws IOException {
        if(!Double.isFinite(latitude)||!Double.isFinite(longitude)||latitude<-90||latitude>90||longitude<-180||longitude>180)throw new IllegalArgumentException("invalid coordinates");
        URL url=new URL("https://api.open-meteo.com/v1/forecast?latitude="+latitude+"&longitude="+longitude+"&timezone=auto&timeformat=unixtime&current=temperature_2m,weather_code&daily=temperature_2m_min,temperature_2m_max,weather_code&forecast_days=7");
        HttpsURLConnection c=(HttpsURLConnection)url.openConnection(); InputStream in=null;
        try {c.setInstanceFollowRedirects(false);c.setConnectTimeout(TIMEOUT_MS);c.setReadTimeout(TIMEOUT_MS);c.setRequestProperty("Accept","application/json");
        if(c.getResponseCode()!=200)throw new IOException("Open-Meteo HTTP "+c.getResponseCode()); in=c.getInputStream();return parse(readBounded(in),latitude,longitude,city,now);
        } finally {if(in!=null)try{in.close();}catch(IOException ignored){} c.disconnect();}
    }
    static byte[] readBounded(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];for(int n;(n=in.read(b))!=-1;){if(out.size()+n>MAX_BYTES)throw new IOException("Open-Meteo response exceeds 256KiB");out.write(b,0,n);}return out.toByteArray();}
    static WeatherSnapshot parse(byte[] bytes,double lat,double lon,String city,long now)throws IOException{try{
        JSONObject root=new JSONObject(new String(bytes,"UTF-8"));JSONObject current=root.getJSONObject("current"),units=root.getJSONObject("current_units"),daily=root.getJSONObject("daily");
        if(!"°C".equals(units.getString("temperature_2m")))throw new IOException("temperature unit is not Celsius");double temp=current.getDouble("temperature_2m"),code=current.getDouble("weather_code");long time=current.getLong("time");if(!Double.isFinite(temp)||!Double.isFinite(code)||code!=(int)code||time<=0)throw new IOException("invalid current values");
        JSONArray min=daily.getJSONArray("temperature_2m_min"),max=daily.getJSONArray("temperature_2m_max");if(min.length()<1||max.length()<1)throw new IOException("missing daily bounds");double lo=min.getDouble(0),hi=max.getDouble(0);if(!Double.isFinite(lo)||!Double.isFinite(hi)||lo>hi)throw new IOException("invalid daily bounds");
        java.time.ZoneId phoneZone=java.time.ZoneId.systemDefault();java.time.LocalDate phoneDate=java.time.Instant.ofEpochMilli(now).atZone(phoneZone).toLocalDate();java.util.List<WeatherSnapshot.ForecastDay> forecast=new java.util.ArrayList<>();
        try{
            JSONObject dailyUnits=root.getJSONObject("daily_units");if(!"°C".equals(dailyUnits.getString("temperature_2m_min"))||!"°C".equals(dailyUnits.getString("temperature_2m_max")))throw new IllegalArgumentException("forecast Celsius units");
            java.time.ZoneId providerZone=java.time.ZoneId.of(root.getString("timezone"));JSONArray dates=daily.getJSONArray("time"),codes=daily.getJSONArray("weather_code");int count=dates.length();if(count<2||count>7||min.length()!=count||max.length()!=count||codes.length()!=count)throw new IllegalArgumentException("forecast alignment");
            java.time.LocalDate previous=null;
            for(int i=0;i<count;i++){
                Object timestamp=dates.get(i),weather=codes.get(i);if(!(timestamp instanceof Integer||timestamp instanceof Long)||!(weather instanceof Integer||weather instanceof Long))throw new IllegalArgumentException("forecast integer values");
                java.time.LocalDate date=java.time.Instant.ofEpochSecond(((Number)timestamp).longValue()).atZone(providerZone).toLocalDate();if(previous!=null&&!date.equals(previous.plusDays(1)))throw new IllegalArgumentException("forecast dates");previous=date;
                double low=min.getDouble(i),high=max.getDouble(i);int wmo=((Number)weather).intValue();if(((Number)weather).longValue()!=wmo)throw new IllegalArgumentException("forecast WMO range");
                if(date.isAfter(phoneDate))forecast.add(new WeatherSnapshot.ForecastDay(date,low,high,wmo));
            }
            WeatherSnapshot checked=new WeatherSnapshot(lat,lon,temp,(int)code,now,time,lo,hi,"Open-Meteo current+daily local; city="+(city==null?"":city),forecast,phoneZone.getId(),phoneDate.toString());if(!new WeatherPayload(checked).validForecast())forecast.clear();
        }catch(Exception unavailable){forecast.clear();}
        return new WeatherSnapshot(lat,lon,temp,(int)code,now,time,lo,hi,"Open-Meteo current+daily local; city="+(city==null?"":city),forecast,phoneZone.getId(),phoneDate.toString());
    }catch(JSONException x){throw new IOException("invalid Open-Meteo JSON",x);}}
}
