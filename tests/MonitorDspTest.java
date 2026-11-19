import com.freakazoid.watch.Spectrum;
public class MonitorDspTest {
 public static void main(String[] args) {
  for(int rate:new int[]{16000,44100,48000}) {
   short[] a=new short[Spectrum.N];
   for(int i=0;i<a.length;i++) a[i]=(short)(10000*Math.sin(2*Math.PI*440*i/rate));
   Spectrum.Frame f=Spectrum.analyze(a,rate);
   require(f.hz.length==1 && Math.abs(f.hz[0]-440)<rate/(double)Spectrum.N,"440 Hz "+rate);
   for(int i=0;i<a.length;i++) a[i]=(short)(8000*Math.sin(2*Math.PI*440*i/rate)+6000*Math.sin(2*Math.PI*1200*i/rate));
   f=Spectrum.analyze(a,rate);
   require(has(f,440,rate) && has(f,1200,rate),"Two simultaneous tones "+rate);
   require(Spectrum.analyze(new short[Spectrum.N],rate).hz.length==0,"Silence");
   java.util.Arrays.fill(a,(short)12000);
   require(Spectrum.analyze(a,rate).hz.length==0,"DC rejection");
   java.util.Arrays.fill(a,(short)32767);
   require(Spectrum.analyze(a,rate).clipped,"Clipping");
  }
  System.out.println("PASS: single tones, dual tones, silence, DC rejection, clipping at 3 sample rates");
 }
 static boolean has(Spectrum.Frame f,double hz,int rate){for(double v:f.hz)if(Math.abs(v-hz)<rate/(double)Spectrum.N)return true;return false;}
 static void require(boolean b,String s){if(!b)throw new AssertionError(s);}
}

