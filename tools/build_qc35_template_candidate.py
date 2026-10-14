#!/usr/bin/env python3
"""Build a pinned, offline-only QC35 package candidate; never install it."""
from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tools.build_qc176_palette_variant import _indexed_blob_with_source_header, _png_bgra
from tools.inspect_dial import OUTER_ENTRY, RESOURCE_ENTRY, inspect
from tools.pgf_codec import decode, lz4_literal_only
from tools.plb_inspect import R, node

DONOR_SHA = "32b267376ff51e749212e0a7a6f51d259fdda3ab1ed331fa09dd862e4cfb5063"
PLB_SHA = "f95cc2687ce749a3f7d2be31d355147013bdad0a523ebb6be2c4dbe1179ca991"
BACKGROUND_SHA = "38a294df4b2f6df9fc33dc218e02d275ca30362ffec2d8bd3cc226b3075b0871"
BACKGROUND = (8, "0xd4958190")
ARROWS = {1: "0x4f6e5a51", 7: "0xc266b682", 10: "0xe32d5126"}
RECTS = {"KCAL": [88,174,66,34], "HEART": [88,226,46,32], "STEPS": [79,278,101,34], "BATTERY": [88,335,60,29], "DISTAN": [190,344,59,29], "HOUR": [313,163,42,47], "MIN": [313,276,42,47], "TIME_M_D": [145,105,72,34], "TIME_W": [225,105,59,34], "TIME_S": [367,281,31,30], "AM_PM": [362,175,35,21]}
BUTTONS = {"BUT_HEART": "HEART", "BUT_STEP": "STEPS", "BUT_CALO": "KCAL", "BUT_DISTAN": "DISTAN"}

def digest(path: Path) -> str: return hashlib.sha256(path.read_bytes()).hexdigest()

def transparent_alpha(source: bytes) -> bytes:
    meta, plane, palette = decode(source)
    if meta["format_raw"] not in (4, 6): raise ValueError("arrow is not indexed PGF")
    changed = bytearray(palette)
    for offset in range(3, len(changed), 4): changed[offset] = 0
    payload = plane + changed
    if meta["compression"] == 0: return source[:42] + payload
    header = bytearray(source[:46]); packed = lz4_literal_only(payload); struct.pack_into("<I", header, 42, len(packed)); return bytes(header) + packed

def patch_plb(source: bytes, bindings: dict) -> bytes:
    parsed, _ = inspect_plb_bytes(source)
    nodes = {node["id"]: node for node in walk(parsed["root"])}
    out = bytearray(source)
    for node_id, rect in RECTS.items():
        node = nodes.get(node_id)
        if node is None: raise ValueError(f"missing PLB text node {node_id}")
        prop = tag6(node); expected = bindings[node_id]
        actual = [prop["value"][key] for key in ("x","y","w","h")]
        if actual != expected: raise ValueError(f"unexpected donor geometry for {node_id}")
        struct.pack_into("<4H", out, prop["offset"] + 2, *rect)
    for node_id, value_id in BUTTONS.items():
        node = nodes.get(node_id)
        if node is None: raise ValueError(f"missing PLB button {node_id}")
        prop = tag6(node); actual = [prop["value"][key] for key in ("x","y","w","h")]
        if actual != bindings[node_id]: raise ValueError(f"unexpected donor button geometry for {node_id}")
        struct.pack_into("<4H", out, prop["offset"] + 2, *RECTS[value_id])
    return bytes(out)

def inspect_plb_bytes(source: bytes):
    if len(source) < 32 or source[:4] != b"PLB\0": raise ValueError("invalid PLB")
    reader, state = R(source), {"nodes": 0, "serial": 0}; reader.p = 32
    root = node(reader, 0, [], state)
    if reader.p != len(source): raise ValueError("PLB trailing bytes")
    return {"root": root}, None

def walk(node):
    yield node
    for child in node["children"]: yield from walk(child)

def tag6(node):
    matches = [item for item in node["properties"] if item["tag"] == 6]
    if len(matches) != 1: raise ValueError(f"missing/ambiguous tag6: {node['id']}")
    return matches[0]

