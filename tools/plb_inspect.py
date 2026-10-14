#!/usr/bin/env python3
"""Strict bounded recursive parser for GT01 PLB layout blobs."""
import argparse,hashlib,json,struct
from pathlib import Path
HEADER_SIZE=32; MAX_BLOB=32*1024*1024; MAX_DEPTH=64; MAX_NODES=16384; MAX_PROPS=256; MAX_STRING=65535; MAX_FRAMES=4096
TYPES={15:'root',4:'button',10:'image',11:'text',8:'clock',1:'animated'}
class Reject(ValueError): pass
class R:
 def __init__(s,b):s.b=b;s.p=0
 def raw(s,n):
  if s.p+n>len(s.b):raise Reject(f'truncated at {s.p}')
  x=s.b[s.p:s.p+n];s.p+=n;return x
 def u8(s):return s.raw(1)[0]
 def u16(s):return struct.unpack('<H',s.raw(2))[0]
 def u32(s):return struct.unpack('<I',s.raw(4))[0]
 def st(s):
  n=s.u16()
  if n>MAX_STRING:raise Reject('string limit')
  try:return s.raw(n).decode('utf-8')
  except UnicodeDecodeError:raise Reject('invalid UTF-8')
def prop(r,node_type,t,o):
 # typed property
 def z(v):return {'tag':t,'offset':o,'end':r.p,'raw_hex':r.b[o:r.p].hex(),'value':v,'decoded_value':v,'payload_offset':o+2,'evidence':'typed_property'}
 if t in(0,1,10,11):return z(r.u32())
 if t in(2,7):return z(r.st())
 if t==3:
  k=r.u16(); return z({'index':k,'value':r.st()})
 if t==4:return z(r.u16())
 if t==5:return z(True)
 if t==6:return z({'x':r.u16(),'y':r.u16(),'w':r.u16(),'h':r.u16()})
 if t==8:
  if node_type==8:v={'node_type':node_type,'value':r.st()}
  elif node_type==1:v={'node_type':node_type,'value':r.u32()}
  else:raise Reject(f'unsupported node type {node_type} for tag8 at {o}')
  return z(v)
 if t==9:
  if node_type==8:v={'node_type':node_type,'value':r.st()}
  elif node_type==1:
   n=r.u16()
   if n>MAX_FRAMES:raise Reject('frame limit')
   v={'node_type':node_type,'count':n,'value':[r.st() for _ in range(n)]}
  else:raise Reject(f'unsupported node type {node_type} for tag9 at {o}')
  return z(v)
 if t in(12,13,14):return z([r.u32(),r.u32()])
 raise Reject(f'unknown tag {t} at {o}')
def node(r,d,path,state):
 if d>MAX_DEPTH or state['nodes']>=MAX_NODES:raise Reject('depth/node limit')
 start=r.p;t=r.u16();ident=r.st();aux=r.st();n=r.u16()
 if n>MAX_PROPS:raise Reject('property limit')
 ps=[]
 for i in range(n):o=r.p;x=prop(r,t,r.u16(),o);x['order']=i;ps.append(x)
 cc=r.u16();serial=state['serial'];state['serial']+=1;state['nodes']+=1
 if cc>MAX_NODES:raise Reject('child limit')
 q={'type_code':t,'type':TYPES.get(t,'unknown'),'id':ident,'auxiliary':aux,'offset':start,'properties':ps,'child_count':cc,'children':[],'path':path,'parent_path':path[:-1] if path else None,'child_index':path[-1] if path else None,'order':path[-1] if path else None,'serialization_order':serial}
 for i in range(cc):q['children'].append(node(r,d+1,path+[i],state))
 q['end']=r.p;q['raw_hex']=r.b[start:r.p].hex();q['evidence']='strict_recursive_node'
 pivots={12:'hour',13:'minute',14:'second'}
 q['derived']={'geometry_rect':next((dict(x['value'],source_offset=x['offset']) for x in ps if x['tag']==6),None),'clock_pivots':{pivots[x['tag']]:{'x':x['value'][0],'y':x['value'][1],'source_offset':x['offset']} for x in ps if x['tag'] in pivots},'animation_frames':next(({'count':x['value']['count'],'paths':x['value']['value'],'source_offset':x['offset']} for x in ps if x['tag']==9 and isinstance(x['value'],dict) and x['value'].get('node_type')==1),None),'timing_raw':next(({'value':x['value']['value'],'source_offset':x['offset'],'unit':'unknown'} for x in ps if x['tag']==8 and isinstance(x['value'],dict) and x['value'].get('node_type')==1),None),'render_order_candidate':serial}
 return q
