package com.freqazoid.membrane
import kotlin.math.*

/** Pure Kotlin Hann-windowed FFT, DC removal, interpolated spectral peaks. */
class AudioDsp(private val rate:Int,private val n:Int=16384) {
    init{require(rate>0 && n>=1024 && (n and (n-1))==0)}
    fun analyze(pcm:ShortArray,stamp:Long=0):AudioFrame {
        require(pcm.size==n)
        val re=DoubleArray(n);val im=DoubleArray(n)
        var mean=0.0;var energy=0.0
        for(v in pcm){mean+=v.toDouble()/n;energy+=(v/32768.0).pow(2)}
        var sumWindow=0.0
        for(i in pcm.indices){val w=.5-.5*cos(2*PI*i/(n-1));sumWindow+=w;re[i]=(pcm[i]-mean)/32768.0*w}
        var j=0
        for(i in 1 until n){var bit=n shr 1;while(j and bit !=0){j=j xor bit;bit=bit shr 1};j=j xor bit;if(i<j){val v=re[i];re[i]=re[j];re[j]=v}}
        var len=2
        while(len<=n){val a=-2*PI/len;var start=0
            while(start<n){for(k in 0 until len/2){val c=cos(a*k);val s=sin(a*k);val x=start+k;val y=x+len/2;val r=re[y]*c-im[y]*s;val v=re[y]*s+im[y]*c;re[y]=re[x]-r;im[y]=im[x]-v;re[x]+=r;im[x]+=v};start+=len};len=len shl 1}
        val db=DoubleArray(n/2){20*log10(max(1e-9,2*hypot(re[it],im[it])/sumWindow))}
        val strongest=(2 until db.lastIndex).filter{it*rate.toDouble()/n>=30}.maxOfOrNull{db[it]} ?: -180.0
        val candidates=(2 until db.lastIndex).filter { k -> k*rate.toDouble()/n>=30 && db[k]>max(-65.0,strongest-24) && db[k]>db[k-1] && db[k]>=db[k+1] && db[k]-min(db[max(0,k-3)],db[min(db.lastIndex,k+3)])>6 }.sortedByDescending{db[it]}
        val selected=ArrayList<Int>();val tones=ArrayList<Tone>()
        for(k in candidates){if(selected.any{abs(it-k)<3})continue
            val denominator=db[k-1]-2*db[k]+db[k+1];val d=if(abs(denominator)<1e-12)0.0 else (.5*(db[k-1]-db[k+1])/denominator).coerceIn(-.5,.5)
            val peakDb=db[k]-.25*(db[k-1]-db[k+1])*d
            tones+=Tone((k+d)*rate/n,10.0.pow(peakDb/20).coerceIn(0.0,1.0));selected+=k;if(tones.size==32)break
        }
        var power=0.0;var logPower=0.0;var centroid=0.0;var bins=0
        for(k in 1 until n/2){val hz=k*rate.toDouble()/n;if(hz<30 || hz>min(12000.0,rate/2.0))continue
            val p=max(1e-18,re[k]*re[k]+im[k]*im[k]);power+=p;logPower+=ln(p);centroid+=p*hz;bins++}
        val flatness=if(power<1e-10)0.0 else (exp(logPower/bins)/(power/bins)).coerceIn(0.0,1.0)
        return AudioFrame(tones,20*log10(max(1e-6,sqrt(energy/n))),stamp,flatness,if(power>0)centroid/power else 0.0)
    }
}

