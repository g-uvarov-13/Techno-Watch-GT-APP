package app.gtdial.lab;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Exact text node dimensions and fonts of the confirmed dial3000 v5 donor. */
final class DialRuntimeProfile {
    static final String ID = "gt01-text11-v1", DEVICE = "GT01-466";
    static final int SIZE = 466;
    static final class Field {
        final String id, label, example;
        final int x, y, width, height, fontPixels, align;
        Field(String id, String label, String example, int x, int y, int w, int h, int font) {
            this.id=id; this.label=label; this.example=example; this.x=x; this.y=y;
            width=w; height=h; fontPixels=font;
            align="HOUR".equals(id)||"BATTERY".equals(id)?66:
                "TIME_M_D".equals(id)||"TIME_S".equals(id)||"AM_PM".equals(id)||"HEART".equals(id)?68:65;
        }
    }
    static final List<Field> FIELDS = Collections.unmodifiableList(Arrays.asList(
        new Field("TIME_M_D", "Дата", "09/28",145,105,72,34,19),
        new Field("TIME_W", "День недели", "MON",225,105,59,34,19),
        new Field("HOUR", "Часы", "12",313,163,55,47,30),
        new Field("MIN", "Минуты", "34",313,276,55,47,30),
        new Field("TIME_S", "Секунды", "56",367,281,31,30,16),
        new Field("AM_PM", "AM/PM", "PM",362,175,35,21,14),
        new Field("STEPS", "Шаги", "1234",79,270,101,34,21),
        new Field("KCAL", "Калории", "123",88,166,66,34,21),
        new Field("HEART", "Последний пульс", "72",88,218,46,32,19),
        new Field("BATTERY", "Заряд", "85%",88,335,60,29,19),
        new Field("DISTAN", "Расстояние", "2.80",190,344,59,29,19)
    ));
    static Field field(String id) {
        for (Field f:FIELDS) if(f.id.equals(id)) return f;
        throw new IllegalArgumentException("Неизвестный показатель");
    }
    private DialRuntimeProfile() {}
}
