package app.gtdial.lab;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Lightweight render-spec preview: it keeps paints and text only, never decodes a bitmap per frame. */
final class WatchFacePreviewView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private String title="WATCH FACE", time="16:25";
    private int accent=Color.rgb(42,255,166);
    WatchFacePreviewView(Context context){super(context);paint.setTypeface(android.graphics.Typeface.MONOSPACE);setBackgroundColor(Color.BLACK);}
    void setPaletteColor(int color){accent=color;invalidate();}
    void setTitle(String value){title=value==null?"WATCH FACE":value;invalidate();}
    @Override protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight();paint.setColor(accent);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(Math.min(w,h)*.10f);c.drawText(time,w*.5f,h*.53f,paint);paint.setTextSize(Math.min(w,h)*.045f);c.drawText(title,w*.5f,h*.72f,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);c.drawCircle(w*.5f,h*.5f,Math.min(w,h)*.42f,paint);paint.setStyle(Paint.Style.FILL);}
}
