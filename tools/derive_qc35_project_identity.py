#!/usr/bin/env python3
"""Derive an offline-only QC35 identity candidate without repacking the container."""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tools.inspect_dial import inspect


SOURCE_SHA256 = "79c33b2ed2e856f7404735bae58d1e6b83f1c2614f5370eea2698062c8bf309b"
RESOURCE_INDEX = 4
RESOURCE_OFFSET = 824
RESOURCE_LENGTH = 315
IDENTITY = {
    "id": "com.gtdial.dl3000",
    "name": "diallab3000",
    "alias": "2309202601",
}
MARKER = "OFFLINE_ONLY_NEVER_INSTALL"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def manifest_resource(report: dict, raw: bytes) -> tuple[dict, bytes]:
    locales = report["locales"]
    if len(locales) != 28:
        raise ValueError("QC35 must contain exactly 28 locale resource tables")
    entries = []
    for locale in locales:
        matches = [item for item in locale["resources"] if item["index"] == RESOURCE_INDEX]
        if len(matches) != 1:
            raise ValueError(f"locale {locale['name']} does not have one manifest resource")
        entries.append(matches[0])
    first = entries[0]
    if (first["offset"], first["length"], first["type"]) != (RESOURCE_OFFSET, RESOURCE_LENGTH, "JSON"):
        raise ValueError("unexpected manifest resource location or type")
    if any((item["offset"], item["length"], item["name_hash"], item["raw_fourth_field"])
           != (first["offset"], first["length"], first["name_hash"], first["raw_fourth_field"])
           for item in entries[1:]):
        raise ValueError("all locale manifest references must alias one identical resource block")
    return first, raw[RESOURCE_OFFSET:RESOURCE_OFFSET + RESOURCE_LENGTH]


def derive(source: Path) -> tuple[bytes, dict]:
    raw = source.read_bytes()
    if sha256(raw) != SOURCE_SHA256:
        raise ValueError("source candidate SHA-256 is not pinned QC35 candidate-v2")
    report = inspect(source, raw)
    entry, original_block = manifest_resource(report, raw)
    old_manifest = json.loads(original_block.decode("utf-8"))
    new_manifest = dict(old_manifest)
    new_manifest.update(IDENTITY)
    compact = json.dumps(new_manifest, ensure_ascii=True, separators=(",", ":")).encode("utf-8")
    if len(compact) > RESOURCE_LENGTH:
        raise ValueError("compact manifest exceeds fixed resource block")
    candidate = bytearray(raw)
    candidate[RESOURCE_OFFSET:RESOURCE_OFFSET + RESOURCE_LENGTH] = compact.ljust(RESOURCE_LENGTH, b" ")
    metadata = {
        "classification": MARKER,
        "marker": MARKER,
        "sourceSha256": SOURCE_SHA256,
        "candidateSha256": sha256(bytes(candidate)),
        "identity": IDENTITY,
        "manifestResource": {
            "index": RESOURCE_INDEX,
            "offset": RESOURCE_OFFSET,
            "length": RESOURCE_LENGTH,
            "byteRange": [RESOURCE_OFFSET, RESOURCE_OFFSET + RESOURCE_LENGTH],
            "oldSha256": sha256(original_block),
            "newSha256": sha256(bytes(candidate[RESOURCE_OFFSET:RESOURCE_OFFSET + RESOURCE_LENGTH])),
            "localeReferences": len(report["locales"]),
            "nameHash": entry["name_hash"],
        },
    }
    return bytes(candidate), metadata


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    outputs = [args.output / "face.dial", args.output / "manifest.json"]
    if any(path.exists() for path in outputs):
        raise FileExistsError("refusing to overwrite identity candidate artifacts")
    candidate, metadata = derive(args.source)
    args.output.mkdir(parents=True, exist_ok=True)
    outputs[0].write_bytes(candidate)
    outputs[1].write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(metadata))


if __name__ == "__main__":
    main()
