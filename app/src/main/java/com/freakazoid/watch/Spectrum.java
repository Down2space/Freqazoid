package com.freakazoid.watch;

import java.util.*;

/** Pure Java DSP. Frequencies are spectral peaks, NOT a count of sound sources. */
public final class Spectrum {
    public static final int N = 4096;
    public static final class Frame {
        public final double[] db, hz;
        public final double level;
        public final int size, rate;
        public final boolean clipped;
        Frame(double[] db, double[] hz, double level, boolean clipped, int size, int rate) {
            this.size=size; this.rate=rate; this.db=db; this.hz=hz; this.level=level; this.clipped=clipped;
        }
    }
    public static Frame analyze(short[] pcm, int rate) {
        final int N=pcm.length;
        if (N<2048 || N>16384 || (N&(N-1))!=0 || rate <= 0) throw new IllegalArgumentException();
        double[] re=new double[N], im=new double[N];
        double mean=0, energy=0; boolean clipped=false;
        for(short s:pcm) { mean+=s/(double)N; energy+=(s/32768.0)*(s/32768.0); if(Math.abs((int)s)>32700) clipped=true; }
        double windowSum=0;
        for(int i=0;i<N;i++) {
            double w=0.5-0.5*Math.cos(2*Math.PI*i/(N-1)); windowSum+=w;
            re[i]=(pcm[i]-mean)/32768.0*w;
        }
        for(int i=1,j=0;i<N;i++) {
            int bit=N>>1; for(; (j&bit)!=0;bit>>=1) j^=bit; j^=bit;
            if(i<j) {double t=re[i];re[i]=re[j];re[j]=t;}
        }
        for(int len=2;len<=N;len<<=1) {
            double angle=-2*Math.PI/len;
            for(int start=0;start<N;start+=len) {
                for(int j=0;j<len/2;j++) {
                    int a=start+j,b=a+len/2;
                    double c=Math.cos(angle*j),s=Math.sin(angle*j);
                    double r=re[b]*c-im[b]*s, v=re[b]*s+im[b]*c;
                    re[b]=re[a]-r; im[b]=im[a]-v; re[a]+=r; im[a]+=v;
                }
            }
        }
        double[] db=new double[N/2];
        for(int k=0;k<db.length;k++) db[k]=20*Math.log10(Math.max(1e-6,2*Math.hypot(re[k],im[k])/windowSum));
        List<Integer> candidates=new ArrayList<>();
        double strongest=-120;
        for(int k=2;k<db.length-1;k++) if(k*rate/(double)N>=40) strongest=Math.max(strongest,db[k]);
        for(int k=2;k<db.length-1;k++) {
            if(k*rate/(double)N<40) continue;
            if(db[k]>Math.max(-65,strongest-25) && db[k]>db[k-1] && db[k]>=db[k+1]
                && db[k]-Math.min(db[Math.max(0,k-3)],db[Math.min(db.length-1,k+3)])>6) candidates.add(k);
        }
        candidates.sort((a,b)->Double.compare(db[b],db[a]));
        List<Double> peaks=new ArrayList<>(); List<Integer> chosen=new ArrayList<>();
        for(int k:candidates) {
            boolean near=false; for(int c:chosen) if(Math.abs(k-c)<3) near=true;
            if(near) continue;
            double denom=db[k-1]-2*db[k]+db[k+1];
            double offset=denom==0?0:0.5*(db[k-1]-db[k+1])/denom;
            peaks.add((k+Math.max(-0.5,Math.min(0.5,offset)))*rate/N); chosen.add(k);
            if(peaks.size()==5) break;
        }
        return new Frame(db,peaks.stream().mapToDouble(Double::doubleValue).toArray(),
            20*Math.log10(Math.max(1e-6,Math.sqrt(energy/N))),clipped,N,rate);
    }
}

