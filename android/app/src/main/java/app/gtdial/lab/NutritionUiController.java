package app.gtdial.lab;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/** Native unified nutrition diary. Valid edits are checkpointed; confirmation is a separate action. */
final class NutritionUiController {
    interface Host {void go(String screen,String arg);void redraw();void settings();void sourceDeleted();}
    private final Activity activity;private final ExpansionStore store;private final NutritionDiary diary;private final FoodSearchController search;private final OpenRouterClient client;private final NutritionPhotoController photo;private final Host host;
    private final Handler main=new Handler(Looper.getMainLooper());private UiKit ui;private LocalDate date=LocalDate.now();private List<JSONObject> entries=Collections.emptyList(),results=Collections.emptyList();private JSONObject day,draft;private volatile String draftId="";private String previewEntryId="",searchTarget="",searchTerm="",message="",photoState="DRAFT",photoModel="";private boolean loading,saving,dirty,destroyed;private volatile int editorGeneration;private int searchGeneration;private String pendingPhotoId="";private long pendingPhotoRevision;private Runnable afterSave;private AlertDialog itemDialog;private final Map<String,String> unfinishedInputs=new HashMap<>();
    private final Map<String,Boolean> sections=new HashMap<>();
    private final Runnable checkpoint=()->saveDraft(null);
    NutritionUiController(Activity activity,ExpansionStore store,OpenRouterClient client,Host host){this.activity=activity;this.store=store;this.host=host;this.client=client;diary=new NutritionDiary(store);search=new FoodSearchController(store);photo=new NutritionPhotoController(activity,client);}
    void save(Bundle state){Bundle openSections=new Bundle();for(Map.Entry<String,Boolean> section:sections.entrySet())openSections.putBoolean(section.getKey(),section.getValue());state.putBundle("nutrition.sections",openSections);state.putString("nutrition.day",date.toString());state.putString("nutrition.editor.id",draftId);state.putString("nutrition.photo.draft.id",pendingPhotoId);Bundle fields=new Bundle();for(Map.Entry<String,String> field:unfinishedInputs.entrySet())if(field.getKey().length()<=120&&field.getValue().length()<=1000)fields.putString(field.getKey(),field.getValue());state.putBundle("nutrition.editor.inputs",fields);}
    void restore(Bundle state){if(state==null)return;Bundle openSections=state.getBundle("nutrition.sections");if(openSections!=null)for(String key:openSections.keySet())sections.put(key,openSections.getBoolean(key));try{date=LocalDate.parse(state.getString("nutrition.day",date.toString()));String id=state.getString("nutrition.editor.id","");if(!id.isEmpty())NutritionModels.id(id);draftId=id;String pending=state.getString("nutrition.photo.draft.id","");if(!pending.isEmpty())NutritionModels.id(pending);pendingPhotoId=pending;Bundle fields=state.getBundle("nutrition.editor.inputs");if(fields!=null)for(String key:fields.keySet()){String value=fields.getString(key,"");if(key.matches("[0-9a-f-]{36}:[0-9a-f-]{36}:[A-Za-z]+")&&value.length()<=1000)unfinishedInputs.put(key,value);}}catch(RuntimeException e){date=LocalDate.now();draftId="";pendingPhotoId="";unfinishedInputs.clear();}}
    void pause(){main.removeCallbacks(checkpoint);saveDraft(null);photo.pause();if("SENDING".equals(photoState)||"SAVING_PROPOSALS".equals(photoState))photoState=photo.preview()!=null?"PREVIEW":"DRAFT";}
    void destroy(){pause();destroyed=true;photo.cancel();if(itemDialog!=null)itemDialog.dismiss();}
    void refresh(){if(loading||destroyed)return;loading=true;LocalDate selected=date;store.submit(()->NutritionModels.object("entries",new JSONArray(diary.entries(selected)),"day",diary.day(selected)),(data,error)->{loading=false;if(destroyed)return;if(!date.equals(selected)){refresh();return;}if(data!=null){entries=new ArrayList<>();JSONArray rows=data.optJSONArray("entries");for(int i=0;i<rows.length();i++)entries.add(rows.optJSONObject(i));day=data.optJSONObject("day");}else message="Не удалось открыть дневник.";host.redraw();});}
    void build(LinearLayout page,UiKit kit,String screen,String arg){ui=kit;if(!message.isEmpty())note(page,message);switch(screen){case "nutrition":diary(page);break;case "nutrition_editor":editor(page,arg);break;case "food_search":foodSearch(page,arg);break;default:diary(page);}}
    private void diary(LinearLayout page){
        title(page,"Питание");dateButtons(page);
        if(day==null){note(page,"Загрузка…");refresh();return;}
        JSONObject total=NutritionSummary.day(entries,day);LinearLayout summary=card(page);
        summary.addView(ui.iconHeading("Съедено за день",UiIcons.Kind.FOOD),ui.fillWrap(4,8));
        TextView calories=ui.label(total.isNull("kcalMilli")?"Нет подтверждённых данных":NutritionModels.decimal(total.optLong("kcalMilli"))+" ккал",total.isNull("kcalMilli")?20:34,ui.text);calories.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);summary.addView(calories,ui.fillWrap(4,8));
        button(summary,"Цель: "+(day.isNull("goalKcalMilli")?"не задана":NutritionModels.decimal(day.optLong("goalKcalMilli"))+" ккал"),this::goal);
        if(!total.isNull("kcalMilli")&&!day.isNull("goalKcalMilli")&&day.optLong("goalKcalMilli")>0){android.widget.ProgressBar progress=new android.widget.ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);ui.progress(progress);progress.setMax(1000);progress.setProgress((int)Math.min(1000,Math.round(1000.0*total.optLong("kcalMilli")/day.optLong("goalKcalMilli"))));summary.addView(progress,ui.fillWrap(8,8));}
        note(summary,"Только подтверждённая еда. Калории часов в этот итог не входят.");
        LinearLayout macros=ui.disclosure(summary,"Белки, жиры и углеводы","day.macros",sections);
        note(macros,"Б: "+nutrient(total,"proteinMg")+" · Ж: "+nutrient(total,"fatMg")+" · У: "+nutrient(total,"carbohydrateMg"));
        note(macros,"Полные БЖУ: "+total.optInt("macrosKnownItems")+" из "+total.optInt("confirmedItemCount")+" позиций");
        if(!total.isNull("differenceToUserGoalKcalMilli"))note(summary,"Разница с целью: "+NutritionModels.decimal(total.optLong("differenceToUserGoalKcalMilli"))+" ккал");
        LinearLayout quick=new LinearLayout(activity);quick.addView(ui.iconAction("Вручную",UiIcons.Kind.PLUS,true,()->newMeal(0)),ui.weighted(1));LinearLayout.LayoutParams photoAction=ui.weighted(1);photoAction.setMargins(ui.dp(8),0,0,0);quick.addView(ui.iconAction("Фото",UiIcons.Kind.CAMERA,false,()->newMeal(2)),photoAction);page.addView(quick,ui.fillWrap(12,12));
        for(String meal:new String[]{"BREAKFAST","LUNCH","DINNER","SNACK","OTHER"}){
            boolean heading=false;for(JSONObject entry:entries){if(!meal.equals(entry.optString("meal"))||"REMOVED".equals(entry.optString("state")))continue;
                if(!heading){title(page,mealName(meal));heading=true;}
                String status="CONFIRMED".equals(entry.optString("state"))?"Подтверждено":"Черновик";
                JSONArray items=entry.optJSONArray("items");List<String> names=new ArrayList<>();long kcal=0;boolean known=true,approximate=false;
                for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);names.add(item.optString("name"));known&=!item.isNull("kcalMilli");if(!item.isNull("kcalMilli"))kcal+=item.optLong("kcalMilli");approximate|="PHOTO_ESTIMATE".equals(item.optJSONObject("provenance").optString("kind"));}
                String time=java.time.Instant.ofEpochMilli(entry.optLong("consumedAt")).atZone(ZoneId.of(entry.optString("zoneId"))).toLocalTime().withSecond(0).withNano(0).toString();
                boolean confirmed="CONFIRMED".equals(entry.optString("state"));
                LinearLayout mealRow=ui.compactRow(android.text.TextUtils.join(", ",names),time+" · "+status+" · "+(known?(approximate?"≈ ":"")+NutritionModels.decimal(kcal)+" ккал":"калории неизвестны")+(approximate?" · по фото":"")+(confirmed?"":"\nВ итог дня ещё не включён"),()->openEntry(entry));if(!confirmed)ui.tintedCard(mealRow);page.addView(mealRow,ui.fillWrap(0,8));
            }
        }
        if(entries.isEmpty())note(page,"Записей за этот день пока нет. Пустой дневник не означает отсутствие еды.");
        LinearLayout extra=ui.disclosure(page,"Дневник и свои продукты","day.extra",sections);button(extra,"Свои и недавние продукты",()->host.go("food_search",""));
        CheckBox complete=check(extra,"День заполнен полностью",day.optBoolean("completeDayDeclared"));complete.setOnCheckedChangeListener((view,checked)->{JSONObject next=NutritionModels.copy(day);NutritionModels.put(next,"completeDayDeclared",checked);work(()->diary.saveDay(next),value->{day=value;refresh();});});
        button(extra,"Экспорт дневника JSON",()->document(Intent.ACTION_CREATE_DOCUMENT,ExpansionUiController.NUTRITION_EXPORT,"nutrition.json"));
        button(extra,"Импорт дневника JSON",()->document(Intent.ACTION_OPEN_DOCUMENT,ExpansionUiController.NUTRITION_IMPORT,null));
        for(JSONObject entry:entries)if("REMOVED".equals(entry.optString("state")))extra.addView(ui.compactRow("Удалённая запись · "+mealName(entry.optString("meal")),"Удалить окончательно",()->deleteEntry(entry)),ui.fillWrap(4,4));
    }
    private void openEntry(JSONObject entry){ui.dialogBuilder().setTitle(mealName(entry.optString("meal"))).setItems(new String[]{"Открыть и редактировать","Удалить навсегда"},(dialog,which)->{if(which==0){setDraft(entry);host.go("nutrition_editor",draftId);}else deleteEntry(entry);}).setNegativeButton("Закрыть",null).show();}
    private void newMeal(int method){
        if(method==1){host.go("food_search","");return;}
        work(()->diary.create(date),value->{setDraft(value);host.go("nutrition_editor",draftId);if(method==0)editItem(draft.optJSONArray("items").optJSONObject(0).optString("id"));else selectPhoto();});
    }
    private void dateButtons(LinearLayout page){
        LinearLayout row=new LinearLayout(activity);Button previous=ui.action("‹",false,()->changeDate(date.minusDays(1)));previous.setContentDescription("Предыдущий день");row.addView(previous,new LinearLayout.LayoutParams(ui.dp(48),-2));
        row.addView(ui.action(date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy",java.util.Locale.getDefault())),false,()->pickDate(date,this::changeDate)),ui.weightedGap(1,8,8));
        Button next=ui.action("›",false,()->changeDate(date.plusDays(1)));next.setContentDescription("Следующий день");next.setEnabled(date.isBefore(LocalDate.now()));row.addView(next,new LinearLayout.LayoutParams(ui.dp(48),-2));page.addView(row,ui.fillWrap(4,8));
    }
    private void changeDate(LocalDate next){date=next;day=null;refresh();host.redraw();}
    private void goal(){LinearLayout form=column();EditText field=input(form,"Ккал, пусто — без цели",day.isNull("goalKcalMilli")?"":NutritionModels.decimal(day.optLong("goalKcalMilli")),true);AlertDialog dialog=ui.dialogBuilder().setTitle("Ваша цель на выбранный день").setView(ui.scrollForm(form)).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).create();dialog.setOnShowListener(v->{ui.dialog(dialog);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{try{Long value=NutritionModels.parseDecimal(field.getText().toString(),NutritionModels.MAX_KCAL,true);JSONObject next=NutritionModels.copy(day);NutritionModels.put(next,"goalKcalMilli",value);NutritionModels.validateDay(next);work(()->diary.saveDay(next),saved->{day=saved;dialog.dismiss();refresh();});}catch(RuntimeException e){field.setError("Введите положительные калории, не более 20 000");}});});dialog.show();}
    private void setDraft(JSONObject value){afterSave=null;main.removeCallbacks(checkpoint);if(!draftId.equals(value.optString("id"))){photo.cancel();previewEntryId="";photoState="DRAFT";}draft=NutritionModels.copy(value);draftId=value.optString("id");dirty=false;editorGeneration++;}
    void leave(){main.removeCallbacks(checkpoint);saveDraft(null);photo.pause();if("SENDING".equals(photoState)||"SAVING_PROPOSALS".equals(photoState))photoState=photo.preview()!=null?"PREVIEW":"DRAFT";}
    private void editor(LinearLayout page,String id){
        title(page,"Приём пищи");
        if(!id.equals(draftId)||draft==null){if(!loading){loading=true;int expected=++editorGeneration;draftId=id;store.submit(()->diary.find(id),(value,error)->{loading=false;if(destroyed||expected!=editorGeneration)return;if(value==null){message="Запись не найдена.";host.go("nutrition","");return;}setDraft(value);host.redraw();});}note(page,"Загрузка…");return;}
        if("REMOVED".equals(draft.optString("state"))){note(page,"Эта старая запись помечена удалённой.");button(page,"Удалить навсегда",()->deleteEntry(draft));return;}
        LocalTime consumed=java.time.Instant.ofEpochMilli(draft.optLong("consumedAt")).atZone(ZoneId.of(draft.optString("zoneId"))).toLocalTime().withSecond(0).withNano(0);
        LinearLayout metadata=new LinearLayout(activity);
        metadata.addView(ui.action(LocalDate.parse(draft.optString("localDay")).format(java.time.format.DateTimeFormatter.ofPattern("d MMM",java.util.Locale.getDefault())),false,()->pickDate(LocalDate.parse(draft.optString("localDay")),next->{ZoneId zone=ZoneId.of(draft.optString("zoneId"));LocalTime time=java.time.Instant.ofEpochMilli(draft.optLong("consumedAt")).atZone(zone).toLocalTime();NutritionModels.put(draft,"localDay",next.toString());NutritionModels.put(draft,"consumedAt",next.atTime(time).atZone(zone).toInstant().toEpochMilli());changed();host.redraw();})),ui.weightedGap(1,0,4));
        metadata.addView(ui.action(consumed.toString(),false,()->{android.app.TimePickerDialog pickerDialog=new android.app.TimePickerDialog(ui.dialogContext(),(picker,h,m)->{NutritionModels.put(draft,"consumedAt",LocalDate.parse(draft.optString("localDay")).atTime(h,m).atZone(ZoneId.of(draft.optString("zoneId"))).toInstant().toEpochMilli());changed();host.redraw();},consumed.getHour(),consumed.getMinute(),true);pickerDialog.show();ui.dialog(pickerDialog);}),ui.weightedGap(1,4,0));
        page.addView(metadata,ui.fillWrap(4,8));page.addView(ui.action(mealName(draft.optString("meal")),false,()->ui.dialogBuilder().setTitle("Приём пищи").setItems(new String[]{"Завтрак","Обед","Ужин","Перекус","Другое"},(dialog,which)->{NutritionModels.put(draft,"meal",new String[]{"BREAKFAST","LUNCH","DINNER","SNACK","OTHER"}[which]);changed();host.redraw();}).show()),ui.fillWrap(0,8));
        JSONArray items=draft.optJSONArray("items");
        for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);String itemId=item.optString("id");page.addView(ui.compactRow(item.optString("name"),itemDescription(item),()->editItem(itemId)),ui.fillWrap(0,8));}
        if(items.length()<32)button(page,"Добавить продукт",()->ui.dialogBuilder().setTitle("Добавить продукт").setItems(new String[]{"Ввести вручную","Свои и недавние","Фото еды"},(dialog,which)->{if(which==0){JSONObject item=NutritionModels.item();draft.optJSONArray("items").put(item);changed();host.redraw();editItem(item.optString("id"));}else if(which==1)saveDraft(()->host.go("food_search",draftId));else selectPhoto();}).setNegativeButton("Отмена",null).show());
        photoPanel(page);
        if(!"PREPARING".equals(photoState)&&!"PREVIEW".equals(photoState)&&!"SENDING".equals(photoState)&&!"SAVING_PROPOSALS".equals(photoState)){if(!"CONFIRMED".equals(draft.optString("state")))page.addView(ui.iconAction("Подтвердить приём пищи",UiIcons.Kind.CHECK,true,this::confirmMeal),ui.fillWrap(8,8));else note(page,"Приём пищи подтверждён. Изменения сохранят его как черновик для повторной проверки.");}
        if(saving)note(page,"Сохраняю черновик…");
        LinearLayout extra=ui.disclosure(page,"Заметка, источники и действия","editor.extra",sections);EditText note=input(extra,"Заметка (остаётся на телефоне)",draft.optString("note"),false);note.setTag("draft.nutrition."+draftId+".note");watch(note,()->{String value=note.getText().toString();if(value.length()<=1000){NutritionModels.put(draft,"note",value);changed();}});
        for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);String itemId=item.optString("id");LinearLayout details=card(extra);title(details,item.optString("name"));note(details,"Б: "+nutrient(item,"proteinMg")+" · Ж: "+nutrient(item,"fatMg")+" · У: "+nutrient(item,"carbohydrateMg"));JSONObject origin=item.optJSONObject("provenance");
            if("PHOTO_ESTIMATE".equals(origin.optString("kind"))){note(details,"Приблизительно по фото · "+origin.optString("model"));JSONObject estimate=item.optJSONObject("originalEstimate");if(estimate!=null)note(details,"Исходная оценка: "+(estimate.isNull("kcalMilli")?"калории неизвестны":NutritionModels.decimal(estimate.optLong("kcalMilli"))+" ккал")+(estimate.isNull("quantityMilli")?"":" · "+NutritionModels.decimal(estimate.optLong("quantityMilli"))+" г"));JSONArray assumptions=origin.optJSONArray("assumptions");if(assumptions!=null)for(int a=0;a<assumptions.length();a++)note(details,"Предположение: "+assumptions.optString(a));note(details,"Исправлено вами полей: "+item.optJSONArray("correctedFields").length());}
            button(details,"Сохранить как свой продукт",()->saveDraft(()->{JSONObject latest=NutritionModels.copy(findItem(itemId));work(()->diary.saveFood(latest),food->{message="Продукт сохранён в вашем справочнике.";host.redraw();});}));
            if(items.length()>1)button(details,"Убрать позицию",()->{JSONArray next=new JSONArray();for(int j=0;j<draft.optJSONArray("items").length();j++){JSONObject row=draft.optJSONArray("items").optJSONObject(j);if(!itemId.equals(row.optString("id")))next.put(row);}NutritionModels.put(draft,"items",next);changed();host.redraw();});
        }
        button(extra,"Выбрать фото еды",this::selectPhoto);button(extra,"Сохранить черновик сейчас",()->saveDraft(()->{message="Черновик сохранён.";host.redraw();}));
    }
    private String itemDescription(JSONObject item){String quantity=item.isNull("quantityMilli")?"Количество не задано":NutritionModels.decimal(item.optLong("quantityMilli"))+" "+unitName(item.optString("unit"));return quantity+" · "+(item.isNull("kcalMilli")?"калории неизвестны":NutritionModels.decimal(item.optLong("kcalMilli"))+" ккал")+("PHOTO_ESTIMATE".equals(item.optJSONObject("provenance").optString("kind"))?" · ≈ по фото":"");}
    private static String unitName(String unit){return "g".equals(unit)?"г":"ml".equals(unit)?"мл":"piece".equals(unit)?"шт.":"порц.";}
    private void selectPhoto(){changed();saveDraft(()->{pendingPhotoId=draftId;pendingPhotoRevision=draft.optLong("revision");Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("image/*");intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/jpeg","image/png","image/webp"});activity.startActivityForResult(intent,ExpansionUiController.NUTRITION_PHOTO);});}
    private void confirmMeal(){saveDraft(()->{JSONObject candidate=NutritionModels.copy(draft);NutritionModels.put(candidate,"state","CONFIRMED");try{NutritionModels.validateEntry(candidate);}catch(RuntimeException e){message="Укажите известные калории каждой позиции перед подтверждением.";host.redraw();return;}String exactId=draftId;long exactRevision=draft.optLong("revision");work(()->diary.state(exactId,exactRevision,"CONFIRM"),value->{setDraft(value);photo.cancel();photoState="DRAFT";date=LocalDate.parse(value.optString("localDay"));day=null;host.go("nutrition","");refresh();});});}
    private JSONObject findItem(String id){if(draft==null)throw new IllegalStateException("ENTRY_MISSING");JSONArray items=draft.optJSONArray("items");for(int i=0;i<items.length();i++)if(id.equals(items.optJSONObject(i).optString("id")))return items.optJSONObject(i);throw new IllegalStateException("ITEM_MISSING");}
    private static final String[] ITEM_KEYS={"name","quantityMilli","basisQuantityMilli","basisKcalMilli","basisProteinMg","basisFatMg","basisCarbohydrateMg","kcalMilli","proteinMg","fatMg","carbohydrateMg"};
    private static final String[] ITEM_LABELS={"Название продукта","Количество выбранной единицы","Размер базы в выбранной единице","Ккал на размер базы","Белки на базу, г","Жиры на базу, г","Углеводы на базу, г","Калории всей порции, ккал","Белки всей порции, г","Жиры всей порции, г","Углеводы всей порции, г"};
    private static boolean activeField(String key,boolean basis){return "name".equals(key)||"quantityMilli".equals(key)||(basis?key.startsWith("basis"):!key.startsWith("basis"));}
    private Map<String,EditText> portionFields(LinearLayout form,JSONObject original,Spinner calculation,String itemId){
        Map<String,EditText> fields=new HashMap<>();Map<String,LinearLayout> groups=new HashMap<>();
        LinearLayout basics=new LinearLayout(activity);basics.setOrientation(LinearLayout.VERTICAL);form.addView(basics);
        LinearLayout calories=new LinearLayout(activity);calories.setOrientation(LinearLayout.VERTICAL);form.addView(calories);
        LinearLayout macros=ui.disclosure(form,"БЖУ · необязательно");
        for(int i=0;i<ITEM_KEYS.length;i++){
            String key=ITEM_KEYS[i];LinearLayout parent=i<2?basics:key.contains("Protein")||key.contains("Fat")||key.contains("Carbohydrate")||key.equals("proteinMg")||key.equals("fatMg")||key.equals("carbohydrateMg")?macros:calories;
            LinearLayout group=new LinearLayout(activity);group.setOrientation(LinearLayout.VERTICAL);parent.addView(group);groups.put(key,group);
            String value="name".equals(key)?original.optString(key):original.isNull(key)?"":NutritionModels.decimal(original.optLong(key));
            if(!itemId.isEmpty())value=unfinishedInputs.getOrDefault(draftId+":"+itemId+":"+key,value);
            fields.put(key,input(group,ITEM_LABELS[i],value,!"name".equals(key)));
        }
        Runnable visibility=()->{boolean basis=calculation.getSelectedItemPosition()==1;for(String key:ITEM_KEYS)groups.get(key).setVisibility(activeField(key,basis)?android.view.View.VISIBLE:android.view.View.GONE);};
        calculation.setTag(visibility);visibility.run();note(form,"Например, база: 100 г, 100 мл или 1 порция. Количество и база используют одну выбранную единицу. Перевода мл в граммы нет.");return fields;
    }
    private void editItem(String id){
        JSONObject original=NutritionModels.copy(findItem(id));LinearLayout form=column();ScrollView scroll=new ScrollView(activity);scroll.addView(form);
        note(form,"Способ расчёта калорий");Spinner calculation=spinner(form,new String[]{"Калории всей порции","На 100 г / другую базу"},"BASIS".equals(original.optString("calculation"))?1:0);
        note(form,"Единица количества и базы");Spinner unit=spinner(form,new String[]{"г","мл","порция","шт."},java.util.Arrays.asList("g","ml","portion","piece").indexOf(original.optString("unit")));
        Map<String,EditText> fields=portionFields(form,original,calculation,id);
        LinearLayout provenance=ui.disclosure(form,"Источник значений");CheckBox label=check(provenance,"Значения с этикетки","LABEL".equals(original.optJSONObject("provenance").optString("kind")));
        TextView feedback=ui.label("Введите калории порции или значения на базу. Неизвестные БЖУ оставьте пустыми.",14,ui.muted);form.addView(feedback,ui.fillWrap(8,8));final boolean[] ready={false},valid={true};
        Runnable update=()->{
            ((Runnable)calculation.getTag()).run();if(!ready[0])return;valid[0]=false;
            try{
                JSONObject edited=NutritionModels.copy(original);boolean basis=calculation.getSelectedItemPosition()==1;
                NutritionModels.put(edited,"calculation",basis?"BASIS":"DIRECT");NutritionModels.put(edited,"unit",new String[]{"g","ml","portion","piece"}[unit.getSelectedItemPosition()]);
                for(String key:ITEM_KEYS){String value=fields.get(key).getText().toString();unfinishedInputs.put(draftId+":"+id+":"+key,value);if(!activeField(key,basis))continue;
                    if("name".equals(key))NutritionModels.put(edited,key,value.trim());else NutritionModels.put(edited,key,parseVisibleNumber(fields.get(key),key));
                }
                if(!"PHOTO_ESTIMATE".equals(edited.optJSONObject("provenance").optString("kind"))&&!"CATALOG".equals(edited.optJSONObject("provenance").optString("kind")))NutritionModels.put(edited,"provenance",NutritionModels.object("kind",label.isChecked()?"LABEL":"MANUAL_ESTIMATE"));
                NutritionModels.recalculate(edited);java.util.Set<String> corrected=new java.util.LinkedHashSet<>();JSONArray previous=original.optJSONArray("correctedFields");for(int j=0;j<previous.length();j++)corrected.add(previous.optString(j));
                for(String key:ITEM_KEYS)if(!NutritionModels.canonical(original.opt(key)).equals(NutritionModels.canonical(edited.opt(key))))corrected.add(key);
                for(String key:new String[]{"calculation","unit"})if(!original.optString(key).equals(edited.optString(key)))corrected.add(key);NutritionModels.put(edited,"correctedFields",new JSONArray(corrected));NutritionModels.validateItem(edited);
                JSONArray items=draft.optJSONArray("items");for(int j=0;j<items.length();j++)if(id.equals(items.optJSONObject(j).optString("id")))try{items.put(j,edited);}catch(org.json.JSONException e){throw new IllegalStateException("ITEM_UPDATE");}
                valid[0]=true;changed();feedback.setText(edited.isNull("kcalMilli")?"Итог: калории неизвестны. Подтверждение потребует калории каждой позиции.":"Итог порции: "+NutritionModels.decimal(edited.optLong("kcalMilli"))+" ккал · черновик сохраняется");
            }catch(RuntimeException e){feedback.setText("Проверьте видимые поля: количество до 10 000, калории до 20 000, до 3 знаков после запятой. В расчёте на базу количество и калории обязательны.");}
        };
        for(EditText field:fields.values())watch(field,update);calculation.setOnItemSelectedListener(selected(update));unit.setOnItemSelectedListener(selected(update));label.setOnCheckedChangeListener((v,c)->update.run());ready[0]=true;
        itemDialog=ui.dialogBuilder().setTitle("Продукт и порция").setView(scroll).setPositiveButton("Готово",null).setNegativeButton("Закрыть",(d,w)->{saveDraft(null);host.redraw();}).create();AlertDialog exactDialog=itemDialog;exactDialog.setOnShowListener(v->{ui.dialog(exactDialog);exactDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{update.run();if(!valid[0])return;saveDraft(null);exactDialog.dismiss();host.redraw();});});exactDialog.show();
    }
    private static android.widget.AdapterView.OnItemSelectedListener selected(Runnable update){return new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> p,android.view.View v,int position,long id){update.run();}public void onNothingSelected(android.widget.AdapterView<?> p){}};}
    private void changed(){NutritionModels.put(draft,"state","DRAFT");NutritionModels.put(draft,"removedFrom",null);dirty=true;main.removeCallbacks(checkpoint);main.postDelayed(checkpoint,500);}
    private void saveDraft(Runnable done){main.removeCallbacks(checkpoint);if(done!=null){Runnable previous=afterSave;afterSave=previous==null?done:()->{previous.run();done.run();};}if(saving)return;if(draft==null||!dirty){Runnable next=afterSave;afterSave=null;if(next!=null)next.run();return;}JSONObject captured=NutritionModels.copy(draft);try{NutritionModels.validateEntry(captured);}catch(RuntimeException e){message="Черновик содержит недопустимое значение. Исправьте поля перед сохранением.";afterSave=null;return;}saving=true;dirty=false;int expected=editorGeneration;store.submit(()->diary.save(captured),(saved,error)->{saving=false;if(destroyed)return;if(expected!=editorGeneration){if(dirty||afterSave!=null)saveDraft(null);return;}if(saved==null){dirty=true;afterSave=null;message="Не удалось сохранить черновик: запись изменилась или данные недопустимы.";host.redraw();return;}NutritionModels.put(draft,"revision",saved.optLong("revision"));if(dirty){saveDraft(null);return;}Runnable next=afterSave;afterSave=null;if(next!=null)next.run();});}
    private void deleteEntry(JSONObject entry){String id=entry.optString("id");long revision=entry.optLong("revision");ui.dialogBuilder().setTitle("Удалить приём пищи навсегда?").setMessage("Запись будет удалена с телефона без возможности возврата. Связанные разборы ИИ тоже будут удалены.").setNegativeButton("Отмена",null).setPositiveButton("Удалить",(dialog,which)->{photo.cancel();main.removeCallbacks(checkpoint);work(()->{diary.deleteEntry(id,revision);return true;},ignored->{host.sourceDeleted();if(id.equals(draftId)){editorGeneration++;draft=null;draftId="";dirty=false;afterSave=null;photoState="DRAFT";previewEntryId="";unfinishedInputs.clear();}day=null;entries=Collections.emptyList();host.go("nutrition","");refresh();});}).show();}
    private void foodSearch(LinearLayout page,String target){title(page,"Свои и недавние продукты");searchTarget=target;EditText query=input(page,"Название продукта",searchTerm,false);query.setTag("draft.nutrition.search.query");LinearLayout result=column();page.addView(result);Runnable load=()->{String term=query.getText().toString();searchTerm=term;int expected=++searchGeneration;store.submit(()->search.search(term),(rows,error)->{if(destroyed||expected!=searchGeneration)return;result.removeAllViews();if(rows==null){note(result,"Не удалось выполнить поиск.");return;}results=rows;int shown=0;for(JSONObject row:rows){if(shown++>=150){note(result,"Показаны первые 150 совпадений. Уточните поиск.");break;}JSONObject item=row.optJSONObject("item"),food=row.optJSONObject("food");result.addView(ui.compactRow(item.optString("name"),itemDescription(item)+" · "+(food!=null?"свой продукт":"из недавних"),()->addSearchItem(item,target)),ui.fillWrap(4,4));if(food!=null){LinearLayout actions=ui.disclosure(result,"Изменить «"+item.optString("name")+"»");button(actions,"Редактировать свой продукт",()->editFood(food));button(actions,"Удалить из своих продуктов",()->ui.dialogBuilder().setTitle("Удалить свой продукт навсегда?").setMessage("Продукт исчезнет из справочника без возможности возврата. Копии в приёмах пищи сохранятся.").setNegativeButton("Отмена",null).setPositiveButton("Удалить",(d,w)->work(()->{diary.removeFood(food);return true;},ignored->loadSearch(query,result))).show());}}});};watch(query,load);load.run();button(page,"Новый собственный продукт",()->work(()->diary.create(date),value->{setDraft(value);host.go("nutrition_editor",draftId);message="Введите продукт, затем сохраните его в справочнике через дополнительные действия.";editItem(draft.optJSONArray("items").optJSONObject(0).optString("id"));}));}
    private void loadSearch(EditText query,LinearLayout result){results=Collections.emptyList();host.redraw();}
    private void editFood(JSONObject food){
        JSONObject original=NutritionModels.copy(food.optJSONObject("item"));LinearLayout form=column();ScrollView scroll=new ScrollView(activity);scroll.addView(form);
        note(form,"Способ расчёта калорий");Spinner calculation=spinner(form,new String[]{"Калории всей порции","На 100 г / другую базу"},"BASIS".equals(original.optString("calculation"))?1:0);
        note(form,"Единица количества и базы");Spinner unit=spinner(form,new String[]{"г","мл","порция","шт."},java.util.Arrays.asList("g","ml","portion","piece").indexOf(original.optString("unit")));
        Map<String,EditText> fields=portionFields(form,original,calculation,"");calculation.setOnItemSelectedListener(selected(()->((Runnable)calculation.getTag()).run()));
        AlertDialog dialog=ui.dialogBuilder().setTitle("Изменить свой продукт").setView(scroll).setPositiveButton("Сохранить",null).setNegativeButton("Отмена",null).create();
        dialog.setOnShowListener(v->{ui.dialog(dialog);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{try{
            JSONObject item=NutritionModels.copy(original);boolean basis=calculation.getSelectedItemPosition()==1;NutritionModels.put(item,"unit",new String[]{"g","ml","portion","piece"}[unit.getSelectedItemPosition()]);NutritionModels.put(item,"calculation",basis?"BASIS":"DIRECT");
            for(String key:ITEM_KEYS)if(activeField(key,basis)){String text=fields.get(key).getText().toString();NutritionModels.put(item,key,"name".equals(key)?text.trim():parseVisibleNumber(fields.get(key),key));}
            NutritionModels.recalculate(item);java.util.Set<String> corrected=new java.util.LinkedHashSet<>();JSONArray previous=original.optJSONArray("correctedFields");for(int i=0;i<previous.length();i++)corrected.add(previous.optString(i));for(String key:ITEM_KEYS)if(!NutritionModels.canonical(item.opt(key)).equals(NutritionModels.canonical(original.opt(key))))corrected.add(key);for(String key:new String[]{"unit","calculation"})if(!item.optString(key).equals(original.optString(key)))corrected.add(key);NutritionModels.put(item,"correctedFields",new JSONArray(corrected));NutritionModels.validateItem(item);
            work(()->diary.updateFood(food,item),saved->{dialog.dismiss();message="Продукт обновлён. Состав прежних записей сохранён.";host.redraw();});
        }catch(RuntimeException e){for(EditText field:fields.values())if(field.getError()!=null){field.requestFocus();return;}fields.get("name").setError("Проверьте название и обязательные значения порции");}});});dialog.show();
    }
    private void addSearchItem(JSONObject source,String target){if(target.isEmpty()){work(()->{JSONObject entry=diary.create(date);JSONArray items=new JSONArray().put(cloneSearch(source));NutritionModels.put(entry,"items",items);return diary.save(entry);},value->{setDraft(value);host.go("nutrition_editor",draftId);});return;}work(()->{JSONObject entry=diary.find(target);if(entry==null||"REMOVED".equals(entry.optString("state")))throw new IllegalStateException("ENTRY_MISSING");JSONArray items=entry.optJSONArray("items");if(items.length()>=32)throw new IllegalArgumentException("ITEM_LIMIT");if(items.length()==1&&items.optJSONObject(0).isNull("kcalMilli")&&"Продукт".equals(items.optJSONObject(0).optString("name")))items=new JSONArray();items.put(cloneSearch(source));NutritionModels.put(entry,"items",items);return diary.save(entry);},value->{setDraft(value);host.go("nutrition_editor",draftId);});}
    private static JSONObject cloneSearch(JSONObject source){JSONObject item=NutritionModels.copy(source);NutritionModels.put(item,"id",NutritionModels.uuid());NutritionModels.put(item,"inputMethod","CATALOG");return item;}
    private void photoPanel(LinearLayout page){
        if("DRAFT".equals(photoState))return;
        if("DRAFT_PROPOSALS".equals(photoState)){note(page,"Предложения фото добавлены как черновик. Откройте каждый продукт, проверьте порцию и калории, затем подтвердите приём пищи.");return;}
        title(page,"Фото еды");
        if("PREPARING".equals(photoState)){note(page,"Подготовка изображения…");button(page,"Отменить фото и продолжить вручную",this::cancelPhoto);return;}
        if(photo.preview()==null||!draftId.equals(previewEntryId))return;
        ImageView image=new ImageView(activity);image.setAdjustViewBounds(true);image.setMaxHeight(ui.dp(260));image.setImageURI(android.net.Uri.fromFile(photo.preview()));image.setContentDescription("Фото, подготовленное для отправки в OpenRouter");page.addView(image,ui.fillWrap(4,8));
        note(page,"Оценка еды и порции приблизительная. Фото будет отправлено только после вашего нажатия; ответ потребуется проверить.");
        if("SENDING".equals(photoState))note(page,"Один запрос выполняется…");if("SAVING_PROPOSALS".equals(photoState))note(page,"Сохраняю предложения как черновик…");
        photoModel=client.selected(true);note(page,client.selectionLabel(true));button(page,"Модель фото в настройках OpenRouter",host::settings);
        LinearLayout details=ui.disclosure(page,"Модель и подготовка фото","photo.details",sections);
        note(details,"Фото отправляется без исходных метаданных. Сервису запрещены сохранение и сбор данных; выбранная модель не заменяется автоматически. Итоговая стоимость зависит от запроса.");
        Button send=ui.iconAction("Отправить это фото один раз",UiIcons.Kind.SPARKLES,true,()->saveDraft(()->{
            photoModel=client.selected(true);if(photoModel.isEmpty()){message="Выберите модель фото в настройках OpenRouter.";sections.put("photo.details",true);host.redraw();return;}
            String id=draftId;int expectedEditor=editorGeneration;long revision=draft.optLong("revision");photoState="SENDING";message="";host.redraw();
            photo.send(photoModel,(items,error)->{
                if(!error.isEmpty()){photoState="PREVIEW";message=OpenRouterClient.message(error);host.redraw();return;}
                photoState="SAVING_PROPOSALS";host.redraw();
                work(()->{
                    if(editorGeneration!=expectedEditor||!id.equals(draftId))throw new IllegalStateException("SOURCE_CHANGED");JSONObject current=diary.find(id);
                    if(current==null||current.optLong("revision")!=revision||!"DRAFT".equals(current.optString("state")))throw new IllegalStateException("SOURCE_CHANGED");
                    JSONArray merged=current.optJSONArray("items");if(merged.length()==1&&merged.optJSONObject(0).isNull("kcalMilli")&&"Продукт".equals(merged.optJSONObject(0).optString("name")))merged=new JSONArray();
                    if(merged.length()+items.length()>32)throw new IllegalArgumentException("ITEM_LIMIT");for(int i=0;i<items.length();i++)merged.put(items.optJSONObject(i));NutritionModels.put(current,"items",merged);return diary.save(current);
                },value->{if(editorGeneration!=expectedEditor||!id.equals(draftId)){photoState=photo.preview()!=null?"PREVIEW":"DRAFT";message="Предложения не открыты: выбранная запись изменилась. Проверьте дневник.";host.redraw();return;}setDraft(value);photoState="DRAFT_PROPOSALS";message="Предложения сохранены как черновик. Проверьте каждую позицию и подтвердите вручную.";host.redraw();});
            });
        }));send.setEnabled(!"SENDING".equals(photoState)&&!"SAVING_PROPOSALS".equals(photoState)&&!client.busy());page.addView(send,ui.fillWrap(8,8));button(page,"Отменить фото и продолжить вручную",this::cancelPhoto);
    }
    private void cancelPhoto(){photo.cancel();photoState="DRAFT";pendingPhotoId="";host.redraw();}
    void documentResult(int request,int result,Intent intent){if(result!=Activity.RESULT_OK||intent==null||intent.getData()==null){if(request==ExpansionUiController.NUTRITION_PHOTO)pendingPhotoId="";return;}android.net.Uri uri=intent.getData();if(request==ExpansionUiController.NUTRITION_PHOTO){String id=pendingPhotoId;if(id.isEmpty())return;pendingPhotoId="";photoState="PREPARING";message="";photo.prepare(uri,(file,error)->{previewEntryId=file==null?"":id;photoState=file==null?"DRAFT":"PREVIEW";if(file==null)message="Не удалось подготовить фото. Допускаются JPEG/PNG/WebP до 12 МиБ и 24 Мп. Ручной черновик сохранён.";host.go("nutrition_editor",id);});return;}if(request==ExpansionUiController.NUTRITION_EXPORT)work(()->{byte[] bytes=NutritionArchive.export(store);try(java.io.OutputStream out=activity.getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new java.io.IOException();out.write(bytes);}return true;},value->{message="Дневник экспортирован без фото, URI и ключа.";host.redraw();});else if(request==ExpansionUiController.NUTRITION_IMPORT)work(()->{JSONObject archive;try(java.io.InputStream input=activity.getContentResolver().openInputStream(uri)){if(input==null)throw new java.io.IOException();archive=NutritionArchive.read(input);}return NutritionArchive.importArchive(store,archive);},count->{message="Импортировано записей: "+count+". Идентичные записи пропущены.";draft=null;day=null;refresh();});}
    private void document(String action,int request,String name){Intent intent=new Intent(action);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("application/json");if(name!=null)intent.putExtra(Intent.EXTRA_TITLE,name);activity.startActivityForResult(intent,request);}
    private <T> void work(java.util.concurrent.Callable<T> action,java.util.function.Consumer<T> done){store.submit(action,(value,error)->{if(destroyed)return;if(!error.isEmpty()){if("SENDING".equals(photoState)||"SAVING_PROPOSALS".equals(photoState)){photoState=photo.preview()!=null?"PREVIEW":"DRAFT";}message=error.contains("SOURCE_CHANGED")?"Фото не добавлено: черновик изменился. Проверьте запись; новая отправка только вручную.":error.contains("CONFLICT")?"Импорт отменён целиком: одинаковый ID содержит разные данные.":"Действие не завершено: проверьте данные и повторите вручную.";host.redraw();return;}done.accept(value);});}
    private void pickDate(LocalDate initial,java.util.function.Consumer<LocalDate> done){DatePickerDialog picker=new DatePickerDialog(ui.dialogContext(),(d,y,m,day)->done.accept(LocalDate.of(y,m+1,day)),initial.getYear(),initial.getMonthValue()-1,initial.getDayOfMonth());picker.getDatePicker().setMaxDate(System.currentTimeMillis());picker.show();ui.dialog(picker);}
    private Long parseVisibleNumber(EditText field,String key){
        long limit=key.contains("Quantity")||key.equals("quantityMilli")?NutritionModels.MAX_QUANTITY:NutritionModels.MAX_KCAL;
        field.setError(null);try{return NutritionModels.parseDecimal(field.getText().toString(),limit,true);}catch(RuntimeException error){field.setError(UiText.choose("en".equals(new AppPreferences(activity).language()),"Введите число: до 3 знаков после запятой"));throw error;}
    }
    private static String mealName(String meal){switch(meal){case "BREAKFAST":return "Завтрак";case "LUNCH":return "Обед";case "DINNER":return "Ужин";case "SNACK":return "Перекус";default:return "Другое";}}
    private static String nutrient(JSONObject o,String key){return o.isNull(key)?"неизвестно":NutritionModels.decimal(o.optLong(key))+" г";}
    private LinearLayout column(){LinearLayout v=new LinearLayout(activity);v.setOrientation(LinearLayout.VERTICAL);v.setPadding(ui.dp(10),ui.dp(8),ui.dp(10),ui.dp(8));return v;}
    private LinearLayout card(LinearLayout p){LinearLayout v=column();ui.card(v);p.addView(v,ui.fillWrap(8,8));return v;}
    private void title(LinearLayout p,String text){p.addView(ui.heading(text),ui.fillWrap(10,8));}
    private void note(LinearLayout p,String text){p.addView(ui.label(text,14,ui.muted),ui.fillWrap(4,6));}
    private Button makeButton(String text,Runnable action){return ui.action(text,text.startsWith("Отправить это фото"),action);}
    private void button(LinearLayout p,String text,Runnable action){p.addView(makeButton(text,action),ui.fillWrap(4,4));}
    private EditText input(LinearLayout p,String label,String text,boolean decimal){TextView name=ui.label(label,14,ui.text);p.addView(name,ui.fillWrap(4,4));EditText field=new EditText(activity);field.setId(android.view.View.generateViewId());name.setLabelFor(field.getId());field.setMinHeight(ui.dp(48));field.setText(text);field.setTextColor(ui.text);field.setHintTextColor(ui.muted);field.setInputType(decimal?InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);ui.input(field);p.addView(field,ui.fillWrap(2,6));return field;}
    private CheckBox check(LinearLayout p,String text,boolean checked){CheckBox c=new CheckBox(activity);c.setText(text);c.setTextColor(ui.text);ui.check(c);c.setChecked(checked);p.addView(c,ui.fillWrap(4,6));return c;}
    private Spinner spinner(LinearLayout p,String[] values,int selected){Spinner s=new Spinner(ui.dialogContext());ArrayAdapter<String> adapter=new ArrayAdapter<>(ui.dialogContext(),android.R.layout.simple_spinner_dropdown_item,values);s.setAdapter(adapter);s.setSelection(Math.max(0,selected));s.setMinimumHeight(ui.dp(48));p.addView(s,ui.fillWrap(4,6));return s;}
    private static void watch(EditText field,Runnable changed){field.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){changed.run();}public void afterTextChanged(Editable text){}});}
}



