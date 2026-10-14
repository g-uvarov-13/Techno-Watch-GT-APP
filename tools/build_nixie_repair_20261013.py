"""Offline, bounded repair of six existing QC61 Nixie packages; no device access."""
from pathlib import Path
import json, re, struct, xml.etree.ElementTree as ET
from hashlib import sha256
from PIL import Image, ImageDraw
from tools.inspect_dial import inspect
from tools.pgf_codec import decode, lz4_literal_only
from tools.build_qc61_face_variant import build_package, _rgb_png_bgra, _indexed_blob_with_source_header, _tint, PLB_INDEX, BACKGROUND_INDEX
from tools.build_final_face_palettes import QC61
from tools.patch_face_layout import apply_plan, load_plan

ROOT=Path(__file__).resolve().parents[1]
WORK=ROOT/'work/nixie-repair-20261013'
ASSETS=ROOT/'work/android/app/src/main/assets/dynamic_dials'

def digit_mask(path):
    # Rasterize the approved code-native SVG paths at 4x, including round end caps.
    tokens=re.findall(r'[MCLHVZS]|-?\d+(?:\.\d+)?',path);i=0;cmd='';current=(0.,0.);start=current;control=None;points=[]
    while i<len(tokens):
        if tokens[i].isalpha():cmd=tokens[i];i+=1
        if cmd=='Z':points.append(start);current=start;cmd='';continue
        counts={'M':2,'L':2,'C':6,'S':4,'H':1,'V':1};n=counts[cmd];v=list(map(float,tokens[i:i+n]));i+=n
        if cmd in ('M','L'):
            current=tuple(v);points.append(current)
            if cmd=='M':start=current;cmd='L'
            control=None
        elif cmd in ('H','V'):
            current=(v[0],current[1]) if cmd=='H' else (current[0],v[0]);points.append(current);control=None
        else:
            a=current;b=tuple(v[:2]) if cmd=='C' else (2*a[0]-control[0],2*a[1]-control[1]) if control else a
            c=tuple(v[2:4]) if cmd=='C' else tuple(v[:2]);d=tuple(v[4:6]) if cmd=='C' else tuple(v[2:4])
            for step in range(1,41):
                t=step/40;u=1-t;points.append(tuple(u**3*a[k]+3*u*u*t*b[k]+3*u*t*t*c[k]+t**3*d[k] for k in (0,1)))
            current=d;control=c
    image=Image.new('L',(160,320));draw=ImageDraw.Draw(image);scaled=[(x*4,y*4) for x,y in points];draw.line(scaled,fill=255,width=10,joint='curve')
    for x,y in (scaled[0],scaled[-1]):draw.ellipse((x-5,y-5,x+5,y+5),fill=255)
    return image.resize((40,80),Image.Resampling.LANCZOS)

def glyph(source,mask):
    meta,_,palette=decode(source);assert (meta['width'],meta['height'],meta['stride'])==(40,80,48) and meta['format_raw']==6 and meta['compression'] in (0,1)
    alphas=list(palette[3::4]);assert 0 in alphas and 255 in alphas
    mapping=[min(range(len(alphas)),key=lambda i:abs(alphas[i]-a)) for a in range(256)]
    plane=bytearray(48*80)
    for y in range(80):
        for x in range(40):plane[y*48+x]=mapping[mask.getpixel((x,y))]
    payload=bytes(plane)+palette;header=bytearray(source[:46] if meta['compression'] else source[:42]);struct.pack_into('<I',header,38,len(payload))
    if meta['compression']:packed=lz4_literal_only(payload);struct.pack_into('<I',header,42,len(packed));result=bytes(header)+packed
    else:result=bytes(header)+payload
    m,p,c=decode(result);assert c==palette and m['header_hex']==meta['header_hex'];return result

