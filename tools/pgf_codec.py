#!/usr/bin/env python3
"""Bounded decoder/inventory for the indexed-color PGF variant seen on GT01."""
from __future__ import annotations
import argparse, json, struct, zlib
from pathlib import Path
MAX_BLOB=64*1024*1024; MAX_TOTAL=512*1024*1024
def encode_direct_bgra(width, height, bgra):
 if width < 1 or height < 1 or len(bgra) != width*height*4: raise ValueError('invalid BGRA dimensions')
 stride=(width*4+15)//16*16
 plane=b''.join(bgra[y*width*4:(y+1)*width*4]+b'\0'*(stride-width*4) for y in range(height))
 header=bytearray(44); header[:4]=b'PGF\0'; struct.pack_into('<HHH',header,28,width,height,stride)
 header[34]=1; header[35]=1; struct.pack_into('<I',header,36,len(plane)); packed=lz4_literal_only(plane); struct.pack_into('<I',header,40,len(packed))
 return bytes(header)+packed
def encode_indexed_bgra(width, height, bgra, format_raw=6, stride=None):
 if width < 1 or height < 1 or width > 4096 or height > 4096 or len(bgra) != width*height*4: raise ValueError('invalid BGRA dimensions')
 if format_raw not in (4,6): raise ValueError('indexed PGF format must be 4 or 6')
 stride = ((width+15)//16)*16 if stride is None else stride
 if stride < width or stride > 16384: raise ValueError('invalid indexed stride')
 codes=[]
 for i in range(0,len(bgra),4):
  b,g,r,a=bgra[i:i+4]
  if a!=255: raise ValueError('indexed encoder requires opaque pixels')
  codes.append(((r>>5)<<5)|((g>>5)<<2)|(b>>6))
 used=sorted(set(codes)); remap={code:index for index,code in enumerate(used)}
 palette=bytearray()
 for code in used:
  r=((code>>5)&7)*255//7; g=((code>>2)&7)*255//7; b=(code&3)*255//3
  palette.extend((b,g,r,255))
 plane=bytearray()
 for y in range(height):
  plane.extend(remap[c] for c in codes[y*width:(y+1)*width]); plane.extend(b'\0'*(stride-width))
 payload=bytes(plane)+bytes(palette); packed=lz4_literal_only(payload)
 header=bytearray(46); header[:4]=b'PGF\0'; struct.pack_into('<HHH',header,28,width,height,stride)
 header[34]=format_raw; header[35]=1; struct.pack_into('<H',header,36,len(used)); struct.pack_into('<I',header,38,len(payload)); struct.pack_into('<I',header,42,len(packed))
 return bytes(header)+packed
def lz4_decode(src,expected):
 out=bytearray(); p=0
 while p<len(src):
  token=src[p];p+=1;lit=token>>4
  if lit==15:
   while True:
    if p>=len(src):raise ValueError('truncated LZ4 literal length')
    v=src[p];p+=1;lit+=v
    if v!=255:break
  if p+lit>len(src):raise ValueError('LZ4 literal outside stream')
  if len(out)+lit>expected:raise ValueError('LZ4 literal exceeds declared size')
  out.extend(src[p:p+lit]);p+=lit
  if p==len(src):break
  if p+2>len(src):raise ValueError('truncated LZ4 offset')
  off=src[p]|src[p+1]<<8;p+=2
  if not off or off>len(out):raise ValueError('invalid LZ4 offset')
  match=token&15
  if match==15:
   while True:
    if p>=len(src):raise ValueError('truncated LZ4 match length')
    v=src[p];p+=1;match+=v
    if v!=255:break
  for _ in range(match+4):
   out.append(out[-off])
   if len(out)>expected:raise ValueError('LZ4 output exceeds declared size')
 if len(out)!=expected:raise ValueError(f'LZ4 output {len(out)} != {expected}')
 return bytes(out)
def lz4_literal_only(data):
 n=len(data);out=bytearray([min(n,15)<<4])
 if n>=15:
  n-=15
  while n>=255:out.append(255);n-=255
  out.append(n)
 out.extend(data);return bytes(out)
def write_png(path,w,h,rgba):
 def chunk(t,d):return struct.pack('>I',len(d))+t+d+struct.pack('>I',zlib.crc32(t+d)&0xffffffff)
 raw=b''.join(b'\0'+rgba[y*w*4:(y+1)*w*4] for y in range(h))
 path.write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(raw,9))+chunk(b'IEND',b''))
