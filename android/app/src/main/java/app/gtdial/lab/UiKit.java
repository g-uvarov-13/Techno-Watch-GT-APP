package app.gtdial.lab;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small, dependency-free visual system shared by the programmatic activity UI. */
final class UiKit {
    final int surface;
    final int panel;
    final int text;
    final int muted;
    final int border;
    final int accent;
    final int accentText;
    final int accentInk;
    final int tint;
    final int soft;
    final int actionFill;
    final int input;
    private final Context context;
    private final String theme;

    UiKit(Context context, String theme) {
        this.context = context;this.theme=theme;
        if ("midnight-blue".equals(theme)) {
            surface = Color.rgb(6, 13, 24); panel = Color.rgb(11, 24, 41);
            text = Color.rgb(238, 245, 255); muted = Color.rgb(143, 165, 190);
            border = Color.rgb(43, 65, 91); accent = Color.rgb(231, 62, 77); input = Color.rgb(16, 32, 51);
        } else if ("arctic".equals(theme)) {
            surface = Color.rgb(232, 239, 242); panel = Color.rgb(246, 249, 250);
            text = Color.rgb(21, 34, 40); muted = Color.rgb(80, 100, 108);
            border = Color.rgb(182, 197, 202); accent = Color.rgb(190, 38, 53); input = Color.rgb(255, 255, 255);
        } else if ("olive".equals(theme)) {
            surface = Color.rgb(20, 24, 16); panel = Color.rgb(29, 35, 23);
            text = Color.rgb(238, 241, 224); muted = Color.rgb(172, 183, 151);
            border = Color.rgb(72, 84, 57); accent = Color.rgb(225, 67, 75); input = Color.rgb(39, 47, 30);
        } else {
            surface = Color.rgb(14, 15, 18); panel = Color.rgb(23, 25, 30);
            text = Color.rgb(245, 246, 248); muted = Color.rgb(169, 174, 184);
            border = Color.rgb(61, 65, 75); accent = Color.rgb(229, 57, 68); input = Color.rgb(31, 34, 40);
        }
        accentInk="arctic".equals(theme)?Color.rgb(182,46,66):Color.rgb(238,105,122);
        soft="arctic".equals(theme)?Color.rgb(233,236,240):"midnight-blue".equals(theme)?Color.rgb(21,40,60):"olive".equals(theme)?Color.rgb(44,52,36):Color.rgb(37,40,46);
        tint="arctic".equals(theme)?Color.rgb(247,231,234):"midnight-blue".equals(theme)?Color.rgb(47,29,41):"olive".equals(theme)?Color.rgb(51,34,32):Color.rgb(52,32,39);
        actionFill="midnight-blue".equals(theme)?Color.rgb(174,49,78):"olive".equals(theme)?Color.rgb(163,57,49):"arctic".equals(theme)?Color.rgb(169,38,57):Color.rgb(182,50,69);
        double luminance=.2126*linear(Color.red(actionFill))+.7152*linear(Color.green(actionFill))+.0722*linear(Color.blue(actionFill));
        accentText=1.05/(luminance+.05)>=4.5?Color.WHITE:Color.BLACK;
    }
    private static double linear(int channel){double value=channel/255.0;return value<=.04045?value/12.92:Math.pow((value+.055)/1.055,2.4);}

    int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    android.widget.ScrollView scrollForm(View content) {
        android.widget.ScrollView scroll=new android.widget.ScrollView(context);scroll.setFillViewport(false);scroll.addView(content);return scroll;
    }

