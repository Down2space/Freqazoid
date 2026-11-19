package com.freakazoid.watch;

/** Pythagorean distance in 8-bit sRGB. Not a perceptually uniform Delta E metric. */
public final class ColorMath {
    public static final double MAX_DISTANCE = Math.sqrt(3.0 * 255 * 255);
    private ColorMath() {}
    public static double distance(int a, int b) {
        int dr=((a>>>16)&255)-((b>>>16)&255);
        int dg=((a>>>8)&255)-((b>>>8)&255);
        int db=(a&255)-(b&255);
        return Math.sqrt(dr*dr + dg*dg + db*db);
    }
    public static double percent(int a, int b) { return 100*distance(a,b)/MAX_DISTANCE; }
    public static int fromDb(double db) { return palette((db+110)/110); }
    /** Black -> blue -> cyan -> yellow -> red -> white, from the supplied HTML. */
    public static int palette(double t) {
        if(!Double.isFinite(t)) t=t==Double.POSITIVE_INFINITY?1:0;
        t=Math.max(0,Math.min(1,t));
        double r,g,b,k;
        if(t<.2){k=t/.2;r=10*k;g=15*k;b=90*k;}
        else if(t<.45){k=(t-.2)/.25;r=10;g=15+210*k;b=90+165*k;}
        else if(t<.7){k=(t-.45)/.25;r=10+245*k;g=225+30*k;b=255*(1-k);}
        else if(t<.9){k=(t-.7)/.2;r=255;g=255*(1-k);b=0;}
        else {k=(t-.9)/.1;r=255;g=255*k;b=255*k;}
        return 0xff000000|((int)Math.round(r)<<16)|((int)Math.round(g)<<8)|(int)Math.round(b);
    }
}

