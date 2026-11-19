package com.freakazoid.watch;

/** Equal temperament, A4 = 440 Hz. Spectral peaks are not necessarily fundamentals. */
public final class NoteMath {
    private static final String[] NAMES={"C","C♯","D","D♯","E","F","F♯","G","G♯","A","A♯","B"};
    public static int midi(double hz) {
        if(!Double.isFinite(hz)||hz<=0) throw new IllegalArgumentException("Positive frequency required");
        return (int)Math.round(69+12*Math.log(hz/440)/Math.log(2));
    }
    public static double frequency(int midi){return 440*Math.pow(2,(midi-69)/12.0);}
    public static String name(int midi){return NAMES[Math.floorMod(midi,12)]+(Math.floorDiv(midi,12)-1);}
    public static double cents(double hz){return 1200*Math.log(hz/frequency(midi(hz)))/Math.log(2);}
    /** Intentional color key: C red, progressing around the hue wheel, A violet. */
    public static int color(int midi, double level){
        double hue=Math.floorMod(midi,12)/2.0, x=1-Math.abs(hue%2-1);
        double r=0,g=0,b=0;
        if(hue<1){r=1;g=x;}else if(hue<2){r=x;g=1;}else if(hue<3){g=1;b=x;}
        else if(hue<4){g=x;b=1;}else if(hue<5){r=x;b=1;}else{r=1;b=x;}
        double v=Math.max(.45,Math.min(1,(level+90)/70));
        return 0xff000000|((int)(255*v*r)<<16)|((int)(255*v*g)<<8)|(int)(255*v*b);
    }
}