def legacy(b):
 out=[];p=32
 while p+2<=len(b):
  n=struct.unpack_from('<H',b,p)[0];e=p+2+n
  if 1<=n<=255 and e<=len(b) and all(32<=x<=126 for x in b[p+2:e]):out.append({'prefix_offset':p,'text_offset':p+2,'length':n,'text':b[p+2:e].decode()});p=e
  else:p+=1
 return out
def dump(b):
 return '\n'.join(f"{p:08x}  {' '.join(f'{x:02x}' for x in b[p:p+16]):<47}  |{''.join(chr(x) if 32<=x<=126 else '.' for x in b[p:p+16])}|" for p in range(0,len(b),16))+'\n'
def inspect_plb(path,refs):
 b=path.read_bytes()
 if len(b)>MAX_BLOB or len(b)<32 or b[:4]!=b'PLB\0':raise Reject(f'not bounded PLB: {path}')
 r=R(b);r.p=32;st={'nodes':0,'serial':0};root=node(r,0,[],st)
 if r.p!=len(b):raise Reject(f'trailing bytes at {r.p}')
 strings=legacy(b)
 return {'sha256':hashlib.sha256(b).hexdigest(),'blob_path':str(path),'packages':refs,'size':len(b),'header':{'magic':'PLB\\0','size':32,'hex':b[:32].hex(),'u32_le':list(struct.unpack('<8I',b[:32])),'payload_length_at_8':struct.unpack_from('<I',b,8)[0],'payload_length_matches_size_minus_32':struct.unpack_from('<I',b,8)[0]==len(b)-32},'node_count':st['nodes'],'root':root,'length_prefixed_strings':strings,'candidate_record_starts':[x['prefix_offset'] for x in strings],'geometry_status':'tag 6 rectangles decoded; render order and timing semantics remain candidates'},dump(b)
def corpus(index_path,out):
 if out.exists():raise ValueError(f'output already exists: {out}')
 idx=json.loads(index_path.read_text(encoding='utf-8'));refs={}
 for src in idx.get('sources',[]):
  pkg=src.get('manifest',{}).get('id',Path(src['path']).stem)
  for loc in src.get('locales',[]):
   if loc.get('name')!='default':continue
   for x in loc.get('resources',[]):
    if x.get('type')=='PLB layout':refs.setdefault(x.get('sha256') or x.get('blob_sha256'),[]).append({'package':pkg,'resource_index':x.get('index'),'name_hash':x.get('name_hash')})
 out.mkdir(parents=True);hd=out/'hexdumps';hd.mkdir();items=[]
 for d,rr in sorted(refs.items()):
  p=Path(idx.get('blobs',{}).get(d,{}).get('path',index_path.parent/'blobs'/f'{d}.bin'));p=p if p.is_absolute() else Path.cwd()/p
  x,h=inspect_plb(p,rr);(hd/f'{d}.txt').write_text(h,encoding='ascii');x['hexdump']=str(hd/f'{d}.txt');items.append(x)
 report={'format':2,'unique_plb':len(items),'packages':len({z['package'] for x in items for z in x['packages']}),'strict_eof_passed':len(items),'payload_length_checks_passed':sum(x['header']['payload_length_matches_size_minus_32'] for x in items),'geometry_rectangles_decoded':True,'runtime_render_decoded':False,'geometry_decoded':True,'items':items}
 (out/'index.json').write_text(json.dumps(report,indent=2,ensure_ascii=False),encoding='utf-8');print(json.dumps({k:report[k] for k in('unique_plb','packages','strict_eof_passed','geometry_decoded')}))
def main():
 p=argparse.ArgumentParser();s=p.add_subparsers(dest='cmd',required=True);c=s.add_parser('corpus');c.add_argument('index',type=Path);c.add_argument('--output',required=True,type=Path);a=p.parse_args();corpus(a.index,a.output)
if __name__=='__main__':main()
