#!/usr/bin/env python3
"""Build an offline QC35 derivative with zero-valued clock image palettes."""
from __future__ import annotations

import hashlib
import json
import struct
from pathlib import Path

from tools.build_qc35_template_candidate import rebuild
from tools.inspect_dial import inspect
from tools.pgf_codec import decode, lz4_literal_only

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "work/template-preservation-qc35/identity-candidate/face.dial"
DEST = ROOT / "work/qc35-zero-bgra-candidate"
SOURCE_SHA = "2154c496a8fb7176d25847418a332d8e67cdd493013753e4d009dd64558544d9"
ARROWS = {1: "0x4f6e5a51", 7: "0xc266b682", 10: "0xe32d5126"}


def zero_palette(blob: bytes) -> bytes:
    meta, plane, palette = decode(blob)
    if meta["format_raw"] not in (4, 6) or meta["compression"] not in (0, 1):
        raise ValueError("unexpected clock image encoding")
    if not any(palette):
        raise ValueError("clock image palette already zero")
    payload = plane + bytes(len(palette))
    if meta["compression"] == 0:
        return blob[:42] + payload
    header = bytearray(blob[:46])
    packed = lz4_literal_only(payload)
    struct.pack_into("<I", header, 42, len(packed))
    return bytes(header) + packed


def main() -> None:
    raw = SOURCE.read_bytes()
    if hashlib.sha256(raw).hexdigest() != SOURCE_SHA:
        raise ValueError("source package changed")
    report = inspect(SOURCE, raw)
    if len(report["locales"]) != 28:
        raise ValueError("unexpected locale count")
    default = {item["index"]: item for item in report["default_resources"]}
    replacements: dict[int, bytes] = {}
    for index, expected_hash in ARROWS.items():
        item = default[index]
        if item["type"] != "PGF image" or item["name_hash"] != expected_hash:
            raise ValueError(f"unexpected clock image {index}")
        offset = item["offset"]
        replacements[offset] = zero_palette(raw[offset:offset + item["length"]])
    candidate = rebuild(raw, report, replacements)
    DEST.mkdir(parents=True, exist_ok=True)
    face = DEST / "face.dial"
    manifest = DEST / "manifest.json"
    if face.exists() or manifest.exists():
        raise FileExistsError("candidate already exists")
    face.write_bytes(candidate)
    manifest.write_text(json.dumps({
        "classification": "OFFLINE_ONLY_NEVER_INSTALL",
        "sourceSha256": SOURCE_SHA,
        "candidateSha256": hashlib.sha256(candidate).hexdigest(),
        "changedResourceIndices": sorted(ARROWS),
        "hypothesis": "clock renderer may show RGB despite zero alpha",
    }, indent=2) + "\n", encoding="utf-8")
    print(manifest.read_text(encoding="utf-8"))


if __name__ == "__main__":
    main()
