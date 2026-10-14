package app.gtdial.lab;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Validate the entire bounded archive before opening a transaction; conflicts roll back everything. */
final class NutritionArchive {
    static final int LIMIT=8*1024*1024;
    static byte[] export(ExpansionStore store){JSONObject root=NutritionModels.object("schemaVersion",1,"foods",new JSONArray(store.nutritionRows("nutrition_foods",null)),"entries",new JSONArray(store.nutritionRows("nutrition_entries",null)),"days",new JSONArray(store.nutritionRows("nutrition_days",null)));validate(root);byte[] bytes=root.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>LIMIT)throw new IllegalArgumentException("ARCHIVE_SIZE");return bytes;}
    static JSONObject read(InputStream input)throws java.io.IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){if(out.size()+n>LIMIT)throw new IllegalArgumentException("ARCHIVE_SIZE");out.write(buffer,0,n);}JSONObject root=NutritionModels.parseObject(out.toString(StandardCharsets.UTF_8.name()));validate(root);return root;}
    static void validate(JSONObject root){NutritionModels.keys(root,"schemaVersion","foods","entries","days");NutritionModels.integer(root,"schemaVersion",1,1);int total=0;for(String group:new String[]{"foods","entries","days"}){JSONArray values=NutritionModels.array(root,group,0,50000);total+=values.length();Set<String> ids=new HashSet<>();for(int i=0;i<values.length();i++){JSONObject row=NutritionModels.child(values,i);if("foods".equals(group))NutritionModels.validateFood(row);else if("entries".equals(group))NutritionModels.validateEntry(row);else NutritionModels.validateDay(row);if(!ids.add(row.optString("days".equals(group)?"localDay":"id")))throw new IllegalArgumentException("ARCHIVE_DUPLICATE");}}if(total>50000)throw new IllegalArgumentException("ARCHIVE_RECORD_LIMIT");}
    static int importArchive(ExpansionStore store,JSONObject root){validate(root);final int[] inserted={0};store.transaction(()->{for(String group:new String[]{"foods","entries","days"}){String table="nutrition_"+group;JSONArray rows=root.optJSONArray(group);for(int i=0;i<rows.length();i++){JSONObject value=rows.optJSONObject(i);String key=value.optString("days".equals(group)?"localDay":"id");JSONObject previous=store.nutritionFind(table,key);if(previous!=null){if(!NutritionModels.canonical(previous).equals(NutritionModels.canonical(value)))throw new IllegalStateException("ARCHIVE_CONFLICT");continue;}store.nutritionWrite(table,value,-1);inserted[0]++;}}});return inserted[0];}
    private NutritionArchive(){}
}

