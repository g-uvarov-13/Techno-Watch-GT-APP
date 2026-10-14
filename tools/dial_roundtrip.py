#!/usr/bin/env python3
"""Lossless CR01/CR02 unpack/repack without retaining a complete source copy."""
from __future__ import annotations
import argparse,hashlib,json,struct
from pathlib import Path
from inspect_dial import OUTER_ENTRY,RESOURCE_ENTRY,inspect
def digest(data):return hashlib.sha256(data).hexdigest()
def save(directory,data):
 h=digest(data);p=directory/h
 if not p.exists():p.write_bytes(data)
 return h
def unpack(source,destination):
 data=source.read_bytes();report=inspect(source,data);outer_end=8+len(report['locales'])*OUTER_ENTRY.size
 tables=[(x['offset'],x['offset']+x['length']) for x in report['locales']];resources=sorted({(r['offset'],r['offset']+r['length']) for loc in report['locales'] for r in loc['resources']})
 destination.mkdir(parents=True,exist_ok=False);segdir=destination/'segments';segdir.mkdir();stored=[];occupied=sorted([(0,outer_end)]+tables+resources);cursor=0
 for start,end in occupied:
  if start<cursor:raise ValueError('unexpected structural overlap')
  if start>cursor:
   b=data[cursor:start];stored.append({'kind':'opaque','offset':cursor,'length':len(b),'sha256':save(segdir,b)})
  if (start,end) in resources:
   b=data[start:end];stored.append({'kind':'resource','offset':start,'length':len(b),'sha256':save(segdir,b)})
  cursor=end
 if cursor<len(data):
  b=data[cursor:];stored.append({'kind':'opaque','offset':cursor,'length':len(b),'sha256':save(segdir,b)})
 outer=[]
 for i in range(len(report['locales'])):
  nh,off,length,raw=OUTER_ENTRY.unpack_from(data,8+i*OUTER_ENTRY.size);outer.append({'name_hash':nh,'offset':off,'length':length,'raw_name8':raw.hex()})
 tables_json=[]
 for loc in report['locales']:
  tables_json.append({'offset':loc['offset'],'length':loc['length'],'resources':[{'name_hash':int(r['name_hash'],16),'offset':r['offset'],'length':r['length'],'raw_fourth_field':r['raw_fourth_field']} for r in loc['resources']]})
 manifest={'schema_version':1,'source_name':source.name,'source_size':len(data),'source_sha256':digest(data),'outer_records':outer,'locale_tables':tables_json,'stored_segments':stored}
 (destination/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8');return manifest
def pack(manifest_path,output):
 m=json.loads(manifest_path.read_text(encoding='utf-8'))
 if output.exists():raise FileExistsError(f'refusing overwrite: {output}')
 if output.name==m['source_name']:raise ValueError('output filename must differ from source')
 data=bytearray(m['source_size']);covered=bytearray(m['source_size'])
 def write(offset,blob):
  end=offset+len(blob)
  if offset<0 or end>len(data) or any(covered[offset:end]):raise ValueError('overlap/out of bounds')
  data[offset:end]=blob;covered[offset:end]=b'\1'*len(blob)
 outer=bytearray(struct.pack('<4sI',b'CR\x01\0',len(m['outer_records'])))
 for r in m['outer_records']:outer.extend(OUTER_ENTRY.pack(r['name_hash'],r['offset'],r['length'],bytes.fromhex(r['raw_name8'])))
 write(0,outer)
 for table in m['locale_tables']:
  b=bytearray(struct.pack('<4sI',b'CR\x02\0',len(table['resources'])))
  for r in table['resources']:b.extend(RESOURCE_ENTRY.pack(r['name_hash'],r['offset'],r['length'],r['raw_fourth_field']))
  if len(b)!=table['length']:raise ValueError('locale table length changed')
  write(table['offset'],b)
 for s in m['stored_segments']:
  b=(manifest_path.parent/'segments'/s['sha256']).read_bytes()
  if len(b)!=s['length'] or digest(b)!=s['sha256']:raise ValueError('segment mismatch')
  write(s['offset'],b)
 if not all(covered):raise ValueError('incomplete byte coverage')
 result=bytes(data);output.parent.mkdir(parents=True,exist_ok=True);output.write_bytes(result);return result
def corpus(dial_dir,output):
 output.mkdir(parents=True,exist_ok=False);items=[]
 for source in sorted(dial_dir.glob('*.dial')):
  original=source.read_bytes();package=output/f'{source.stem}-{digest(original)[:12]}';m=unpack(source,package);rebuilt=output/'rebuilt'/f'{source.stem}.roundtrip.dial';result=pack(package/'manifest.json',rebuilt);equal=result==original and digest(result)==m['source_sha256'];parsed=inspect(rebuilt,result);items.append({'source':str(source),'manifest':str(package/'manifest.json'),'rebuilt':str(rebuilt),'byte_equal':equal,'sha256':digest(result),'locales':len(parsed['locales'])})
 report={'packages':len(items),'byte_equal':sum(x['byte_equal'] for x in items),'failed':sum(not x['byte_equal'] for x in items),'items':items};(output/'report.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps({k:report[k] for k in ('packages','byte_equal','failed')}));return report
def main():
 p=argparse.ArgumentParser();subs=p.add_subparsers(dest='command',required=True);c=subs.add_parser('corpus');c.add_argument('dial_dir',type=Path);c.add_argument('--output',type=Path,required=True);q=subs.add_parser('pack');q.add_argument('manifest',type=Path);q.add_argument('output',type=Path);a=p.parse_args();corpus(a.dial_dir,a.output) if a.command=='corpus' else pack(a.manifest,a.output)
if __name__=='__main__':main()
