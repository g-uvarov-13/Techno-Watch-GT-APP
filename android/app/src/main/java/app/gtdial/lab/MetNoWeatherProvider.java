package app.gtdial.lab;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Actual MET periods only. Extrema from boundary-overlapping periods are conservative. */
final class MetNoWeatherProvider {
    private static final int LIMIT=256*1024;
    private final Context context;
    MetNoWeatherProvider(Context context){this.context=context.getApplicationContext();}
    WeatherSnapshot fetch(double latitude,double longitude,String city,long now)throws Exception{
        String coordinates=String.format(Locale.US,"%.4f,%.4f",Math.copySign(Math.floor(Math.abs(latitude)*10000)/10000,latitude),Math.copySign(Math.floor(Math.abs(longitude)*10000)/10000,longitude));
        JSONObject cached=readCache(coordinates);long blocked=context.getSharedPreferences("gt_dial_preferences",Context.MODE_PRIVATE).getLong("weather.metno-retry-after",0);
        if(cached!=null&&cached.getLong("expires")>now)return evaluate(cached,latitude,longitude,city,now);
        if(blocked>now)throw new IOException("MET rate limit remains active");
        String[] pair=coordinates.split(",");URL url=new URL("https://api.met.no/weatherapi/locationforecast/2.0/complete?lat="+pair[0]+"&lon="+pair[1]);long deadline=android.os.SystemClock.elapsedRealtime()+10_000;
        for(int redirects=0;;redirects++){
            if(!"https".equals(url.getProtocol())||!"api.met.no".equals(url.getHost())||url.getPort()!=-1&&url.getPort()!=443)throw new IOException("MET redirect target");
            if(android.os.SystemClock.elapsedRealtime()>=deadline)throw new IOException("MET deadline");
            HttpURLConnection connection=(HttpURLConnection)url.openConnection();int remaining=(int)Math.max(1,deadline-android.os.SystemClock.elapsedRealtime());connection.setConnectTimeout(remaining);connection.setReadTimeout(remaining);connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent","Chasovoy/"+BuildConfig.VERSION_NAME+" (TECNO Watch GT companion)");connection.setRequestProperty("Accept-Encoding","gzip");
            if(cached!=null&&!cached.getString("lastModified").isEmpty())connection.setRequestProperty("If-Modified-Since",cached.getString("lastModified"));
            try{
                int code=connection.getResponseCode();if(android.os.SystemClock.elapsedRealtime()>deadline)throw new IOException("MET deadline");
                if(code==301||code==302||code==303||code==307||code==308){if(redirects>=3)throw new IOException("MET redirect limit");String location=connection.getHeaderField("Location");if(location==null)throw new IOException("MET redirect missing");url=new URL(url,location);continue;}
                if(code==429){long retry=Math.max(now+1_800_000L,retryAfter(connection.getHeaderField("Retry-After"),now));if(!context.getSharedPreferences("gt_dial_preferences",Context.MODE_PRIVATE).edit().putLong("weather.metno-retry-after",retry).commit())throw new IOException("MET throttle persistence");throw new IOException("MET rate limited");}
                long expires=httpDate(connection.getHeaderField("Expires"));if(expires<=now)expires=now+1_800_000L;
                if(code==304){if(cached==null)throw new IOException("MET 304 without matching cache");cached.put("expires",expires);String modified=connection.getHeaderField("Last-Modified");if(modified!=null&&!modified.isEmpty())cached.put("lastModified",modified);writeCache(cached);return evaluate(cached,latitude,longitude,city,now);}
                if(code!=200)throw new IOException("MET HTTP "+code);
                connection.setReadTimeout((int)Math.max(1,deadline-android.os.SystemClock.elapsedRealtime()));
                byte[] raw;try(InputStream original=connection.getInputStream();InputStream input="gzip".equalsIgnoreCase(connection.getContentEncoding())?new GZIPInputStream(original):original;ByteArrayOutputStream output=new ByteArrayOutputStream()){
                    byte[] bytes=new byte[4096];for(;;){long left=deadline-android.os.SystemClock.elapsedRealtime();if(left<=0)throw new IOException("MET deadline");connection.setReadTimeout((int)Math.max(1,left));int count=input.read(bytes);if(count==-1)break;if(output.size()+count>LIMIT||android.os.SystemClock.elapsedRealtime()>deadline)throw new IOException("MET response limit/deadline");output.write(bytes,0,count);}raw=output.toByteArray();
                }
                String modified=connection.getHeaderField("Last-Modified");JSONObject next=new JSONObject().put("version",1).put("coordinates",coordinates).put("retrievedAt",System.currentTimeMillis()).put("expires",expires).put("lastModified",modified==null?"":modified).put("rawBase64",Base64.encodeToString(raw,Base64.NO_WRAP)).put("rawSha256",sha(raw));
                WeatherSnapshot snapshot=evaluate(next,latitude,longitude,city,System.currentTimeMillis());writeCache(next);return snapshot;
            }finally{connection.disconnect();}
        }
    }
    private AtomicFile cacheFile(){return new AtomicFile(new File(context.getFilesDir(),"weather-metno-cache.json"));}
    private JSONObject readCache(String coordinates){try{AtomicFile file=cacheFile();if(file.getBaseFile().length()>384*1024)return null;byte[] bytes=readBounded(file.openRead(),384*1024);JSONObject cache=new JSONObject(new String(bytes,StandardCharsets.UTF_8));String checksum=cache.getString("sha256");cache.remove("sha256");if(!sha(cache.toString().getBytes(StandardCharsets.UTF_8)).equals(checksum)||cache.getInt("version")!=1||!coordinates.equals(cache.getString("coordinates")))return null;byte[] raw=Base64.decode(cache.getString("rawBase64"),Base64.NO_WRAP);if(raw.length>LIMIT||!sha(raw).equals(cache.getString("rawSha256"))||cache.getLong("retrievedAt")<=0)return null;cache.getString("lastModified");cache.getLong("expires");return cache;}catch(Exception e){return null;}}
    private void writeCache(JSONObject cache)throws Exception{cache.remove("sha256");cache.put("sha256",sha(cache.toString().getBytes(StandardCharsets.UTF_8)));byte[] bytes=cache.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>384*1024)throw new IOException("MET cache size");AtomicFile file=cacheFile();FileOutputStream stream=null;try{stream=file.startWrite();stream.write(bytes);stream.flush();file.finishWrite(stream);}catch(Exception e){if(stream!=null)file.failWrite(stream);throw e;}if(!Arrays.equals(bytes,readBounded(file.openRead(),384*1024)))throw new IOException("MET cache readback");cache.remove("sha256");}
    private static byte[] readBounded(InputStream stream,int limit)throws Exception{try(InputStream input=stream;ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] bytes=new byte[4096];for(int n;(n=input.read(bytes))!=-1;){if(output.size()+n>limit)throw new IOException("MET cache limit");output.write(bytes,0,n);}return output.toByteArray();}}
    private static long httpDate(String value){try{return ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();}catch(Exception e){return 0;}}
    private static long retryAfter(String value,long now){try{return Math.addExact(now,Math.multiplyExact(Long.parseLong(value),1000L));}catch(Exception e){return httpDate(value);}}
    private static String sha(byte[] bytes)throws Exception{StringBuilder value=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))value.append(String.format(Locale.US,"%02x",b&255));return value.toString();}
    private static final class Point {final long time;final JSONObject data;final double temperature;Point(long time,JSONObject data)throws Exception{this.time=time;this.data=data;temperature=temperature(data.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature"));}}
    private static double temperature(double value)throws Exception{if(!Double.isFinite(value)||value<-90||value>60)throw new IOException("MET temperature");return value;}
    private static WeatherSnapshot evaluate(JSONObject cache,double latitude,double longitude,String city,long now)throws Exception{
        JSONObject document=new JSONObject(new String(Base64.decode(cache.getString("rawBase64"),Base64.NO_WRAP),StandardCharsets.UTF_8));JSONObject properties=document.getJSONObject("properties"),meta=properties.getJSONObject("meta"),units=meta.getJSONObject("units");for(String key:new String[]{"air_temperature","air_temperature_min","air_temperature_max"})if(!"celsius".equals(units.getString(key)))throw new IOException("MET temperature units");
        long issued=Instant.parse(meta.getString("updated_at")).getEpochSecond();if(issued*1000<now-43_200_000L||issued*1000>now+300_000L)throw new IOException("MET model stale");
        JSONArray series=properties.getJSONArray("timeseries");if(series.length()<1||series.length()>1024)throw new IOException("MET timeseries bounds");List<Point> points=new ArrayList<>();Point nearest=null;for(int i=0;i<series.length();i++){JSONObject row=series.getJSONObject(i);Point point=new Point(Instant.parse(row.getString("time")).getEpochSecond(),row.getJSONObject("data"));if(!points.isEmpty()&&point.time<=points.get(points.size()-1).time)throw new IOException("MET timestamps not ordered");points.add(point);if(nearest==null||Math.abs(point.time*1000-now)<Math.abs(nearest.time*1000-now))nearest=point;}
        if(nearest==null||Math.abs(nearest.time*1000-now)>5_400_000L)throw new IOException("MET nearest forecast stale");
        ZoneId zone=ZoneId.systemDefault();LocalDate base=Instant.ofEpochMilli(now).atZone(zone).toLocalDate();double[] today=range(points,now/1000,base.plusDays(1).atStartOfDay(zone).toEpochSecond());String currentSymbol=symbol(points,nearest.time);List<WeatherSnapshot.ForecastDay> days=new ArrayList<>();List<String> symbols=new ArrayList<>();
        for(int i=1;i<=6;i++){LocalDate date=base.plusDays(i);try{double[] bounds=range(points,date.atStartOfDay(zone).toEpochSecond(),date.plusDays(1).atStartOfDay(zone).toEpochSecond());String code=symbol(points,date.atTime(12,0).atZone(zone).toEpochSecond());days.add(new WeatherSnapshot.ForecastDay(date,bounds[0],bounds[1],wmo(code)));symbols.add(code);}catch(Exception unavailable){break;}}
        return new WeatherSnapshot(latitude,longitude,nearest.temperature,wmo(currentSymbol),cache.getLong("retrievedAt"),0,today[0],today[1],"MET Norway; city="+city+"; symbol="+currentSymbol+"; noon="+String.join(",",symbols),days,zone.getId(),base.toString(),issued,nearest.time,now,"conservative overlapping next_6_hours extrema + within-day instant; remaining-day today; noon shortest actual summary; sleet mapped to coarse rain category");
    }
    private static double[] range(List<Point> points,long start,long end)throws Exception{
        long covered=start;double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY;
        for(Point point:points){if(point.time>=start&&point.time<end){low=Math.min(low,point.temperature);high=Math.max(high,point.temperature);}long finish=point.time+21600;JSONObject period=point.data.optJSONObject("next_6_hours"),details=period==null?null:period.optJSONObject("details");if(point.time>=end||finish<=start||details==null||!details.has("air_temperature_min")||!details.has("air_temperature_max"))continue;
            double min=temperature(details.getDouble("air_temperature_min")),max=temperature(details.getDouble("air_temperature_max"));if(min>max)throw new IOException("MET inverted range");if(point.time>covered)throw new IOException("MET day coverage gap");covered=Math.max(covered,Math.min(finish,end));low=Math.min(low,min);high=Math.max(high,max);
        }
        if(covered<end||!Double.isFinite(low)||!Double.isFinite(high))throw new IOException("MET incomplete day coverage");return new double[]{low,high};
    }
    private static String symbol(List<Point> points,long instant)throws Exception{String selected=null;int shortest=Integer.MAX_VALUE;long selectedStart=Long.MIN_VALUE;for(Point point:points)for(int hours:new int[]{1,6,12})if(point.time<=instant&&instant<point.time+hours*3600L){JSONObject period=point.data.optJSONObject("next_"+hours+"_hours"),summary=period==null?null:period.optJSONObject("summary");if(summary!=null&&(hours<shortest||hours==shortest&&point.time>selectedStart)){String code=summary.getString("symbol_code");wmo(code);selected=code;shortest=hours;selectedStart=point.time;}}if(selected==null)throw new IOException("MET summary unavailable");return selected;}
    private static int wmo(String symbol)throws Exception{
        String name=symbol.replaceFirst("_(day|night|polartwilight)$","");
        switch(name){case "clearsky":return 0;case "fair":return 1;case "partlycloudy":return 2;case "cloudy":return 3;case "fog":return 45;
            case "lightrain":case "rain":case "heavyrain":case "lightrainshowers":case "rainshowers":case "heavyrainshowers":case "lightsleet":case "sleet":case "heavysleet":case "lightsleetshowers":case "sleetshowers":case "heavysleetshowers":return 61;
            case "lightsnow":case "snow":case "heavysnow":case "lightsnowshowers":case "snowshowers":case "heavysnowshowers":return 71;
            case "lightrainandthunder":case "rainandthunder":case "heavyrainandthunder":case "lightrainshowersandthunder":case "rainshowersandthunder":case "heavyrainshowersandthunder":case "lightsleetandthunder":case "sleetandthunder":case "heavysleetandthunder":case "lightssleetshowersandthunder":case "sleetshowersandthunder":case "heavysleetshowersandthunder":case "lightsnowandthunder":case "snowandthunder":case "heavysnowandthunder":case "lightssnowshowersandthunder":case "snowshowersandthunder":case "heavysnowshowersandthunder":return 95;
            default:throw new IOException("MET unknown symbol");
        }
    }
}
