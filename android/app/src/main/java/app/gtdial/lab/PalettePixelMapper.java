package app.gtdial.lab;

/** Converts an already colored preview to a named palette without retaining its source hue. */
final class PalettePixelMapper {
    private PalettePixelMapper() {}
    static int map(int source, int background, int primary) {
        int a=(source >>> 24)&255;
        int level=Math.max((source >>> 16)&255,Math.max((source >>> 8)&255,source&255))*a/255;
        int r=blend((background >>> 16)&255,(primary >>> 16)&255,level);
        int g=blend((background >>> 8)&255,(primary >>> 8)&255,level);
        int b=blend(background&255,primary&255,level);
        return 0xff000000 | r<<16 | g<<8 | b;
    }
    private static int blend(int start,int end,int level){return (start*(255-level)+end*level+127)/255;}
}
