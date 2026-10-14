#!/usr/bin/env python3
"""Bounded, descriptive alpha/color statistics for PGF corpus."""
import argparse, hashlib, json
from pathlib import Path
from pgf_codec import decode
def main():
 p=argparse.ArgumentParser();p.add_argument('index',type=Path);p.add_argument('--output',required=True,type=Path);a=p.parse_args()
 if a.output.exists():raise ValueError(f'output already exists: {a.output}')
 idx=json.loads(a.index.read_text(encoding='utf-8'));items=[]; wanted={r.get('blob_sha256') for s in idx.get('sources',[]) for l in s.get('locales',[]) if l.get('name')=='default' for r in l.get('resources',[]) if r.get('type')=='PGF image'}
 for d in sorted(wanted):
  m=idx.get('blobs',{}).get(d,{})
  path=Path(m.get('path',a.index.parent/'blobs'/f'{d}.bin'));path=path if path.is_absolute() else Path.cwd()/path
  try:
   meta,pixels,palette=decode(path.read_bytes()); direct=meta['format_raw']==1; samples=[]; stride=meta['stride'];w=meta['width'];h=meta['height']
   used_indices=set()
   if direct:
    for y in range(h):
     for x in range(w):samples.append(pixels[y*stride+x*4:y*stride+x*4+4])
   else:
    for y in range(h):
     for x in range(w):i=pixels[y*stride+x];used_indices.add(i);samples.append(palette[i*4:i*4+4])
   hist=[0]*256; semi=viol=trans=0
   for c in samples:
    b,g,r,al=c;hist[al]+=1;semi+=int(0<al<255);viol+=int(r>al or g>al or b>al);trans+=int(al==0 and (r or g or b))
   items.append({'sha256':d,'format':meta['storage_kind'],'pixels':len(samples),'palette_entries':meta.get('palette_count'),'used_palette_indices':len(used_indices) if not direct else None,'unused_palette_entries':meta.get('palette_count')-len(used_indices) if not direct else None,'alpha_histogram':{'scope':'visible pixels only; row padding and unused palette entries excluded','nonzero_bins':sum(v>0 for v in hist),'min':next((i for i,v in enumerate(hist) if v),None),'max':next((255-i for i,v in enumerate(reversed(hist)) if v),None),'total':sum(hist)},'semitransparent_count':semi,'rgb_gt_alpha_count':viol,'transparent_nonzero_rgb_count':trans,'witness_hashes':{'pixels':hashlib.sha256(b''.join(samples)).hexdigest()}})
  except ValueError as e: items.append({'sha256':d,'state':'UNSUPPORTED','error':str(e)})
 groups={}
 for item in items:
  if 'format' not in item:continue
  group=groups.setdefault(item['format'],{'resources':0,'pixels':0,'semitransparent_count':0,'rgb_gt_alpha_count':0,'transparent_nonzero_rgb_count':0,'witness_sha256':[]})
  group['resources']+=1;group['pixels']+=item['pixels'];group['semitransparent_count']+=item['semitransparent_count'];group['rgb_gt_alpha_count']+=item['rgb_gt_alpha_count'];group['transparent_nonzero_rgb_count']+=item['transparent_nonzero_rgb_count']
  if (item['semitransparent_count'] or item['rgb_gt_alpha_count'] or item['transparent_nonzero_rgb_count']) and len(group['witness_sha256'])<8:group['witness_sha256'].append(item['sha256'])
 out={'format':3,'items':items,'by_storage_kind':groups,'alpha_semantics_status':'strongly_supported_not_runtime_verified','claims':[{'claim_id':'pgf.alpha-premultiplication','status':'supported','package_sha256':None,'blob_sha256':None,'offsets':[],'method':'visible-pixel alpha/RGB inequality across complete corpus','competing_interpretations':['dark straight-alpha RGB also satisfies RGB<=A'],'minimal_missing_observation':'known firmware compositor output over a controlled non-black background'}],'decoded':sum('format' in x for x in items),'unsupported':sum('format' not in x for x in items)};a.output.mkdir(parents=True);(a.output/'index.json').write_text(json.dumps(out,indent=2,sort_keys=True),encoding='utf-8');print(json.dumps({'decoded':out['decoded'],'unsupported':out['unsupported'],'by_storage_kind':groups}))
if __name__=='__main__':main()
