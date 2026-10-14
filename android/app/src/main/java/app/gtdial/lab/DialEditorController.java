package app.gtdial.lab;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local authoring only. No BLE/session dependency and no installation authorization. */
final class DialEditorController implements DialEditorView.Listener {
    static final int IMAGE_REQUEST=103;
    private final Activity activity;
    private final DialProjectRepository repository;
    private final Runnable galleryChanged;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean destroyed;
    private DialProject project;
    private String selectedId,pendingUri="";
    private boolean cropMode,busy;
    private float panX,panY;
    private Bitmap source,background;
    private Dialog dialog;
    private DialEditorView canvas;
    private LinearLayout form,selectionControls,backgroundControls,reviewControls,fieldList;
    private TextView feedback;
    private Button cropToggle,saveButton;
    private final Button[] tabs=new Button[3];
    private int activePanel=1;
    private boolean fieldHelpExpanded;
    private SeekBar zoom;
    private UiKit ui;

    DialEditorController(Activity activity,DialProjectRepository repository,Runnable galleryChanged) {
        this.activity=activity;this.repository=repository;this.galleryChanged=galleryChanged;
    }
    void restore(Bundle state,String theme) {
        ui=new UiKit(activity,theme);
        try {
            DialProjectRepository.Draft draft=repository.loadDraft();
            if(draft==null)return;
            project=draft.project;selectedId=draft.selectedId;cropMode=draft.cropMode;pendingUri=draft.pendingUri;
            activePanel=state==null?(cropMode?0:1):state.getInt("dialEditor.panel",cropMode?0:1);
            if(activePanel<0||activePanel>2)activePanel=1;cropMode=activePanel==0;
            // A disk draft is available to resume, but never opens itself on a cold start.
            if(state==null||!state.getBoolean("dialEditor.open",false)){galleryChanged.run();return;}
            show();loadSource();galleryChanged.run();
        }catch(Exception e){message(tx("Не удалось восстановить черновик: ")+e.getMessage());}
    }
    boolean deletionReady(){return !busy&&(dialog==null||!dialog.isShowing());}
    void discardDeletedProject(String id){
        if(project==null||!project.id.equals(id))return;
        project=null;selectedId=null;pendingUri="";canvas=null;
        if(dialog!=null){dialog.setOnDismissListener(null);dialog.dismiss();dialog=null;}
        recycleImages();
    }
    boolean hasDraft(){return project!=null;}
    boolean isOpen(){return dialog!=null&&dialog.isShowing();}
    void resume(String theme) {
        if(project==null)return;ui=new UiKit(activity,theme);show();
        if(source==null)loadSource();
    }
    void create(String theme) {
        if(project!=null){resume(theme);return;}
        ui=new UiKit(activity,theme);project=DialProject.blank();selectedId=null;activePanel=0;cropMode=true;pendingUri="";
        checkpoint();show();
    }
    void edit(DialProjectRepository.Revision revision,String theme) {
        if(busy){message(tx("Дождитесь подготовки фотографии"));return;}
        if(project!=null) {
            new UiKit(activity,theme).dialogBuilder().setTitle(tx("Открыть сохранённый проект?"))
                .setMessage(tx("Текущий черновик будет заменён выбранной ревизией."))
                .setNegativeButton(tx("Продолжить черновик"),(d,w)->resume(theme))
                .setPositiveButton(tx("Открыть"),(d,w)->openRevision(revision,theme)).show();
        }else openRevision(revision,theme);
    }
    private void openRevision(DialProjectRepository.Revision revision,String theme) {
        ui=new UiKit(activity,theme);project=revision.project;selectedId=null;activePanel=1;cropMode=false;pendingUri="";
        recycleImages();checkpoint();show();loadSource();
    }
    private void show() {
        if(dialog!=null&&dialog.isShowing())return;
        form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);form.setBackgroundColor(ui.surface);
        form.setPadding(ui.dp(16),ui.dp(8),ui.dp(16),ui.dp(8));form.setFitsSystemWindows(false);
        LinearLayout header=new LinearLayout(activity);header.setGravity(Gravity.CENTER_VERTICAL);
        Button back=button("");ui.navigationIcon(back,true);back.setBackgroundColor(Color.TRANSPARENT);back.setTextSize(24);back.setPadding(0,0,0,0);back.setContentDescription(tx("Сохранить черновик и вернуться"));back.setOnClickListener(v->{checkpoint();dialog.dismiss();});
        header.addView(back,new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));
        TextView title=ui.label(tx("Редактор циферблата"),16,ui.text);title.setMaxLines(1);title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLayout=ui.weighted(1);titleLayout.setMargins(ui.dp(8),0,ui.dp(8),0);header.addView(title,titleLayout);
        saveButton=button(tx("Сохранить"));saveButton.setBackgroundColor(Color.TRANSPARENT);ui.buttonText(saveButton,ui.accentInk);saveButton.setPadding(ui.dp(8),0,ui.dp(8),0);saveButton.setMinWidth(0);saveButton.setMinimumWidth(0);
        saveButton.setOnClickListener(v->save());header.addView(saveButton,new LinearLayout.LayoutParams(-2,-2));form.addView(header);
        EditText name=new EditText(activity);name.setSingleLine(true);name.setHint(tx("Название циферблата"));
        name.setTextColor(ui.text);name.setHintTextColor(ui.muted);name.setContentDescription(tx("Название циферблата"));
        name.setText(project.name);name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});ui.input(name);name.setTextSize(16);name.setPadding(ui.dp(12),0,ui.dp(12),0);
        LinearLayout.LayoutParams nameLayout=ui.fillWrap(12,12);nameLayout.height=ui.dp(48);form.addView(name,nameLayout);
        name.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){
                if(!busy){project=project.with(s.toString(),project.background,project.elements);checkpoint();}}
            public void afterTextChanged(Editable e){}
        });
        canvas=new DialEditorView(activity);canvas.listener(this);form.addView(canvas,new LinearLayout.LayoutParams(-1,0,.48f));
        TextView caption=label(tx("Предпросмотр с примерными значениями"));caption.setGravity(Gravity.CENTER);form.addView(caption);
        feedback=label("");feedback.setMaxLines(2);feedback.setEllipsize(android.text.TextUtils.TruncateAt.END);form.addView(feedback);
        LinearLayout tabBar=new LinearLayout(activity);String[] tabNames={tx("Фон"),tx("Показатели"),tx("Просмотр")};
        for(int i=0;i<3;i++){final int panel=i;tabs[i]=button(tabNames[i]);tabs[i].setOnClickListener(v->switchPanel(panel));tabBar.addView(tabs[i],ui.weighted(1));}
        form.addView(tabBar,ui.fillWrap(0,6));
        LinearLayout panels=new LinearLayout(activity);panels.setOrientation(LinearLayout.VERTICAL);
        backgroundControls=new LinearLayout(activity);backgroundControls.setOrientation(LinearLayout.VERTICAL);panels.addView(backgroundControls);
        Button choose=button(tx("Заменить фотографию"));choose.setOnClickListener(v->choosePhoto());backgroundControls.addView(choose);
        Button template=button(tx("Затмение · готовая композиция"));template.setOnClickListener(v->{if(busy)return;
            if(project.background==null&&project.elements.isEmpty())prepareEclipse();
            else ui.dialogBuilder().setTitle(tx("Применить «Затмение»?")).setMessage(tx("Фон и расположение показателей в текущем черновике будут заменены готовой композицией."))
                .setNegativeButton(tx("Отмена"),null).setPositiveButton(tx("Применить"),(d,w)->prepareEclipse()).show();});backgroundControls.addView(template);
        cropToggle=button(tx("Перейти к показателям"));cropToggle.setOnClickListener(v->switchPanel(1));backgroundControls.addView(cropToggle);
        zoom=new SeekBar(activity);zoom.setMax(300);ui.progress(zoom);zoom.setContentDescription(tx("Увеличение фотографии от одного до четырёх раз"));
        zoom.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){checkpoint();}
            public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){
                if(fromUser&&!busy&&project.background!=null){DialProject.Background b=project.background;
                    int size=Math.max(1,Math.round(Math.min(b.width,b.height)/(1+progress/100f)));
                    changeCrop(b.x+b.size/2f-size/2f,b.y+b.size/2f-size/2f,size);}}
        });backgroundControls.addView(zoom);
        Button center=button(tx("Обрезать по центру"));center.setOnClickListener(v->{if(busy||project.background==null)return;
            DialProject.Background b=project.background;int size=Math.min(b.width,b.height);
            changeCrop((b.width-size)/2f,(b.height-size)/2f,size);checkpoint();refresh();});backgroundControls.addView(center);
        selectionControls=new LinearLayout(activity);selectionControls.setOrientation(LinearLayout.VERTICAL);panels.addView(selectionControls);
        fieldList=new LinearLayout(activity);fieldList.setOrientation(LinearLayout.VERTICAL);panels.addView(fieldList);
        reviewControls=new LinearLayout(activity);reviewControls.setOrientation(LinearLayout.VERTICAL);panels.addView(reviewControls);
        reviewControls.addView(label(tx("Цвета и шрифт на часах могут отличаться от предпросмотра.")));
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);scroll.addView(panels);form.addView(scroll,new LinearLayout.LayoutParams(-1,0,.52f));
        dialog=new Dialog(ui.dialogContext());dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);dialog.setContentView(form);
        dialog.setOnDismissListener(d->{checkpoint();dialog=null;canvas=null;if(!destroyed)galleryChanged.run();});
        dialog.setOnShowListener(d->{Window window=dialog.getWindow();if(window!=null){window.setLayout(-1,-1);
            window.setBackgroundDrawableResource(android.R.color.transparent);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.setStatusBarColor(ui.surface);window.setNavigationBarColor(ui.surface);}
            form.setPadding(ui.dp(16),ui.dp(8),ui.dp(16),ui.dp(8));
            refresh();setBusy(busy);});dialog.show();
    }
    private void switchPanel(int panel){if(busy)return;activePanel=panel;cropMode=panel==0;checkpoint();refresh();}
    private void prepareEclipse(){
        if(busy)return;setBusy(true);status(tx("Подготовка композиции…"));
        worker.execute(()->{Bitmap decoded=null;
            try{byte[] png;try(java.io.InputStream input=activity.getAssets().open("dial_editor/backgrounds/eclipse-v1.png")){png=DialImageNormalizer.read(input,DialImageNormalizer.MAX_BYTES);}
                if(!"5fd108accd4f553c161f8e1e1d6f75851663e2806c2b18c36527b81523f14d73".equals(DialImageNormalizer.hash(png)))throw new java.io.IOException(tx("Фон композиции повреждён"));
                DialImageNormalizer.Result image=new DialImageNormalizer.Result(png,1254,1254);repository.storeSource(image);decoded=repository.loadSource(image.background);
                int[][] positions={{135,59},{235,59},{162,101},{225,101},{294,119},{328,120},{108,326},{246,326},{326,326},{145,384},{245,384}};
                List<DialProject.Element> nodes=new ArrayList<>();for(int i=0;i<positions.length;i++){DialRuntimeProfile.Field f=DialRuntimeProfile.FIELDS.get(i);
                    nodes.add(new DialProject.Element(f.id,positions[i][0],positions[i][1],f.width,f.height,Color.WHITE));}
                DialProject composition=project.with(project.name,image.background,nodes);composition.validate(false);Bitmap loaded=decoded;decoded=null;
                main.post(()->{if(destroyed){loaded.recycle();return;}recycleImages();source=loaded;project=composition;pendingUri="";selectedId=null;activePanel=2;cropMode=false;
                    setBusy(false);checkpoint();renderBackground();refresh();});
            }catch(Exception|OutOfMemoryError error){main.post(()->{if(!destroyed){setBusy(false);message(error instanceof OutOfMemoryError?tx("Недостаточно памяти для композиции"):error.getMessage());}});}
            finally{if(decoded!=null)decoded.recycle();}
        });
    }
    private void choosePhoto() {
        if(busy)return;checkpoint();
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try{activity.startActivityForResult(intent,IMAGE_REQUEST);}catch(RuntimeException e){message(tx("Выбор фотографии недоступен"));}
    }
    void imageResult(Intent data) {
        if(project==null||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        try {activity.getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
        catch(SecurityException e){message(tx("Поставщик не сохраняет доступ: не закрывайте приложение до подготовки фотографии"));}
        pendingUri=uri.toString();checkpoint();preparePhoto(uri);
    }
    private void preparePhoto(Uri uri) {
        if(busy)return;setBusy(true);status(tx("Подготовка фотографии…"));
        worker.execute(()->{
            Bitmap decoded=null;
            try {
                DialImageNormalizer.Result normalized=DialImageNormalizer.normalize(activity.getContentResolver(),uri);
                if(destroyed)return;repository.storeSource(normalized);decoded=repository.loadSource(normalized.background);
                Bitmap loaded=decoded;decoded=null;
                main.post(()->{
                    if(destroyed){loaded.recycle();return;}
                    recycleImages();source=loaded;project=project.with(project.name,normalized.background,project.elements);
                    pendingUri="";setBusy(false);checkpoint();renderBackground();refresh();
                });
            }catch(Exception e){String error=e.getMessage();main.post(()->{if(destroyed)return;pendingUri="";setBusy(false);checkpoint();status(error);message(error);});}
            finally {if(decoded!=null)decoded.recycle();}
        });
    }
    private void loadSource() {
        if(busy)return;
        if(!pendingUri.isEmpty()){preparePhoto(Uri.parse(pendingUri));return;}
        if(project.background==null){refresh();return;}
        setBusy(true);DialProject.Background bg=project.background;
        worker.execute(()->{
            try {Bitmap loaded=repository.loadSource(bg);main.post(()->{if(destroyed){loaded.recycle();return;}
                recycleImages();source=loaded;setBusy(false);
                if(!pendingUri.isEmpty()){preparePhoto(Uri.parse(pendingUri));return;}
                renderBackground();refresh();});}
            catch(Exception e){main.post(()->{if(!destroyed){setBusy(false);
                if(!pendingUri.isEmpty()){preparePhoto(Uri.parse(pendingUri));return;}
                status(e.getMessage());message(e.getMessage());}});}
        });
    }
    private void addField() {
        if(busy)return;List<DialRuntimeProfile.Field> available=new ArrayList<>();
        for(DialRuntimeProfile.Field field:DialRuntimeProfile.FIELDS)if(element(field.id)==null)available.add(field);
        boolean offerTime=!DialEditorGeometry.joinedTime(project);
        if(available.isEmpty()&&!offerTime){message(tx("Все показатели уже добавлены"));return;}
        String[] names=new String[available.size()+(offerTime?1:0)];
        if(offerTime)names[0]=tx("Часы + минуты · общий блок");
        for(int i=0;i<available.size();i++)names[i+(offerTime?1:0)]=fieldLabel(available.get(i));
        ui.dialogBuilder().setTitle(tx("Добавить показатель")).setItems(names,(d,index)->{
            if(offerTime&&index==0){joinTime();return;}
            DialRuntimeProfile.Field f=available.get(index-(offerTime?1:0));List<DialProject.Element> nodes=new ArrayList<>(project.elements);
            nodes.add(new DialProject.Element(f.id,f.x,f.y,f.width,f.height,Color.WHITE));
            selectedId=f.id;cropMode=false;activePanel=1;project=project.with(project.name,project.background,nodes);checkpoint();refresh();
        }).show();
    }
    private void joinTime(){
        DialProject.Element hour=element("HOUR");int x=hour==null?174:hour.x,y=hour==null?163:hour.y;
        int color=hour==null?Color.WHITE:hour.argb;
        DialProject.Element box=new DialProject.Element("HOUR",x,y,118,47,color);
        if(!DialEditorGeometry.valid(box)){x=174;y=163;}
        List<DialProject.Element> nodes=new ArrayList<>(project.elements);
        nodes.removeIf(e->DialEditorGeometry.timeNode(e.id));
        nodes.add(new DialProject.Element("HOUR",x,y,55,47,color));
        nodes.add(new DialProject.Element("MIN",x+63,y,55,47,color));
        project=project.with(project.name,project.background,nodes);selectedId="HOUR";activePanel=1;cropMode=false;checkpoint();refresh();
    }
    private void splitTime(){
        DialProject.Element minute=element("MIN"),hour=element("HOUR");
        DialProject.Element separate=minute.at(minute.x+12,minute.y);
        if(!DialEditorGeometry.valid(separate))separate=minute.at(hour.x,hour.y+hour.height+8);
        if(!DialEditorGeometry.valid(separate)){
            replace(hour.at(313,163));separate=minute.at(313,276);
        }
        replace(separate);checkpoint();refresh();
    }
    @Override public void select(String id){if(busy)return;selectedId=DialEditorGeometry.timeNode(id)&&DialEditorGeometry.joinedTime(project)?"HOUR":id;activePanel=1;cropMode=false;checkpoint();refresh();}
    private DialProject.Element element(String id){for(DialProject.Element e:project.elements)if(e.id.equals(id))return e;return null;}
    @Override public void move(String id,int x,int y) {
        if(busy||element(id)==null)return;
        int[] snapped=DialEditorGeometry.snap(project,id,x,y);moveExact(id,snapped[0],snapped[1]);
    }
    private void moveExact(String id,int x,int y) {
        if(busy)return;DialProject.Element old=element(id);if(old==null)return;
        DialProject.Element box=DialEditorGeometry.bounds(project,id);
        if(!DialEditorGeometry.valid(box.at(box.x+x-old.x,box.y+y-old.y))){
            status(tx("Все четыре угла рамки должны оставаться внутри круга"));return;}
        boolean joined=DialEditorGeometry.timeNode(id)&&DialEditorGeometry.joinedTime(project);
        if(joined){DialProject.Element mate=element("HOUR".equals(id)?"MIN":"HOUR");replace(mate.at(mate.x+x-old.x,mate.y+y-old.y));}
        replace(old.at(x,y));refreshCanvas();updateFeedback();
    }
    private void replace(DialProject.Element changed) {
        List<DialProject.Element> nodes=new ArrayList<>(project.elements);
        for(int i=0;i<nodes.size();i++)if(nodes.get(i).id.equals(changed.id)){nodes.set(i,changed);break;}
        project=project.with(project.name,project.background,nodes);
    }
    @Override public void pan(float dx,float dy) {
        if(busy||project.background==null)return;
        DialProject.Background b=project.background;panX-=dx*b.size/466f;panY-=dy*b.size/466f;
        changeCrop(panX,panY,b.size);refreshCanvas();
    }
    private void changeCrop(float x,float y,int size) {
        DialProject.Background b=project.background;
        panX=Math.max(0,Math.min(b.width-size,x));panY=Math.max(0,Math.min(b.height-size,y));
        project=project.with(project.name,b.crop(Math.round(panX),Math.round(panY),size),project.elements);
        renderBackground();refreshCanvas();
    }
    @Override public void gestureFinished(){checkpoint();refresh();}
    private void renderBackground() {
        Bitmap rendered=DialImageNormalizer.renderBackground(source,project.background);
        try{Bitmap quantized=Gt01PgfCodec.quantizedPreview(rendered);Bitmap old=background;background=quantized;
            if(old!=null)old.recycle();}
        catch(java.io.IOException e){status(e.getMessage());}
        finally{rendered.recycle();}
    }
    private void refreshCanvas(){if(canvas!=null){canvas.presentation(activePanel!=2,ui.surface);canvas.bind(project,background,selectedId,cropMode);}}
    private void refresh() {
        if(dialog==null||canvas==null)return;
        if("MIN".equals(selectedId)&&DialEditorGeometry.joinedTime(project))selectedId="HOUR";
        saveButton.setEnabled(!busy&&source!=null&&project.background!=null&&!project.elements.isEmpty());
        backgroundControls.setVisibility(activePanel==0?View.VISIBLE:View.GONE);
        fieldList.setVisibility(activePanel==1?View.VISIBLE:View.GONE);
        selectionControls.setVisibility(activePanel==1?View.VISIBLE:View.GONE);
        reviewControls.setVisibility(activePanel==2?View.VISIBLE:View.GONE);
        for(int i=0;i<3;i++)styleTab(tabs[i],i==activePanel);
        zoom.setEnabled(project.background!=null&&!busy&&cropMode);
        if(project.background!=null){DialProject.Background b=project.background;
            zoom.setProgress(Math.max(0,Math.min(300,Math.round((Math.min(b.width,b.height)/(float)b.size-1)*100))));
            panX=b.x;panY=b.y;}
        fieldList.removeAllViews();
        Button help=button(tx("Как перемещать показатели"));
        help.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);help.setBackgroundColor(Color.TRANSPARENT);
        ui.buttonText(help,ui.muted);help.setOnClickListener(v->{fieldHelpExpanded=!fieldHelpExpanded;refresh();});fieldList.addView(help);
        if(fieldHelpExpanded)fieldList.addView(label(tx("Выберите показатель на циферблате или в списке. При перемещении рамки выравниваются по соседним краям и центрам. Секунды добавляются отдельно.")));
        LinearLayout fieldRow=null;int index=0;
        for(DialProject.Element node:project.elements){
            if("MIN".equals(node.id)&&DialEditorGeometry.joinedTime(project))continue;
            if(index%2==0){fieldRow=new LinearLayout(activity);fieldList.addView(fieldRow,ui.fillWrap(0,8));}
            Button chip=button("HOUR".equals(node.id)&&DialEditorGeometry.joinedTime(project)?tx("Часы + минуты"):fieldLabel(DialRuntimeProfile.field(node.id)));styleField(chip,node.id,node.id.equals(selectedId));
            LinearLayout.LayoutParams tile=ui.weighted(1);tile.height=ui.dp(56);
            if(index%2==0)tile.rightMargin=ui.dp(4);else tile.leftMargin=ui.dp(4);
            chip.setOnClickListener(v->select(node.id));fieldRow.addView(chip,tile);index++;
        }
        if(index%2!=0)fieldRow.addView(new View(activity),ui.weighted(1));
        Button add=button(tx("＋ Добавить показатель"));add.setOnClickListener(v->addField());fieldList.addView(add);
        selectionControls.removeAllViews();DialProject.Element selected=element(selectedId);
        if(selected!=null){
            DialRuntimeProfile.Field f=DialRuntimeProfile.field(selected.id);
            boolean joined=DialEditorGeometry.timeNode(selected.id)&&DialEditorGeometry.joinedTime(project);
            selectionControls.addView(ui.heading(joined?tx("Часы + минуты"):fieldLabel(f)));
            if(joined){Button split=button(tx("Разделить часы и минуты"));split.setOnClickListener(v->splitTime());selectionControls.addView(split);}
            else if(DialEditorGeometry.timeNode(selected.id)){Button join=button(tx("Объединить часы и минуты"));join.setOnClickListener(v->joinTime());selectionControls.addView(join);}
            Button position=button(tx("Положение: X ")+selected.x+" · Y "+selected.y+"");position.setOnClickListener(v->choosePosition(selected.id));selectionControls.addView(position);
            Button color=button(tx("Цвет: ")+String.format(java.util.Locale.US,"#%06X",selected.argb&0xffffff));
            color.setOnClickListener(v->chooseColor(selected.id));selectionControls.addView(color);
            Button remove=button(tx("Убрать показатель"));remove.setOnClickListener(v->{List<DialProject.Element> nodes=new ArrayList<>(project.elements);
                nodes.removeIf(e->e.id.equals(selected.id)||(joined&&DialEditorGeometry.timeNode(e.id)));project=project.with(project.name,project.background,nodes);selectedId=null;checkpoint();refresh();});selectionControls.addView(remove);
        }else selectionControls.setVisibility(View.GONE);
        refreshCanvas();updateFeedback();
    }
    private void choosePosition(String id){
        DialProject.Element selected=element(id);if(selected==null)return;
        LinearLayout row=new LinearLayout(activity);row.setPadding(ui.dp(16),ui.dp(8),ui.dp(16),ui.dp(8));
        EditText x=coordinate("X",selected.x),y=coordinate("Y",selected.y);row.addView(x,ui.weighted(1));row.addView(y,ui.weighted(1));
        AlertDialog position=ui.dialogBuilder().setTitle(tx("Положение в пикселях часов")).setView(ui.scrollForm(row)).setNegativeButton(tx("Отмена"),null).setPositiveButton(tx("Применить"),null).create();
        position.setOnShowListener(d->{ui.dialog(position);position.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{int px=Integer.parseInt(x.getText().toString()),py=Integer.parseInt(y.getText().toString());
                DialProject.Element current=element(id);if(current==null)return;
                if(px<0||py<0||px>466-current.width||py>466-current.height||!current.at(px,py).inCircle()){x.setError(tx("Рамка должна оставаться внутри круга"));return;}
                DialProject.Element box=DialEditorGeometry.bounds(project,id);
                if(!DialEditorGeometry.valid(box.at(box.x+px-current.x,box.y+py-current.y))){x.setError(tx("Весь блок должен оставаться внутри круга"));return;}
                moveExact(id,px,py);checkpoint();refresh();position.dismiss();
            }catch(NumberFormatException e){x.setError(tx("Введите целые X и Y"));}
        });});position.show();
    }
    private EditText coordinate(String label,int value){EditText e=new EditText(activity);e.setTextColor(ui.text);e.setHintTextColor(ui.muted);ui.input(e);e.setHint(label);e.setContentDescription(label+tx(" в пикселях циферблата"));
        e.setSingleLine(true);e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED);e.setText(Integer.toString(value));return e;}
    private void chooseColor(String id) {
        EditText value=new EditText(activity);value.setSingleLine(true);value.setHint("#RRGGBB");
        value.setTextColor(ui.text);value.setHintTextColor(ui.muted);ui.input(value);
        value.setText(String.format(java.util.Locale.US,"#%06X",element(id).argb&0xffffff));
        AlertDialog colors=ui.dialogBuilder().setTitle(tx("Непрозрачный цвет текста")).setView(value)
            .setNegativeButton(tx("Отмена"),null).setPositiveButton(tx("Применить"),null).create();
        colors.setOnShowListener(d->colors.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String hex=value.getText().toString().trim();
            if(!hex.matches("#[0-9a-fA-F]{6}")){value.setError(tx("Введите #RRGGBB"));return;}
            int color=Color.parseColor(hex);boolean joined=DialEditorGeometry.timeNode(id)&&DialEditorGeometry.joinedTime(project);
            if(joined)replace(element("HOUR".equals(id)?"MIN":"HOUR").color(color));
            replace(element(id).color(color));checkpoint();refresh();colors.dismiss();}));colors.show();ui.dialog(colors);
    }
    private void updateFeedback(){
        int overlaps=0;for(int i=0;i<project.elements.size();i++)for(int j=i+1;j<project.elements.size();j++)
            if(project.elements.get(i).overlaps(project.elements.get(j)))overlaps++;
        status((project.background==null?tx("Выберите фотографию. "):"")+tx("Показателей: ")+project.elements.size()+tx(" из 11. ")
            +(overlaps>0?tx("Перекрытий: ")+overlaps+tx(". Оранжевые рамки перекрываются; порядок на часах не гарантирован."):""));
    }
    private void save() {
        if(busy)return;Bitmap preview=null;
        try {project.validate(true);if(source==null)throw new IllegalArgumentException(tx("Фотография ещё не готова"));
            preview=DialEditorView.preview(project,background);setBusy(true);status(tx("Сохранение ревизии…"));
            DialProject snapshot=project;Bitmap image=preview;
            worker.execute(()->{
                try {DialProjectRepository.Revision saved=repository.save(snapshot,image);
                    main.post(()->{if(destroyed)return;project=saved.project;setBusy(false);
                        try{repository.closeDraft();}catch(Exception e){message(e.getMessage());}
                        project=null;if(dialog!=null){dialog.setOnDismissListener(null);dialog.dismiss();dialog=null;}
                        recycleImages();galleryChanged.run();message(tx("Циферблат сохранён в вашей галерее."));});
                }catch(Exception e){main.post(()->{if(!destroyed){setBusy(false);status(e.getMessage());message(e.getMessage());}});}
                finally{image.recycle();}
            });
        }catch(Exception e){if(preview!=null)preview.recycle();message(e.getMessage());}
    }
    void checkpoint() {
        if(project==null||destroyed)return;
        try{repository.saveDraft(project,selectedId,cropMode,pendingUri);}catch(Exception e){status(tx("Черновик не сохранён: ")+e.getMessage());}
    }
    void saveState(Bundle state){checkpoint();state.putBoolean("dialEditor.open",dialog!=null&&dialog.isShowing());state.putInt("dialEditor.panel",activePanel);}
    void destroy() {
        checkpoint();destroyed=true;worker.shutdownNow();
        if(dialog!=null){dialog.setOnDismissListener(null);dialog.dismiss();dialog=null;}
        recycleImages();
    }
    private void recycleImages(){if(background!=null){background.recycle();background=null;}if(source!=null){source.recycle();source=null;}}
    private void setBusy(boolean value){busy=value;if(form!=null)enable(form,!value);
        if(saveButton!=null)saveButton.setEnabled(!value&&project!=null&&source!=null&&project.background!=null&&!project.elements.isEmpty());
        if(zoom!=null)zoom.setEnabled(!value&&cropMode&&project!=null&&project.background!=null);}
    private static void enable(View v,boolean enabled){v.setEnabled(enabled);if(v instanceof ViewGroup){ViewGroup group=(ViewGroup)v;
        for(int i=0;i<group.getChildCount();i++)enable(group.getChildAt(i),enabled);}}
    private TextView label(String text){TextView view=ui.label(text,13,ui.muted);view.setPadding(0,ui.dp(8),0,ui.dp(8));return view;}
    private void styleTab(Button tab,boolean selected){
        ui.buttonText(tab,selected?ui.text:ui.muted);tab.setSelected(selected);
        android.graphics.drawable.GradientDrawable line=new android.graphics.drawable.GradientDrawable();line.setColor(selected?ui.accent:ui.border);
        android.graphics.drawable.LayerDrawable background=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{line});
        background.setLayerHeight(0,ui.dp(selected?2:1));background.setLayerGravity(0,Gravity.BOTTOM);tab.setBackground(background);
    }
    private void styleField(Button tile,String id,boolean selected){
        android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();
        background.setColor(selected?Color.rgb((Color.red(ui.panel)*9+Color.red(ui.accent))/10,(Color.green(ui.panel)*9+Color.green(ui.accent))/10,(Color.blue(ui.panel)*9+Color.blue(ui.accent))/10):ui.panel);
        background.setStroke(ui.dp(1),selected?ui.accent:ui.border);tile.setBackground(background);tile.setSelected(selected);
        tile.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);tile.setPadding(ui.dp(10),0,ui.dp(8),0);tile.setMaxLines(2);
        int icon=switch(id){case "TIME_M_D","TIME_W"->android.R.drawable.ic_menu_today;case "HOUR","MIN","TIME_S","AM_PM"->android.R.drawable.ic_menu_recent_history;case "HEART"->android.R.drawable.ic_menu_myplaces;case "DISTAN","STEPS"->android.R.drawable.ic_menu_directions;case "BATTERY"->android.R.drawable.ic_lock_idle_charging;default->android.R.drawable.ic_menu_compass;};
        android.graphics.drawable.Drawable glyph=activity.getDrawable(icon).mutate();glyph.setTint(selected?ui.accent:ui.muted);glyph.setBounds(0,0,ui.dp(18),ui.dp(18));
        tile.setCompoundDrawables(glyph,null,null,null);tile.setCompoundDrawablePadding(ui.dp(8));
    }
    private String fieldLabel(DialRuntimeProfile.Field field){return "HOUR".equals(field.id)?tx("Часы (HH)"):tx(field.label);}
    private String tx(String text){return UiText.choose("en".equals(new AppPreferences(activity).language()),text);}
    private Button button(String text){Button button=ui.action(text,false,()->{});ui.buttonText(button,ui.text);ui.outline(button);return button;}
    private void status(String text){if(feedback!=null)feedback.setText(text==null?tx("Не удалось выполнить действие"):text);}
    private void message(String text){if(!destroyed)Toast.makeText(activity,text==null?tx("Не удалось выполнить действие"):text,Toast.LENGTH_LONG).show();}
}
