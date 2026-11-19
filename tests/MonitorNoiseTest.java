import com.freakazoid.watch.Spectrum;
import java.util.Random;
public class MonitorNoiseTest {
 public static void main(String[] args){
  Random r=new Random(123);short[] samples=new short[8192];for(int i=0;i<samples.length;i++)samples[i]=(short)(r.nextInt(10001)-5000);
  Spectrum.Frame frame=Spectrum.analyze(samples,48000);int visible=0;for(double db:frame.db)if(db>-110)visible++;
  if(visible<frame.db.length*.95)throw new AssertionError("Broadband bins discarded");
  System.out.println("PASS: raw spectrum retains broadband noise across FFT bins");
 }
}

