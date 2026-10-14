package app.gtdial.lab;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.*;

/** Validated metadata from the authenticated account catalog; never a routing alias. */
final class OpenRouterModelCatalog {
    static final class Model {
        final String id,name,reason; final boolean image,text,structured,free;
        final Map<String,BigDecimal> prices;
        Model(String id,String name,boolean image,boolean text,boolean structured,Map<String,BigDecimal> prices,String reason,boolean free){this.id=id;this.name=name;this.image=image;this.text=text;this.structured=structured;this.prices=Collections.unmodifiableMap(prices);this.reason=reason;this.free=free;}
        String incompatibility(boolean photo){if(!reason.isEmpty())return reason;if(!text)return "Нет текстового ввода и ответа";if(!structured)return "Нет structured_outputs";if(photo&&!image)return "Нет ввода изображения";return "";}
        JSONObject maxPrice(boolean photo){JSONObject value=new JSONObject();for(String key:new String[]{"prompt","completion","request","image"}){BigDecimal price=prices.get(key);if(price==null)price=BigDecimal.ZERO;if("prompt".equals(key)||"completion".equals(key))price=price.multiply(new BigDecimal("1000000"));NutritionModels.put(value,key,price);}return value;}
        String priceLabel(){if(free)return "Бесплатно: известные тарифы равны нулю";if(!reason.isEmpty())return reason;String value="USD / 1 млн токенов: ввод "+display("prompt",true)+", ответ "+display("completion",true)+"; запрос "+display("request",false)+", изображение "+display("image",false);for(String key:new String[]{"input_cache_read","input_cache_write"})if(prices.containsKey(key))value+="; "+("input_cache_read".equals(key)?"чтение кэша":"запись кэша")+" "+display(key,true)+" / 1 млн токенов";return value;}
        private String display(String key,boolean million){BigDecimal p=prices.get(key);return p==null?"не указан":(million?p.multiply(new BigDecimal("1000000")):p).stripTrailingZeros().toPlainString();}
    }
    private List<Model> all=Collections.emptyList(); private long revision=-1;
    synchronized void clear(){all=Collections.emptyList();revision=-1;}
    synchronized void adopt(OpenRouterModelCatalog candidate){all=candidate.all;revision=candidate.revision;}
    synchronized void replace(JSONObject value){replace(value,0);}
    synchronized void replace(JSONObject value,long credentialRevision){JSONArray rows=value.optJSONArray("data");if(rows==null||rows.length()>10000)throw new IllegalArgumentException("MODEL_CATALOG_FORMAT");List<Model> next=new ArrayList<>();Set<String> ids=new HashSet<>();for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row==null)throw new IllegalArgumentException("MODEL_CATALOG_FORMAT");String id=row.optString("id"),name=row.optString("name",id);if(id.isEmpty()||id.length()>200||!id.matches("[A-Za-z0-9._:/-]+")||!ids.add(id)||name.length()>240||name.chars().anyMatch(c->c<32))throw new IllegalArgumentException("MODEL_CATALOG_FORMAT");JSONObject arch=row.optJSONObject("architecture"),pricing=row.optJSONObject("pricing");boolean text=arch!=null&&contains(arch.optJSONArray("input_modalities"),"text")&&contains(arch.optJSONArray("output_modalities"),"text"),image=arch!=null&&contains(arch.optJSONArray("input_modalities"),"image"),structured=contains(row.optJSONArray("supported_parameters"),"structured_outputs");Map<String,BigDecimal> prices=new LinkedHashMap<>();String reason="";boolean free=true;if(pricing==null)reason="Тарифы неизвестны";else{Iterator<String> keys=pricing.keys();int count=0;while(keys.hasNext()){String key=keys.next();if(++count>32)throw new IllegalArgumentException("MODEL_CATALOG_FORMAT");Object raw=pricing.opt(key);try{if(!(raw instanceof String)&&!(raw instanceof Number))throw new IllegalArgumentException();String s=raw.toString();if(s.length()>64)throw new IllegalArgumentException();BigDecimal p=new BigDecimal(s);if(p.signum()<0||p.precision()>32||Math.abs(p.scale())>32)throw new IllegalArgumentException();prices.put(key,p);if(p.signum()!=0){free=false;if(!Arrays.asList("prompt","completion","request","image","input_cache_read","input_cache_write").contains(key))reason="Дополнительный платный тариф не поддерживается";}}catch(RuntimeException e){reason="Тарифы неизвестны или некорректны";free=false;}}if(!prices.containsKey("prompt")||!prices.containsKey("completion")){reason="Основные тарифы неизвестны";free=false;}}if(!prices.containsKey("request")||image&&!prices.containsKey("image"))free=false;if(!reason.isEmpty())free=false;if(id.equals("openrouter/free")||id.equals("openrouter/auto")||id.equals("auto"))reason="Автоматическая маршрутизация недоступна";next.add(new Model(id,name,image,text,structured,prices,reason,free));}next.sort(Comparator.comparing(m->m.name.toLowerCase(Locale.ROOT)));all=Collections.unmodifiableList(next);revision=credentialRevision;}
    synchronized List<Model> all(){return all;}
    synchronized List<Model> models(boolean photo){List<Model> selected=new ArrayList<>();for(Model m:all)if(m.incompatibility(photo).isEmpty())selected.add(m);return Collections.unmodifiableList(selected);}
    synchronized Model require(String id,boolean photo){for(Model m:all)if(m.id.equals(id)&&m.incompatibility(photo).isEmpty())return m;throw new IllegalArgumentException("MODEL_RELOAD_SELECT_REQUIRED");}
    synchronized Model require(String id,boolean photo,long expected){if(revision!=expected)throw new IllegalArgumentException("MODEL_RELOAD_SELECT_REQUIRED");return require(id,photo);}
    private static boolean contains(JSONArray values,String value){if(values==null||values.length()>64)return false;for(int i=0;i<values.length();i++)if(value.equals(values.optString(i)))return true;return false;}
}