def decode(blob, alpha_mode='stored'):
 if len(blob)<42 or blob[:4]!=b'PGF\0':raise ValueError('not observed PGF')
 w,h,stride=struct.unpack_from('<HHH',blob,28);fmt,comp=blob[34],blob[35]
 if not(1<=w<=4096 and 1<=h<=4096 and w* (4 if fmt==1 else 1)<=stride<=16384):raise ValueError('invalid dimensions/stride')
 pc=struct.unpack_from('<H',blob,36)[0] if fmt in (4,6) else None
 size=struct.unpack_from('<I',blob,36)[0] if fmt==1 else struct.unpack_from('<I',blob,38)[0]
 expected=stride*h+(pc*4 if pc is not None else 0)
 if size!=expected or size>MAX_BLOB:raise ValueError(f'decoded size {size} != {expected}')
 csize=None
 if fmt not in (1,4,6):raise ValueError(f'unsupported format {fmt}')
 if fmt==1 and comp!=1:raise ValueError('format1 requires compression 1')
 payload_offset=44 if fmt==1 else 42
 if comp==0:
  payload=blob[payload_offset:]
  if len(payload)!=size:raise ValueError('raw payload length mismatch')
 elif comp==1:
  if len(blob)<46:raise ValueError('missing compressed size')
  csize=struct.unpack_from('<I',blob,40 if fmt==1 else 42)[0]
  start=44 if fmt==1 else 46
  if start+csize!=len(blob):raise ValueError('compressed tail/truncation')
  payload=lz4_decode(blob[start:],size)
 else:raise ValueError(f'unsupported compression {comp}')
 plane=payload[:stride*h];palette=payload[stride*h:] if pc is not None else b''
 if pc is not None:
  for y in range(h):
   for value in plane[y*stride:y*stride+w]:
    if value>=pc:raise ValueError(f'palette index {value} >= {pc}')
 literal=lz4_literal_only(payload)
 if lz4_decode(literal,size)!=payload:raise ValueError('literal LZ4 self-check failed')
 meta={'width':w,'height':h,'stride':stride,'row_stride_bytes':stride,'format_raw':fmt,'compression':comp,'palette_count':pc,'storage_kind':'direct_bgra32' if fmt==1 else 'indexed8','decoded_size':size,'compressed_size':csize,'literal_only_size':len(literal),'header_hex':blob[:(44 if fmt==1 else 42)].hex()}
 return meta,plane,palette
def corpus(index_path,output,pixels,channels,alpha_mode):
 index=json.loads(index_path.read_text(encoding='utf-8'));refs={}
 for source in index['sources']:
  default=next(x for x in source['locales'] if x['name']=='default')
  for r in default['resources']:
   if r['type']=='PGF image':refs.setdefault(r['blob_sha256'],[]).append({'package':source['manifest']['id'],'index':r['index'],'name_hash':r['name_hash']})
 output.mkdir(parents=True,exist_ok=False);decoded_dir=output/'decoded'
 if pixels:decoded_dir.mkdir()
 items=[];total=0
 for digest in sorted(refs):
  path=Path(index['blobs'][digest]['path']);path=path if path.is_absolute() else Path.cwd()/path;blob=path.read_bytes();item={'sha256':digest,'size':len(blob),'refs':refs[digest]}
  try:
   details,plane,palette=decode(blob,alpha_mode);total+=details['decoded_size']
   if total>MAX_TOTAL:raise ValueError('corpus decoded budget exceeded')
   item.update(details);item['state']='DECODED'
   if pixels:
    if details['storage_kind']=='direct_bgra32':(decoded_dir/f'{digest}.pixels').write_bytes(plane)
    else:
     (decoded_dir/f'{digest}.indices').write_bytes(plane)
     (decoded_dir/f'{digest}.palette').write_bytes(palette)
    rgba=bytearray()
    for y in range(details['height']):
     for value in range(details['width']):
      if details['format_raw']==1:c=plane[y*details['stride']+value*4:y*details['stride']+value*4+4]
      else:c=palette[plane[y*details['stride']+value]*4:plane[y*details['stride']+value]*4+4]
      if alpha_mode=='unpremultiply' and c[3]==0:c=b'\0\0\0\0'
      elif alpha_mode=='unpremultiply' and c[3]!=255:c=bytes(min(255,(v*255+c[3]//2)//c[3]) if i<3 else v for i,v in enumerate(c))
      rgba.extend(c if channels=='rgba' else bytes((c[2],c[1],c[0],c[3])))
    write_png(decoded_dir/f'{digest}-{channels}-candidate.png',details['width'],details['height'],bytes(rgba))
  except ValueError as exc:item.update({'state':'UNSUPPORTED','error':str(exc)})
  items.append(item)
 result={'format':2,'channels':channels if pixels else None,'alpha_mode':alpha_mode,'unique_pgf':len(items),'decoded':sum(x['state']=='DECODED' for x in items),'unsupported':sum(x['state']!='DECODED' for x in items),'decoded_bytes':total,'items':items}
 (output/'index.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');return result
def main():
 p=argparse.ArgumentParser();subs=p.add_subparsers(dest='command',required=True)
 for name in ('inventory','decode-corpus'):
  s=subs.add_parser(name);s.add_argument('index',type=Path);s.add_argument('--output',type=Path,required=True);s.add_argument('--channels',choices=('rgba','bgra'),default='bgra');s.add_argument('--alpha-mode',choices=('stored','unpremultiply'),default='stored')
 a=p.parse_args();r=corpus(a.index,a.output,a.command=='decode-corpus',a.channels,a.alpha_mode);print(json.dumps({k:r[k] for k in ('unique_pgf','decoded','unsupported','decoded_bytes')}))
if __name__=='__main__':main()