def rebuild(raw: bytes, report: dict, replacements: dict[int, bytes]) -> bytes:
    resources, tables = {}, []
    for locale in report["locales"]:
        tables.append((locale["offset"], locale["length"]))
        for item in locale["resources"]:
            offset, length = item["offset"], item["length"]
            previous = resources.setdefault(offset, raw[offset:offset+length])
            if previous != raw[offset:offset+length]: raise ValueError("resource alias mismatch")
    replaced = {offset: replacements.get(offset, blob) for offset, blob in resources.items()}
    outer_size = 8 + len(report["locales"]) * OUTER_ENTRY.size
    occupied = sorted([(offset, offset+len(blob), "resource", offset) for offset, blob in resources.items()] + [(offset, offset+length, "table", offset) for offset, length in tables])
    cursor, chunks = outer_size, []
    for start, end, kind, key in occupied:
        if start < cursor: raise ValueError("overlapping container extents")
        chunks.extend((("opaque", raw[cursor:start]), (kind, key))); cursor = end
    chunks.append(("opaque", raw[cursor:]))
    positions, position, lengths = {}, outer_size, dict(tables)
    for kind, key in chunks:
        if kind == "opaque": position += len(key)
        elif kind == "resource": positions[key] = position; position += len(replaced[key])
        else: positions[("table", key)] = position; position += lengths[key]
    out = bytearray(b"\0" * outer_size)
    for kind, key in chunks:
        if kind == "opaque": out.extend(key)
        elif kind == "resource": out.extend(replaced[key])
        else:
            count = struct.unpack_from("<I", raw, key + 4)[0]; table = bytearray(raw[key:key+8])
            for index in range(count):
                name_hash, old_offset, _, fourth = RESOURCE_ENTRY.unpack_from(raw, key+8+index*RESOURCE_ENTRY.size)
                table.extend(RESOURCE_ENTRY.pack(name_hash, positions[old_offset], len(replaced[old_offset]), fourth))
            out.extend(table)
    out[:8] = struct.pack("<4sI", b"CR\1\0", len(report["locales"]))
    for index, locale in enumerate(report["locales"]):
        name_hash, _, length, raw_name = OUTER_ENTRY.unpack_from(raw, 8+index*OUTER_ENTRY.size)
        OUTER_ENTRY.pack_into(out, 8+index*OUTER_ENTRY.size, name_hash, positions[("table", locale["offset"])], length, raw_name)
    return bytes(out)

def build(donor: Path, background: Path, layout: Path, bindings: Path) -> tuple[bytes, dict]:
    raw = donor.read_bytes()
    if hashlib.sha256(raw).hexdigest() != DONOR_SHA or digest(background) != BACKGROUND_SHA: raise ValueError("unpinned donor/background")
    plan, inventory = json.loads(layout.read_text(encoding="utf-8")), json.loads(bindings.read_text(encoding="utf-8"))
    if plan["source_layout"]["plb_sha256"] != PLB_SHA or inventory["donor"]["sha256"] != DONOR_SHA: raise ValueError("unpinned layout inputs")
    binding_rects = {node["id"]: [node["geometry"][key] for key in ("x","y","w","h")] for node in inventory["nodes"] if node["id"] in set(RECTS) | set(BUTTONS)}
    report = inspect(donor, raw); default = {item["index"]: item for item in report["default_resources"]}
    bg = default.get(BACKGROUND[0]); plb = default.get(3)
    if not bg or (bg["name_hash"], bg["type"]) != (BACKGROUND[1], "PGF image") or not plb or plb["sha256"] != PLB_SHA: raise ValueError("unexpected QC35 resources")
    width, height, pixels = _png_bgra(background)
    if (width, height) != (466,466) or any(pixels[offset] != 255 for offset in range(3, len(pixels), 4)): raise ValueError("background must be opaque 466x466")
    replacements = {bg["offset"]: _indexed_blob_with_source_header(raw[bg["offset"]:bg["offset"]+bg["length"]], width, height, pixels, decode(raw[bg["offset"]:bg["offset"]+bg["length"]])[0]), plb["offset"]: patch_plb(raw[plb["offset"]:plb["offset"]+plb["length"]], binding_rects)}
    for index, name_hash in ARROWS.items():
        item = default.get(index)
        if not item or item["name_hash"] != name_hash: raise ValueError("unexpected arrow resource")
        replacements[item["offset"]] = transparent_alpha(raw[item["offset"]:item["offset"]+item["length"]])
    return rebuild(raw, report, replacements), {"donorSha256": DONOR_SHA, "sourceBackgroundSha256": BACKGROUND_SHA, "changedIndices": [1,3,7,8,10], "marker": "OFFLINE_ONLY_NEVER_INSTALL"}

def main() -> None:
    parser = argparse.ArgumentParser(); parser.add_argument("--output", type=Path, required=True); parser.add_argument("--donor", type=Path, required=True); parser.add_argument("--background", type=Path, required=True); parser.add_argument("--layout", type=Path, required=True); parser.add_argument("--bindings", type=Path, required=True); args = parser.parse_args()
    files = [args.output / "face.dial", args.output / "manifest.json"]
    if any(path.exists() for path in files): raise FileExistsError("refusing to overwrite candidate artifacts")
    candidate, manifest = build(args.donor, args.background, args.layout, args.bindings)
    args.output.mkdir(parents=True, exist_ok=True); files[0].write_bytes(candidate); manifest.update({"candidateSha256": hashlib.sha256(candidate).hexdigest(), "classification": "OFFLINE_ONLY_NEVER_INSTALL"}); files[1].write_text(json.dumps(manifest, indent=2)+"\n", encoding="utf-8"); print(json.dumps(manifest))

if __name__ == "__main__": main()
