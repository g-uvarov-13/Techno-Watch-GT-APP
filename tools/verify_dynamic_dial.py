#!/usr/bin/env python3
import argparse, hashlib, json, struct
from pathlib import Path
try:
 from tools.inspect_dial import inspect, OUTER_ENTRY, RESOURCE_ENTRY, reject_partial_overlaps
 from tools.pgf_codec import decode
except ModuleNotFoundError:
 from inspect_dial import inspect, OUTER_ENTRY, RESOURCE_ENTRY, reject_partial_overlaps
 from pgf_codec import decode
DONOR_SHA='DFAAC700817E3D363C7A03E8F6B053C1E7DAF8B869E2A6C800FDCD782123EDAD'; BG_SHA='EE2367D2D60ECB72099372E527F81CD5971B7C1A764722FE515885EE137205FE'
def check(data):
 assert data[:4]==b'CR\1\0'; n=struct.unpack_from('<I',data,4)[0]; assert n==28; ranges=[]; entries=[]; outer=[]; end=0
 for i in range(n):
  h,o,l,raw=OUTER_ENTRY.unpack_from(data,8+i*20); outer.append((h,l,raw)); assert o+l<=len(data) and data[o:o+4]==b'CR\2\0' and l==8+133*16; ranges.append((o,o+l,f'l{i}')); end=max(end,o+l)
  for j in range(133):
   nh,bo,bl,f=RESOURCE_ENTRY.unpack_from(data,o+8+j*16); assert bo+bl<=len(data); entries.append((i,j,nh,bo,bl,f))
 reject_partial_overlaps(ranges+[(x[3],x[3]+x[4],f'r{x[0]}:{x[1]}') for x in entries]); assert end==len(data); return outer,entries
def main():
 p=argparse.ArgumentParser();p.add_argument('donor',type=Path);p.add_argument('result',type=Path);p.add_argument('report',type=Path);a=p.parse_args();d0=a.donor.read_bytes();d1=a.result.read_bytes();assert hashlib.sha256(d0).hexdigest().upper()==DONOR_SHA
 before=inspect(a.donor,d0);after=inspect(a.result,d1);o0,e0=check(d0);o1,e1=check(d1);assert o0==o1;expected_manifest=dict(before['manifest']);expected_manifest.update({'id':'com.gtdial.pipboy01','name':'pipboy01','alias':'1909202601'});assert after['manifest']==expected_manifest
 for x,y in zip(e0,e1):
  assert x[:3]==y[:3] and x[5]==y[5]
  payload=d1[y[3]:y[3]+y[4]]
  if x[1] not in (38,65): assert d0[x[3]:x[3]+x[4]]==payload
  elif x[1]==65: assert y[4]==317 and b'\0' not in payload and json.loads(payload.decode('utf-8'))==after['manifest']
  else:
   meta,_,_=decode(payload);assert (meta['width'],meta['height'],meta['format_raw'],meta['stride'])==(466,466,6,480);assert hashlib.sha256(payload).hexdigest().upper()==BG_SHA
 assert all(s not in d1 for s in (b'com.QC.qc176',b'qc176',b'60001176'))
 out={'donorSha256':DONOR_SHA,'resultSha256':hashlib.sha256(d1).hexdigest().upper(),'locales':28,'resourcesPerLocale':133,'backgroundIndex':38,'manifestBytes':317,'physicalAcceptance':'UNVERIFIED'};a.report.parent.mkdir(parents=True,exist_ok=True);a.report.write_text(json.dumps(out,indent=2)+'\n',encoding='utf-8');print(json.dumps(out))
if __name__=='__main__':main()
