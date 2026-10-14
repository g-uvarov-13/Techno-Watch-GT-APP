#!/usr/bin/env python3
"""Fail-closed offline verifier for the QC35 template candidate."""
from __future__ import annotations
import argparse, hashlib, json
import sys
from pathlib import Path
if __package__ in (None, ""): sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from tools.inspect_dial import inspect
from tools.pgf_codec import decode
from tools.plb_inspect import R, node
from tools.inspect_qc35_template_candidate import read_rgba_png

DONOR_SHA="32b267376ff51e749212e0a7a6f51d259fdda3ab1ed331fa09dd862e4cfb5063"; BACKGROUND_SHA="38a294df4b2f6df9fc33dc218e02d275ca30362ffec2d8bd3cc226b3075b0871"
CHANGED={1,3,7,8,10}; ARROWS={1,7,10}; RECTS={"KCAL":[88,174,66,34],"HEART":[88,226,46,32],"STEPS":[79,278,101,34],"BATTERY":[88,335,60,29],"DISTAN":[190,344,59,29],"HOUR":[313,163,42,47],"MIN":[313,276,42,47],"TIME_M_D":[145,105,72,34],"TIME_W":[225,105,59,34],"TIME_S":[367,281,31,30],"AM_PM":[362,175,35,21]}
BUTTONS={"BUT_HEART":"HEART","BUT_STEP":"STEPS","BUT_CALO":"KCAL","BUT_DISTAN":"DISTAN"}
def blob(raw,item): return raw[item["offset"]:item["offset"]+item["length"]]
def parse_plb(raw):
 if raw[:4]!=b"PLB\0": raise ValueError("invalid PLB")
 r,state=R(raw),{"nodes":0,"serial":0};r.p=32;root=node(r,0,[],state)
 if r.p!=len(raw):raise ValueError("PLB trailing bytes")
 return {x["id"]:x for x in walk(root)}
def walk(n):
 yield n
 for c in n["children"]:yield from walk(c)
def rect(n):
 p=[x for x in n["properties"] if x["tag"]==6]
 if len(p)!=1:raise ValueError("tag6 count")
 return [p[0]["value"][k] for k in ("x","y","w","h")]
def verify_background(candidate, background):
 width,height,rgba=read_rgba_png(background)
 meta,plane,palette=decode(candidate)
 if (width,height,meta["width"],meta["height"],meta["format_raw"])!=(466,466,466,466,6):raise ValueError("background geometry/format")
 codes=[((rgba[i]>>5)<<5)|((rgba[i+1]>>5)<<2)|(rgba[i+2]>>6) for i in range(0,len(rgba),4)]
 used=sorted(set(codes)); expected_palette=bytearray(); remap={c:i for i,c in enumerate(used)}
 for c in used: expected_palette.extend(((c&3)*255//3,((c>>2)&7)*255//7,((c>>5)&7)*255//7,255))
 expected_plane=bytearray()
 for y in range(height): expected_plane.extend(remap[c] for c in codes[y*width:(y+1)*width]); expected_plane.extend(b"\0"*(meta["stride"]-width))
 if bytes(expected_plane)!=plane or bytes(expected_palette)!=palette:raise ValueError("decoded background quantization mismatch")
def verify(donor,candidate,background,layout,bindings):
 before,after=donor.read_bytes(),candidate.read_bytes()
 if hashlib.sha256(before).hexdigest()!=DONOR_SHA or hashlib.sha256(background.read_bytes()).hexdigest()!=BACKGROUND_SHA:raise ValueError("input SHA")
 old,new=inspect(donor,before),inspect(candidate,after)
 if len(old["locales"])!=28 or len(new["locales"])!=28 or old["manifest"]!=new["manifest"]:raise ValueError("locale/manifest")
 inventory=json.loads(bindings.read_text(encoding="utf-8")); plan=json.loads(layout.read_text(encoding="utf-8"))
 if plan["proposed_runtime_rectangles"]!={k:v for k,v in RECTS.items() if k in plan["proposed_runtime_rectangles"]}:raise ValueError("layout proposal mismatch")
 old_default={x["index"]:x for x in old["default_resources"]}; new_default={x["index"]:x for x in new["default_resources"]}
 for index in range(len(old_default)):
  a,b=old_default[index],new_default[index]
  if (a["index"],a["name_hash"],a["raw_fourth_field"],a["type"])!=(b["index"],b["name_hash"],b["raw_fourth_field"],b["type"]):raise ValueError("resource metadata")
  if index not in CHANGED and blob(before,a)!=blob(after,b):raise ValueError(f"changed resource {index}")
 for index in ARROWS:
  om,op,opa=decode(blob(before,old_default[index])); nm,np,npa=decode(blob(after,new_default[index]))
  if (om["width"],om["height"],om["stride"],om["format_raw"],om["compression"],om["palette_count"]) != (nm["width"],nm["height"],nm["stride"],nm["format_raw"],nm["compression"],nm["palette_count"]) or op!=np or opa[0::4]!=npa[0::4] or opa[1::4]!=npa[1::4] or opa[2::4]!=npa[2::4] or any(npa[3::4]):raise ValueError(f"arrow {index}")
 verify_background(blob(after,new_default[8]),background)
 old_nodes,new_nodes=parse_plb(blob(before,old_default[3])),parse_plb(blob(after,new_default[3]))
 allowed_offsets=set()
 for name,target in RECTS.items():
  if name not in old_nodes or rect(new_nodes[name])!=target:raise ValueError(f"PLB replacement {name}")
  allowed_offsets.add(next(x["offset"] for x in old_nodes[name]["properties"] if x["tag"]==6)+2)
 for name,value_name in BUTTONS.items():
  if rect(new_nodes[name])!=RECTS[value_name]:raise ValueError(f"button target {name}")
  allowed_offsets.add(next(x["offset"] for x in old_nodes[name]["properties"] if x["tag"]==6)+2)
 old_plb,new_plb=blob(before,old_default[3]),blob(after,new_default[3])
 diffs={i for i,(a,b) in enumerate(zip(old_plb,new_plb)) if a!=b}
 permitted={offset+j for offset in allowed_offsets for j in range(8)}
 if diffs-permitted:raise ValueError("PLB changed outside tag6 payloads")
 return {"classification":"OFFLINE_ONLY_NEVER_INSTALL","donorSha256":DONOR_SHA,"candidateSha256":hashlib.sha256(after).hexdigest(),"locales":28,"changedIndices":sorted(CHANGED),"backgroundQuantization":"verified","plbTag6Nodes":len(RECTS)+len(BUTTONS)}
def main():
 p=argparse.ArgumentParser();p.add_argument("--donor",type=Path,required=True);p.add_argument("--candidate",type=Path,required=True);p.add_argument("--background",type=Path,required=True);p.add_argument("--layout",type=Path,required=True);p.add_argument("--bindings",type=Path,required=True);p.add_argument("--output",type=Path,required=True);a=p.parse_args()
 if a.output.exists():raise FileExistsError("refusing to overwrite verification")
 result=verify(a.donor,a.candidate,a.background,a.layout,a.bindings);a.output.write_text(json.dumps(result,indent=2)+"\n",encoding="utf-8");print(json.dumps(result))
if __name__=="__main__":main()
