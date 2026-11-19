package com.freqazoid.analysis

import kotlin.math.*

/** Platform-independent reference core for the Claude rebuild. Not yet wired into the app UI. */
data class AnalysisSettings(
    val fftSize: Int = 8192,
    val maxPeaks: Int = 32,
    val peakThresholdDb: Double = -75.0,
    val spectrumFloorDb: Double = -160.0
) {
    init {
        require(fftSize in 2048..32768 && fftSize and (fftSize-1) == 0)
        require(maxPeaks in 1..64 && peakThresholdDb.isFinite() && spectrumFloorDb.isFinite())
        require(spectrumFloorDb < peakThresholdDb && peakThresholdDb <= 0)
    }
}

data class Peak(val hz: Double, val amplitude: Double, val dbfs: Double, val bin: Int)
data class AnalysisSnapshot(
    val sampleRate: Int,
    val fftSize: Int,
    /** Monotonic clock, timestamp of the end of this sample window, not UI draw time. */
    val windowEndNanos: Long,
    val windowDurationNanos: Long,
    /** DC through Nyquist inclusive. All bins remain present, including noise. */
    val spectrumDbfs: FloatArray,
    val peaks: List<Peak>,
    val rmsDbfs: Double,
    val clipped: Boolean,
    val spectralFlatness: Double,
    val centroidHz: Double
) {
    val binSpacingHz get() = sampleRate.toDouble()/fftSize
    val windowCenterNanos get() = windowEndNanos-windowDurationNanos/2
    fun binHz(bin:Int):Double { require(bin in spectrumDbfs.indices);return bin*binSpacingHz }
}

/** Single-worker ownership. FFT tables are reused; published frames own their output arrays. */
class UnifiedAudioAnalyzer(val settings:AnalysisSettings=AnalysisSettings()) {
    private val n=settings.fftSize
    private val window=DoubleArray(n){.5-.5*cos(2*PI*it/(n-1))}
    private val windowSum=window.sum()
    private val cosine=DoubleArray(n/2){cos(-2*PI*it/n)}
    private val sine=DoubleArray(n/2){sin(-2*PI*it/n)}
    private val real=DoubleArray(n)
    private val imag=DoubleArray(n)
    private val reversed=IntArray(n){Integer.reverse(it) ushr (32-Integer.numberOfTrailingZeros(n))}

