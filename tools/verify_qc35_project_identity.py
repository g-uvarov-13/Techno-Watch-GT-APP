#!/usr/bin/env python3
"""Independently verify the bounded QC35 offline identity change."""
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
    fields = ("offset", "length", "name_hash", "raw_fourth_field")
    if (first["offset"], first["length"], first["type"]) != (RESOURCE_OFFSET, RESOURCE_LENGTH, "JSON"):
        raise ValueError("unexpected manifest resource location or type")
    if any(tuple(item[field] for field in fields) != tuple(first[field] for field in fields)
           for item in entries[1:]):
        raise ValueError("all locale manifest references must alias one identical resource block")
    return first, raw[RESOURCE_OFFSET:RESOURCE_OFFSET + RESOURCE_LENGTH]


def verify(source: Path, candidate: Path) -> dict:
    before, after = source.read_bytes(), candidate.read_bytes()
    if sha256(before) != SOURCE_SHA256:
        raise ValueError("source SHA-256 is not pinned QC35 candidate-v2")
    if len(before) != len(after):
        raise ValueError("candidate changed container length")
    old_report, new_report = inspect(source, before), inspect(candidate, after)
    old_entry, old_block = manifest_resource(old_report, before)
    new_entry, new_block = manifest_resource(new_report, after)
    table_fields = ("index", "name_hash", "offset", "length", "raw_fourth_field", "source_size_field", "type")
    if any(old_entry[field] != new_entry[field] for field in table_fields):
        raise ValueError("manifest resource table metadata changed")
    old_manifest, new_manifest = json.loads(old_block.decode("utf-8")), json.loads(new_block.decode("utf-8"))
    changed = {key: [old_manifest.get(key), new_manifest.get(key)]
               for key in set(old_manifest) | set(new_manifest)
               if old_manifest.get(key) != new_manifest.get(key)}
    if changed != {key: [old_manifest[key], value] for key, value in IDENTITY.items()}:
        raise ValueError("manifest semantic diff is not exactly id, name, and alias")
    if {key: new_manifest[key] for key in IDENTITY} != IDENTITY:
        raise ValueError("candidate identity is incorrect")
    byte_diffs = [index for index, (left, right) in enumerate(zip(before, after)) if left != right]
    if any(index < RESOURCE_OFFSET or index >= RESOURCE_OFFSET + RESOURCE_LENGTH for index in byte_diffs):
        raise ValueError("byte diff escapes manifest resource range")
    old_defaults = {item["index"]: item for item in old_report["default_resources"]}
    new_defaults = {item["index"]: item for item in new_report["default_resources"]}
    if old_defaults.keys() != new_defaults.keys():
        raise ValueError("resource index set changed")
    for index, old_item in old_defaults.items():
        new_item = new_defaults[index]
        if index != RESOURCE_INDEX and before[old_item["offset"]:old_item["offset"] + old_item["length"]] != after[new_item["offset"]:new_item["offset"] + new_item["length"]]:
            raise ValueError(f"resource {index} changed")
        if index != RESOURCE_INDEX and old_item != new_item:
            raise ValueError(f"resource {index} metadata changed")
    for old_locale, new_locale in zip(old_report["locales"], new_report["locales"]):
        old_table = tuple(old_locale[field] for field in ("name", "hash", "offset", "length"))
        new_table = tuple(new_locale[field] for field in ("name", "hash", "offset", "length"))
        if old_table != new_table:
            raise ValueError("locale resource table changed")
    return {
        "classification": MARKER,
        "marker": MARKER,
        "sourceSha256": sha256(before),
        "candidateSha256": sha256(after),
        "identity": IDENTITY,
        "locales": len(old_report["locales"]),
        "manifestResource": {
            "index": RESOURCE_INDEX,
            "offset": RESOURCE_OFFSET,
            "length": RESOURCE_LENGTH,
            "byteRange": [RESOURCE_OFFSET, RESOURCE_OFFSET + RESOURCE_LENGTH],
            "oldSha256": sha256(old_block),
            "newSha256": sha256(new_block),
        },
        "semanticChangedFields": sorted(changed),
        "byteDiffRange": [min(byte_diffs), max(byte_diffs) + 1] if byte_diffs else None,
        "jrryPlbPgfAndTables": "identical",
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError("refusing to overwrite verification artifact")
    result = verify(args.source, args.candidate)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
