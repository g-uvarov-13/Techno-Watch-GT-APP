package app.gtdial.lab;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.res.ColorStateList;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import java.util.function.Consumer;

/** A manual schedule saved in the app. Persistence never implies delivery to the watch. */
final class AlarmEditorUi {
    static void edit(Activity activity,UiKit ui,JSONObject original,Consumer<JSONObject> save) {
        boolean english="en".equals(new AppPreferences(activity).language());
        JSONObject draft=original==null?ExpansionModels.record(ExpansionModels.MANUAL):ExpansionModels.copy(original);
        int[] selectedTime={draft.optInt("hour",7),draft.optInt("minute",0)};
        LinearLayout form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(ui.dp(16),ui.dp(12),ui.dp(16),ui.dp(12));
        form.addView(ui.label(UiText.choose(english,"Время"),14,ui.text),ui.fillWrap(0,6));
        Button time=new Button(ui.dialogContext());time.setAllCaps(false);time.setText(String.format(Locale.getDefault(),"%02d:%02d",selectedTime[0],selectedTime[1]));time.setTextSize(30);time.setTextColor(ui.text);time.setMinHeight(ui.dp(56));ui.outline(time);
        time.setOnClickListener(v->{TimePickerDialog picker=new TimePickerDialog(ui.dialogContext(),(view,hour,minute)->{selectedTime[0]=hour;selectedTime[1]=minute;time.setText(String.format(Locale.getDefault(),"%02d:%02d",hour,minute));},selectedTime[0],selectedTime[1],true);picker.show();ui.dialog(picker);});form.addView(time,ui.fillWrap(0,12));
        TextView name=ui.label(UiText.choose(english,"Название"),14,ui.text);form.addView(name,ui.fillWrap(0,4));
        EditText label=new EditText(ui.dialogContext());label.setId(View.generateViewId());name.setLabelFor(label.getId());label.setTextColor(ui.text);label.setHintTextColor(ui.muted);label.setHint(UiText.choose(english,"Необязательно"));label.setText(draft.optString("label"));label.setSingleLine(true);label.setMinHeight(ui.dp(48));label.setPadding(ui.dp(10),ui.dp(8),ui.dp(10),ui.dp(8));label.setInputType(InputType.TYPE_CLASS_TEXT);ui.input(label);form.addView(label,ui.fillWrap(0,10));
        Switch enabled=new Switch(ui.dialogContext());enabled.setText(UiText.choose(english,"Включён в приложении"));enabled.setTextColor(ui.text);enabled.setChecked(draft.optBoolean("enabled",true));enabled.setMinHeight(ui.dp(48));ui.toggle(enabled);form.addView(enabled,ui.fillWrap(0,8));
        form.addView(ui.label(UiText.choose(english,"Повторение"),14,ui.text),ui.fillWrap(0,6));
        CheckBox[] days=new CheckBox[7];String[] names={UiText.choose(english,"Пн"),UiText.choose(english,"Вт"),UiText.choose(english,"Ср"),UiText.choose(english,"Чт"),UiText.choose(english,"Пт"),UiText.choose(english,"Сб"),UiText.choose(english,"Вс")},full={UiText.choose(english,"Понедельник"),UiText.choose(english,"Вторник"),UiText.choose(english,"Среда"),UiText.choose(english,"Четверг"),UiText.choose(english,"Пятница"),UiText.choose(english,"Суббота"),UiText.choose(english,"Воскресенье")};JSONArray selected=draft.optJSONArray("weekdays");
        LinearLayout row=null;
        int columns=activity.getResources().getConfiguration().fontScale>1.25f?1:2;
        for(int i=0;i<7;i++){
            if(i%columns==0){row=new LinearLayout(activity);form.addView(row,ui.fillWrap(0,4));}
            days[i]=new CheckBox(ui.dialogContext());days[i].setText(names[i]);days[i].setContentDescription(full[i]);days[i].setTextColor(ui.text);days[i].setTextSize(14);days[i].setButtonTintList(ColorStateList.valueOf(ui.accent));days[i].setMinHeight(ui.dp(48));
            if(selected!=null)for(int j=0;j<selected.length();j++)if(selected.optInt(j)==i+1)days[i].setChecked(true);ui.touchTarget(days[i]);row.addView(days[i],ui.weightedGap(1,0,4));
        }
        if(row!=null&&columns==2){LinearLayout.LayoutParams spacer=ui.weighted(1);spacer.height=0;row.addView(new View(activity),spacer);}
        form.addView(ui.label(UiText.choose(english,"Без выбранных дней — без повтора."),12,ui.muted),ui.fillWrap(4,4));
        ScrollView scroll=new ScrollView(activity);scroll.addView(form);AlertDialog dialog=ui.dialogBuilder().setTitle(original==null?UiText.choose(english,"Новый будильник"):UiText.choose(english,"Изменить будильник")).setView(scroll).setNegativeButton(UiText.choose(english,"Отмена"),null).setPositiveButton(UiText.choose(english,"Сохранить"),null).create();
        dialog.setOnShowListener(v->{ui.dialog(dialog);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            try{
                JSONArray weekday=new JSONArray();for(int i=0;i<7;i++)if(days[i].isChecked())weekday.put(i+1);
                ExpansionModels.put(draft,"hour",selectedTime[0]);ExpansionModels.put(draft,"minute",selectedTime[1]);ExpansionModels.put(draft,"enabled",enabled.isChecked());ExpansionModels.put(draft,"label",ExpansionModels.text(label.getText().toString(),80));ExpansionModels.put(draft,"weekdays",weekday);ExpansionModels.put(draft,"recurrence",weekday.length()>0?"MANUAL_WEEKDAYS":"MANUAL_ONCE");ExpansionModels.put(draft,"state","LOCAL_DRAFT");ExpansionModels.put(draft,"origin","MANUAL");validate(draft);save.accept(draft);dialog.dismiss();
            }catch(RuntimeException e){label.setError(UiText.choose(english,"Название до 80 символов"));}
        });});dialog.show();
    }
    static void validate(JSONObject draft){
        ExpansionModels.requireId(draft.optString("id"));int hour=draft.optInt("hour",-1),minute=draft.optInt("minute",-1);
        if(hour<0||hour>23||minute<0||minute>59||!(draft.opt("enabled") instanceof Boolean))throw new IllegalArgumentException("ALARM_FIELDS");
        ExpansionModels.text(draft.optString("label"),80);JSONArray days=draft.optJSONArray("weekdays");if(days==null||days.length()>7)throw new IllegalArgumentException("ALARM_DAYS");
        java.util.HashSet<Integer> unique=new java.util.HashSet<>();for(int i=0;i<days.length();i++){Object raw=days.opt(i);if(!(raw instanceof Integer)&&!(raw instanceof Long))throw new IllegalArgumentException("ALARM_DAYS");int day=((Number)raw).intValue();if(day<1||day>7||!unique.add(day))throw new IllegalArgumentException("ALARM_DAYS");}
    }
    static String time(JSONObject draft){return String.format(Locale.getDefault(),"%02d:%02d",draft.optInt("hour"),draft.optInt("minute"));}
    static String repeat(JSONObject draft){return repeat(draft,false);}
    static String repeat(JSONObject draft,boolean english){
        JSONArray days=draft.optJSONArray("weekdays");if(days==null||days.length()==0)return UiText.choose(english,"Без повтора");if(days.length()==7)return UiText.choose(english,"Каждый день");
        java.util.Set<Integer> selected=new java.util.HashSet<>();for(int i=0;i<days.length();i++)selected.add(days.optInt(i));
        if(selected.equals(new java.util.HashSet<>(java.util.Arrays.asList(1,2,3,4,5))))return UiText.choose(english,"Будни");
        if(selected.equals(new java.util.HashSet<>(java.util.Arrays.asList(6,7))))return UiText.choose(english,"Выходные");
        String[] names={UiText.choose(english,"Пн"),UiText.choose(english,"Вт"),UiText.choose(english,"Ср"),UiText.choose(english,"Чт"),UiText.choose(english,"Пт"),UiText.choose(english,"Сб"),UiText.choose(english,"Вс")};StringBuilder result=new StringBuilder();for(int i=1;i<=7;i++)if(selected.contains(i)){if(result.length()>0)result.append(", ");result.append(names[i-1]);}return result.toString();
    }
    static String label(JSONObject draft,boolean english){return time(draft)+" · "+repeat(draft,english);}
    static String label(JSONObject draft){return time(draft)+" · "+repeat(draft);}
    private AlarmEditorUi(){ }
}
