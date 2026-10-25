package app.gtdial.lab;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One-set-at-a-time diary. Pending field edits survive redraws and are acknowledged before actions. */
final class StrengthUiController {
    private final Activity activity;
    private final ExpansionStore store;
    private final StrengthDiary diary;
    private final Runnable refresh;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<String,DraftWriter> writers=new HashMap<>();
    private UiKit ui;
    private TextView feedback,restView,walkingView;
    private JSONObject walkingSnapshot;private long walkingAnchor;
    private JSONObject workout;
    private String focusedEntry="";
    private boolean disposed,actionBusy;
    private boolean exerciseList;
    private JSONObject lastRemoved;
    private String workoutId="";
    boolean back(){if(exerciseList)return false;exerciseList=true;flush(refresh);return true;}

    StrengthUiController(Activity activity,ExpansionStore store,Runnable refresh){this.activity=activity;this.store=store;this.refresh=refresh;diary=new StrengthDiary(activity,store);}
    void destroy(){disposed=true;for(DraftWriter writer:writers.values())writer.send();}
    void pause(){for(DraftWriter writer:writers.values())writer.send();}
    void tick(){if(walkingView!=null&&walkingSnapshot!=null){JSONObject now=ExpansionModels.copy(walkingSnapshot);if("RUNNING".equals(now.optString("state"))||"ACTIVE".equals(now.optString("state")))ExpansionModels.put(now,"durationMillis",now.optLong("durationMillis")+Math.max(0,android.os.SystemClock.elapsedRealtime()-walkingAnchor));walkingView.setText(walkingText(now));}if(restView!=null&&workout!=null){long remaining=diary.restRemainingMillis(workout);restView.setText(remaining>0?tx("Отдых · ")+WorkoutRecorder.duration(remaining):tx("Можно начать следующий подход"));}}
    void flush(Runnable done){
        ArrayList<DraftWriter> pending=new ArrayList<>(writers.values());flushNext(pending,0,done);
    }
    private void flushNext(List<DraftWriter> pending,int index,Runnable done){if(index>=pending.size()){done.run();return;}pending.get(index).flush(()->flushNext(pending,index+1,done));}

