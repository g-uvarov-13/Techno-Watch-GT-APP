#!/usr/bin/env python3
"""Bounded static JRRY inventory. Bytecode is never executed or disassembled."""
import argparse
import json
import re
import struct
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--index", action="append", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError(f"output exists: {args.output}")
    rows = {}
    for index_path in args.index:
        index = json.loads(index_path.read_text(encoding="utf-8"))
        for source in index.get("sources", []):
            package = source.get("manifest", {}).get("id")
            for locale in source.get("locales", []):
                for resource in locale.get("resources", []):
                    if "JRRY" not in str(resource.get("type", "")).upper():
                        continue
                    digest = resource.get("blob_sha256") or resource.get("sha256")
                    reference = {"package": package, "package_sha256": source.get("sha256"),
                                 "locale": locale.get("name"), "resource_index": resource.get("index")}
                    if digest in rows:
                        rows[digest]["references"].append(reference)
                        continue
                    meta = index.get("blobs", {}).get(digest, {})
                    path = Path(meta.get("path", index_path.parent / "blobs" / f"{digest}.bin"))
                    if not path.is_absolute():
                        path = Path.cwd() / path
                    try:
                        blob = path.read_bytes()
                        if len(blob) > 32 * 1024 * 1024:
                            raise ValueError("JRRY blob exceeds 32 MiB bound")
                        strings = [{"offset": match.start(), "text": match.group().decode("ascii")}
                                   for match in re.finditer(rb"[\x20-\x7e]{3,}", blob)]
                        paths = [item for item in strings if "/" in item["text"] or item["text"].endswith((".js", ".png"))]
                        api_words = ("start", "pause", "loop", "sensor", "subscribe", "unsubscribe",
                                     "onload", "onshow", "onhide", "onexit", "setdata", "battery", "health")
                        apis = [item for item in strings if any(word in item["text"].lower() for word in api_words)]
                        rows[digest] = {"sha256": digest, "size": len(blob), "header_hex": blob[:32].hex(),
                                        "u32_le": list(struct.unpack("<8I", blob[:32])) if len(blob) >= 32 else [],
                                        "strings": strings, "paths": paths, "api_lifecycle_candidates": apis,
                                        "references": [reference], "execution": "not_performed"}
                    except (OSError, ValueError) as exc:
                        rows[digest] = {"sha256": digest, "error": str(exc), "references": [reference],
                                        "execution": "not_performed"}
    items = sorted(rows.values(), key=lambda item: item["sha256"])
    header_clusters = {}
    string_clusters = {}
    for item in items:
        header_clusters.setdefault(item.get("header_hex", "ERROR"), []).append(item["sha256"])
        signature = tuple(sorted({entry["text"] for entry in item.get("strings", [])}))
        string_clusters.setdefault(json.dumps(signature, ensure_ascii=False), []).append(item["sha256"])
    output = {
        "format": 3,
        "items": items,
        "clusters": {"exact_header": dict(sorted(header_clusters.items())),
                     "exact_string_set": dict(sorted(string_clusters.items()))},
        "claims": [
            {"claim_id": "jrry.raw-structure", "status": "confirmed", "package_sha256": None,
             "blob_sha256": None, "offsets": [], "method": "bounded raw-byte string/header scan",
             "competing_interpretations": [], "minimal_missing_observation": None},
            {"claim_id": "jrry.control-flow", "status": "not_observed", "package_sha256": None,
             "blob_sha256": None, "offsets": [], "method": "no opcode dialect assumed; no execution",
             "competing_interpretations": ["JerryScript dialect/version unknown"],
             "minimal_missing_observation": "proven bytecode dialect or runtime trace"},
        ],
    }
    args.output.mkdir(parents=True)
    (args.output / "index.json").write_text(json.dumps(output, indent=2, ensure_ascii=False, sort_keys=True), encoding="utf-8")
    print(json.dumps({"unique_items": len(items), "references": sum(len(item["references"]) for item in items),
                      "header_clusters": len(header_clusters), "string_set_clusters": len(string_clusters)}))


if __name__ == "__main__":
    main()
