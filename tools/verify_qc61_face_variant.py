#!/usr/bin/env python3
"""Independently verify a bounded offline QC61 transparent-hand candidate."""
from __future__ import annotations

import argparse
import json
import struct
from hashlib import sha256
from pathlib import Path

try:
    from tools.build_qc61_face_variant import DONOR_SHA256, HAND_RESOURCES, PLB_HASH, PLB_INDEX, PLB_SHA256
    from tools.inspect_dial import inspect
    from tools.pgf_codec import decode
    from tools.patch_face_layout import apply_plan, load_plan
    from tools.patch_qc61_text_colors import _nodes as _color_nodes, NIXIE as NIXIE_TEXT_COLOR
except ModuleNotFoundError:
    from build_qc61_face_variant import DONOR_SHA256, HAND_RESOURCES, PLB_HASH, PLB_INDEX, PLB_SHA256
    from inspect_dial import inspect
    from pgf_codec import decode
    from patch_face_layout import apply_plan, load_plan
    from patch_qc61_text_colors import _nodes as _color_nodes, NIXIE as NIXIE_TEXT_COLOR


def verify(donor: Path, candidate: Path, *, nixie: bool = False, glyph_dir: Path | None = None, plb_result: Path | None = None, plb_plan: Path | None = None, accent: str | None = None) -> dict:
    before, after = donor.read_bytes(), candidate.read_bytes()
    if sha256(before).hexdigest() != DONOR_SHA256: raise ValueError("unsupported donor SHA-256")
    old, new = inspect(donor, before), inspect(candidate, after)
    if len(new["locales"]) != 28 or any(x["resource_count"] != 65 for x in new["locales"]): raise ValueError("invalid locale/resource layout")
    table_ends = [struct.unpack_from("<I", after, 8 + index * 20 + 4)[0] + struct.unpack_from("<I", after, 8 + index * 20 + 8)[0] for index in range(28)]
    if max(table_ends) != len(after): raise ValueError("final CR02 table is not at EOF")
    if not nixie and old["manifest"] != new["manifest"]: raise ValueError("manifest identity changed")
    if nixie and (new["manifest"].get("id"), new["manifest"].get("alias"), new["manifest"].get("name")) != ("com.gtdial.nixiecore", "2309202602", "nixiecore"): raise ValueError("invalid nixie identity")
    # The PLB and JRRY remain byte-identical, and no old distinct resource may alias a new one.
    old_offsets, new_offsets, changed = {}, {}, set()
    for old_locale, new_locale in zip(old["locales"], new["locales"]):
        if (old_locale["name"], old_locale["hash"], old_locale["length"]) != (new_locale["name"], new_locale["hash"], new_locale["length"]): raise ValueError("locale identity changed")
        for old_item, new_item in zip(old_locale["resources"], new_locale["resources"]):
            if (old_item["index"], old_item["name_hash"], old_item["raw_fourth_field"]) != (new_item["index"], new_item["name_hash"], new_item["raw_fourth_field"]): raise ValueError("resource metadata changed")
            old_blob = before[old_item["offset"]:old_item["offset"] + old_item["length"]]
            new_blob = after[new_item["offset"]:new_item["offset"] + new_item["length"]]
            if old_offsets.setdefault(old_item["offset"], new_item["offset"]) != new_item["offset"]: raise ValueError("shared donor resource split")
            if new_offsets.setdefault(new_item["offset"], old_item["offset"]) != old_item["offset"]: raise ValueError("candidate resource aliasing")
            key = (old_item["index"], old_item["name_hash"])
            if key in HAND_RESOURCES.items():
                changed.add(key)
                old_meta, old_plane, old_palette = decode(old_blob)
                new_meta, new_plane, new_palette = decode(new_blob)
                if old_blob[:42] != new_blob[:42]: raise ValueError("hand PGF first 42 header bytes changed")
                if (old_meta["width"], old_meta["height"], old_meta["stride"], old_meta["format_raw"], old_meta["compression"]) != (new_meta["width"], new_meta["height"], new_meta["stride"], new_meta["format_raw"], new_meta["compression"]): raise ValueError("hand PGF metadata changed")
                if (old_meta["palette_count"], old_meta["decoded_size"], len(old_plane), len(old_palette)) != (new_meta["palette_count"], new_meta["decoded_size"], len(new_plane), len(new_palette)): raise ValueError("hand PGF palette or decoded lengths changed")
                if old_plane != new_plane or any(new_palette): raise ValueError("hand plane or palette is not preserved/transparent")
            elif nixie and old_item["index"] in {0,1,6,9,12,15,21,24,25,29,32,34,36,38,40,43,46,54,55,59,63,28,35}:
                pass
            elif old_blob != new_blob: raise ValueError("non-hand resource changed")
    if changed != set(HAND_RESOURCES.items()): raise ValueError("wrong hand resources changed")
    plb = next(x for x in new["default_resources"] if x["index"] == PLB_INDEX and x["name_hash"] == PLB_HASH)
    if not nixie and plb["sha256"] != PLB_SHA256: raise ValueError("PLB changed")
    changed_indices = sorted({new_item["index"] for old_locale, new_locale in zip(old["locales"], new["locales"]) for old_item, new_item in zip(old_locale["resources"], new_locale["resources"]) if before[old_item["offset"]:old_item["offset"]+old_item["length"]] != after[new_item["offset"]:new_item["offset"]+new_item["length"]]})
    if nixie:
        allowed = {0,1,6,9,12,15,19,21,24,25,28,29,32,34,35,36,38,40,43,46,49,54,55,59,60,63}
        if set(changed_indices) != allowed: raise ValueError("wrong nixie resource diff")
        if plb_result is None or plb_plan is None or after[plb["offset"]:plb["offset"]+plb["length"]] != plb_result.read_bytes(): raise ValueError("PLB result mismatch")
        donor_plb = Path("work/qc61-authoring-20260923/qc61-donor.plb")
        expected = bytearray(apply_plan(donor_plb, load_plan(plb_plan)))
        color_offsets = _color_nodes(donor_plb)
        for offset in color_offsets.values(): struct.pack_into("<I", expected, offset, NIXIE_TEXT_COLOR)
        if bytes(expected) != plb_result.read_bytes(): raise ValueError("combined PLB union diff mismatch")
        if glyph_dir is None: raise ValueError("glyph manifest is required")
        glyph_manifest = json.loads((glyph_dir / "manifest.json").read_text(encoding="utf-8"))
        if glyph_manifest.get("donor_sha256") != DONOR_SHA256 or len(glyph_manifest.get("glyphs", [])) != 20: raise ValueError("invalid glyph manifest")
        for glyph in glyph_manifest["glyphs"]:
            item = next(x for x in new["default_resources"] if x["index"] == glyph["resource_index"])
            actual = after[item["offset"]:item["offset"]+item["length"]]
            source = (glyph_dir / glyph["output"]).read_bytes()
            if sha256(source).hexdigest() != glyph["output_sha256"]: raise ValueError("glyph input hash mismatch")
            if accent is None:
                if actual != source: raise ValueError("glyph differs from pinned output")
            elif accent == "#FF7A16":
                sm, sp, sla = decode(source); am, ap, ala = decode(actual)
                if sp != ap or sla[3::4] != ala[3::4]: raise ValueError("glyph index or alpha plane changed")
                for o in range(0, len(sla), 4):
                    v=max(sla[o:o+3]); expected=bytes((22*v//255,122*v//255,255*v//255))
                    if ala[o:o+3] != expected: raise ValueError("glyph accent differs")
            else: raise ValueError("unsupported accent")
    return {"candidateSha256": sha256(after).hexdigest(), "changedResources": len(changed), "changedIndices": changed_indices, "offlineOnly": True}

def verify_pixel(donor: Path, candidate: Path) -> dict:
    before, after=donor.read_bytes(),candidate.read_bytes()
    if sha256(before).hexdigest()!=DONOR_SHA256: raise ValueError('unsupported donor SHA-256')
    old,new=inspect(donor,before),inspect(candidate,after)
    if len(new['locales']) != 28 or any(x['resource_count'] != 65 for x in new['locales']): raise ValueError('pixel locale layout')
    table_ends=[struct.unpack_from('<I',after,8+i*20+4)[0]+struct.unpack_from('<I',after,8+i*20+8)[0] for i in range(28)]
    if max(table_ends)!=len(after):raise ValueError('pixel final CR02 table is not at EOF')
    if (new['manifest'].get('id'),new['manifest'].get('alias'),new['manifest'].get('name')) != ('com.gtdial.pixeldeck','2309202603','pixeldeck'):raise ValueError('pixel identity')
    root=Path('work/qc61-authoring-20260923'); manifest=json.loads((root/'pixel-glyphs/manifest.json').read_text()); donor_plb=root/'qc61-donor.plb'; expected=bytearray(apply_plan(donor_plb,load_plan(root/'pixel-deck-qc61-layout-v3.json')))
    for offset in _color_nodes(donor_plb).values():struct.pack_into('<I',expected,offset,0xFF72FFF4)
    allowed={0,1,6,9,12,15,19,21,24,25,28,29,32,34,35,36,38,40,43,46,49,54,55,59,60,63}
    old_offsets,new_offsets={},{}
    for lo,ln in zip(old['locales'],new['locales']):
      if (lo['name'],lo['hash'],lo['length'],lo['resource_count']) != (ln['name'],ln['hash'],ln['length'],ln['resource_count']):raise ValueError('locale metadata')
      for a,b in zip(lo['resources'],ln['resources']):
       if (a['index'],a['name_hash'],a['raw_fourth_field']) != (b['index'],b['name_hash'],b['raw_fourth_field']):raise ValueError('resource metadata')
       ab=before[a['offset']:a['offset']+a['length']];bb=after[b['offset']:b['offset']+b['length']]
       if old_offsets.setdefault(a['offset'],b['offset']) != b['offset'] or new_offsets.setdefault(b['offset'],a['offset']) != a['offset']:raise ValueError('resource aliasing')
       if a['index'] not in allowed and ab != bb:raise ValueError('non-approved Pixel resource changed')
       if a['index'] in HAND_RESOURCES:
        om,op,opa=decode(ab);nm,np,npa=decode(bb)
        if ab[:42]!=bb[:42] or op!=np or any(npa) or (om['palette_count'],om['decoded_size'],len(opa)) != (nm['palette_count'],nm['decoded_size'],len(npa)):raise ValueError('pixel hand invariant')
    plb=next(x for x in new['default_resources'] if x['index']==PLB_INDEX)
    if after[plb['offset']:plb['offset']+plb['length']] != bytes(expected):raise ValueError('pixel PLB union')
    for g in manifest['glyphs']:
      item=next(x for x in new['default_resources'] if x['index']==g['resource_index']); actual=after[item['offset']:item['offset']+item['length']]
      source=(root/'pixel-glyphs'/g['output']).read_bytes()
      if sha256(source).hexdigest()!=g['output_sha256']:raise ValueError('pixel glyph input pin')
      sm,sp,sla=decode(source);am,ap,ala=decode(actual)
      if sp!=ap or sla[3::4]!=ala[3::4] or (sm['width'],sm['height'],sm['format_raw'])!=(am['width'],am['height'],am['format_raw']):raise ValueError('pixel glyph geometry or alpha changed')
      for o in range(0,len(sla),4):
       v=max(sla[o:o+3])
       if ala[o:o+3]!=bytes((244*v//255,255*v//255,114*v//255)):raise ValueError('pixel glyph color mismatch')
    # Pinned source artifacts must still be exactly the accepted inputs.
    if sha256((root/'pixel-deck-clean-v2-466.png').read_bytes()).hexdigest() != 'c86d8204e4438427a461a3a6a8f56ec59863f4542b5398bc92308d18aa3b741b':raise ValueError('pixel background pin')
    if sha256((root/'pixel-deck-qc61-layout-v3.plb').read_bytes()).hexdigest() != 'c25ff8012ff5ad4e313b86d50b7ae102af6325f8a6cfb7afe6797e847d5cf6fb':raise ValueError('pixel geometry pin')
    if sha256((root/'pixel-glyphs/manifest.json').read_bytes()).hexdigest() != '8a99194f942eeadb43fec2f2f4560c753df2ed37857b0fb5551362cf8df0ccd7':raise ValueError('pixel glyph manifest pin')
    return {'candidateSha256':sha256(after).hexdigest(),'profile':'pixel','offlineOnly':True}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument("donor", type=Path); parser.add_argument("candidate", type=Path); parser.add_argument("--nixie", action="store_true"); parser.add_argument("--pixel", action="store_true"); parser.add_argument("--glyph-dir", type=Path); parser.add_argument("--plb-result", type=Path); parser.add_argument("--plb-plan", type=Path); parser.add_argument("--accent")
    args = parser.parse_args(); print(json.dumps(verify_pixel(args.donor,args.candidate) if args.pixel else verify(args.donor, args.candidate, nixie=args.nixie, glyph_dir=args.glyph_dir, plb_result=args.plb_result, plb_plan=args.plb_plan, accent=args.accent), sort_keys=True))


if __name__ == "__main__": main()
