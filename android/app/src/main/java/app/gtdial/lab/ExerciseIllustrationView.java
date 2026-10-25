package app.gtdial.lab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.LruCache;
import android.view.View;
import java.io.IOException;
import java.io.InputStream;

/** Original offline anatomical pairs. A bounded cache shares the exercise atlases across catalog rows. */
final class ExerciseIllustrationView extends View {
    private static final String[][] EXERCISES={
        {"bench_bar","bench_db","incline_bar","incline_db","chest_machine","fly_db","fly_machine","pushup","row_bar","row_db"},
        {"row_cable","pulldown","pullup","pullup_added","pullup_assisted","deadlift","squat_bar","goblet","leg_press","lunge_db"},
        {"romanian_bar","romanian_db","leg_extension","leg_curl","calf_machine","hip_thrust","press_bar","press_db","raise_db","rear_db"},
        {"curl_bar","curl_db","hammer_db","triceps_cable","triceps_db","dip","crunch","leg_raise"},
        {"shoulder_machine","hip_adduction","curl_machine","french_press","back_extension","pullover_machine"},
        {"lateral_cable","row_machine","hip_abduction","walk_timed"}
    };
    private static final LruCache<String,Bitmap> ATLASES=new LruCache<String,Bitmap>(16*1024*1024){
        @Override protected int sizeOf(String key,Bitmap value){return value.getAllocationByteCount();}
    };
    private final UiKit ui;
    private final boolean compact;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Bitmap atlas;
    private final int cell,atlasGroup;
    ExerciseIllustrationView(Context context,UiKit ui,String exercise,boolean compact){
        super(context);this.ui=ui;this.compact=compact;
        String key=exercise==null?"":exercise;boolean generic=false;
        if("overhead_db".equals(key))key="triceps_db";if("dip_chest".equals(key))key="dip";
        // User-created exercises retain an explicitly general illustration chosen by movement family.
        switch(key){case "bench":key="bench_bar";generic=true;break;case "row":key="row_bar";generic=true;break;
            case "squat":key="squat_bar";generic=true;break;case "pull":key="pulldown";generic=true;break;
            case "press":key="press_db";generic=true;break;case "curl":key="curl_db";generic=true;break;
            case "plank":key="pushup";generic=true;break;case "raise":key="raise_db";generic=true;break;default:break;}
        int group=-1,index=-1;
        for(int g=0;g<EXERCISES.length;g++)for(int i=0;i<EXERCISES[g].length;i++)if(EXERCISES[g][i].equals(key)){group=g;index=i;}
        cell=index;atlasGroup=group;Bitmap image=null;
        if(group>=0){String asset="exercise_illustrations/atlas-"+group+".png";image=ATLASES.get(asset);
            if(image==null){BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.RGB_565;
                try(InputStream in=context.getAssets().open(asset)){image=BitmapFactory.decodeStream(in,null,options);
                    if(image!=null&&image.getWidth()>=400&&image.getHeight()>=500&&image.getAllocationByteCount()<=8*1024*1024)ATLASES.put(asset,image);else image=null;
                }catch(IOException unavailable){image=null;}}}
        atlas=image;
        setContentDescription(tx(generic?"Общая иллюстрация движения":"Начальная и конечная позиции; работающие мышцы выделены красным"));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);
        float radius=ui.dp(compact?8:16);RectF bounds=new RectF(0,0,getWidth(),getHeight());canvas.drawRoundRect(bounds,radius,radius,paint);
        if(atlas==null||cell<0){paint.setColor(ui.muted);paint.setTextSize(ui.dp(12));paint.setTextAlign(Paint.Align.CENTER);canvas.drawText(tx("Иллюстрация недоступна"),getWidth()/2f,getHeight()/2f,paint);return;}
        int saved=canvas.save();Path clip=new Path();clip.addRoundRect(bounds,radius,radius,Path.Direction.CW);canvas.clipPath(clip);
        int left=cell%2*atlas.getWidth()/2,right=(cell%2+1)*atlas.getWidth()/2;
        int rows=atlasGroup==4?3:atlasGroup==5?2:5;
        int top=cell/2*atlas.getHeight()/rows,bottom=(cell/2+1)*atlas.getHeight()/rows;
        // The generated six-cell sheet has unequal row separators. Use reviewed boundaries, not a crop through bodies.
        if(atlasGroup==4){float[] edges={0f,469f/1402f,905f/1402f,1f};top=Math.round(edges[cell/2]*atlas.getHeight());bottom=Math.round(edges[cell/2+1]*atlas.getHeight());}
        // Crop the atlas grid lines only; preserve the whole person and apparatus.
        Rect source=new Rect(left+2,top+2,compact?(left+right)/2-1:right-2,bottom-2);
        float available=getHeight()-(compact?0:ui.dp(26));
        float scale=Math.min(getWidth()/(float)source.width(),available/source.height());
        float width=source.width()*scale,height=source.height()*scale;
        canvas.drawBitmap(atlas,source,new RectF((getWidth()-width)/2,(available-height)/2,(getWidth()+width)/2,(available+height)/2),paint);
        if(!compact){paint.setColor(Color.rgb(55,62,65));paint.setTextSize(ui.dp(12));paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(tx("Начало"),getWidth()/4f,getHeight()-ui.dp(9),paint);canvas.drawText(tx("Движение"),getWidth()*3/4f,getHeight()-ui.dp(9),paint);}
        canvas.restoreToCount(saved);
    }
    private String tx(String text){return UiText.choose("en".equals(new AppPreferences(getContext()).language()),text);}
}
