package app.gtdial.lab;

/** Both drawing and touch hit testing use the same centered 466px coordinate space. */
final class DialCanvasTransform {
    final float scale,left,top;
    DialCanvasTransform(int width,int height) {
        scale=Math.max(.0001f,Math.min(width/466f,height/466f));
        left=(width-466*scale)/2f;top=(height-466*scale)/2f;
    }
    float x(float screen) { return (screen-left)/scale; }
    float y(float screen) { return (screen-top)/scale; }
}
