package app.gtdial.lab;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;

/** Explicit city selection; no background location permission or silent location guess. */
final class OpenMeteoCityResolver {
    static final class City {
        final String label;
        final double latitude, longitude;
        City(String label,double latitude,double longitude){this.label=label;this.latitude=latitude;this.longitude=longitude;}
    }
    List<City> search(String query) throws IOException {
        String term=query==null?"":query.trim();
        if(term.length()<2||term.length()>100)throw new IOException("Enter a city name (2–100 characters)");
        URL url=new URL("https://geocoding-api.open-meteo.com/v1/search?name="+URLEncoder.encode(term,"UTF-8")+"&count=5&language=ru&format=json");
        HttpsURLConnection c=(HttpsURLConnection)url.openConnection(); InputStream in=null;
        try {
            c.setInstanceFollowRedirects(false);c.setConnectTimeout(10_000);c.setReadTimeout(10_000);
            c.setRequestProperty("Accept","application/json");
            if(c.getResponseCode()!=200)throw new IOException("Geocoding HTTP "+c.getResponseCode());
            in=c.getInputStream();return parse(OpenMeteoWeatherProvider.readBounded(in));
        } finally {if(in!=null)try{in.close();}catch(IOException ignored){}c.disconnect();}
    }
    static List<City> parse(byte[] bytes)throws IOException {
        try {
            JSONObject root=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            JSONArray results=root.optJSONArray("results");List<City> cities=new ArrayList<>();
            if(results==null)return cities;
            for(int i=0;i<Math.min(5,results.length());i++){
                JSONObject item=results.getJSONObject(i);
                double lat=item.getDouble("latitude"),lon=item.getDouble("longitude");
                if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)continue;
                String name=item.getString("name"),country=item.optString("country","");
                String region=item.optString("admin1","");
                cities.add(new City(name+(region.isEmpty()?"":", "+region)+(country.isEmpty()?"":", "+country),lat,lon));
            }
            return cities;
        }catch(JSONException e){throw new IOException("Invalid geocoding response",e);}
    }
}
