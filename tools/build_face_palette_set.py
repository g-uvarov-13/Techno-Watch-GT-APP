#!/usr/bin/env python3
"""Build the six pinned Dial Lab 3000 palettes as offline-only artifacts."""
import json
from hashlib import sha256
from pathlib import Path
try:
 from tools.build_qc176_palette_variant import build_package
except ModuleNotFoundError:
 from build_qc176_palette_variant import build_package

ROOT=Path('work/face-palettes-20260923'); DONOR=Path('work/dial-corpus-v2/sources/com.QC.qc176.dial-dfaac700817e.dial'); PLAN=Path('work/face-layout-20260923/diallab3000-qc176-plan-v3.json'); PLB=Path('work/face-layout-20260923/diallab3000-qc176-v3.plb')
def main():
 rows=[x for x in json.loads((ROOT/'backgrounds.json').read_text()) if x['faceId']=='pipboy3000']
 out=Path('outputs/dynamic-dials/palette-set-v1/pipboy3000');out.mkdir(parents=True,exist_ok=True); registry=[]
 for i,row in enumerate(rows,1):
  bg=Path(row['path']);
  if sha256(bg.read_bytes()).hexdigest().upper()!=row['backgroundSha256'] or row['sourceSha256']!='38A294DF4B2F6DF9FC33DC218E02D275CA30362FFEC2D8BD3CC226B3075B0871':raise ValueError('background pin')
  ident=('com.gtdial.dl3000' if i==1 else f'com.gtdial.dl3000{i:02d}','dl3000' if i==1 else f'dl3000{i:02d}',f'230920260{i}')
  blob=build_package(DONOR,bg,'#'+row['primary'][-6:],plb_plan=PLAN,plb_result=PLB,identity=ident);name=f'{row["paletteId"]}.dial';(out/name).write_bytes(blob);registry.append({'paletteId':row['paletteId'],'path':str(out/name),'sha256':sha256(blob).hexdigest(),'bytes':len(blob),'primary':row['primary'],'backgroundSha256':row['backgroundSha256'],'sourceSha256':row['sourceSha256'],'packageId':ident[0],'alias':ident[2],'status':'OFFLINE_VERIFIED'})
 (out/'registry.json').write_text(json.dumps(registry,indent=2)+'\n')
if __name__=='__main__':main()
