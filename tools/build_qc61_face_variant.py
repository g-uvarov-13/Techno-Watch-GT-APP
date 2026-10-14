#!/usr/bin/env python3
"""Build the bounded, offline-only QC61 transparent-hand candidate."""
from __future__ import annotations

import argparse
import json
import struct
import zlib
from hashlib import sha256
from pathlib import Path

try:
    from tools.inspect_dial import OUTER_ENTRY, RESOURCE_ENTRY, inspect
    from tools.pgf_codec import decode, lz4_literal_only
    from tools.patch_face_layout import apply_plan, load_plan
    from tools.build_qc176_palette_variant import _indexed_blob_with_source_header, _png_bgra
    from tools.patch_qc61_text_colors import _nodes as _color_nodes, NIXIE as NIXIE_TEXT_COLOR
except ModuleNotFoundError:
    from inspect_dial import OUTER_ENTRY, RESOURCE_ENTRY, inspect
    from pgf_codec import decode, lz4_literal_only
    from patch_face_layout import apply_plan, load_plan
    from build_qc176_palette_variant import _indexed_blob_with_source_header, _png_bgra
    from patch_qc61_text_colors import _nodes as _color_nodes, NIXIE as NIXIE_TEXT_COLOR


DONOR_SHA256 = "6cacd1ca10a08241845df1ecdd381b8256d0f880fbd8ab2bcd6d37f37e802731"
HAND_RESOURCES = {60: "0xe32d5126", 49: "0xc266b682", 19: "0x4f6e5a51"}
PLB_INDEX, PLB_HASH, PLB_SHA256 = 28, "0x71b4a11f", "a4553c036a2cc0b1f830aaca3e6ae460eba2fef2fc45a90fd85cd8524765fd9d"
BACKGROUND_INDEX = 55
NIXIE_ID, NIXIE_ALIAS = "com.gtdial.nixiecore", "2309202602"
NIXIE_BACKGROUND_SHA256 = "4ff7351db05eee920fac0cdc7afe39a71d58f3afd0711faf29599b98c7abb3c1"
PIXEL_ID, PIXEL_ALIAS = "com.gtdial.pixeldeck", "2309202603"
PIXEL_BACKGROUND_SHA256 = "c86d8204e4438427a461a3a6a8f56ec59863f4542b5398bc92308d18aa3b741b"


def _resources(report: dict, raw: bytes) -> tuple[dict[int, tuple[int, bytes]], list[tuple[int, int]]]:
    resources, tables = {}, []
    for locale in report["locales"]:
        tables.append((locale["offset"], locale["length"]))
        for item in locale["resources"]:
            offset, length = item["offset"], item["length"]
            blob = raw[offset:offset + length]
            previous = resources.setdefault(offset, (length, blob))
            if previous != (length, blob):
                raise ValueError("unsupported overlapping resource extents")
    return resources, tables


def _transparent_palette(source: bytes) -> bytes:
    meta, plane, palette = decode(source)
    if meta["format_raw"] not in (4, 6) or meta["compression"] not in (0, 1):
        raise ValueError("hand resource is not an indexed PGF")
    payload = plane + b"\0" * len(palette)
    if meta["compression"] == 0:
        return source[:42] + payload
    header = bytearray(source[:46])
    packed = lz4_literal_only(payload)
    struct.pack_into("<I", header, 42, len(packed))
    return bytes(header) + packed