def main():
    output=WORK/'packages';assert not output.exists(),'candidate output already exists'
    bitmap=WORK/'background-empty-candidate.png';assert sha256(bitmap.read_bytes()).hexdigest()=='4c41759fd5e06ef55a3673f2715ae784245ea29281b9755ba6848b70fa6782eb'
    # Size normalization of the approved ImageGen background for the existing 466 canvas.
    background=Image.open(bitmap).convert('RGB').resize((466,466),Image.Resampling.LANCZOS);background.save(WORK/'background-empty-466.png')
    masks={int(p.attrib['id'].split('-')[1]):digit_mask(p.attrib['d']) for p in ET.parse(WORK/'digits.svg').getroot().iter('{http://www.w3.org/2000/svg}path')};assert len(masks)==10
    plan=json.loads((ROOT/'work/face-layout-20260927/nixie-core-qc61-layout-v3.json').read_text());edits=0
    for row in plan['edits']:
        if row['node_id'] in ('H_S','H_G','M_S','M_G','S_S','S_G'):assert row['replacement'][1]==190;row['replacement'][1]=180;edits+=1
    assert edits==6;(WORK/'layout-y180.json').write_text(json.dumps(plan,indent=2))
    plb=apply_plan(ROOT/'work/qc61-authoring-20260923/qc61-donor.plb',load_plan(WORK/'layout-y180.json'))
    manifest=json.loads((ROOT/'work/qc61-authoring-20260923/resized-glyphs-40x80/manifest.json').read_text())
    registry=json.loads((ASSETS/'offline-package-registry.json').read_text());rows=[]
    for row in registry['items']:
        if row['faceId']!='bitax':continue
        oldpath=ASSETS/'bitax'/row['paletteId']/'face.dial';raw=oldpath.read_bytes();assert sha256(raw).hexdigest()==row['packageSha256'].lower()
        report=inspect(oldpath,raw);by={r['index']:r for r in report['default_resources']};extra={i:raw[r['offset']:r['offset']+r['length']] for i,r in by.items()}
        # Existing text colors are retained; only the six digit rectangles move.
        oldplb=extra[PLB_INDEX];donorplan=apply_plan(ROOT/'work/qc61-authoring-20260923/qc61-donor.plb',load_plan(ROOT/'work/face-layout-20260927/nixie-core-qc61-layout-v3.json'))
        changed=[i for i,(a,b) in enumerate(zip(plb,donorplan)) if a!=b];assert len(changed)==6
        edited=bytearray(oldplb)
        for index in changed:assert oldplb[index]==donorplan[index];edited[index]=plb[index]
        extra[PLB_INDEX]=bytes(edited)
        meta,_,_=decode(extra[BACKGROUND_INDEX]);w,h,bgra=_rgb_png_bgra(WORK/'background-empty-466.png');base=_indexed_blob_with_source_header(extra[BACKGROUND_INDEX],w,h,bgra,meta)
        color=row['primary'];rgb=tuple(int(color[i:i+2],16) for i in (3,5,7));extra[BACKGROUND_INDEX]=_tint(base,rgb)
        for g in manifest['glyphs']:extra[g['resource_index']]=glyph(extra[g['resource_index']],masks[g['digit']])
        identity=tuple(report['manifest'][k] for k in ('id','alias','name'));blob=build_package(QC61,extra=extra,identity=identity);target=output/row['paletteId']/'face.dial';new=inspect(target,blob)
        assert tuple(new['manifest'][k] for k in ('id','alias','name'))==identity and len(new['locales'])==28
        changed_resources=[r['index'] for r in new['default_resources'] if r['sha256']!=by[r['index']]['sha256']];assert set(changed_resources)=={PLB_INDEX,BACKGROUND_INDEX,*[g['resource_index'] for g in manifest['glyphs']]}
        target.parent.mkdir(parents=True);target.write_bytes(blob);updated=dict(row);updated.update(packageSha256=sha256(blob).hexdigest(),packageSize=len(blob),backgroundSha256=sha256((WORK/'background-empty-466.png').read_bytes()).hexdigest());rows.append({'old':row,'new':updated,'changedResources':changed_resources})
    assert len(rows)==6;(output/'repair-registry.json').write_text(json.dumps(rows,indent=2));print('Six Nixie candidates validated: identity/28 locales/22 bounded resources, alpha palettes retained, y180.')

if __name__=='__main__':main()
