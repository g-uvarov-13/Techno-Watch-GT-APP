package app.gtdial.lab;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

/** Documented GraphQL weather path. Unknown or partial responses fail closed. */
final class YandexWeatherProvider {
    static final class HttpStatusException extends IOException {
        final int status;
        HttpStatusException(int status){super("Yandex HTTP failure");this.status=status;}
    }
    private static final String QUERY="query PhoneWeather($point: PointInput!){weatherByPoint(request:$point){now{temperature(unit:CELSIUS) condition} forecast{days(limit:1){time summary{day{minTemperature(unit:CELSIUS) maxTemperature(unit:CELSIUS)}}}}}}";
    WeatherSnapshot fetch(double lat,double lon,String city,long now,String key)throws IOException{
        if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180||key==null||key.isEmpty())throw new IOException("Invalid request");
        HttpsURLConnection connection=(HttpsURLConnection)new URL("https://api.weather.yandex.ru/graphql/query").openConnection();
        try{
            JSONObject point=new JSONObject().put("lat",lat).put("lon",lon);
            byte[] body=new JSONObject().put("query",QUERY).put("variables",new JSONObject().put("point",point)).toString().getBytes(StandardCharsets.UTF_8);
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10000);connection.setReadTimeout(10000);
            connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("X-Yandex-Weather-Key",key);
            try(OutputStream output=connection.getOutputStream()){output.write(body);}
            int status=connection.getResponseCode();if(status!=200)throw new HttpStatusException(status);
            try(InputStream input=connection.getInputStream()){return parse(OpenMeteoWeatherProvider.readBounded(input),lat,lon,city,now);}
        }catch(JSONException e){throw new IOException("Yandex request invalid");}finally{connection.disconnect();}
    }
    static WeatherSnapshot parse(byte[] bytes,double lat,double lon,String city,long now)throws IOException{
        try{
            JSONObject root=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            if(root.has("errors")&&root.getJSONArray("errors").length()!=0)throw new IOException("Yandex errors");
            JSONObject weather=root.getJSONObject("data").getJSONObject("weatherByPoint"),current=weather.getJSONObject("now");
            JSONArray days=weather.getJSONObject("forecast").getJSONArray("days");if(days.length()!=1)throw new IOException("Yandex day count");
            JSONObject day=days.getJSONObject(0);OffsetDateTime date=OffsetDateTime.parse(day.getString("time"));
            if(!date.toLocalDate().equals(Instant.ofEpochMilli(now).atOffset(date.getOffset()).toLocalDate()))throw new IOException("Yandex day date");
            JSONObject summary=day.getJSONObject("summary").getJSONObject("day");
            double temperature=temperature(current,"temperature"),min=temperature(summary,"minTemperature"),max=temperature(summary,"maxTemperature");
            if(min>max)throw new IOException("Yandex bounds");
            if(city==null||city.trim().isEmpty())throw new IOException("Yandex city");
            return new WeatherSnapshot(lat,lon,temperature,wmo(current.getString("condition")),now,0,min,max,"Yandex Weather; city="+city);
        }catch(JSONException|java.time.DateTimeException e){throw new IOException("Yandex response invalid");}
    }
    private static double temperature(JSONObject object,String field)throws JSONException,IOException{
        Object raw=object.get(field);if(!(raw instanceof Number))throw new IOException("Yandex temperature type");
        double value=((Number)raw).doubleValue();if(!Double.isFinite(value)||value< -90||value>60)throw new IOException("Yandex temperature bounds");return value;
    }
    private static int wmo(String condition)throws IOException{
        switch(condition){
            case "CLEAR":return 0;case "PARTLY_CLOUDY":case "CLOUDY":return 2;case "OVERCAST":return 3;
            case "LIGHT_RAIN":case "SLEET":return 61;case "RAIN":return 63;case "HEAVY_RAIN":return 65;case "SHOWERS":return 80;
            case "LIGHT_SNOW":return 71;case "SNOW":return 73;case "SNOWFALL":return 75;
            case "THUNDERSTORM":case "THUNDERSTORM_WITH_RAIN":return 95;case "THUNDERSTORM_WITH_HAIL":return 99;
            default:throw new IOException("Yandex condition unsupported");
        }
    }
}
