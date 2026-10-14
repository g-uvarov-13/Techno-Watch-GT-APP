#!/usr/bin/env python3
"""Evidence-only SVG maps from strict PLB parser output."""
import argparse,json
from xml.sax.saxutils import escape
from pathlib import Path
def walk(n):
 yield n
 for c in n.get('children',[]):yield from walk(c)
def svg(item):
 nodes=list(walk(item['root'])); w=h=466
 out=[f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}"><rect width="100%" height="100%" fill="#101218"/><text x="8" y="18" fill="#fff" font-size="11">candidate render order; timing_raw is opaque evidence</text>']
 for i,n in enumerate(nodes):
  g=n.get('derived',{}).get('geometry_rect')
  for g in ([g] if g else []):
   x,y,ww,hh=[int(g[k]) for k in ('x','y','w','h')]
   clip=' out-of-bounds' if x<0 or y<0 or x+ww> w or y+hh> h else ''
   out.append(f'<rect x="{x}" y="{y}" width="{ww}" height="{hh}" fill="none" stroke="#e64b5d"/><text x="{max(2,x+2)}" y="{max(28,y+12)}" fill="#fff" font-size="9">{i}:{escape(str(n.get("id","")))} [{x},{y},{ww},{hh}]{clip}</text>')
  for label,pivot in n.get('derived',{}).get('clock_pivots',{}).items():
   px,py=int(pivot['x']),int(pivot['y']);out.append(f'<circle cx="{px}" cy="{py}" r="4" fill="none" stroke="#55ddee"/><text x="{px+5}" y="{py}" fill="#55ddee" font-size="8">{escape(label)} pivot</text>')
  if i<28: out.append(f'<text x="8" y="{30+i*12}" fill="#aab" font-size="8">{i} {escape(str(n.get("type")))} {escape(str(n.get("id")))} order-candidate={escape(str(n.get("serialization_order")))}</text>')
 return ''.join(out)+'</svg>'
def main():
 p=argparse.ArgumentParser();p.add_argument('index',type=Path);p.add_argument('--output',required=True,type=Path);a=p.parse_args()
 if a.output.exists():raise ValueError(f'output already exists: {a.output}')
 d=json.loads(a.index.read_text(encoding='utf-8'));a.output.mkdir(parents=True)
 for x in d['items']:
  for ref in x.get('packages',[]):
   name=f"{ref['package'].replace('.','_')}_{ref.get('resource_index','x')}_{x['sha256'][:8]}.svg"; (a.output/name).write_text(svg(x),encoding='utf-8')
 print(json.dumps({'maps':len(list(a.output.glob('*.svg'))),'candidate_only':True}))
if __name__=='__main__':main()
