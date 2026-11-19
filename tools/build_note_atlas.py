"""Extract the supplied chart, skeletonize strokes, and match adjacent note graphs.
Requires numpy, scipy and Pillow. Original chart is the only shape reference.
"""
from pathlib import Path
import struct, sys
import numpy as np
from PIL import Image
from scipy.optimize import linear_sum_assignment
from scipy.spatial.distance import cdist

def thin(image):
 a=np.pad(image.astype(np.uint8),1)
 while True:
  changed=False
  for phase in (0,1):
   p=[a[:-2,1:-1],a[:-2,2:],a[1:-1,2:],a[2:,2:],a[2:,1:-1],a[2:,:-2],a[1:-1,:-2],a[:-2,:-2]]
   neighbors=sum(p);trans=sum((p[k]==0)&(p[(k+1)%8]==1) for k in range(8))
   if phase==0:conditions=(p[0]*p[2]*p[4]==0)&(p[2]*p[4]*p[6]==0)
   else:conditions=(p[0]*p[2]*p[6]==0)&(p[0]*p[4]*p[6]==0)
   remove=(a[1:-1,1:-1]==1)&(neighbors>=2)&(neighbors<=6)&(trans==1)&conditions
   if remove.any():a[1:-1,1:-1][remove]=0;changed=True
  if not changed:return a[1:-1,1:-1].astype(bool)

def graph(mask):
 sk=thin(mask);ys,xs=np.where(sk);ids=np.full(sk.shape,-1,dtype=int);ids[ys,xs]=np.arange(len(xs));edges=[]
 for dy,dx in [(0,1),(1,-1),(1,0),(1,1)]:
  for y,x in zip(ys,xs):
   yy,xx=y+dy,x+dx
   if 0<=yy<sk.shape[0] and 0<=xx<sk.shape[1] and ids[yy,xx]>=0:
    # Avoid triangle artifacts where a diagonal also has an orthogonal path.
    if dx!=0 and dy!=0 and (ids[y,xx]>=0 or ids[yy,x]>=0):continue
    edges.append((ids[y,x],ids[yy,xx]))
 points=np.column_stack([xs,ys]).astype(float)/(sk.shape[0]-1)*1.32-.66
 adj=[[] for _ in points]
 for a,b in edges:adj[a].append(b);adj[b].append(a)
 # Preserve junctions and end points; soften raster stair steps along ordinary curves.
 for _ in range(3):
  old=points.copy()
  for i,near in enumerate(adj):
   if len(near)==2:points[i]=.5*old[i]+.25*(old[near[0]]+old[near[1]])
 return points,np.asarray(edges,dtype=np.int32)

root=Path(__file__).resolve().parents[1]
source=Path(sys.argv[1]) if len(sys.argv)>1 else root/'tools/reference-chart.jpg'
im=Image.open(source).convert('L')
xs=[276,407,537,667,797,927,1057];ys=[417,547,677,807,937,1067,1197,1327]
glyphs=[]
for row,y in enumerate(ys):
 for col,x in enumerate(xs):
  crop=im.crop((x,y,x+91,y+91)).resize((72,72),Image.Resampling.LANCZOS)
  pc=[0,2,4,5,7,9,11][col];midi=12*(row+2)+pc
  glyphs.append((midi,*graph(np.asarray(crop)<130)))
# Last C on the last row: C9 is the final provided endpoint.
crop=im.crop((1187,1327,1278,1418)).resize((72,72),Image.Resampling.LANCZOS)
glyphs.append((120,*graph(np.asarray(crop)<130)))
count=max(len(g[1]) for g in glyphs)
print('Atlas:',len(glyphs),'notes;',count,'matched vertices',flush=True)
padded=[]
for midi,points,edges in glyphs:
 # Duplicate existing vertices to equalize graph sizes, not random filler points.
 padded.append(np.vstack([points,points[np.arange(count-len(points))%len(points)]]))
output=root/'app/src/main/assets/note-atlas.bin'
with output.open('wb') as f:
 def ints(*v):f.write(struct.pack('>'+str(len(v))+'i',*v))
 def floats(a):f.write(np.asarray(a,dtype='>f4').tobytes())
 ints(0x46515a41,1,len(glyphs),count)
 for i,(midi,points,edges) in enumerate(glyphs):
  ints(midi,len(edges));floats(padded[i]);ints(*edges.flatten().tolist())
 for i in range(len(glyphs)-1):
  _,permutation=linear_sum_assignment(cdist(padded[i],padded[i+1],metric='sqeuclidean'))
  ints(*permutation.tolist())
print('Saved',output,flush=True)