    void build(LinearLayout parent,UiKit kit,JSONObject current,List<JSONObject> entries,Map<String,List<JSONObject>> sets){
        ui=kit;workout=current;restView=null;walkingView=null;walkingSnapshot=null;
        if(!workoutId.equals(current.optString("id"))){workoutId=current.optString("id");exerciseList=false;lastRemoved=null;focusedEntry="";writers.clear();}
        feedback=ui.label("",12,ui.muted);feedback.setVisibility(View.GONE);parent.addView(feedback,ui.fillWrap(4,12));
        boolean running="RUNNING".equals(current.optString("status"));
        if(lastRemoved!=null){Button undo=secondary(parent,tx("Упражнение убрано · Вернуть"),()->perform(()->diary.restoreExercise(lastRemoved.optString("id"),lastRemoved.optLong("revision")),restored->{focusedEntry=restored.optString("id");lastRemoved=null;exerciseList=false;}));undo.setEnabled(WorkoutRecorder.isActive(current));}
        if(entries.isEmpty()){
            TextView empty=ui.label(tx("Выберите первое упражнение"),20,ui.text);parent.addView(empty,ui.fillWrap(22,8));
            Button add=primary(parent,tx("Выбрать упражнение"),()->catalog(current.optString("id")));add.setEnabled(running);
            return;
        }
        if(exerciseList){
            parent.addView(ui.heading(tx("Упражнения тренировки")),ui.fillWrap(12,12));
            for(JSONObject item:entries){LinearLayout row=column();ui.card(row);row.addView(ui.heading(entryName(item)));int completed=0;for(JSONObject set:sets.getOrDefault(item.optString("id"),Collections.emptyList()))if("DONE".equals(set.optString("state")))completed++;if("TIMED_WALK".equals(item.optString("entryKind")))note(row,walkingText(item.optJSONObject("timedSummary")));else note(row,tx("Выполнено подходов: ")+completed);
                primary(row,tx("Открыть упражнение"),()->perform(()->diary.focus(current.optString("id"),item.optString("id")),saved->{focusedEntry=item.optString("id");exerciseList=false;}));
                quiet(row,tx("Убрать из тренировки"),()->removeEntry(item));parent.addView(row,ui.fillWrap(0,12));}
            Button add=primary(parent,tx("Добавить упражнение"),()->catalog(current.optString("id")));add.setEnabled(running);return;
        }
        JSONObject entry=null;
        String saved=current.optString("strengthFocusEntryId");
        if(focusedEntry.isEmpty())focusedEntry=saved;
        for(JSONObject candidate:entries)if(candidate.optString("id").equals(focusedEntry))entry=candidate;
        if(entry==null){entry=entries.get(0);focusedEntry=entry.optString("id");}
        final JSONObject chosen=entry;
        LinearLayout picker=new LinearLayout(activity);picker.setGravity(Gravity.CENTER_VERTICAL);
        TextView queue=ui.label(tx("Упражнений: ")+entries.size(),12,ui.muted);picker.addView(queue,new LinearLayout.LayoutParams(0,-2,1));
        Button switcher=button(tx("Все упражнения"),()->flush(()->{exerciseList=true;refresh.run();}));picker.addView(switcher);parent.addView(picker,ui.fillWrap(0,8));
        if("TIMED_WALK".equals(entry.optString("entryKind"))){buildWalking(parent,current,chosen,running);return;}
        JSONObject catalogEntry=StrengthCatalog.find(entry.optString("variantId"));
        String illustration=catalogEntry==null?entry.optString("illustration","other"):catalogEntry.optString("illustration","other");
        ExerciseIllustrationView picture=new ExerciseIllustrationView(activity,ui,catalogEntry==null?illustration:catalogEntry.optString("id"),false);picture.setContentDescription(tx("Схема: ")+entryName(entry));ui.card(picture);parent.addView(picture,new LinearLayout.LayoutParams(-1,ui.dp(210)));
        note(parent,tx("Работающие мышцы выделены красным"));
        TextView title=ui.label(entryName(entry),22,ui.text);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);parent.addView(title,ui.fillWrap(16,5));
        note(parent,loadLabel(entry.optString("loadMode")));
        if("MACHINE".equals(entry.optString("loadMode"))||"ASSISTANCE".equals(entry.optString("loadMode"))){note(parent,entry.optString("equipmentProfileId").isEmpty()?tx("Для сравнения прогресса укажите конкретный тренажёр."):tx("Тренажёр: ")+entry.optString("equipmentProfileId"));quiet(parent,tx("Указать тренажёр"),()->flush(()->equipmentProfile(chosen)));}
        List<JSONObject> rows=sets.getOrDefault(entry.optString("id"),Collections.emptyList());
        int done=0;JSONObject draft=null;
        for(JSONObject row:rows){if("DONE".equals(row.optString("state")))done++;else if(draft==null)draft=row;}
        note(parent,tx("Выполнено подходов: ")+done+(draft==null?"":tx(" · следующий ")+(done+1)));
        LinearLayout setPanel=column();ui.card(setPanel);parent.addView(setPanel,ui.fillWrap(8,12));setPanel.addView(ui.heading(tx("Текущий подход")),ui.fillWrap(0,10));
        if(draft!=null){
            final JSONObject selected=draft;
            DraftWriter writer=writers.computeIfAbsent(draft.optString("id"),id->new DraftWriter(selected));writer.accept(selected);
            LinearLayout fields=new LinearLayout(activity);fields.setBaselineAligned(false);
            String mode=entry.optString("loadMode");boolean hasLoad=!"BODYWEIGHT".equals(mode)&&!"DURATION".equals(mode);
            if(hasLoad){LinearLayout load=column();load.setPadding(0,0,ui.dp(10),0);EditText weight=input(load,"ASSISTANCE".equals(mode)?tx("Помощь, кг"):tx("Вес, кг"),writer.load,true);weight.setContentDescription(loadLabel(mode));watch(weight,value->writer.change(value,writer.reps));fields.addView(load,ui.weighted(1));}
            LinearLayout repetitions=column();repetitions.setPadding(0,0,0,0);EditText reps=input(repetitions,"DURATION".equals(mode)?tx("Время, секунд"):tx("Повторения"),writer.reps,false);watch(reps,value->writer.change(writer.load,value));fields.addView(repetitions,ui.weighted(1));setPanel.addView(fields,ui.fillWrap(8,4));
            CheckBoxRow warmup=new CheckBoxRow(setPanel,tx("Разминочный подход"),selected.optString("setKind").equals("WARMUP"));
            warmup.box.setOnCheckedChangeListener((view,on)->{flush(()->perform(()->diary.setKind(selected.optString("id"),on?"WARMUP":"WORK",writer.revision),ignored->{}));});
            Button complete=primary(setPanel,tx("Подход выполнен"),()->{
                if(actionBusy)return;actionBusy=true;flush(()->store.submit(()->diary.completeSet(selected.optString("id"),writer.revision),(result,error)->{actionBusy=false;if(disposed)return;if(!error.isEmpty()){showFailure(error);return;}writers.remove(selected.optString("id"));refresh.run();}));
            });complete.setEnabled(running&&!actionBusy);
        }else note(setPanel,tx("Все записанные подходы выполнены."));
        LinearLayout restPanel=column();ui.card(restPanel);parent.addView(restPanel,ui.fillWrap(0,12));restPanel.addView(ui.section(tx("Отдых между подходами")),ui.fillWrap(0,6));
        restView=ui.label("",20,ui.text);restPanel.addView(restView,ui.fillWrap(4,10));tick();
        LinearLayout restActions=new LinearLayout(activity);
        Button skip=button(tx("Пропустить отдых"),()->perform(()->diary.cancelRest(current.optString("id")),ignored->{}));restActions.addView(skip,ui.weighted(1));
        Button duration=button(tx("Настроить"),()->chooseRest(current));LinearLayout.LayoutParams durationParams=ui.weighted(1);durationParams.setMargins(ui.dp(8),0,0,0);restActions.addView(duration,durationParams);restPanel.addView(restActions,ui.fillWrap(0,0));
        quiet(parent,tx("Постоянная заметка к упражнению"),()->flush(()->templateNote(chosen)));
        if(!entry.optString("currentTemplateNote").isEmpty())note(parent,entry.optString("currentTemplateNote"));
        quiet(parent,tx("Заметка в этой тренировке"),()->flush(()->entryNote(chosen)));
        if(!entry.optString("note").isEmpty())note(parent,entry.optString("note"));
        if(done>0){LinearLayout completed=column();ui.card(completed);parent.addView(completed,ui.fillWrap(10,6));completed.addView(ui.section(tx("Выполненные подходы")));for(JSONObject row:rows)if("DONE".equals(row.optString("state")))secondary(completed,setText(chosen,row),()->flush(()->editCompleted(chosen,row)));}
        Button add=secondary(parent,tx("+ Добавить упражнение"),()->flush(()->catalog(current.optString("id"))));add.setEnabled(running);
        quiet(parent,tx("Убрать это упражнение"),()->flush(()->removeEntry(chosen)));
    }

    private void buildWalking(LinearLayout parent,JSONObject current,JSONObject entry,boolean running){
        parent.addView(ui.heading(entryName(entry)),ui.fillWrap(12,8));
        LinearLayout panel=column();ui.card(panel);parent.addView(panel,ui.fillWrap(4,12));
        JSONObject summary=entry.optJSONObject("timedSummary");walkingSnapshot=summary;walkingAnchor=android.os.SystemClock.elapsedRealtime();walkingView=ui.label(walkingText(summary),16,ui.text);panel.addView(walkingView,ui.fillWrap(4,8));
        note(panel,tx("Шаги и пульс поступают с часов. Если связи нет, время сохраняется, а показатели могут быть неполными."));
        boolean active=summary!=null&&("RUNNING".equals(summary.optString("state"))||"ACTIVE".equals(summary.optString("state")));
        Button toggle=primary(panel,tx(active?"Остановить ходьбу":"Начать ходьбу"),()->flush(()->perform(()->active?diary.stopTimed(entry.optString("id")):diary.startTimed(current.optString("id"),entry.optString("id")),ignored->{})));toggle.setEnabled(running||active);
        note(panel,tx("Пока ходьба включена, тренировка не завершится автоматически. По прибытии нажмите «Остановить ходьбу»."));
        quiet(parent,tx("Постоянная заметка к упражнению"),()->templateNote(entry));
        if(!entry.optString("currentTemplateNote").isEmpty())note(parent,entry.optString("currentTemplateNote"));
        quiet(parent,tx("Заметка в этой тренировке"),()->entryNote(entry));if(!entry.optString("note").isEmpty())note(parent,entry.optString("note"));
        primary(parent,tx("Добавить упражнение"),()->catalog(current.optString("id"))).setEnabled(running);
        quiet(parent,tx("Убрать это упражнение"),()->removeEntry(entry));
    }
    private String walkingText(JSONObject summary){
        if(summary==null)return tx("Ходьба ещё не записана");
        String state=summary.optString("state");String text=tx("RUNNING".equals(state)||"ACTIVE".equals(state)?"Ходьба идёт":"Ходьба")+" · "+WorkoutRecorder.duration(summary.optLong("durationMillis"));
        text+="\n"+tx("Шаги: ")+(summary.isNull("stepsObserved")?tx("нет данных"):summary.optLong("stepsObserved"));
        if(summary.optInt("heartSampleCount")>0)text+="\n"+tx("Пульс: ")+String.format(java.util.Locale.getDefault(),"%.0f",summary.optDouble("heartAverage"))+tx(" · максимум ")+summary.optInt("heartMax")+tx(" уд/мин");
        else text+="\n"+tx("Пульс: нет данных");
        if(summary.optLong("stepsCoverageMillis")>0)text+="\n"+tx("Покрытие шагов: ")+WorkoutRecorder.duration(summary.optLong("stepsCoverageMillis"));
        if("PARTIAL_TIME_UNKNOWN".equals(summary.optString("quality")))text+="\n"+tx("Запись ходьбы прервалась. Неизвестный интервал не включён во время.");
        return text;
    }
    private void templateNote(JSONObject entry){
        String variant=entry.optString("templateVariantId",entry.optString("variantId"));
        store.submit(()->diary.templateNote(variant),(record,error)->{if(disposed)return;if(!error.isEmpty()){showFailure(error);return;}
            LinearLayout body=column();note(body,tx("Эта заметка относится к упражнению и доступна в следующих тренировках. Заметки отдельных занятий сохраняются отдельно."));
            EditText input=input(body,tx("Постоянная заметка"),record.optString("note"),false);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);input.setSingleLine(false);input.setMinLines(3);input.setTextSize(16);
            AlertDialog dialog=ui.dialogBuilder().setTitle(tx("Постоянная заметка")).setView(ui.scrollForm(body)).setNegativeButton(tx("Отмена"),null).setPositiveButton(tx("Сохранить"),null).create();
            dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{if(input.length()>2000){input.setError(tx("Не более 2000 символов"));return;}perform(()->diary.saveTemplateNote(record.optString("variantId",variant),input.getText().toString(),record.optLong("revision")),saved->dialog.dismiss());}));dialog.show();ui.dialog(dialog);
        });
    }
    private void removeEntry(JSONObject entry){
        ui.dialogBuilder().setTitle(tx("Убрать упражнение?"))
            .setMessage(entryName(entry)+"\n"+tx("Упражнение и его подходы исчезнут из этой тренировки. После удаления можно нажать «Вернуть»."))
            .setNegativeButton(tx("Оставить"),null).setPositiveButton(tx("Убрать"),(dialog,which)->flush(()->perform(()->diary.removeExercise(entry.optString("id"),entry.optLong("revision")),removed->{lastRemoved=removed;focusedEntry="";exerciseList=true;writers.clear();}))).show();
    }

    void history(LinearLayout parent,UiKit kit,List<JSONObject> entries,Map<String,List<JSONObject>> sets){
        ui=kit;restView=null;walkingView=null;walkingSnapshot=null;for(JSONObject entry:entries){LinearLayout card=column();ui.card(card);parent.addView(card,ui.fillWrap(7,9));TextView title=ui.label(entryName(entry),18,ui.text);card.addView(title);if("TIMED_WALK".equals(entry.optString("entryKind")))note(card,walkingText(entry.optJSONObject("timedSummary")));else note(card,loadLabel(entry.optString("loadMode")));for(JSONObject set:sets.getOrDefault(entry.optString("id"),Collections.emptyList()))if("DONE".equals(set.optString("state")))secondary(card,setText(entry,set),()->editCompleted(entry,set));if(!entry.optString("note").isEmpty())note(card,entry.optString("note"));}
    }

    private void chooseEntry(JSONObject current,List<JSONObject> entries){String[] names=new String[entries.size()];for(int i=0;i<names.length;i++)names[i]=entryName(entries.get(i));ui.dialogBuilder().setTitle(tx("Текущее упражнение")).setItems(names,(dialog,index)->{focusedEntry=entries.get(index).optString("id");perform(()->diary.focus(current.optString("id"),focusedEntry),ignored->{});}).setNegativeButton(tx("Отмена"),null).show();}
    private void catalog(String workoutId){
        store.submit(diary::catalog,(catalog,error)->{if(disposed)return;if(!error.isEmpty()){showFailure(error);return;}showCatalog(workoutId,catalog);});
    }
    private void showCatalog(String workoutId,List<JSONObject> catalog){
        LinearLayout body=column();EditText search=input(body,tx("Поиск упражнения"),"",false);search.setInputType(InputType.TYPE_CLASS_TEXT);
        String[] selectedGroup={""};LinearLayout navigation=column();navigation.setPadding(0,0,0,0);body.addView(navigation);
        LinearLayout results=column();results.setPadding(0,0,0,0);body.addView(results);ScrollView scroll=new ScrollView(activity);scroll.addView(body);
        AlertDialog dialog=ui.dialogBuilder().setTitle(tx("Группы мышц")).setView(scroll).setNegativeButton(tx("Закрыть"),null).create();
        Runnable[] render={null};render[0]=()->{
            results.removeAllViews();navigation.removeAllViews();String query=search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT).replace('ё','е');
            boolean groups=selectedGroup[0].isEmpty()&&query.isEmpty();dialog.setTitle(tx(groups?"Группы мышц":selectedGroup[0].isEmpty()?"Поиск упражнений":selectedGroup[0]));
            if(!groups)secondary(navigation,tx("Все группы"),()->{selectedGroup[0]="";search.setText("");render[0].run();scroll.smoothScrollTo(0,0);});
            if(groups){
                String[] names={"Грудь","Спина","Ноги","Плечи","Руки","Корпус","Ходьба","Другие"};LinearLayout pair=null;int visible=0;
                for(String group:names){int count=0;for(JSONObject exercise:catalog)if(group.equals(StrengthCatalog.navigationGroup(exercise)))count++;if(count==0)continue;
                    if(visible++%2==0){pair=new LinearLayout(activity);pair.setBaselineAligned(false);results.addView(pair,ui.fillWrap(0,8));}
                    LinearLayout tile=new LinearLayout(activity){@Override public CharSequence getAccessibilityClassName(){return android.widget.Button.class.getName();}};
                    tile.setOrientation(LinearLayout.VERTICAL);tile.setPadding(0,0,0,ui.dp(12));ui.card(tile);tile.setClipToOutline(true);
                    tile.setClickable(true);tile.setFocusable(true);tile.setContentDescription(tx(group)+", "+count+" "+tx("упр."));
                    tile.setForeground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf((ui.accent&0x00ffffff)|0x33000000),null,null));
                    tile.setOnClickListener(view->{selectedGroup[0]=group;render[0].run();scroll.smoothScrollTo(0,0);});
                    tile.addView(new MuscleGroupIllustrationView(activity,group),new LinearLayout.LayoutParams(-1,ui.dp(124)));
                    TextView title=ui.label(tx(group),18,ui.text);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(-1,-2);titleParams.setMargins(ui.dp(12),ui.dp(10),ui.dp(12),ui.dp(3));tile.addView(title,titleParams);
                    TextView counter=ui.label(count+" "+tx("упр."),13,ui.muted);counter.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    LinearLayout.LayoutParams countParams=new LinearLayout.LayoutParams(-1,-2);countParams.setMargins(ui.dp(12),0,ui.dp(12),0);tile.addView(counter,countParams);
                    LinearLayout.LayoutParams lp=ui.weighted(1);if((visible-1)%2==1)lp.setMargins(ui.dp(8),0,0,0);pair.addView(tile,lp);
                }
                if(pair!=null&&visible%2==1)pair.addView(new View(activity),ui.weighted(1));
            }else{
                int count=0;for(JSONObject exercise:catalog){
                    if(!selectedGroup[0].isEmpty()&&!selectedGroup[0].equals(StrengthCatalog.navigationGroup(exercise)))continue;
                    if(!(catalogText(exercise,"name")+" "+catalogText(exercise,"group")+" "+catalogText(exercise,"equipment")).toLowerCase(java.util.Locale.ROOT).replace('ё','е').contains(query))continue;count++;
                    LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(ui.dp(8),ui.dp(10),ui.dp(12),ui.dp(10));ui.card(row);
                    ExerciseIllustrationView picture=new ExerciseIllustrationView(activity,ui,StrengthCatalog.find(exercise.optString("id"))==null?exercise.optString("illustration"):exercise.optString("id"),true);picture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(picture,new LinearLayout.LayoutParams(ui.dp(72),ui.dp(72)));
                    LinearLayout labels=column();labels.addView(ui.label(catalogText(exercise,"name"),16,ui.text));labels.addView(ui.label(catalogText(exercise,"equipment"),13,ui.muted),ui.fillWrap(4,0));row.addView(labels,ui.weighted(1));ui.touchTarget(row);row.setContentDescription(catalogText(exercise,"name"));row.setOnClickListener(view->{row.setEnabled(false);perform(()->diary.addExercise(workoutId,exercise.optString("id")),entry->{focusedEntry=entry.optString("id");exerciseList=false;dialog.dismiss();});});results.addView(row,ui.fillWrap(0,8));
                }
                if(count==0)note(results,tx("Ничего не найдено"));
            }
            secondary(results,tx("+ Своё упражнение"),()->{dialog.dismiss();customExercise(workoutId);});
        };
        dialog.setOnKeyListener((d,key,event)->{if(key==android.view.KeyEvent.KEYCODE_BACK&&event.getAction()==android.view.KeyEvent.ACTION_UP&&(!selectedGroup[0].isEmpty()||search.length()>0)){selectedGroup[0]="";search.setText("");render[0].run();scroll.smoothScrollTo(0,0);return true;}return false;});
        watch(search,value->render[0].run());render[0].run();dialog.show();ui.dialog(dialog);
    }
    private void customExercise(String workoutId){
        LinearLayout body=column();EditText name=input(body,tx("Название"),"",false);name.setInputType(InputType.TYPE_CLASS_TEXT);EditText group=input(body,tx("Группа мышц"),"",false);group.setInputType(InputType.TYPE_CLASS_TEXT);
        String[] labels={tx("Штанга: полный вес"),tx("Гантель: вес одной"),tx("Тренажёр"),tx("Собственный вес"),tx("Дополнительный вес"),tx("Помощь тренажёра")};String[] modes={"BARBELL_TOTAL","DUMBBELL_EACH","MACHINE","BODYWEIGHT","BODYWEIGHT_ADDED","ASSISTANCE"};int[] selected={0};Button[] mode={null};mode[0]=secondary(body,labels[0],()->ui.dialogBuilder().setTitle(tx("Как записывать нагрузку")).setItems(labels,(dialog,index)->{selected[0]=index;mode[0].setText(labels[index]);}).show());
        note(body,tx("Для своего упражнения используется общая схема движения."));
        AlertDialog dialog=ui.dialogBuilder().setTitle(tx("Своё упражнение")).setView(ui.scrollForm(body)).setNegativeButton(tx("Отмена"),null).setPositiveButton(tx("Добавить"),null).create();dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->perform(()->{JSONObject exercise=diary.addCustomExercise(name.getText().toString(),group.getText().toString(),labels[selected[0]],modes[selected[0]],"press");return diary.addExercise(workoutId,exercise.optString("id"));},entry->{focusedEntry=entry.optString("id");dialog.dismiss();})));dialog.show();ui.dialog(dialog);
    }
    private void equipmentProfile(JSONObject entry){
        LinearLayout body=column();EditText name=input(body,tx("Например: верхний блок у окна"),entry.optString("equipmentProfileId"),false);name.setInputType(InputType.TYPE_CLASS_TEXT);note(body,tx("Укажите название до первого выполненного подхода. Разные тренажёры сравниваются отдельно."));AlertDialog dialog=ui.dialogBuilder().setTitle(tx("Конкретный тренажёр")).setView(ui.scrollForm(body)).setNegativeButton(tx("Отмена"),null).setPositiveButton(tx("Сохранить"),null).create();dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{if(name.length()>120){name.setError(tx("Не более 120 символов"));return;}perform(()->diary.setEquipmentProfile(entry.optString("id"),name.getText().toString(),entry.optLong("revision")),saved->dialog.dismiss());}));dialog.show();ui.dialog(dialog);
    }
    private void chooseRest(JSONObject current){String[] labels={tx("Без таймера"),tx("30 секунд"),tx("60 секунд"),tx("90 секунд"),tx("2 минуты"),tx("3 минуты")};int[] seconds={0,30,60,90,120,180};ui.dialogBuilder().setTitle(tx("Отдых между подходами")).setItems(labels,(dialog,index)->perform(()->diary.settings(current.optString("id"),seconds[index]),ignored->{})).setNegativeButton(tx("Отмена"),null).show();}
    private void entryNote(JSONObject entry){LinearLayout body=column();EditText text=input(body,tx("Заметка"),entry.optString("note"),false);text.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);text.setSingleLine(false);AlertDialog dialog=ui.dialogBuilder().setTitle(tx("Заметка к упражнению")).setView(ui.scrollForm(body)).setNegativeButton(tx("Отмена"),null).setPositiveButton(tx("Сохранить"),null).create();dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{if(text.length()>500){text.setError(tx("Не более 500 символов"));return;}perform(()->diary.entryNote(entry.optString("id"),text.getText().toString(),entry.optLong("revision")),saved->dialog.dismiss());}));dialog.show();ui.dialog(dialog);}
    private void perform(java.util.concurrent.Callable<JSONObject> work,java.util.function.Consumer<JSONObject> done){if(actionBusy)return;actionBusy=true;store.submit(work,(result,error)->{actionBusy=false;if(disposed)return;if(!error.isEmpty()){showFailure(error);return;}done.accept(result);refresh.run();});}
    private void showFailure(String code){
        actionBusy=false;String reason;
        if(code.contains("SET_REPS"))reason=tx("Укажите от 1 до 1000 повторений.");
        else if(code.contains("SET_WEIGHT"))reason=tx("Укажите вес от 0 до 2000 кг, не более трёх знаков после запятой.");
        else if(code.contains("EXERCISE_FIELDS"))reason=tx("Укажите название упражнения, не более 120 символов.");
        else if(code.contains("EQUIPMENT_PROFILE_LOCKED"))reason=tx("Тренажёр нельзя менять после выполненного подхода. Добавьте отдельное упражнение для другого тренажёра.");
        else if(code.contains("DIARY_REVISION_CHANGED"))reason=tx("Запись уже изменилась. Откройте её заново; ваш текущий ввод остаётся в форме.");
        else if(code.contains("SET_REQUIRES_RUNNING")||code.contains("WORKOUT_TERMINAL"))reason=tx("Для записи подхода продолжите тренировку.");
        else if(code.contains("EXERCISE_LIMIT"))reason=tx("Достигнут предел: 500 своих упражнений.");
        else if(code.contains("WORKOUT_EXERCISE_LIMIT"))reason=tx("В одном занятии можно записать до 100 упражнений.");
        else if(code.contains("ENTRY_SET_LIMIT"))reason=tx("В одном упражнении можно записать до 500 подходов.");
        else if(code.contains("DIARY_TEXT_LIMIT"))reason=tx("Текст слишком длинный. Сократите название или заметку.");
        else reason=tx("Не удалось сохранить запись. Ваш ввод сохранён в форме; повторите действие.");
        if(feedback!=null){feedback.setText(reason);feedback.setVisibility(View.VISIBLE);}if(ui!=null)ui.dialogBuilder().setTitle(tx("Запись не сохранена")).setMessage(reason).setPositiveButton(tx("Закрыть"),null).show();
    }
    private void editCompleted(JSONObject entry,JSONObject set){
        LinearLayout body=column();EditText load=input(body,loadLabel(entry.optString("loadMode")),set.optString("loadInput"),true);load.setVisibility("BODYWEIGHT".equals(entry.optString("loadMode"))?View.GONE:View.VISIBLE);EditText reps=input(body,tx("Повторения"),set.optString("repsInput"),false);EditText note=input(body,tx("Заметка к подходу"),set.optString("note"),false);note.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);note.setSingleLine(false);
        AlertDialog dialog=ui.dialogBuilder().setTitle(tx("Исправить выполненный подход")).setView(ui.scrollForm(body)).setNegativeButton(tx("Отмена"),null).setPositiveButton(tx("Сохранить"),null).create();dialog.setOnShowListener(ignored->{ui.dialog(dialog);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->perform(()->diary.editSet(set.optString("id"),load.getText().toString(),reps.getText().toString(),note.getText().toString(),set.optLong("revision")),saved->dialog.dismiss()));});dialog.show();
    }
    private LinearLayout column(){LinearLayout result=new LinearLayout(activity);result.setOrientation(LinearLayout.VERTICAL);result.setPadding(ui.dp(12),ui.dp(12),ui.dp(12),ui.dp(12));return result;}
    private void note(LinearLayout parent,String text){parent.addView(ui.label(text,12,ui.muted),ui.fillWrap(3,7));}
    private String entryName(JSONObject entry){String value=entry.optString("nameSnapshot");return StrengthCatalog.find(entry.optString("variantId"))==null?value:tx(value);}
    private String catalogText(JSONObject entry,String key){String value=entry.optString(key);return StrengthCatalog.find(entry.optString("id"))==null?value:tx(value);}
    private String tx(String text){return UiText.choose("en".equals(new AppPreferences(activity).language()),text);}
    private Button button(String text,Runnable clicked){Button result=ui.action(text,false,clicked);ui.buttonText(result,ui.text);ui.outline(result);return result;}
    private Button secondary(LinearLayout parent,String text,Runnable clicked){Button result=button(text,clicked);parent.addView(result,ui.fillWrap(5,7));return result;}
    private Button primary(LinearLayout parent,String text,Runnable clicked){Button result=secondary(parent,text,clicked);ui.accent(result);ui.buttonText(result,android.graphics.Color.WHITE);return result;}
    private void quiet(LinearLayout parent,String text,Runnable clicked){Button result=secondary(parent,text,clicked);result.setBackgroundColor(android.graphics.Color.TRANSPARENT);result.setTextColor(ui.muted);}
    private EditText input(LinearLayout parent,String label,String value,boolean decimal){TextView name=ui.label(label,13,ui.muted);parent.addView(name,ui.fillWrap(2,5));EditText result=new EditText(ui.dialogContext());result.setId(View.generateViewId());name.setLabelFor(result.getId());result.setTextColor(ui.text);result.setHintTextColor(ui.muted);result.setTextSize(24);result.setSingleLine(true);result.setInputType(InputType.TYPE_CLASS_NUMBER|(decimal?InputType.TYPE_NUMBER_FLAG_DECIMAL:0));result.setText(value);result.setMinHeight(ui.dp(64));ui.input(result);parent.addView(result,ui.fillWrap(0,5));return result;}
    private void watch(EditText field,java.util.function.Consumer<String> changed){field.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){changed.accept(s.toString());}public void afterTextChanged(Editable e){}});}
    String loadLabel(String mode){switch(mode){case "BARBELL_TOTAL":return tx("Общий вес штанги вместе с грифом, кг");case "DUMBBELL_EACH":return tx("Вес одной гантели, кг");case "ASSISTANCE":return tx("Вес помощи тренажёра, кг");case "BODYWEIGHT":return tx("Собственный вес · записываются повторения");case "BODYWEIGHT_ADDED":return tx("Дополнительный вес, кг");case "DURATION":return tx("Длительность подхода в секундах");case "TIMED":return tx("Время, шаги и пульс");default:return tx("Указанный вес конкретного тренажёра, кг");}}
    String setText(JSONObject entry,JSONObject set){String kind="WARMUP".equals(set.optString("setKind"))?tx("Разминка"):tx("Подход ")+(set.optInt("position")+1);String mode=entry.optString("loadMode");if("DURATION".equals(mode))return kind+" · "+set.optInt("reps")+tx(" с");String load="BODYWEIGHT".equals(mode)?"":String.format(java.util.Locale.getDefault(),tx("%.1f кг × "),set.optLong("loadGrams")/1000.0);return kind+" · "+load+set.optInt("reps");}

    private final class CheckBoxRow {final android.widget.CheckBox box;CheckBoxRow(LinearLayout parent,String label,boolean checked){box=new android.widget.CheckBox(ui.dialogContext());box.setText(label);box.setTextColor(ui.muted);box.setTextSize(12);box.setChecked(checked);box.setMinHeight(ui.dp(48));ui.check(box);parent.addView(box,ui.fillWrap(0,3));}}

    private final class DraftWriter {
        final String id;String load,reps,note;long revision;boolean dirty,sending;Runnable after;
        final Runnable delayed=this::send;
        DraftWriter(JSONObject set){id=set.optString("id");load=set.optString("loadInput");reps=set.optString("repsInput");note=set.optString("note");revision=set.optLong("revision");}
        void accept(JSONObject set){if(!dirty&&!sending){revision=set.optLong("revision");load=set.optString("loadInput");reps=set.optString("repsInput");note=set.optString("note");}}
        void change(String load,String reps){this.load=load;this.reps=reps;dirty=true;main.removeCallbacks(delayed);main.postDelayed(delayed,350);if(feedback!=null){feedback.setText(tx("Сохранение…"));feedback.setVisibility(View.VISIBLE);}}
        void flush(Runnable done){after=done;send();}
        void send(){main.removeCallbacks(delayed);if(sending)return;if(!dirty){if(after!=null){Runnable callback=after;after=null;callback.run();}return;}sending=true;dirty=false;String sentLoad=load,sentReps=reps,sentNote=note;long expected=revision;store.submit(()->diary.saveDraft(id,sentLoad,sentReps,sentNote,expected),(updated,error)->{sending=false;if(!error.isEmpty()){dirty=true;after=null;if(!disposed)showFailure(error);return;}revision=updated.optLong("revision");if(!disposed&&feedback!=null)feedback.setText(tx("Сохранено"));send();});}
    }
}
