package app.gtdial.lab;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** The compact chart belongs to its card; detail charts expose actual points and gaps. */
final class HealthChartView extends View {
    enum Style { POINTS, LINE, BARS, SLEEP, DAILY_RANGE }
    static final long HEART_GAP_MS = 30L * 60 * 1000;
    static final class Point {
        final long time, end;
        final double value;
        final String label;
        final int rawStage;
        final boolean breakBefore;
        Point(long time, long end, double value, String label, int rawStage, boolean breakBefore) {
            this.time = time; this.end = end; this.value = value; this.label = label;
            this.rawStage = rawStage; this.breakBefore = breakBefore;
        }
    }
    private final UiKit ui;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Point> points = new ArrayList<>();
    private final Style style;
    private final long from, to;
    private final String unit;
    private final boolean compact;
    private int selected = -1;
    private Consumer<String> selection;
    private Consumer<Long> daySelection;
    private final java.time.ZoneId zone = java.time.ZoneId.systemDefault();
    private final java.util.Map<java.time.LocalDate, List<Point>> days = new java.util.LinkedHashMap<>();
    private double minimum, maximum;
    private float plotLeft, plotTop, plotBottom;

    HealthChartView(Context context, UiKit ui, Style style, long from, long to, String unit, List<Point> values) {
        this(context, ui, style, from, to, unit, values, false);
    }
    HealthChartView(Context context, UiKit ui, Style style, long from, long to, String unit, List<Point> values, boolean compact) {
        super(context); this.ui = ui; this.style = style; this.from = from;
        this.to = Math.max(from + 1, to); this.unit = unit; this.compact = compact;
        for (Point point : values) if (Double.isFinite(point.value) && point.time < this.to && point.end >= from) points.add(point);
        for (Point point : points) days.computeIfAbsent(java.time.Instant.ofEpochMilli(point.time).atZone(zone).toLocalDate(), ignored -> new ArrayList<>()).add(point);
        selected = points.isEmpty() ? -1 : points.size() - 1;
        setMinimumHeight(ui.dp(compact ? 60 : style == Style.SLEEP ? 100 : 200));
        setFocusable(!compact); setClickable(!compact);
        setImportantForAccessibility(compact ? IMPORTANT_FOR_ACCESSIBILITY_NO : IMPORTANT_FOR_ACCESSIBILITY_YES);
        if (!compact) setContentDescription(table());
    }
    void onSelection(Consumer<String> listener) { selection = listener; if (selected >= 0) listener.accept(points.get(selected).label); }
    void onDaySelection(Consumer<Long> listener) { daySelection = listener; }
    String table() {
        SimpleDateFormat date = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());
        StringBuilder out = new StringBuilder(unit + " · " + date.format(from) + " – " + date.format(to - 1));
        for (Point p : points) out.append('\n').append(p.label);
        if (points.isEmpty()) out.append("\n"+tx("Нет записей за этот период"));
        return out.toString();
    }
    private float left() { return compact ? ui.dp(4) : plotLeft; }
    private float right() { return Math.max(left() + 1, getWidth() - ui.dp(compact ? 4 : 12)); }
    private float x(long time) { return left() + (right() - left()) * (float) ((double) (Math.max(from, Math.min(to, time)) - from) / (to - from)); }
    private float y(double value) { return plotBottom - (float)((value - minimum) / (maximum - minimum)) * (plotBottom - plotTop); }
    private float sp(int value) { return value * getResources().getDisplayMetrics().scaledDensity; }
    private long dayMiddle(java.time.LocalDate day) { long a=day.atStartOfDay(zone).toInstant().toEpochMilli(),b=day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();return a+(b-a)/2; }
    private void geometry() {
        minimum=Double.POSITIVE_INFINITY;maximum=Double.NEGATIVE_INFINITY;
        for(Point point:points){minimum=Math.min(minimum,point.value);maximum=Math.max(maximum,point.value);}
        if(points.isEmpty()){minimum=0;maximum=1;}
        if(style==Style.BARS)minimum=0;
        if(maximum==minimum){if(style==Style.BARS)maximum=minimum+1;else{minimum-=1;maximum+=1;}}
        else if(style!=Style.BARS&&style!=Style.SLEEP){double pad=(maximum-minimum)*.12;minimum-=pad;maximum+=pad;}
        paint.setTextSize(sp(11));
        plotLeft=compact?ui.dp(4):style==Style.SLEEP?ui.dp(8):Math.max(ui.dp(36),Math.max(paint.measureText(format(minimum)),paint.measureText(format(maximum)))+ui.dp(10));
        plotTop=ui.dp(compact?6:style==Style.SLEEP?12:24);
        plotBottom=Math.max(plotTop+1,getHeight()-Math.max(ui.dp(compact?6:32),compact?0:sp(11)+ui.dp(12)));
    }
    private void axes(Canvas canvas) {
        paint.setTextSize(sp(11));paint.setStrokeWidth(ui.dp(1));paint.setTextAlign(Paint.Align.LEFT);
        if(style!=Style.SLEEP){
            for(int i=0;i<4;i++){float py=plotTop+(plotBottom-plotTop)*i/3;paint.setColor(ui.border);canvas.drawLine(left(),py,right(),py,paint);paint.setColor(ui.muted);String label=format(maximum-(maximum-minimum)*i/3);canvas.drawText(label,left()-ui.dp(6)-paint.measureText(label),py+sp(4),paint);}
            paint.setColor(ui.muted);canvas.drawText(unit,left(),sp(12),paint);
        }
        java.time.LocalDate start=java.time.Instant.ofEpochMilli(from).atZone(zone).toLocalDate(),end=java.time.Instant.ofEpochMilli(to-1).atZone(zone).toLocalDate();
        long count=java.time.temporal.ChronoUnit.DAYS.between(start,end)+1;
        paint.setColor(ui.muted);
        if(style==Style.DAILY_RANGE||style==Style.BARS&&count>1){
            int stride=count<=7?1:(int)Math.ceil((count-1)/4d);
            java.time.format.DateTimeFormatter fmt=java.time.format.DateTimeFormatter.ofPattern(count<=7?"EEE":"d MMM",Locale.getDefault());
            float priorEnd=-Float.MAX_VALUE;
            for(int i=0;i<count;i++){if(i%stride!=0&&i!=count-1)continue;String label=start.plusDays(i).format(fmt);float width=paint.measureText(label),px=x(dayMiddle(start.plusDays(i)));float at=Math.max(left(),Math.min(right()-width,px-width/2));if(at<priorEnd+ui.dp(3))continue;canvas.drawText(label,at,getHeight()-ui.dp(8),paint);priorEnd=at+width;}
        } else {
            SimpleDateFormat fmt=new SimpleDateFormat(count>1&&style!=Style.SLEEP?"dd.MM":"HH:mm",Locale.getDefault());
            int ticks=style==Style.SLEEP?2:5;float previousEnd=-Float.MAX_VALUE;
            for(int i=0;i<ticks;i++){long time=from+(to-from-1)*i/(ticks-1);String label=fmt.format(time);float width=paint.measureText(label),at=Math.max(left(),Math.min(right()-width,x(time)-width/2));if(at<previousEnd+ui.dp(3))continue;canvas.drawText(label,at,getHeight()-ui.dp(8),paint);previousEnd=at+width;}
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);canvas.drawColor(ui.panel);geometry();
        if(!compact)axes(canvas);
        if(points.isEmpty()){
            if(!compact){paint.setColor(ui.muted);paint.setTextSize(sp(13));String empty=tx("Нет записей за этот период");canvas.drawText(empty,Math.max(ui.dp(2),(getWidth()-paint.measureText(empty))/2),(plotTop+plotBottom)/2,paint);}return;
        }
        if(style==Style.DAILY_RANGE){
            // A day range describes only observed extrema, never continuous coverage.
            for(java.util.Map.Entry<java.time.LocalDate,List<Point>> entry:days.entrySet()){
                double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY;
                for(Point point:entry.getValue()){low=Math.min(low,point.value);high=Math.max(high,point.value);}
                float px=x(dayMiddle(entry.getKey()));paint.setColor(ui.accentInk);paint.setStrokeWidth(ui.dp(6));paint.setStrokeCap(Paint.Cap.ROUND);
                if(low==high)canvas.drawCircle(px,y(low),ui.dp(3),paint);else canvas.drawLine(px,y(low),px,y(high),paint);
            }
            paint.setStrokeCap(Paint.Cap.BUTT);return;
        }
        Point previous=null;float previousY=0;
        int saved=canvas.save();canvas.clipRect(left()-ui.dp(4),plotTop-ui.dp(5),right()+ui.dp(4),plotBottom+ui.dp(5));
        boolean singleSleep=style==Style.SLEEP&&points.stream().map(point->point.rawStage).distinct().count()==1;
        if(style==Style.SLEEP&&!singleSleep){paint.setColor(ui.border);paint.setStrokeWidth(ui.dp(1));for(int lane=0;lane<3;lane++){float py=plotTop+(plotBottom-plotTop)*(lane+.5f)/3;canvas.drawLine(left(),py,right(),py,paint);}}
        for(int i=0;i<points.size();i++){
            Point point=points.get(i);float px=x(point.time),py=y(point.value);
            paint.setColor(ui.accentInk);
            if(style==Style.SLEEP){
                paint.setColor(stageColor(point.rawStage));float lane=singleSleep?(plotTop+plotBottom)/2:sleepY(point.rawStage,plotTop,plotBottom),half=ui.dp(compact?4:singleSleep?14:8);
                canvas.drawRoundRect(px,lane-half,Math.max(px+ui.dp(1),x(point.end)),lane+half,ui.dp(4),ui.dp(4),paint);
            }else if(style==Style.BARS){
                canvas.drawRoundRect(px+ui.dp(2),py,Math.max(px+ui.dp(3),x(point.end)-ui.dp(2)),plotBottom,ui.dp(3),ui.dp(3),paint);
            }else{
                if(style==Style.LINE&&previous!=null&&!point.breakBefore&&point.time-previous.time<=HEART_GAP_MS){paint.setStrokeWidth(ui.dp(2));canvas.drawLine(x(previous.time),previousY,px,py,paint);}
                canvas.drawCircle(px,py,ui.dp(compact?2:3),paint);
                if(!compact&&selected==i){paint.setColor(ui.panel);canvas.drawCircle(px,py,ui.dp(5),paint);paint.setColor(ui.accentInk);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(ui.dp(2));canvas.drawCircle(px,py,ui.dp(5),paint);paint.setStyle(Paint.Style.FILL);}
            }
            previous=point;previousY=py;
        }
        canvas.restoreToCount(saved);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if(compact)return false;
        if(event.getAction()!=MotionEvent.ACTION_UP)return true;
        geometry();float touchX=event.getX(),touchY=event.getY();
        if(style==Style.DAILY_RANGE){
            for(java.util.Map.Entry<java.time.LocalDate,List<Point>> entry:days.entrySet()){
                long start=entry.getKey().atStartOfDay(zone).toInstant().toEpochMilli(),end=entry.getKey().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
                if(touchX>=x(start)&&touchX<=x(end)&&touchY>=plotTop-ui.dp(16)&&touchY<=plotBottom+ui.dp(16)){
                    if(daySelection!=null)daySelection.accept(start);performClick();return true;
                }
            }
        }else{
            int best=-1;float nearest=ui.dp(24)*ui.dp(24);
            boolean singleSleep=style==Style.SLEEP&&points.stream().map(p->p.rawStage).distinct().count()==1;
            for(int i=0;i<points.size();i++){
                Point point=points.get(i);float dx=x(point.time)-touchX,dy=y(point.value)-touchY;
                if(style==Style.SLEEP){float lane=singleSleep?(plotTop+plotBottom)/2:sleepY(point.rawStage,plotTop,plotBottom);if(touchX<x(point.time)||touchX>x(point.end)||Math.abs(touchY-lane)>ui.dp(singleSleep?20:16))continue;dx=0;dy=lane-touchY;}
                else if(style==Style.BARS){if(touchX<x(point.time)||touchX>x(point.end)||touchY<y(point.value)||touchY>plotBottom)continue;dx=dy=0;}
                float distance=dx*dx+dy*dy;if(distance<=nearest){nearest=distance;best=i;}
            }
            selected=best;String label=best<0?tx("Нет записей в этом интервале"):points.get(best).label;
            if(selection!=null)selection.accept(label);announceForAccessibility(label);invalidate();
        }
        performClick();return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    private int stageColor(int code) {return stageColor(ui,code);}
    private static boolean knownStage(int code){return code>=0&&code<=3;}
    private static float sleepY(int code,float top,float bottom){return top+(bottom-top)*(code==0?.18f:code==1||code==2?.5f:code==3?.82f:.5f);}
    static int stageColor(UiKit ui,int code) { return code == 0 ? (ui.surface==Color.rgb(232,239,242)?Color.rgb(184,85,24):Color.rgb(244,135,71)) : code == 1 || code == 2 ? Color.rgb(61,149,242) : code == 3 ? Color.rgb(104,91,230) : Color.rgb(188,147,64); }
    private String tx(String text){return UiText.choose("en".equals(new AppPreferences(getContext()).language()),text);}
    private static String format(double value) { return value == Math.rint(value) ? Long.toString(Math.round(value)) : String.format(Locale.getDefault(), "%.1f", value); }
}