def _rgb_png_bgra(path: Path) -> tuple[int, int, bytes]:
    data = path.read_bytes(); width, height, depth, color, compression, filtering, interlace = struct.unpack_from(">IIBBBBB", data, 16)
    if data[:8] != b"\x89PNG\r\n\x1a\n" or (depth, color, compression, filtering, interlace) != (8, 2, 0, 0, 0): return _png_bgra(path)
    pos, packed = 8, b""
    while pos < len(data):
        length = struct.unpack_from(">I", data, pos)[0]; kind = data[pos+4:pos+8]; body = data[pos+8:pos+8+length]; pos += 12 + length
        if kind == b"IDAT": packed += body
        if kind == b"IEND": break
    raw, stride, previous, rows, pos = zlib.decompress(packed), width * 3, bytearray(width * 3), [], 0
    for _ in range(height):
        filter_, row = raw[pos], bytearray(raw[pos+1:pos+1+stride]); pos += stride + 1
        for i in range(stride):
            left, up, upper = (row[i-3] if i >= 3 else 0), previous[i], (previous[i-3] if i >= 3 else 0)
            if filter_ == 1: row[i] = (row[i] + left) & 255
            elif filter_ == 2: row[i] = (row[i] + up) & 255
            elif filter_ == 3: row[i] = (row[i] + (left + up)//2) & 255
            elif filter_ == 4:
                p=left+up-upper; d=(abs(p-left),abs(p-up),abs(p-upper)); row[i]=(row[i]+(left if d[0]<=d[1] and d[0]<=d[2] else up if d[1]<=d[2] else upper))&255
            elif filter_ != 0: raise ValueError("unsupported PNG filter")
        rows.append(row); previous = row
    return width, height, b"".join(bytes((row[i+2], row[i+1], row[i], 255)) for row in rows for i in range(0, stride, 3))


def build_package(donor: Path, *, extra: dict[int, bytes] | None = None, identity: tuple[str, str] | None = None) -> bytes:
    raw = donor.read_bytes()
    if sha256(raw).hexdigest() != DONOR_SHA256:
        raise ValueError("unsupported donor SHA-256")
    report = inspect(donor, raw)
    if len(report["locales"]) != 28 or any(x["resource_count"] != 65 for x in report["locales"]):
        raise ValueError("unsupported QC61 locale/resource layout")
    resources, tables = _resources(report, raw)
    selected = []
    for item in report["default_resources"]:
        if item["index"] in HAND_RESOURCES and item["name_hash"] == HAND_RESOURCES[item["index"]]:
            selected.append(item)
    if len(selected) != 3 or {(x["index"], x["name_hash"]) for x in selected} != set(HAND_RESOURCES.items()):
        raise ValueError("expected exact QC61 hand resources")
    plb = next((x for x in report["default_resources"] if x["index"] == PLB_INDEX and x["name_hash"] == PLB_HASH), None)
    if not plb or plb["sha256"] != PLB_SHA256:
        raise ValueError("expected exact QC61 PLB")
    replacements = {offset: blob for offset, (_, blob) in resources.items()}
    for item in selected:
        replacements[item["offset"]] = _transparent_palette(replacements[item["offset"]])
    if extra:
        by_index = {item["index"]: item for item in report["default_resources"]}
        for index, blob in extra.items():
            if index not in by_index: raise ValueError(f"unknown replacement index {index}")
            replacements[by_index[index]["offset"]] = blob
    if identity:
        manifest_item = next(item for item in report["default_resources"] if item["type"] == "JSON" and b'"id"' in resources[item["offset"]][1])
        old = json.loads(resources[manifest_item["offset"]][1].rstrip(b" \0\r\n").decode("utf-8")); old.update({"id": identity[0], "name": identity[2], "alias": identity[1]})
        encoded = json.dumps(old, separators=(",", ":")).encode("utf-8")
        if len(encoded) > manifest_item["length"]: raise ValueError("identity exceeds manifest extent")
        replacements[manifest_item["offset"]] = encoded + b" " * (manifest_item["length"] - len(encoded))

    outer_size = 8 + len(report["locales"]) * OUTER_ENTRY.size
    occupied = sorted([(o, o + length, "resource", o) for o, (length, _) in resources.items()] + [(o, o + length, "table", o) for o, length in tables])
    cursor, chunks, locations = outer_size, [], {}
    for start, end, kind, key in occupied:
        if start < cursor:
            raise ValueError("unsupported overlapping container extents")
        chunks.extend((("opaque", raw[cursor:start]), (kind, key)))
        cursor = end
    chunks.append(("opaque", raw[cursor:]))
    position = outer_size
    table_lengths = dict(tables)
    for kind, value in chunks:
        if kind == "opaque": position += len(value)
        elif kind == "resource": locations[value], position = position, position + len(replacements[value])
        else: locations[("table", value)], position = position, position + table_lengths[value]
    output = bytearray(b"\0" * outer_size)
    for kind, value in chunks:
        if kind == "opaque": output.extend(value)
        elif kind == "resource": output.extend(replacements[value])
        else:
            count = struct.unpack_from("<I", raw, value + 4)[0]
            table = bytearray(raw[value:value + 8])
            for index in range(count):
                name_hash, old_offset, _, fourth = RESOURCE_ENTRY.unpack_from(raw, value + 8 + index * RESOURCE_ENTRY.size)
                table.extend(RESOURCE_ENTRY.pack(name_hash, locations[old_offset], len(replacements[old_offset]), fourth))
            output.extend(table)
    output[:8] = struct.pack("<4sI", b"CR\1\0", len(report["locales"]))
    for index, locale in enumerate(report["locales"]):
        name_hash, _, length, raw_name = OUTER_ENTRY.unpack_from(raw, 8 + index * OUTER_ENTRY.size)
        OUTER_ENTRY.pack_into(output, 8 + index * OUTER_ENTRY.size, name_hash, locations[("table", locale["offset"])], length, raw_name)
    return bytes(output)


def _tint(source: bytes, rgb: tuple[int, int, int]) -> bytes:
    meta, plane, palette = decode(source); tinted = bytearray(palette)
    for offset in range(0, len(tinted), 4):
        value, alpha = max(tinted[offset:offset + 3]), tinted[offset + 3]
        if value > alpha: raise ValueError("non-premultiplied glyph palette")
        tinted[offset:offset + 4] = bytes((rgb[2] * value // 255, rgb[1] * value // 255, rgb[0] * value // 255, alpha))
    payload = plane + bytes(tinted)
    if meta["compression"] == 0: return source[:42] + payload
    header = bytearray(source[:46]); packed = lz4_literal_only(payload); struct.pack_into("<I", header, 42, len(packed)); return bytes(header) + packed


def build_combined_nixie_plb(plan_path: Path) -> bytes:
    donor_plb = Path("work/qc61-authoring-20260923/qc61-donor.plb")
    geometry = apply_plan(donor_plb, load_plan(plan_path))
    output = bytearray(geometry)
    for offset in _color_nodes(donor_plb).values(): struct.pack_into("<I", output, offset, NIXIE_TEXT_COLOR)
    return bytes(output)


def build_nixie_package(donor: Path, background: Path, glyph_dir: Path, plan_path: Path, plb_result: Path, *, accent: str | None = None) -> bytes:
    if sha256(background.read_bytes()).hexdigest() != NIXIE_BACKGROUND_SHA256: raise ValueError("invalid pinned background hash")
    manifest = json.loads((glyph_dir / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("donor_sha256") != DONOR_SHA256 or len(manifest.get("glyphs", [])) != 20: raise ValueError("invalid pinned glyph manifest")
    raw = donor.read_bytes(); report = inspect(donor, raw); by_index = {x["index"]: x for x in report["default_resources"]}
    plan = load_plan(plan_path)
    if plan.get("donor_plb_sha256") != PLB_SHA256 or build_combined_nixie_plb(plan_path) != plb_result.read_bytes(): raise ValueError("invalid pinned combined PLB result")
    bg = by_index[BACKGROUND_INDEX]; meta, _, _ = decode(raw[bg["offset"]:bg["offset"] + bg["length"]]); width, height, pixels = _rgb_png_bgra(background)
    if (width, height) != (466, 466) or any(pixels[i] != 255 for i in range(3, len(pixels), 4)): raise ValueError("invalid pinned background")
    extra = {PLB_INDEX: plb_result.read_bytes(), BACKGROUND_INDEX: _indexed_blob_with_source_header(raw[bg["offset"]:bg["offset"] + bg["length"]], width, height, pixels, meta)}
    for glyph in manifest["glyphs"]:
        blob = (glyph_dir / glyph["output"]).read_bytes()
        if sha256(blob).hexdigest() != glyph["output_sha256"]: raise ValueError("glyph hash mismatch")
        extra[glyph["resource_index"]] = _tint(blob, (255, 122, 22)) if accent == "#FF7A16" else blob
    return build_package(donor, extra=extra, identity=(NIXIE_ID, NIXIE_ALIAS, "nixiecore"))


def build_pixel_package(donor: Path) -> bytes:
    root = Path("work/qc61-authoring-20260923")
    background, glyph_dir, plan_path, result_path = root / "pixel-deck-clean-v2-466.png", root / "pixel-glyphs", root / "pixel-deck-qc61-layout-v3.json", root / "pixel-deck-qc61-layout-v3.plb"
    if sha256(background.read_bytes()).hexdigest() != PIXEL_BACKGROUND_SHA256: raise ValueError("invalid pinned pixel background")
    raw = donor.read_bytes(); report = inspect(donor, raw); by_index = {x["index"]:x for x in report["default_resources"]}
    manifest=json.loads((glyph_dir/"manifest.json").read_text());
    if manifest.get("profile") != "pixel" or manifest.get("donor_sha256") != DONOR_SHA256 or len(manifest.get("glyphs",[])) != 20: raise ValueError("invalid pinned pixel manifest")
    donor_plb=Path("work/qc61-authoring-20260923/qc61-donor.plb"); expected=bytearray(apply_plan(donor_plb,load_plan(plan_path)))
    if sha256(result_path.read_bytes()).hexdigest() != "c25ff8012ff5ad4e313b86d50b7ae102af6325f8a6cfb7afe6797e847d5cf6fb" or bytes(expected) != result_path.read_bytes(): raise ValueError("invalid pinned pixel geometry PLB")
    for offset in _color_nodes(donor_plb).values(): struct.pack_into("<I",expected,offset,0xFF72FFF4)
    bg=by_index[BACKGROUND_INDEX]; meta,_,_=decode(raw[bg['offset']:bg['offset']+bg['length']]);w,h,p=_rgb_png_bgra(background)
    extra={PLB_INDEX:bytes(expected),BACKGROUND_INDEX:_indexed_blob_with_source_header(raw[bg['offset']:bg['offset']+bg['length']],w,h,p,meta)}
    for g in manifest['glyphs']:
        b=(glyph_dir/g['output']).read_bytes()
        if sha256(b).hexdigest()!=g['output_sha256']:raise ValueError('pixel glyph hash mismatch')
        extra[g['resource_index']]=_tint(b, (114, 255, 244))
    return build_package(donor,extra=extra,identity=(PIXEL_ID,PIXEL_ALIAS,'pixeldeck'))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("donor", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--nixie", action="store_true")
    parser.add_argument("--pixel", action="store_true")
    parser.add_argument("--background", type=Path)
    parser.add_argument("--glyph-dir", type=Path)
    parser.add_argument("--plb-plan", type=Path)
    parser.add_argument("--plb-result", type=Path)
    parser.add_argument("--accent", choices=("#FF7A16",))
    args = parser.parse_args()
    if args.output.exists(): raise FileExistsError(f"refusing to overwrite: {args.output}")
    if args.pixel: candidate = build_pixel_package(args.donor)
    elif args.nixie:
        if not all((args.background, args.glyph_dir, args.plb_plan, args.plb_result)): raise ValueError("nixie inputs are required")
        candidate = build_nixie_package(args.donor, args.background, args.glyph_dir, args.plb_plan, args.plb_result, accent=args.accent)
    else: candidate = build_package(args.donor)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(candidate)
    print(json.dumps({"output": str(args.output), "sha256": sha256(candidate).hexdigest(), "offline_only": True}))


if __name__ == "__main__": main()
