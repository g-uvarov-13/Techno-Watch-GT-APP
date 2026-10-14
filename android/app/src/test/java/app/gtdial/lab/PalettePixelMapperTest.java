package app.gtdial.lab;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class PalettePixelMapperTest {
    @Test public void redSourceCanBecomeCyan() {
        assertEquals(0xff46e8ff, PalettePixelMapper.map(0xffff2000, 0xff00070a, 0xff46e8ff));
    }

    @Test public void blackAndTransparentPixelsUseBackground() {
        assertEquals(0xff090500, PalettePixelMapper.map(0xff000000, 0xff090500, 0xffffb02e));
        assertEquals(0xff090500, PalettePixelMapper.map(0x00000000, 0xff090500, 0xffffb02e));
    }

    @Test public void halfBrightnessPreservesImageDetail() {
        assertEquals(0xff808080, PalettePixelMapper.map(0xff800000, 0xff000000, 0xffffffff));
    }
}
