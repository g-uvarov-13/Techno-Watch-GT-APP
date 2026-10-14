#!/usr/bin/env python3
import json, struct, zlib
from hashlib import sha256
from pathlib import Path
try:
 from tools.inspect_dial import inspect, bkdr
 from tools.pgf_codec import decode, encode_indexed_bgra
except ModuleNotFoundError:
 from inspect_dial import inspect, bkdr
 from pgf_codec import decode, encode_indexed_bgra

DONOR_SHA256 = 'DFAAC700817E3D363C7A03E8F6B053C1E7DAF8B869E2A6C800FDCD782123EDAD'
BACKGROUND_INDEX = 38
BACKGROUND_HASH = '0x4f073730'
MANIFEST_ID = 'com.gtdial.pipboy01'
MANIFEST_NAME = 'pipboy01'
MANIFEST_ALIAS = '1909202601'

def png_rgba(path):
 d=path.read_bytes(); assert d[:8]==b'\x89PNG\r\n\x1a\n'; p=8; raw=b''; w=h=None; palette=None; alpha=None
 while p<len(d):
  n=struct.unpack('>I',d[p:p+4])[0]; typ=d[p+4:p+8]; body=d[p+8:p+8+n]; p+=12+n
  if typ==b'IHDR': w,h,depth,color,_,_,_=struct.unpack('>IIBBBBB',body); assert depth==8 and color in (3,6)
  elif typ==b'PLTE': palette=body
  elif typ==b'tRNS': alpha=body
  elif typ==b'IDAT': raw+=body
  elif typ==b'IEND': break
 raw=zlib.decompress(raw); rows=[]; bpp=1 if color==3 else 4; stride=w*bpp; prev=bytearray(stride); q=0
 for _ in range(h):
  f=raw[q]; q+=1; row=bytearray(raw[q:q+stride]); q+=stride
  for i in range(stride):
   left=row[i-bpp] if i>=bpp else 0; up=prev[i]; ul=prev[i-bpp] if i>=bpp else 0
   if f==1: row[i]=(row[i]+left)&255
   elif f==2: row[i]=(row[i]+up)&255
   elif f==3: row[i]=(row[i]+((left+up)//2))&255
   elif f==4:
    x=left+up-ul; pa=abs(x-left); pb=abs(x-up); pc=abs(x-ul); row[i]=(row[i]+(left if pa<=pb and pa<=pc else up if pb<=pc else ul))&255
  rows.append(row); prev=row
 if color==3:
  rgba=[]
  for row in rows:
   for idx in row: rgba.extend((palette[idx*3+2],palette[idx*3+1],palette[idx*3],alpha[idx] if alpha and idx<len(alpha) else 255))
  return w,h,bytes(rgba)
 return w,h,b''.join(bytes((r[i+2],r[i+1],r[i],r[i+3])) for r in rows for i in range(0,stride,4))

def build_package(donor, image):
 raw=donor.read_bytes()
 if sha256(raw).hexdigest().upper() != DONOR_SHA256: raise ValueError('unsupported donor SHA-256')
 data=bytearray(raw); report=inspect(donor, bytes(data)); old=None
 for r in report['default_resources']:
  if r['index'] != BACKGROUND_INDEX or r['name_hash'] != BACKGROUND_HASH: continue
  if r['type']=='PGF image':
   b=data[r['offset']:r['offset']+r['length']]
   if len(b)>=40 and struct.unpack_from('<HH',b,28)==(466,466): old=(r['offset'],r['length'],bytes(b)); break
 if old is None: raise ValueError('466x466 donor background not found')
 old_manifest = report['manifest']
 if old_manifest is None: raise ValueError('manifest not found')
 old_moff = old_mlen = None
 for resource in report['default_resources']:
  if resource['type'] == 'JSON':
   candidate = raw[resource['offset']:resource['offset']+resource['length']]
   if b'"id"' in candidate and b'"alias"' in candidate:
    old_moff, old_mlen = resource['offset'], resource['length']; break
 if old_moff is None: raise ValueError('manifest resource not found')
 source_meta,_,_=decode(old[2]); w,h,bgra=png_rgba(image)
 if (w,h)!=(466,466): raise ValueError('image must be 466x466')
 bgra=bytes(bgra[i] if (i%4)!=3 else 255 for i in range(len(bgra)))
 blob=encode_indexed_bgra(w,h,bgra,format_raw=source_meta['format_raw'],stride=source_meta['stride'])
 old_off,old_len,_=old
 # Replace the shared background in-place in the blob stream.  Rebuilding the
 # tail (rather than appending a second CR02 resource) keeps every locale
 # table ordered and leaves the final CR02 table at EOF.
 old_end=old_off+old_len
 pad=(-len(blob)) & 3
 replacement=blob+b'\0'*pad
 delta=len(replacement)-old_len
 data[old_off:old_end]=replacement
 # Replace the manifest JSON as a normal resource, preserving every other
 # resource byte-for-byte.  The watch client uses this identity when it
 # inventories and applies a downloaded dial.
 renamed_manifest = dict(old_manifest)
 renamed_manifest.update({'id': MANIFEST_ID, 'name': MANIFEST_NAME, 'alias': MANIFEST_ALIAS})
 manifest = json.dumps(renamed_manifest, separators=(',', ':'), ensure_ascii=False).encode('utf-8')
 if len(manifest) > old_mlen: raise ValueError(f'renamed manifest exceeds {old_mlen} bytes')
 manifest += b' ' * (old_mlen-len(manifest))
 # The GT01 installer appears to scan raw package bytes for the first manifest.
 # Replace the original extent so no stale donor identity remains discoverable.
 if old_moff >= old_end: old_moff += delta
 data[old_moff:old_moff+old_mlen] = manifest
 locale_count=struct.unpack_from('<I',data,4)[0]
 for i in range(locale_count):
  _hash, table_off, table_len, _raw=struct.unpack_from('<III8s',data,8+i*20)
  if table_off >= old_end: table_off += delta
  struct.pack_into('<I', data, 8+i*20+4, table_off)
  count=struct.unpack_from('<I',data,table_off+4)[0]
  for j in range(count):
   entry=table_off+8+j*16
   _name_hash, ofs, length, _fourth=struct.unpack_from('<IIII',data,entry)
   if ofs==old_off and length==old_len:
    struct.pack_into('<II',data,entry+4,old_off,len(blob))
   elif ofs >= old_end:
    struct.pack_into('<I',data,entry+4,ofs+delta)
 # All locale tables are shared-layout copies of the same 133-entry table.
 # Update the manifest offset after the replacement as well, if it moved.
 return bytes(data)

if __name__=='__main__':
 import argparse
 p=argparse.ArgumentParser();p.add_argument('donor',type=Path);p.add_argument('image',type=Path);p.add_argument('output',type=Path);a=p.parse_args();a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_bytes(build_package(a.donor,a.image))
