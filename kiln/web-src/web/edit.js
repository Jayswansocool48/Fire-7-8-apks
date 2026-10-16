// Polygon-mesh modeling core (pure maths, no three.js). Mesh = {v:[[x,y,z],...], f:[[vi,...],...]} (faces CCW from outside)
const add=(a,b)=>[a[0]+b[0],a[1]+b[1],a[2]+b[2]];
export const cen=(em,idx)=>{const c=[0,0,0];idx.forEach(i=>{const p=em.v[i];c[0]+=p[0];c[1]+=p[1];c[2]+=p[2]});return c.map(x=>x/(idx.length||1))};
export function fnorm(em,f){const n=[0,0,0];for(let i=0;i<f.length;i++){const a=em.v[f[i]],b=em.v[f[(i+1)%f.length]];n[0]+=(a[1]-b[1])*(a[2]+b[2]);n[1]+=(a[2]-b[2])*(a[0]+b[0]);n[2]+=(a[0]-b[0])*(a[1]+b[1])}const l=Math.hypot(n[0],n[1],n[2])||1;return[n[0]/l,n[1]/l,n[2]/l]}
export const faceSel=(em,sel)=>em.f.map((f,i)=>i).filter(i=>em.f[i].length&&em.f[i].every(v=>sel.has(v)));
export function edgesOf(em){const m=new Map();em.f.forEach(f=>f.forEach((a,i)=>{const b=f[(i+1)%f.length],k=a<b?a+','+b:b+','+a;if(!m.has(k))m.set(k,[Math.min(a,b),Math.max(a,b)])}));return[...m.values()]}
export function purge(em,sel){const used=new Set(em.f.flat()),map=new Map(),v=[];em.v.forEach((p,i)=>{if(used.has(i)){map.set(i,v.length);v.push(p)}});em.v=v;em.f=em.f.map(f=>f.map(i=>map.get(i)));const ns=new Set();sel&&sel.forEach(i=>map.has(i)&&ns.add(map.get(i)));return ns}
export function extrude(em,sel,dist=.3){
 const fs=faceSel(em,sel),key=(a,b)=>a+','+b,mapv=new Map();
 if(fs.length){const E=new Set(),used=new Set();fs.forEach(fi=>{const f=em.f[fi];f.forEach((a,i)=>{E.add(key(a,f[(i+1)%f.length]));used.add(a)})});
  used.forEach(i=>{mapv.set(i,em.v.length);em.v.push(em.v[i].slice())});
  const sides=[];fs.forEach(fi=>{const f=em.f[fi];f.forEach((a,i)=>{const b=f[(i+1)%f.length];if(!E.has(key(b,a)))sides.push([a,b,mapv.get(b),mapv.get(a)])})});
  fs.forEach(fi=>em.f[fi]=em.f[fi].map(i=>mapv.get(i)));em.f.push(...sides);
  let n=[0,0,0];fs.forEach(fi=>n=add(n,fnorm(em,em.f[fi])));const l=Math.hypot(n[0],n[1],n[2])||1;n=n.map(x=>x/l*dist);used.forEach(i=>{const m=mapv.get(i);em.v[m]=add(em.v[m],n)});
  return new Set([...used].map(i=>mapv.get(i)))}
 // edge extrude: duplicate selected edges into new quads
 const vn=new Map();em.f.forEach(f=>{const n=fnorm(em,f);f.forEach(i=>vn.set(i,add(vn.get(i)||[0,0,0],n)))});
 [...sel].forEach(i=>{mapv.set(i,em.v.length);const n=vn.get(i)||[0,0,0],l=Math.hypot(n[0],n[1],n[2])||1;em.v.push(add(em.v[i],n.map(x=>x/l*dist)))});
 const seen=new Set(),sides=[];em.f.forEach(f=>f.forEach((a,i)=>{const b=f[(i+1)%f.length];if(sel.has(a)&&sel.has(b)){const k=a<b?a+','+b:b+','+a;if(!seen.has(k)){seen.add(k);sides.push([b,a,mapv.get(a),mapv.get(b)])}}}));
 if(!sides.length){em.v.length-=mapv.size;return sel}
 em.f.push(...sides);return new Set([...mapv.values()])}
/* Inset faces by an absolute thickness (world units, like Blender), then push them out by depth.
   Region mode (Blender's default) insets the selection's outer border as one piece;
   individual mode insets every selected face on its own. */
