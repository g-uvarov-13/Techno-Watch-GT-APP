#!/usr/bin/env python3
"""Independent verifier for the offline QC61 40x80 glyph experiment."""
from __future__ import annotations

import argparse, json
from hashlib import sha256
from pathlib import Path

try:
    from tools.inspect_dial import bkdr, inspect
    from tools.pgf_codec import decode
except ModuleNotFoundError:
    from inspect_dial import bkdr, inspect
    from pgf_codec import decode

DONOR_SHA256 = "6cacd1ca10a08241845df1ecdd381b8256d0f880fbd8ab2bcd6d37f37e802731"
EXPECTED = tuple((family, digit, f"images/{family}_{digit}.png") for family in ("time", "sec") for digit in range(10))


def verify(donor: Path, directory: Path) -> dict:
    raw = donor.read_bytes()
    if sha256(raw).hexdigest() != DONOR_SHA256: raise ValueError("unsupported donor SHA-256")
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("donor_sha256") != DONOR_SHA256 or manifest.get("policy") != "OFFLINE_ONLY_NO_PACKAGE_INTEGRATION": raise ValueError("invalid manifest policy or donor")
    if manifest.get("target") != {"resample": "nearest-neighbor indexed plane", "palette": "bitwise preserved"}: raise ValueError("invalid target")
    report = inspect(donor, raw); resources = {x["name_hash"]: x for x in report["default_resources"]}
    rows = manifest.get("glyphs", [])
    if len(rows) != 20 or {(x["family"], x["digit"], x["path"]) for x in rows} != set(EXPECTED): raise ValueError("wrong glyph set")
    for row in rows:
        item = resources.get(f"0x{bkdr(row['path']):08x}")
        source = raw[item["offset"]:item["offset"] + item["length"]]
        output = (directory / row["output"]).read_bytes()
        old_meta, old_plane, old_palette = decode(source); meta, plane, palette = decode(output)
        if row["source_sha256"] != sha256(source).hexdigest() or row["output_sha256"] != sha256(output).hexdigest() or row["palette_sha256"] != sha256(old_palette).hexdigest(): raise ValueError("manifest hashes differ")
        width,height,stride=row["target"]
        if (meta["width"], meta["height"], meta["stride"], meta["format_raw"], meta["compression"], meta["palette_count"]) != (width,height,stride, old_meta["format_raw"], old_meta["compression"], old_meta["palette_count"]): raise ValueError("glyph metadata changed")
        if palette != old_palette or len(plane) != stride * height: raise ValueError("palette or plane length changed")
        for y in range(height):
            for x in range(width):
                if plane[y * stride + x] != old_plane[(y * old_meta["height"] // height) * old_meta["stride"] + x * old_meta["width"] // width]: raise ValueError("not nearest-neighbor indexed plane")
        if any(plane[y * stride + x] for y in range(height) for x in range(width, stride)): raise ValueError("nonzero stride padding")
    return {"glyphCount": 20, "offlineOnly": True, "manifestSha256": sha256((directory / "manifest.json").read_bytes()).hexdigest()}


def main() -> None:
    parser = argparse.ArgumentParser(); parser.add_argument("donor", type=Path); parser.add_argument("directory", type=Path); args = parser.parse_args(); print(json.dumps(verify(args.donor, args.directory), sort_keys=True))


if __name__ == "__main__": main()
