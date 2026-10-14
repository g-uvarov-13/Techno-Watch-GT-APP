package app.gtdial.lab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

final class DialEditorView extends View {
    interface Listener {
        void select(String id);
        void move(String id,int x,int y);
        void pan(float dx,float dy);
        void gestureFinished();
    }
    private DialProject project=DialProject.blank();
    private Bitmap background;
    private String selectedId;
    private boolean cropMode;
    private boolean guides=true;
    private int surface=Color.rgb(35,35,35);
    private Listener listener;
    private float downX,downY,lastX,lastY;
    private DialProject.Element dragging;
    DialEditorView(Context context) {
        super(context);setFocusable(true);setContentDescription(tx("Круглый предпросмотр циферблата. Выберите показатель и перетащите его."));
    }
    void listener(Listener listener) {this.listener=listener;}
    void presentation(boolean guides,int surface){this.guides=guides;this.surface=surface;invalidate();}
    void bind(DialProject project,Bitmap background,String selectedId,boolean cropMode) {
        this.project=project;this.background=background;this.selectedId=selectedId;this.cropMode=cropMode;invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);canvas.drawColor(surface);
        DialCanvasTransform t=new DialCanvasTransform(getWidth(),getHeight());
        canvas.save();canvas.translate(t.left,t.top);canvas.scale(t.scale,t.scale);
        render(canvas,project,background,guides,selectedId);canvas.restore();
    }
    static Bitmap preview(DialProject project,Bitmap background) {
        Bitmap bitmap=Bitmap.createBitmap(466,466,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.BLACK);
        render(canvas,project,background,false,null);return bitmap;
    }
    private static void render(Canvas canvas,DialProject project,Bitmap background,boolean guides,String selection) {
        Paint p=new Paint(3);Path circle=new Path();circle.addCircle(233,233,233,Path.Direction.CW);
        canvas.save();canvas.clipPath(circle);canvas.drawColor(Color.BLACK);
        if(background!=null)canvas.drawBitmap(background,0,0,p);p.setTypeface(Typeface.DEFAULT);
        for(DialProject.Element e:project.elements) {
            DialRuntimeProfile.Field f=DialRuntimeProfile.field(e.id);
            p.setStyle(Paint.Style.FILL);p.setColor(e.argb);p.setTextSize(f.fontPixels);
            // Android approximation of donor alignment. Exact raw PLB property stays in the profile.
            int align=f.align&7;
            p.setTextAlign(align==2?Paint.Align.RIGHT:align==4?Paint.Align.CENTER:Paint.Align.LEFT);
            float textX=align==2?e.x+e.width:align==4?e.x+e.width/2f:e.x;
            Paint.FontMetrics metrics=p.getFontMetrics();
            canvas.save();canvas.clipRect(e.x,e.y,e.x+e.width,e.y+e.height);
            canvas.drawText(f.example,textX,e.y+e.height/2f-(metrics.ascent+metrics.descent)/2,p);canvas.restore();
            if(guides) {
                boolean overlap=false;for(DialProject.Element other:project.elements)
                    if(other!=e&&e.overlaps(other)){overlap=true;break;}
                boolean joined=DialEditorGeometry.timeNode(e.id)&&DialEditorGeometry.joinedTime(project);
                boolean selected=e.id.equals(selection)||(joined&&DialEditorGeometry.timeNode(selection));
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(selected?2:1);
                p.setColor(overlap?Color.rgb(255,170,40):selected?Color.CYAN:0x88999999);
                if(!joined||"HOUR".equals(e.id)){
                    DialProject.Element box=DialEditorGeometry.bounds(project,e.id);
                    canvas.drawRect(box.x,box.y,box.x+box.width,box.y+box.height,p);
                }
            }
        }
        canvas.restore();
        if(guides){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(Color.LTGRAY);canvas.drawCircle(233,233,232.5f,p);}
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if(listener==null||!guides)return false;DialCanvasTransform t=new DialCanvasTransform(getWidth(),getHeight());
        float x=t.x(event.getX()),y=t.y(event.getY());
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if((x-233)*(x-233)+(y-233)*(y-233)>233*233)return false;
                downX=lastX=x;downY=lastY=y;dragging=null;
                if(!cropMode)for(int i=project.elements.size()-1;i>=0;i--){DialProject.Element e=project.elements.get(i);
                    DialProject.Element box=DialEditorGeometry.bounds(project,e.id);
                    if(x>=box.x&&x<=box.x+box.width&&y>=box.y&&y<=box.y+box.height){dragging=e;listener.select(e.id);break;}}
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);return true;
            case MotionEvent.ACTION_MOVE:
                if(cropMode)listener.pan(x-lastX,y-lastY);
                else if(dragging!=null)listener.move(dragging.id,Math.round(dragging.x+x-downX),Math.round(dragging.y+y-downY));
                lastX=x;lastY=y;return true;
            case MotionEvent.ACTION_UP:
                listener.gestureFinished();performClick();dragging=null;
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);return true;
            case MotionEvent.ACTION_CANCEL:
                listener.gestureFinished();dragging=null;
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);return true;
            default:return true;
        }
    }
    @Override public boolean performClick(){super.performClick();return true;}
    private String tx(String text){return UiText.choose("en".equals(new AppPreferences(getContext()).language()),text);}

}
