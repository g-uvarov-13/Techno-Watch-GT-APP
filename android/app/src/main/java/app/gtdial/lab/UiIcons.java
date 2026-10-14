package app.gtdial.lab;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Original native 24-unit line drawings; no downloaded icon assets or font glyphs. */
final class UiIcons {
    enum Kind { ACTIVITY, DUMBBELL, FOOD, WATCH, SETTINGS, USER, TARGET, SHIELD,
        BELL, ALARM, WEATHER, PALETTE, SPARKLES, CHART, HISTORY, MOON, HEART,
        DROPLET, FLAME, STEPS, SCALE, CAMERA, PLUS, CHECK, CHEVRON, DOWN, UP,
        CALENDAR, EDIT, INFO }

    private UiIcons() {}

    static Drawable drawable(Kind kind, int color) {
        return new Drawable() {
            final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            { paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f);
                paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND); }
            @Override public void draw(Canvas canvas) {
                Rect bounds=getBounds();int saved=canvas.save();
                float size=Math.min(bounds.width(),bounds.height());
                canvas.translate(bounds.exactCenterX()-size/2,bounds.exactCenterY()-size/2);
                canvas.scale(size/24f,size/24f);drawIcon(canvas,paint,kind);canvas.restoreToCount(saved);
            }
            @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
            @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
            @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
        };
    }

    private static void lines(Canvas canvas,Paint paint,float... coordinates) {
        Path path=new Path();path.moveTo(coordinates[0],coordinates[1]);
        for(int i=2;i<coordinates.length;i+=2)path.lineTo(coordinates[i],coordinates[i+1]);
        canvas.drawPath(path,paint);
    }

    private static void drawIcon(Canvas c,Paint p,Kind kind) {
        Path path=new Path();
        switch(kind){
            case ACTIVITY:lines(c,p,2,12,6,12,9,3,13,21,16,12,22,12);break;
            case DUMBBELL:
                int saved=c.save();c.rotate(-38,12,12);
                c.drawRoundRect(3,7,6,17,1,1,p);c.drawRoundRect(18,7,21,17,1,1,p);
                c.drawRoundRect(6,5,9,19,1,1,p);c.drawRoundRect(15,5,18,19,1,1,p);
                c.drawLine(9,12,15,12,p);c.restoreToCount(saved);break;
            case FOOD:
                lines(c,p,4,3,4,8,6,10,8,8,8,3);c.drawLine(6,3,6,21,p);c.drawLine(18,3,18,21,p);
                path.moveTo(18,3);path.quadTo(13,5,14,12);path.lineTo(18,12);c.drawPath(path,p);break;
            case WATCH:
                c.drawRoundRect(6,6,18,18,4,4,p);lines(c,p,8,6,9,2,15,2,16,6);
                lines(c,p,8,18,9,22,15,22,16,18);lines(c,p,12,9,12,12,15,13);break;
            case SETTINGS:
                c.drawLine(3,7,8,7,p);c.drawCircle(11,7,3,p);c.drawLine(14,7,21,7,p);
                c.drawLine(3,17,12,17,p);c.drawCircle(15,17,3,p);c.drawLine(18,17,21,17,p);break;
            case USER:
                c.drawCircle(12,7,4,p);c.drawArc(4,13,20,28,180,180,false,p);break;
            case TARGET:
                c.drawCircle(12,12,9,p);c.drawCircle(12,12,5,p);c.drawCircle(12,12,1,p);break;
            case SHIELD:
                path.moveTo(12,2);path.lineTo(21,6);path.lineTo(20,14);path.quadTo(18,19,12,22);
                path.quadTo(6,19,4,14);path.lineTo(3,6);path.close();c.drawPath(path,p);lines(c,p,8,12,11,15,16,9);break;
            case BELL:
                path.moveTo(5,17);path.quadTo(7,14,7,8);path.quadTo(7,3,12,3);path.quadTo(17,3,17,8);
                path.quadTo(17,14,19,17);path.close();c.drawPath(path,p);c.drawArc(10,18,14,22,0,180,false,p);break;
            case ALARM:
                c.drawCircle(12,13,8,p);lines(c,p,12,8,12,13,16,15);c.drawLine(3,5,6,2,p);
                c.drawLine(18,2,21,5,p);c.drawLine(5,21,7,19,p);c.drawLine(17,19,19,21,p);break;
            case WEATHER:
                c.drawCircle(8,7,3,p);c.drawLine(8,1,8,2,p);c.drawLine(2,7,3,7,p);c.drawLine(3,2,4,3,p);c.drawLine(12,3,13,2,p);
                path.moveTo(6,19);path.cubicTo(1,19,2,13,6,13);path.cubicTo(6,7,16,7,17,13);
                path.cubicTo(23,12,23,19,18,19);path.close();c.drawPath(path,p);break;
            case PALETTE:
                path.moveTo(13,3);path.cubicTo(2,1,0,19,10,21);path.cubicTo(16,23,14,16,17,16);
                path.cubicTo(25,16,23,4,13,3);c.drawPath(path,p);
                for(float[] dot:new float[][]{{7,8},{11,6},{16,7},{18,11}})c.drawCircle(dot[0],dot[1],.7f,p);break;
            case SPARKLES:
                lines(c,p,11,3,13,9,19,11,13,13,11,19,9,13,3,11,9,9,11,3);
                lines(c,p,20,2,20,6);lines(c,p,18,4,22,4);lines(c,p,20,18,20,22);lines(c,p,18,20,22,20);break;
            case CHART:
                lines(c,p,3,3,3,21,21,21);lines(c,p,6,15,10,11,14,13,20,5);
                c.drawLine(7,18,7,20,p);c.drawLine(12,16,12,20,p);c.drawLine(17,12,17,20,p);break;
            case HISTORY:
                c.drawArc(3,3,21,21,-135,290,false,p);lines(c,p,3,3,3,8,8,8);lines(c,p,12,7,12,12,16,14);break;
            case MOON:
                path.moveTo(20,15);path.cubicTo(9,19,5,10,9,3);path.cubicTo(0,6,1,20,12,21);
                path.quadTo(18,21,20,15);c.drawPath(path,p);break;
            case HEART:
                path.moveTo(12,21);path.cubicTo(8,17,2,13,2,8);path.cubicTo(2,2,9,1,12,6);
                path.cubicTo(15,1,22,2,22,8);path.cubicTo(22,13,16,17,12,21);c.drawPath(path,p);break;
            case DROPLET:
                path.moveTo(12,2);path.cubicTo(10,7,5,11,5,15);path.cubicTo(5,24,19,24,19,15);
                path.cubicTo(19,11,14,7,12,2);c.drawPath(path,p);break;
            case FLAME:
                path.moveTo(12,2);path.cubicTo(14,8,9,9,10,13);path.quadTo(16,10,16,7);
                path.cubicTo(24,16,16,25,8,20);path.cubicTo(2,17,5,11,8,8);path.quadTo(7,14,12,2);c.drawPath(path,p);break;
            case STEPS:
                c.drawOval(4,10,10,20,p);c.drawOval(14,4,20,14,p);c.drawCircle(6,6,1,p);c.drawCircle(18,18,1,p);break;
            case SCALE:
                c.drawRoundRect(3,3,21,21,4,4,p);c.drawArc(7,6,17,15,180,180,false,p);c.drawLine(12,11,14,7,p);break;
            case CAMERA:
                lines(c,p,3,6,7,6,9,3,15,3,17,6,21,6,21,20,3,20,3,6);c.drawCircle(12,13,4,p);break;
            case PLUS:lines(c,p,12,4,12,20);lines(c,p,4,12,20,12);break;
            case CHECK:lines(c,p,4,12,9,17,20,6);break;
            case CHEVRON:lines(c,p,9,5,16,12,9,19);break;
            case DOWN:lines(c,p,5,9,12,16,19,9);break;
            case UP:lines(c,p,5,15,12,8,19,15);break;
            case CALENDAR:
                c.drawRoundRect(3,5,21,21,2,2,p);c.drawLine(3,10,21,10,p);c.drawLine(8,2,8,7,p);c.drawLine(16,2,16,7,p);
                for(int y=14;y<=18;y+=4)for(int x=7;x<=17;x+=5)c.drawCircle(x,y,.5f,p);break;
            case EDIT:lines(c,p,4,20,5,14,16,3,21,8,10,19,4,20);c.drawLine(13,6,18,11,p);break;
            case INFO:c.drawCircle(12,12,9,p);c.drawCircle(12,7,.6f,p);c.drawLine(12,11,12,17,p);break;
        }
    }
}
