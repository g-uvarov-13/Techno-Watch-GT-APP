package app.gtdial.lab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import java.io.InputStream;
import java.io.IOException;

/** Shared original anatomy atlas; labels and interaction belong to the parent card. */
final class MuscleGroupIllustrationView extends View {
    private static Bitmap atlas;
    private static boolean attempted;
    private static final String[] GROUPS={"Грудь","Спина","Ноги","Плечи","Руки","Корпус","Ходьба","Другие"};
    // Reviewed whitespace boundaries of the generated sheet, preserving heads and hands.
    private static final float[] ROWS={0f,.245f,.480f,.718f,1f};
    private final int cell;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Rect source=new Rect();
    private final RectF destination=new RectF();
    public MuscleGroupIllustrationView(Context context){this(context,"Другие");}
    MuscleGroupIllustrationView(Context context,String group){
        super(context);int index=7;for(int i=0;i<GROUPS.length;i++)if(GROUPS[i].equals(group))index=i;cell=index;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        synchronized(MuscleGroupIllustrationView.class){if(!attempted){attempted=true;
            BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.RGB_565;
            try(InputStream in=context.getAssets().open("exercise_illustrations/muscle-groups-v1.png")){
                Bitmap image=BitmapFactory.decodeStream(in,null,options);
                if(image!=null&&image.getWidth()>=400&&image.getHeight()>=800&&image.getAllocationByteCount()<=8*1024*1024)atlas=image;
            }catch(IOException unavailable){/* The card's text remains usable if the image cannot be loaded. */}
        }}
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);canvas.drawColor(Color.WHITE);if(atlas==null)return;
        int column=cell%2,row=cell/2;
        source.set(column*atlas.getWidth()/2,Math.round(ROWS[row]*atlas.getHeight()),(column+1)*atlas.getWidth()/2,Math.round(ROWS[row+1]*atlas.getHeight()));
        float inset=6*getResources().getDisplayMetrics().density;
        float scale=Math.min((getWidth()-2*inset)/source.width(),(getHeight()-2*inset)/source.height());
        float width=source.width()*scale,height=source.height()*scale;
        destination.set((getWidth()-width)/2,(getHeight()-height)/2,(getWidth()+width)/2,(getHeight()+height)/2);
        canvas.drawBitmap(atlas,source,destination,paint);
    }
}
