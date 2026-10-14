#!/usr/bin/env python3
"""Offline-only nearest-neighbour resize of the 20 pinned QC61 digit glyphs."""
from __future__ import annotations

import argparse, json, struct
from hashlib import sha256
from pathlib import Path

try:
    from tools.inspect_dial import bkdr, inspect
    from tools.pgf_codec import decode, lz4_literal_only
except ModuleNotFoundError:
    from inspect_dial import bkdr, inspect
    from pgf_codec import decode, lz4_literal_only

DONOR_SHA256 = "6cacd1ca10a08241845df1ecdd381b8256d0f880fbd8ab2bcd6d37f37e802731"
TARGET = (40, 80, 48)
GLYPHS = tuple((family, digit, f"images/{family}_{digit}.png") for family in ("time", "sec") for digit in range(10))


def _resize(source: bytes, target_size=TARGET) -> bytes:
    meta, plane, palette = decode(source)
    if meta["format_raw"] not in (4, 6) or meta["compression"] not in (0, 1): raise ValueError("unsupported indexed glyph PGF")
    width, height, stride = target_size
    target = bytearray(stride * height)
    for y in range(height):
        sy = y * meta["height"] // height
        for x in range(width): target[y * stride + x] = plane[sy * meta["stride"] + x * meta["width"] // width]
    payload = bytes(target) + palette
    header = bytearray(source[:46] if meta["compression"] else source[:42])
    struct.pack_into("<HHH", header, 28, width, height, stride)
    struct.pack_into("<I", header, 38, len(payload))
    if meta["compression"] == 0: return bytes(header) + payload
    packed = lz4_literal_only(payload); struct.pack_into("<I", header, 42, len(packed))
    return bytes(header) + packed


def resize_glyphs(donor: Path, output: Path, *, pixel=False) -> dict:
    raw = donor.read_bytes()
    if sha256(raw).hexdigest() != DONOR_SHA256: raise ValueError("unsupported donor SHA-256")
    if output.exists(): raise FileExistsError(f"refusing to overwrite: {output}")
    report = inspect(donor, raw)
    if len(report["locales"]) != 28 or any(x["resource_count"] != 65 for x in report["locales"]): raise ValueError("unexpected QC61 layout")
    resources = {x["name_hash"]: x for x in report["default_resources"]}
    output.mkdir(parents=True); entries = []
    for family, digit, path in GLYPHS:
        name_hash = f"0x{bkdr(path):08x}"; item = resources.get(name_hash)
        if not item or item["type"] != "PGF image": raise ValueError(f"missing glyph {path}")
        target = (64, 88, 64) if pixel and family == "time" else (24, 32, 32) if pixel else TARGET
        source = raw[item["offset"]:item["offset"] + item["length"]]; candidate = _resize(source, target)
        name = f"{family}_{digit}.pgf"; (output / name).write_bytes(candidate)
        _, _, old_palette = decode(source); new_meta, _, new_palette = decode(candidate)
        if old_palette != new_palette or (new_meta["width"], new_meta["height"], new_meta["stride"]) != target: raise ValueError("glyph generation invariant failed")
        entries.append({"family": family, "digit": digit, "path": path, "resource_index": item["index"], "name_hash": name_hash, "source_sha256": sha256(source).hexdigest(), "output": name, "output_sha256": sha256(candidate).hexdigest(), "palette_sha256": sha256(old_palette).hexdigest(), "target": list(target)})
    manifest = {"format": 1, "policy": "OFFLINE_ONLY_NO_PACKAGE_INTEGRATION", "donor_sha256": DONOR_SHA256, "profile": "pixel" if pixel else "nixie", "target": {"resample": "nearest-neighbor indexed plane", "palette": "bitwise preserved"}, "glyphs": entries}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(); parser.add_argument("donor", type=Path); parser.add_argument("output", type=Path); parser.add_argument("--pixel", action="store_true"); args = parser.parse_args()
    print(json.dumps({"glyphs": len(resize_glyphs(args.donor, args.output, pixel=args.pixel)["glyphs"]), "offline_only": True}))


if __name__ == "__main__": main()
