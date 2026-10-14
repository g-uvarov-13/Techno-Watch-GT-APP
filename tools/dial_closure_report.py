#!/usr/bin/env python3
import argparse,json
from pathlib import Path
def claim(cid,status,method,evidence=None,missing=None,competing=None):return {'claim_id':cid,'status':status,'package_sha256':None,'blob_sha256':None,'offsets':[],'method':method,'evidence':evidence or {},'competing_interpretations':competing or [],'minimal_missing_observation':missing}
def walk(n):
 yield n
 for c in n.get('children',[]):yield from walk(c)
def main():
 p=argparse.ArgumentParser();p.add_argument('--index',action='append',required=True,type=Path);p.add_argument('--plb-index',action='append',type=Path,default=[]);p.add_argument('--output',required=True,type=Path);a=p.parse_args()
 if a.output.exists():raise ValueError('output exists')
 sources=[]
 for ip in a.index:sources += json.loads(ip.read_text(encoding='utf-8')).get('sources',[])
 packages=[]
 for s in sorted({x.get('sha256'):x for x in sources}.values(),key=lambda x:x.get('manifest',{}).get('id','')):
  loc=[];default=next((x for x in s.get('locales',[]) if x.get('name')=='default'),None);base={r.get('name_hash'):r for r in (default or {}).get('resources',[])}
  for l in sorted(s.get('locales',[]),key=lambda x:x.get('name','')):
   current={r.get('name_hash'):r for r in l.get('resources',[])};added=sorted(set(current)-set(base));removed=sorted(set(base)-set(current));changed=[]
   for h in sorted(set(base)&set(current)):
    left,right=base[h],current[h]
    if (left.get('blob_sha256') or left.get('sha256'),left.get('raw_fourth_field'))!=(right.get('blob_sha256') or right.get('sha256'),right.get('raw_fourth_field')):changed.append({'name_hash':h,'default_blob':left.get('blob_sha256') or left.get('sha256'),'locale_blob':right.get('blob_sha256') or right.get('sha256'),'default_raw_fourth':left.get('raw_fourth_field'),'locale_raw_fourth':right.get('raw_fourth_field')})
   loc.append({'name':l.get('name'),'hash':l.get('hash'),'resource_count':l.get('resource_count'),'delta_from_default':{'added':added,'removed':removed,'changed':changed},'resources':[{'index':r.get('index'),'name_hash':r.get('name_hash'),'type':r.get('type'),'sha256':r.get('sha256') or r.get('blob_sha256'),'raw_fourth':r.get('raw_fourth_field')} for r in sorted(l.get('resources',[]),key=lambda x:x.get('index',0))]})
  packages.append({'package':s.get('manifest',{}).get('id'),'sha256':s.get('sha256'),'locales':loc})
 props={};animations=[];plb_packages=set()
 for pp in a.plb_index:
  parsed=json.loads(pp.read_text(encoding='utf-8'))
  for item in parsed.get('items',[]):
   pkg=item.get('packages',[{}])[0].get('package');plb_packages.add(pkg)
   for n in walk(item['root']):
    for pr in n.get('properties',[]):
     key=f"type{n.get('type_code')}.tag{pr.get('tag')}";bucket=props.setdefault(key,{'occurrences':0,'distinct_values':{},'witnesses':[]});bucket['occurrences']+=1;val=json.dumps(pr.get('decoded_value',pr.get('value')),sort_keys=True,ensure_ascii=False);bucket['distinct_values'][val]=bucket['distinct_values'].get(val,0)+1
     if len(bucket['witnesses'])<8:bucket['witnesses'].append({'package':pkg,'plb_sha256':item.get('sha256'),'node':n.get('id'),'node_offset':n.get('offset'),'property_offset':pr.get('offset')})
    af=n.get('derived',{}).get('animation_frames')
    if af:animations.append({'package':pkg,'plb_sha256':item.get('sha256'),'node':n.get('id'),'node_offset':n.get('offset'),'rect':n.get('derived',{}).get('geometry_rect'),'timing_raw':n.get('derived',{}).get('timing_raw'),'frame_count':af.get('count'),'frames':af.get('paths')})
 claims=[claim('closure.local-corpus','confirmed','merge by source SHA',{'packages':len(packages)}),claim('locale.runtime-selection','ambiguous','static CR01/CR02 locale comparison',{'packages_with_locale_deltas':sum(any(x['delta_from_default']['added'] or x['delta_from_default']['removed'] or x['delta_from_default']['changed'] for x in p['locales']) for p in packages)},'device locale-selection trace'),claim('plb.render-order','ambiguous','serialization order and rectangle overlap only',{'plb_packages':len(plb_packages)},'runtime capture with distinguishable overlapping opaque nodes',['forward traversal','reverse traversal','type-specific compositor']),claim('animation.timing-unit','ambiguous','static type1 property inventory',{'animations':len(animations)},'runtime frame timestamps',['milliseconds','ticks','frames-per-unit'])]
 out={'format':2,'packages':packages,'plb_property_evidence':dict(sorted(props.items())),'animations':animations,'claims':claims};a.output.mkdir(parents=True);(a.output/'index.json').write_text(json.dumps(out,indent=2,ensure_ascii=False,sort_keys=True),encoding='utf-8');print(json.dumps({'packages':len(packages),'plb_packages':len(plb_packages),'property_pairs':len(props),'animations':len(animations)}))
if __name__=='__main__':main()