    LinearLayout.LayoutParams fillWrap(int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(top), 0, dp(bottom));
        return p;
    }

    LinearLayout.LayoutParams weighted(float weight) {
        return new LinearLayout.LayoutParams(0, -2, weight);
    }
    LinearLayout.LayoutParams weightedGap(float weight, int left, int right) {
        LinearLayout.LayoutParams value=weighted(weight);value.setMargins(dp(left),0,dp(right),0);return value;
    }

    TextView label(String value, float size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }

    TextView mono(String value) {
        TextView view = label(value.toUpperCase(), 11, muted);
        view.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        view.setLetterSpacing(.08f);
        return view;
    }

    TextView heading(String value) {
        TextView view = label(value, 20, text);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    TextView section(String value) {
        TextView view = label(value, 14, muted);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    void touchTarget(View view) {
        view.setMinimumHeight(Math.max(view.getMinimumHeight(), dp(48)));
        view.setFocusable(true);
        view.setClickable(true);
        view.setForeground(interaction());
    }

    private android.graphics.drawable.Drawable interaction() {
        int tint=Color.argb(64,Color.red(accent),Color.green(accent),Color.blue(accent));
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(tint),null,round(Color.WHITE,0,0,10));
    }

    android.widget.Button action(String value, boolean primary, Runnable clicked) {
        android.widget.Button button=new android.widget.Button(dialogContext());
        button.setText(value);button.setAllCaps(false);button.setTextSize(14);
        button.setPadding(dp(12),dp(10),dp(12),dp(10));button.setMinHeight(dp(48));button.setMinimumWidth(0);button.setMinWidth(0);touchTarget(button);
        if(primary){accent(button);buttonText(button,accentText);}else{button.setBackground(round(panel,0,0,14));buttonText(button,accentInk);}
        button.setOnClickListener(v->clicked.run());return button;
    }

    LinearLayout compactRow(String title,String detail,Runnable clicked) {
        LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12),dp(8),dp(12),dp(8));card(row);
        LinearLayout labels=new LinearLayout(context);labels.setOrientation(LinearLayout.VERTICAL);
        TextView name=label(title,16,text);name.setTypeface(Typeface.DEFAULT,Typeface.BOLD);labels.addView(name);
        if(!detail.isEmpty())labels.addView(label(detail,13,muted),fillWrap(4,0));
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        android.widget.ImageView arrow=icon(UiIcons.Kind.CHEVRON,18,muted);row.addView(arrow,new LinearLayout.LayoutParams(dp(18),dp(18)));
        row.setContentDescription(title+", "+detail);touchTarget(row);row.setOnClickListener(v->clicked.run());return row;
    }

    android.widget.ImageView icon(UiIcons.Kind kind,int size,int color){
        android.widget.ImageView view=new android.widget.ImageView(context);view.setImageDrawable(UiIcons.drawable(kind,color));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);view.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);return view;
    }
    LinearLayout iconHeading(String title,UiIcons.Kind kind){
        LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(icon(kind,20,accentInk),new LinearLayout.LayoutParams(dp(20),dp(20)));
        TextView name=label(title,16,text);name.setTypeface(Typeface.DEFAULT,Typeface.BOLD);LinearLayout.LayoutParams labels=new LinearLayout.LayoutParams(0,-2,1);labels.setMargins(dp(10),0,0,0);row.addView(name,labels);return row;
    }
    android.widget.Button iconAction(String title,UiIcons.Kind kind,boolean primary,Runnable clicked){
        android.widget.Button button=action(title,primary,clicked);android.graphics.drawable.Drawable icon=UiIcons.drawable(kind,primary?accentText:accentInk);icon.setBounds(0,0,dp(20),dp(20));button.setCompoundDrawables(icon,null,null,null);button.setCompoundDrawablePadding(dp(8));return button;
    }
    void tintedCard(View view){view.setBackground(round(tint,0,0,17));}
    LinearLayout settingRow(String title,String detail,UiIcons.Kind kind,boolean highlighted,Runnable clicked){
        LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(16),dp(16),dp(16),dp(16));row.setMinimumHeight(dp(80));
        if(highlighted)tintedCard(row);else card(row);
        android.widget.FrameLayout glyph=new android.widget.FrameLayout(context);glyph.setBackground(round(tint,0,0,12));
        android.widget.FrameLayout.LayoutParams image=new android.widget.FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER);glyph.addView(icon(kind,22,accentInk),image);row.addView(glyph,new LinearLayout.LayoutParams(dp(40),dp(40)));
        LinearLayout labels=new LinearLayout(context);labels.setOrientation(LinearLayout.VERTICAL);TextView name=label(title,16,highlighted?accentInk:text);labels.addView(name);
        if(!detail.isEmpty())labels.addView(label(detail,13,muted),fillWrap(4,0));LinearLayout.LayoutParams block=new LinearLayout.LayoutParams(0,-2,1);block.setMargins(dp(14),0,dp(14),0);row.addView(labels,block);
        row.addView(icon(UiIcons.Kind.CHEVRON,18,muted),new LinearLayout.LayoutParams(dp(18),dp(18)));row.setContentDescription(title+", "+detail);touchTarget(row);row.setOnClickListener(v->clicked.run());return row;
    }
    LinearLayout settingDisclosure(LinearLayout parent,String title,String detail,UiIcons.Kind kind,String key,java.util.Map<String,Boolean> states){
        LinearLayout shell=new LinearLayout(context);shell.setOrientation(LinearLayout.VERTICAL);card(shell);parent.addView(shell,fillWrap(4,8));
        LinearLayout content=new LinearLayout(context);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(8),0,dp(8),dp(8));boolean open=Boolean.TRUE.equals(states.get(key));content.setVisibility(open?View.VISIBLE:View.GONE);
        LinearLayout row=settingRow(title,detail,kind,false,()->{});android.widget.ImageView arrow=(android.widget.ImageView)row.getChildAt(row.getChildCount()-1);arrow.setImageDrawable(UiIcons.drawable(open?UiIcons.Kind.UP:UiIcons.Kind.CHEVRON,muted));row.setSelected(open);row.setContentDescription(title+", "+detail+(open?tx(", раскрыто"):tx(", свернуто")));
        row.setOnClickListener(v->{boolean expanded=content.getVisibility()!=View.VISIBLE;states.put(key,expanded);content.setVisibility(expanded?View.VISIBLE:View.GONE);arrow.setImageDrawable(UiIcons.drawable(expanded?UiIcons.Kind.UP:UiIcons.Kind.CHEVRON,muted));row.setSelected(expanded);row.setContentDescription(title+", "+detail+(expanded?tx(", раскрыто"):tx(", свернуто")));});shell.addView(row);shell.addView(content);return content;
    }
    LinearLayout disclosure(LinearLayout parent,String title){return disclosure(parent,title,title,new java.util.HashMap<>());}
    LinearLayout disclosure(LinearLayout parent,String title,String key,java.util.Map<String,Boolean> states) {
        LinearLayout content=new LinearLayout(context);content.setOrientation(LinearLayout.VERTICAL);
        boolean open=Boolean.TRUE.equals(states.get(key));content.setVisibility(open?View.VISIBLE:View.GONE);
        android.widget.Button toggle=action(title,false,()->{});toggle.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        setDisclosureIcon(toggle,open);toggle.setSelected(open);toggle.setContentDescription(title+(open?tx(", раскрыто"):tx(", свернуто")));
        toggle.setOnClickListener(v->{boolean expanded=content.getVisibility()!=View.VISIBLE;states.put(key,expanded);content.setVisibility(expanded?View.VISIBLE:View.GONE);setDisclosureIcon(toggle,expanded);toggle.setSelected(expanded);toggle.setContentDescription(title+(expanded?tx(", раскрыто"):tx(", свернуто")));});
        parent.addView(toggle,fillWrap(4,4));parent.addView(content,fillWrap(0,8));return content;
    }
    private void setDisclosureIcon(android.widget.Button button,boolean open){android.graphics.drawable.Drawable icon=UiIcons.drawable(open?UiIcons.Kind.UP:UiIcons.Kind.DOWN,muted);icon.setBounds(0,0,dp(18),dp(18));button.setCompoundDrawables(null,null,icon,null);button.setCompoundDrawablePadding(dp(12));}

    LinearLayout segmented(String[] labels,int selected,java.util.function.IntConsumer changed) {
        LinearLayout row=new LinearLayout(context);row.setPadding(dp(4),dp(4),dp(4),dp(4));card(row);
        for(int i=0;i<labels.length;i++){final int index=i;android.widget.Button choice=action(labels[i],false,()->changed.accept(index));
            choice.setBackground(round(selected==i?text:panel,0,0,10));buttonText(choice,selected==i?surface:muted);
            choice.setSelected(selected==i);choice.setContentDescription(labels[i]+(selected==i?tx(", выбрано"):""));row.addView(choice,weighted(1));}
        return row;
    }

    void card(View view) { view.setBackground(round(panel, 0, 0, 17)); }
    void selectedCard(View view) { view.setBackground(round(panel, accentInk, 1, 17)); }
    void swatch(View view, int color, boolean selected) { view.setBackground(round(color, selected ? accent : border, selected ? 3 : 1, 8)); }
    void input(View view) { view.setBackground(round(input, border, 1, 8)); }
    void outline(View view) { view.setBackground(round(soft, 0, 0, 12)); }
    void accent(View view) { view.setBackground(round(actionFill, 0, 0, 14)); }
    void underline(View view, boolean selected) { view.setBackgroundColor(selected ? accent : Color.TRANSPARENT); }

    void toggle(android.widget.Switch view) {
        android.content.res.ColorStateList thumb = new android.content.res.ColorStateList(
                new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                new int[] {accent, muted});
        android.content.res.ColorStateList track = new android.content.res.ColorStateList(
                new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                new int[] {Color.argb(110, Color.red(accent), Color.green(accent), Color.blue(accent)), border});
        view.setThumbTintList(thumb); view.setTrackTintList(track);
    }

    private GradientDrawable round(int fill, int stroke, int width, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (width > 0) drawable.setStroke(dp(width), stroke);
        return drawable;
    }

    private String tx(String value){return UiText.choose("en".equals(new AppPreferences(context).language()),value);}
    void center(TextView view) { view.setGravity(Gravity.CENTER); }
    android.graphics.drawable.Drawable navigationDrawable(boolean back){return new android.graphics.drawable.Drawable(){
        final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        @Override public void draw(android.graphics.Canvas canvas){android.graphics.Rect b=getBounds();float cx=b.exactCenterX(),cy=b.exactCenterY(),w=b.width()*.22f,h=b.height()*.28f,direction=back?-1:1;paint.setColor(text);paint.setStrokeWidth(dp(2));paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);canvas.drawLine(cx-direction*w,cy-h,cx+direction*w,cy,paint);canvas.drawLine(cx+direction*w,cy,cx-direction*w,cy+h,paint);}
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}
        @Override public void setColorFilter(android.graphics.ColorFilter filter){paint.setColorFilter(filter);}
        @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    };}
    void navigationIcon(android.widget.Button view,boolean back){android.graphics.drawable.Drawable drawable=navigationDrawable(back);drawable.setBounds(0,0,dp(24),dp(24));view.setCompoundDrawables(drawable,null,null,null);}

    android.app.AlertDialog.Builder dialogBuilder() {
        return new android.app.AlertDialog.Builder(dialogContext()) {
            @Override public android.app.AlertDialog create() {
                android.app.AlertDialog value=super.create();value.setOnShowListener(ignored->dialog(value));return value;
            }
            @Override public android.app.AlertDialog show() {
                android.app.AlertDialog value=super.show();dialog(value);return value;
            }
        };
    }
    void progress(android.widget.ProgressBar view) {
        android.content.res.ColorStateList colors=android.content.res.ColorStateList.valueOf(accent);
        view.setProgressTintList(colors);view.setIndeterminateTintList(colors);
        view.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(border));
        if(view instanceof android.widget.SeekBar)((android.widget.SeekBar)view).setThumbTintList(colors);
    }
    void check(android.widget.CompoundButton view) {view.setButtonTintList(android.content.res.ColorStateList.valueOf(accent));touchTarget(view);}
    void buttonText(android.widget.Button view,int color) {
        if(color==Color.WHITE)color=accentText;
        int dim=Color.argb(110,Color.red(color),Color.green(color),Color.blue(color));
        view.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{dim,color}));
    }
    Context dialogContext() {
        int style="arctic".equals(theme)?R.style.ChasovoyDialogArctic:"midnight-blue".equals(theme)?R.style.ChasovoyDialogBlue:"olive".equals(theme)?R.style.ChasovoyDialogOlive:R.style.ChasovoyDialogDark;
        return new android.view.ContextThemeWrapper(context,style);
    }
    void dialog(android.app.AlertDialog dialog) {
        if(dialog.getWindow()!=null){dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(panel));tint(dialog.getWindow().getDecorView());}
        for(int id:new int[]{android.app.AlertDialog.BUTTON_POSITIVE,android.app.AlertDialog.BUTTON_NEGATIVE,android.app.AlertDialog.BUTTON_NEUTRAL}){
            android.widget.Button button=dialog.getButton(id);if(button!=null)button.setTextColor(accentInk);
        }
    }
    private void tint(View view) {
        // Only recolor platform chrome; custom labels already carry semantic text/muted colors.
        boolean platform=false;try{platform=view.getId()!=View.NO_ID&&"android".equals(context.getResources().getResourcePackageName(view.getId()));}catch(android.content.res.Resources.NotFoundException ignored){}
        if(platform&&view instanceof TextView)((TextView)view).setTextColor(text);
        if(view instanceof android.widget.EditText){((android.widget.EditText)view).setTextColor(text);((android.widget.EditText)view).setHintTextColor(muted);input(view);}
        if(view instanceof android.widget.CompoundButton)((android.widget.CompoundButton)view).setButtonTintList(android.content.res.ColorStateList.valueOf(accent));
        if(view instanceof android.widget.Switch)toggle((android.widget.Switch)view);
        if(view instanceof android.widget.ProgressBar)progress((android.widget.ProgressBar)view);
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)tint(group.getChildAt(i));}
    }
}