const sub=(a,b)=>[a[0]-b[0],a[1]-b[1],a[2]-b[2]],dot=(a,b)=>a[0]*b[0]+a[1]*b[1]+a[2]*b[2];
const cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];
const unit=a=>{const l=Math.hypot(a[0],a[1],a[2])||1;return[a[0]/l,a[1]/l,a[2]/l]},mul=(a,k)=>[a[0]*k,a[1]*k,a[2]*k];
// inward direction for a corner given the inward normals of its edges, scaled so each edge moves by exactly t
function miter(ins,t){let d=[0,0,0];ins.forEach(n=>d=add(d,n));d=unit(d);const c=Math.min(...ins.map(n=>dot(d,n)));return mul(d,t/Math.max(.25,c))}
export function inset(em,sel,t=.1,depth=0,individual=false){const fs=faceSel(em,sel);if(!fs.length)return sel;const ns=new Set(),sides=[];
 if(individual){fs.forEach(fi=>{const f=em.f[fi],n=fnorm(em,f),k=f.length,inner=f.map((a,i)=>{const p=em.v[a],pr=em.v[f[(i+k-1)%k]],nx=em.v[f[(i+1)%k]];
    const i1=unit(cross(n,sub(p,pr))),i2=unit(cross(n,sub(nx,p)));em.v.push(add(add(p,miter([i1,i2],t)),mul(n,depth)));ns.add(em.v.length-1);return em.v.length-1});
   f.forEach((a,i)=>{const j=(i+1)%k;sides.push([a,f[j],inner[j],inner[i]])});em.f[fi]=inner});em.f.push(...sides);return ns}
 // region: find the border edges of the selected faces (edges with no selected twin)
 const R=new Set(fs),dir=new Set();fs.forEach(fi=>{const f=em.f[fi];f.forEach((a,i)=>dir.add(a+','+f[(i+1)%f.length]))});
 const border=[],ins=new Map(),rn=[0,0,0];fs.forEach(fi=>{const f=em.f[fi],n=fnorm(em,f);rn[0]+=n[0];rn[1]+=n[1];rn[2]+=n[2];
  f.forEach((a,i)=>{const b=f[(i+1)%f.length];if(dir.has(b+','+a))return;border.push([a,b]);const inn=unit(cross(n,sub(em.v[b],em.v[a])));
   [a,b].forEach(v=>{if(!ins.has(v))ins.set(v,[]);ins.get(v).push(inn)})})});
 const up=mul(unit(rn),depth),moved=new Map();
 ins.forEach((list,v)=>{em.v.push(add(add(em.v[v],miter(list,t)),up));moved.set(v,em.v.length-1)});
 const inner=new Set(fs.flatMap(fi=>em.f[fi]).filter(v=>!moved.has(v)));if(depth)inner.forEach(v=>em.v[v]=add(em.v[v],up));
 fs.forEach(fi=>em.f[fi]=em.f[fi].map(v=>moved.has(v)?moved.get(v):v));border.forEach(([a,b])=>sides.push([a,b,moved.get(b),moved.get(a)]));
 em.f.push(...sides);fs.forEach(fi=>em.f[fi].forEach(v=>ns.add(v)));return ns}
/* Direction an extrusion moves in: the selected faces' average normal, or the vertex normals for edges/verts. */
export function extrudeDir(em,sel){const fs=faceSel(em,sel);let n=[0,0,0];
 if(fs.length)fs.forEach(fi=>n=add(n,fnorm(em,em.f[fi])));else em.f.forEach(f=>{if(f.some(v=>sel.has(v)))n=add(n,fnorm(em,f))});return unit(n)}
export const clone=em=>({v:em.v.map(p=>p.slice()),f:em.f.map(f=>f.slice())});
export function del(em,sel,mode){if(mode==='f'){const fs=new Set(faceSel(em,sel));em.f=em.f.filter((_,i)=>!fs.has(i))}else em.f=em.f.filter(f=>!f.some(i=>sel.has(i)));return purge(em,new Set())}
export function subdivide(em,sel){const all=!sel.size,S=new Set(em.f.map((f,i)=>i).filter(i=>all||em.f[i].every(v=>sel.has(v)))),mid=new Map();
 const mp=(a,b)=>{const k=a<b?a+','+b:b+','+a;if(!mid.has(k)){const p=em.v[a],q=em.v[b];em.v.push([(p[0]+q[0])/2,(p[1]+q[1])/2,(p[2]+q[2])/2]);mid.set(k,em.v.length-1)}return mid.get(k)};
 const quads=[];em.f.forEach((f,fi)=>{if(!S.has(fi))return;const c=cen(em,f);em.v.push(c);const ci=em.v.length-1,m=f.map((a,i)=>mp(a,f[(i+1)%f.length]));f.forEach((a,i)=>quads.push([a,m[i],ci,m[(i+f.length-1)%f.length]]))});
 const rest=em.f.filter((_,i)=>!S.has(i)).map(f=>{const o=[];f.forEach((a,i)=>{o.push(a);const b=f[(i+1)%f.length],k=a<b?a+','+b:b+','+a;if(mid.has(k))o.push(mid.get(k))});return o});
 em.f=[...rest,...quads];return sel}
