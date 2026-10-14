package app.gtdial.lab;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Actual cumulative observations; unknown intervals and counter resets remain gaps. */
final class ActivityHistoryChartView extends View {
    private final List<ActivityHistoryEntry> entries;
    private final String metric;
    private final UiKit ui;
    private final Consumer<ActivityHistoryEntry> selection;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final long first,last;
    private final double maximum;
    private int selected=-1;
    ActivityHistoryChartView(Context context,List<ActivityHistoryEntry> entries,String metric,UiKit ui,Consumer<ActivityHistoryEntry> selection){
        super(context);this.entries=new ArrayList<>(entries);this.metric=metric;this.ui=ui;this.selection=selection;
        first=entries.get(0).receivedAt;last=entries.get(entries.size()-1).receivedAt;
        double max=1;for(ActivityHistoryEntry e:entries)max=Math.max(max,value(e,metric));maximum=max;
        setBackgroundColor(ui.panel);setContentDescription("steps".equals(metric)?"График наблюдений шагов":"График исходных значений: "+metric);
        selected=entries.size()-1;
        setOnClickListener(v->selection.accept(this.entries.get(selected)));
        selection.accept(this.entries.get(selected));
    }
    static int value(ActivityHistoryEntry e,String metric){return "steps".equals(metric)?e.steps:"calorie".equals(metric)?e.calorieRaw:e.distanceRaw;}
    private float left(){paint.setTextSize(sp(12));return Math.max(ui.dp(42),paint.measureText(Long.toString(Math.round(maximum)))+ui.dp(12));}private float right(){return Math.max(left()+1,getWidth()-ui.dp(14));}
    private float sp(int value){return value*getResources().getDisplayMetrics().scaledDensity;}
    private float x(ActivityHistoryEntry e){return last==first?(left()+right())/2:left()+(float)((e.receivedAt-first)/(double)(last-first))*(right()-left());}
    private float y(ActivityHistoryEntry e){return getHeight()-ui.dp(40)-(float)(value(e,metric)/maximum)*(getHeight()-ui.dp(62));}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);paint.setTextSize(sp(12));paint.setStrokeWidth(ui.dp(1));
        android.icu.text.CompactDecimalFormat numbers=android.icu.text.CompactDecimalFormat.getInstance(java.util.Locale.getDefault(),android.icu.text.CompactDecimalFormat.CompactStyle.SHORT);
        for(int i=0;i<3;i++){float y=ui.dp(22)+i*(getHeight()-ui.dp(62))/2f;paint.setColor(ui.border);canvas.drawLine(left(),y,right(),y,paint);paint.setColor(ui.muted);paint.setTextAlign(Paint.Align.RIGHT);canvas.drawText(numbers.format(Math.round(maximum*(1-i/2d))),left()-ui.dp(6),y+ui.dp(4),paint);}
        DateTimeFormatter time=DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of(entries.get(0).phoneZoneId));paint.setColor(ui.muted);paint.setTextAlign(Paint.Align.LEFT);canvas.drawText(time.format(Instant.ofEpochMilli(first)),left(),getHeight()-ui.dp(16),paint);if(last!=first){paint.setTextAlign(Paint.Align.RIGHT);canvas.drawText(time.format(Instant.ofEpochMilli(last)),right(),getHeight()-ui.dp(16),paint);}
        paint.setColor(ui.accentInk);paint.setStrokeWidth(ui.dp(2));
        ActivityHistoryEntry previous=null;
        for(ActivityHistoryEntry e:entries){if(previous!=null&&e.receivedAt-previous.receivedAt<=90_000&&value(e,metric)>=value(previous,metric))canvas.drawLine(x(previous),y(previous),x(e),y(e),paint);canvas.drawCircle(x(e),y(e),ui.dp(2),paint);previous=e;}
        if(selected>=0){ActivityHistoryEntry e=entries.get(selected);paint.setColor(ui.text);canvas.drawCircle(x(e),y(e),ui.dp(5),paint);}
    }
    @Override public boolean onTouchEvent(MotionEvent event){if(event.getAction()!=MotionEvent.ACTION_UP)return true;float distance=ui.dp(24)*ui.dp(24);int best=-1;for(int i=0;i<entries.size();i++){float dx=x(entries.get(i))-event.getX(),dy=y(entries.get(i))-event.getY(),d=dx*dx+dy*dy;if(d<distance){distance=d;best=i;}}if(best>=0){selected=best;invalidate();performClick();}return true;}
    @Override public boolean performClick(){super.performClick();return true;}
}