    fun analyze(samples:ShortArray,sampleRate:Int,windowEndNanos:Long):AnalysisSnapshot {
        require(samples.size==n && sampleRate>0 && windowEndNanos>=0)
        var mean=0.0;var energy=0.0;var clipped=false
        for(s in samples){mean+=s.toDouble()/n;val v=s/32768.0;energy+=v*v;if(abs(s.toInt())>=32700)clipped=true}
        for(i in samples.indices){real[reversed[i]]=(samples[i]-mean)/32768.0*window[i];imag[i]=0.0}
        var length=2
        while(length<=n){val half=length/2;val stride=n/length
            var start=0
            while(start<n){for(k in 0 until half){
                val a=start+k;val b=a+half;val j=k*stride
                val re=real[b]*cosine[j]-imag[b]*sine[j]
                val im=real[b]*sine[j]+imag[b]*cosine[j]
                real[b]=real[a]-re;imag[b]=imag[a]-im;real[a]+=re;imag[a]+=im
            };start+=length};length=length shl 1
        }
        val amplitudes=DoubleArray(n/2+1){k->hypot(real[k],imag[k])/windowSum*(if(k==0||k==n/2)1 else 2)}
        val db=FloatArray(amplitudes.size){(20*log10(amplitudes[it].coerceAtLeast(1e-12))).coerceAtLeast(settings.spectrumFloorDb).toFloat()}
        // Peak picking produces a derived view; it never removes raw bins from the snapshot.
        val candidates=ArrayList<Int>()
        for(k in 2 until db.lastIndex){
            val hz=k*sampleRate.toDouble()/n
            if(hz<20 || db[k]<settings.peakThresholdDb || db[k]<=db[k-1] || db[k]<db[k+1])continue
            val localBase=min(db[max(1,k-3)],db[min(db.lastIndex,k+3)])
            if(db[k]-localBase>=6)candidates+=k
        }
        candidates.sortByDescending{db[it]}
        val selected=ArrayList<Peak>()
        for(k in candidates){
            if(selected.any{abs(it.bin-k)<3})continue
            val denominator=db[k-1].toDouble()-2*db[k]+db[k+1]
            val offset=if(abs(denominator)<1e-12)0.0 else (.5*(db[k-1]-db[k+1])/denominator).coerceIn(-.5,.5)
            val peakDb=db[k]-.25*(db[k-1]-db[k+1])*offset
            selected+=Peak((k+offset)*sampleRate/n,10.0.pow(peakDb/20).coerceIn(0.0,1.0),peakDb,k)
            if(selected.size>=settings.maxPeaks)break
        }
        var sum=0.0;var logSum=0.0;var centroid=0.0;var bins=0
        for(k in 1 until amplitudes.lastIndex){val hz=k*sampleRate.toDouble()/n;if(hz<20)continue
            val power=amplitudes[k]*amplitudes[k];sum+=power;logSum+=ln(power.coerceAtLeast(1e-24));centroid+=hz*power;bins++}
        val flatness=if(sum<1e-18||bins==0)0.0 else (exp(logSum/bins)/(sum/bins)).coerceIn(0.0,1.0)
        return AnalysisSnapshot(sampleRate,n,windowEndNanos,(1e9*n/sampleRate).toLong(),db,selected,
            (20*log10(sqrt(energy/n).coerceAtLeast(1e-12))).coerceAtLeast(settings.spectrumFloorDb),clipped,flatness,
            if(sum>0)centroid/sum else 0.0)
    }
}

/** Chronological ring buffer. Old spectra keep their timestamps and layout identity. */
class SpectrumHistory(private val capacity:Int=128) {
    init {require(capacity>0)}
    data class Row(val timestampNanos:Long,val sampleRate:Int,val fftSize:Int,val db:FloatArray)
    private val rows=arrayOfNulls<Row>(capacity)
    private var head=0
    var count=0;private set
    private var lastStamp:Long?=null
    fun clear(){rows.fill(null);head=0;count=0;lastStamp=null}
    fun add(frame:AnalysisSnapshot){
        val previous=if(count>0)newest(0)else null
        if(previous!=null && (previous.sampleRate!=frame.sampleRate || previous.fftSize!=frame.fftSize))clear()
        val last=lastStamp
        // Duplicate or older frames must not scroll history or generate fictitious color changes.
        if(last!=null && frame.windowEndNanos<=last)return
        rows[head]=Row(frame.windowEndNanos,frame.sampleRate,frame.fftSize,frame.spectrumDbfs.copyOf())
        head=(head+1)%capacity;count=min(capacity,count+1);lastStamp=frame.windowEndNanos
    }
    fun newest(age:Int):Row {require(age in 0 until count);return rows[(head-1-age+capacity)%capacity]!!}
    fun ageSeconds(age:Int):Double {return (newest(0).timestampNanos-newest(age).timestampNanos)/1e9}
}

/** UI-independent measurement helpers; color distance is separate from harmony geometry. */
object MeasurementMath {
    fun frequencyAtLogPosition(position:Double,minHz:Double,maxHz:Double):Double {
        require(minHz>0 && maxHz>minHz)
        return minHz*(maxHz/minHz).pow(position.coerceIn(0.0,1.0))
    }
    fun normalizedRgbDifference(old:Int,new:Int):Double {
        val dr=((new ushr 16)and 255)-((old ushr 16)and 255)
        val dg=((new ushr 8)and 255)-((old ushr 8)and 255)
        val db=(new and 255)-(old and 255)
        return sqrt((dr*dr+dg*dg+db*db).toDouble())/sqrt(3.0*255*255)
    }
}

