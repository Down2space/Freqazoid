package com.freakazoid.watch;
import android.content.Context;
import android.graphics.*;

/** Reference chart symbols, tinted by pitch. Accidentals blend adjacent naturals. */
final class PatternAtlas {
    private final Bitmap[][] notes=new Bitmap[8][7];
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private static final int[] NATURALS={0,2,4,5,7,9,11};
    PatternAtlas(Context context){
        for(int o=0;o<8;o++)for(int n=0;n<7;n++){
            int id=context.getResources().getIdentifier("note_"+(o+1)+"_"+n,"drawable",context.getPackageName());
            notes[o][n]=BitmapFactory.decodeResource(context.getResources(),id);
        }
    }
    void draw(Canvas c,int midi,RectF rect,int color){
        int pc=Math.floorMod(midi,12),oct=Math.floorDiv(midi,12)-1;
        if(oct<1||oct>8){
            // A separate artistic fallback outside the supplied chart's C1–B8 range.
            paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);
            c.drawCircle(rect.centerX(),rect.centerY(),rect.width()*.4f,paint);
            c.drawLine(rect.left,rect.top,rect.right,rect.bottom,paint);
            c.drawLine(rect.right,rect.top,rect.left,rect.bottom,paint);
            paint.setStyle(Paint.Style.FILL);return;
        }
        for(int i=0;i<7;i++)if(pc==NATURALS[i]){tile(c,notes[oct-1][i],rect,color,255);return;}
        for(int i=0;i<6;i++)if(pc>NATURALS[i]&&pc<NATURALS[i+1]){
            tile(c,notes[oct-1][i],rect,color,155);tile(c,notes[oct-1][i+1],rect,color,155);return;
        }
    }
    private void tile(Canvas c,Bitmap b,RectF rect,int color,int alpha){
        paint.setColorFilter(new PorterDuffColorFilter(color,PorterDuff.Mode.SRC_IN));paint.setAlpha(alpha);
        c.drawBitmap(b,null,rect,paint);paint.setColorFilter(null);paint.setAlpha(255);
    }
}

