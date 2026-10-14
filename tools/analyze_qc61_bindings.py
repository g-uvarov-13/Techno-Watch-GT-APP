#!/usr/bin/env python3
"""Read-only QC61 PLB, resource, and locale-reference binding extractor."""
from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path

try:
    from tools.inspect_dial import bkdr, inspect
except ModuleNotFoundError:
    from inspect_dial import bkdr, inspect

DONOR = Path("work/myhealth-gt01-catalog-20260919/dials/4eeade6e145f42bd8419e34d3d1e51d9.dial")
PLB_SHA256 = "a4553c036a2cc0b1f830aaca3e6ae460eba2fef2fc45a90fd85cd8524765fd9d"
PLB_PARSE = Path("work/gt01-plb-verify-20260920/parsed/index.json")


def _walk(node, parent=None):
    yield node, parent
    for child in node.get("children", []):
        yield from _walk(child, node["id"])


def _paths(node):
    values = []
    for prop in node.get("properties", []):
        value = prop.get("decoded_value")
        if prop["tag"] == 7 and isinstance(value, str) and "/" in value and value.lower().endswith(".png"): values.append(("hour", value, prop["offset"]))
        elif prop["tag"] in (8, 9) and isinstance(value, dict) and isinstance(value.get("value"), str) and "/" in value["value"] and value["value"].lower().endswith(".png"):
            values.append(("minute" if prop["tag"] == 8 else "second", value["value"], prop["offset"]))
    return values


def analyze(donor: Path = DONOR, parsed: Path = PLB_PARSE) -> dict:
    raw = donor.read_bytes(); report = inspect(donor, raw)
    if report["manifest"]["id"] != "com.QC.qc61" or len(report["locales"]) != 28 or any(x["resource_count"] != 65 for x in report["locales"]): raise ValueError("unexpected QC61 donor")
    default = next(x for x in report["locales"] if x["name"] == "default")
    by_hash = {x["name_hash"]: x for x in default["resources"]}
    parsed_item = next((x for x in json.loads(parsed.read_text(encoding="utf-8"))["items"] if x["sha256"] == PLB_SHA256), None)
    if not parsed_item or parsed_item["packages"] != [{"package": "com.QC.qc61", "resource_index": 28, "name_hash": "0x71b4a11f"}]: raise ValueError("unexpected QC61 PLB evidence")
    rows, family_rows = [], []
    for node, parent in _walk(parsed_item["root"]):
        node_paths = _paths(node)
        rows.append({"node_id": node["id"], "node_type": node["type"], "node_offset": node["offset"], "parent_node_id": parent, "geometry": node.get("derived", {}).get("geometry_rect"), "clock_pivots": node.get("derived", {}).get("clock_pivots", {}), "resources": []})
        for family, path, prop_offset in node_paths:
            name_hash = f"0x{bkdr(path):08x}"; resource = by_hash.get(name_hash)
            if resource is None: raise ValueError(f"unlinked PLB path: {path}")
            refs = []
            for locale in report["locales"]:
                item = locale["resources"][resource["index"]]
                if item["name_hash"] != name_hash or item["sha256"] != resource["sha256"]: raise ValueError("locale reference divergence")
                refs.append(locale["name"])
            entry = {"family": family, "path": path, "path_property_offset": prop_offset, "name_hash": name_hash, "resource_index": resource["index"], "resource_sha256": resource["sha256"], "type": resource["type"], "locale_references": refs}
            rows[-1]["resources"].append(entry); family_rows.append(entry)
    runtime_unknown = {"initialRead": None, "update": None, "cleanup": None, "units": None, "missingValueBehavior": None}
    return {"format": 1, "scope": "offline read-only evidence; no JRRY execution, BLE, install, or physical watch operation", "donor": {"path": str(donor), "sha256": __import__("hashlib").sha256(raw).hexdigest(), "identity": report["manifest"]["id"], "locale_count": 28, "resources_per_locale": 65}, "plb": {"index": 28, "name_hash": "0x71b4a11f", "sha256": PLB_SHA256, "node_count": parsed_item["node_count"]}, "method": "strict parsed PLB properties; exact UTF-8 path BKDR-131 lookup in default locale; exact index/hash/blob check in every locale", "nodes": rows, "digit_families": {"clock": family_rows, "runtime": runtime_unknown}, "counts": {"nodes": len(rows), "clock_resources": len(family_rows), "linked_locale_references": sum(len(x["locale_references"]) for x in family_rows)}, "limitations": ["PLB labels and geometry do not prove JRRY runtime behavior.", "initialRead, update, cleanup, units, and missingValueBehavior remain null.", "No physical GT01 operation or verification was performed."]}


def main() -> None:
    parser = argparse.ArgumentParser(); parser.add_argument("--output", type=Path, required=True); parser.add_argument("--donor", type=Path, default=DONOR); parser.add_argument("--parsed", type=Path, default=PLB_PARSE); args = parser.parse_args()
    if args.output.exists(): raise FileExistsError(f"refusing to overwrite: {args.output}")
    result = analyze(args.donor, args.parsed); args.output.parent.mkdir(parents=True, exist_ok=True); args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result["counts"], sort_keys=True))


if __name__ == "__main__": main()
