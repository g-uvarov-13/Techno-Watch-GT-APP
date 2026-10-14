#!/usr/bin/env python3
"""Create a non-writing replacement impact plan for one existing dial blob."""
import argparse
import hashlib
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("index", type=Path)
    parser.add_argument("--package-sha", required=True)
    parser.add_argument("--target-blob", required=True)
    parser.add_argument("--replacement", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError(f"output exists: {args.output}")
    index = json.loads(args.index.read_text(encoding="utf-8"))
    source = next((item for item in index.get("sources", []) if item.get("sha256") == args.package_sha), None)
    if source is None:
        raise ValueError("package SHA not found in index")

    refs = []
    target_extents = set()
    for locale in source.get("locales", []):
        for resource in locale.get("resources", []):
            digest = resource.get("blob_sha256") or resource.get("sha256")
            if digest == args.target_blob:
                extent = (resource.get("offset"), resource.get("length"))
                target_extents.add(extent)
                refs.append({
                    "locale": locale.get("name"), "index": resource.get("index"),
                    "name_hash": resource.get("name_hash"), "type": resource.get("type"),
                    "offset": extent[0], "length": extent[1],
                    "raw_fourth_field": resource.get("raw_fourth_field"),
                })
    if not refs:
        raise ValueError("target blob is not referenced by selected package")
    if len(target_extents) != 1:
        raise ValueError("target blob has multiple physical extents; one replacement plan would be ambiguous")
    target_offset, target_length = next(iter(target_extents))

    replacement = None
    replacement_length = None
    if args.replacement:
        raw = args.replacement.read_bytes()
        replacement = {"path": str(args.replacement), "sha256": hashlib.sha256(raw).hexdigest(), "length": len(raw)}
        replacement_length = len(raw)
    size_delta = None if replacement_length is None else replacement_length - target_length

    relocation = []
    if size_delta:
        for locale in source.get("locales", []):
            if locale.get("offset", 0) > target_offset:
                relocation.append({"kind": "locale_table", "locale": locale.get("name"), "old_offset": locale.get("offset"), "delta": size_delta})
            for resource in locale.get("resources", []):
                if resource.get("offset", 0) > target_offset:
                    relocation.append({"kind": "resource", "locale": locale.get("name"), "index": resource.get("index"), "old_offset": resource.get("offset"), "delta": size_delta})
    relocation.sort(key=lambda row: (row["old_offset"], row["kind"], row.get("locale") or "", row.get("index", -1)))

    same_length = size_delta == 0 if size_delta is not None else None
    plan = {
        "format": 2,
        "write_performed": False,
        "package": source.get("manifest", {}).get("id"),
        "source_path": source.get("path"),
        "source_sha256": source.get("sha256"),
        "target_blob_sha256": args.target_blob,
        "target_extent": {"offset": target_offset, "length": target_length},
        "references": sorted(refs, key=lambda row: (row["locale"] or "", row["index"])),
        "replacement": replacement,
        "size_delta": size_delta,
        "same_length": same_length,
        "required_offset_updates": relocation,
        "feasibility": "structural_plan_only",
        "integrity_unknowns": [
            "CR02 raw_fourth_field semantics",
            "PLB header word at offset 12 if PLB is changed",
            "firmware/runtime acceptance of changed resource bytes",
            "alignment requirements for relocated data",
        ],
        "claims": [{
            "claim_id": "replacement.no-package-write",
            "status": "confirmed",
            "package_sha256": source.get("sha256"),
            "blob_sha256": args.target_blob,
            "offsets": [target_offset],
            "method": "read-only corpus extent and reference analysis",
            "competing_interpretations": [],
            "minimal_missing_observation": "validated integrity fields and a separately approved physical experiment",
        }],
    }
    args.output.mkdir(parents=True)
    (args.output / "plan.json").write_text(json.dumps(plan, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({"references": len(refs), "size_delta": size_delta, "offset_updates": len(relocation), "write_performed": False}))


if __name__ == "__main__":
    main()