export function merge(em,sel){if(sel.size<2)return sel;const first=Math.min(...sel);em.v[first]=cen(em,[...sel]);
 em.f=em.f.map(f=>{const g=f.map(i=>sel.has(i)?first:i),o=[];g.forEach((i,k)=>{if(i!==g[(k+g.length-1)%g.length])o.push(i)});return o}).filter(f=>f.length>=3&&new Set(f).size>=3);return purge(em,new Set([first]))}
export function flip(em,sel){const fs=sel.size?faceSel(em,sel):em.f.map((_,i)=>i);fs.forEach(i=>em.f[i]=em.f[i].slice().reverse());return sel}
/* ---- editable primitives ---- */
const ring=(n,y,r)=>Array.from({length:n},(_,i)=>{const t=i/n*Math.PI*2;return[Math.cos(t)*r,y,Math.sin(t)*r]});
const seq=n=>[...Array(n).keys()];
export const PRIMS={ // each takes optional detail params; scale with sized()
 cube:()=>({v:[[-.5,-.5,-.5],[.5,-.5,-.5],[.5,.5,-.5],[-.5,.5,-.5],[-.5,-.5,.5],[.5,-.5,.5],[.5,.5,.5],[-.5,.5,.5]],f:[[4,5,6,7],[1,0,3,2],[5,1,2,6],[0,4,7,3],[7,6,2,3],[0,1,5,4]]}),
 plane:()=>({v:[[-.5,0,-.5],[.5,0,-.5],[.5,0,.5],[-.5,0,.5]],f:[[3,2,1,0]]}),
 cylinder:(n=12)=>({v:[...ring(n,-.5,.5),...ring(n,.5,.5)],f:[...seq(n).map(i=>[(i+1)%n,i,n+i,n+(i+1)%n]),seq(n).map(i=>n+(n-1-i)),seq(n)]}),
 cone:(n=12)=>({v:[...ring(n,-.5,.5),[0,.5,0]],f:[...seq(n).map(i=>[(i+1)%n,i,n]),seq(n)]}),
 sphere:(s=12,R=8)=>{const v=[[0,.5,0]];for(let r=1;r<R;r++){const p=r/R*Math.PI;v.push(...ring(s,Math.cos(p)*.5,Math.sin(p)*.5))}v.push([0,-.5,0]);const f=[],rg=r=>1+(r-1)*s,bot=v.length-1;
  for(let i=0;i<s;i++)f.push([rg(1)+(i+1)%s,rg(1)+i,0]);
  for(let r=1;r<R-1;r++)for(let i=0;i<s;i++)f.push([rg(r+1)+(i+1)%s,rg(r+1)+i,rg(r)+i,rg(r)+(i+1)%s]);
  for(let i=0;i<s;i++)f.push([bot,rg(R-1)+i,rg(R-1)+(i+1)%s]);return{v,f}},
 torus:(s=12,t=8)=>{const v=[],f=[],R=.35,r=.15;for(let i=0;i<s;i++)for(let j=0;j<t;j++){const a=i/s*Math.PI*2,b=j/t*Math.PI*2;v.push([(R+r*Math.cos(b))*Math.cos(a),r*Math.sin(b),(R+r*Math.cos(b))*Math.sin(a)])}
  const I=(i,j)=>(i%s)*t+(j%t);for(let i=0;i<s;i++)for(let j=0;j<t;j++)f.push([I(i,j),I(i,j+1),I(i+1,j+1),I(i+1,j)]);return{v,f}}
};

/* Scale a primitive to a size and move it, for Add's "Adjust Last Operation" panel. */
export function sized(em,size=1,at=[0,0,0]){em.v=em.v.map(p=>[p[0]*size+at[0],p[1]*size+at[1],p[2]*size+at[2]]);return em}
