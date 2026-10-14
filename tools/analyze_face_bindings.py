#!/usr/bin/env python3
"""Read-only qc176 PLB-to-resource binding extractor.

This tool reads pre-existing corpus and PLB inspection JSON.  It never opens a
watch connection, executes embedded JRRY, changes a dial, or writes outside the
explicit output file.
"""
from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path

DONOR_SHA = "dfaac700817e3d363c7a03e8f6b053c1e7daf8b869e2a6c800fdcd782123edad"
PLB_SHA = "fea5e448204f445616601fd97f3258179885eb0c1c6356acc97665413000da57"


def bkdr131(text: str) -> str:
    value = 0
    for byte in text.encode("utf-8"):
        value = (value * 131 + byte) & 0xffffffff
    return f"0x{value:08x}"


def walk(node, parent_id=None):
    yield node, parent_id
    for child in node.get("children", []):
        yield from walk(child, node.get("id"))


def prop(node, tag):
    for item in node.get("properties", []):
        if item.get("tag") == tag:
            return item
    return None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--plb", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError(f"refusing to overwrite {args.output}")

    corpus = json.loads(args.corpus.read_text(encoding="utf-8"))
    donors = [s for s in corpus["sources"] if s.get("sha256") == DONOR_SHA]
    if len(donors) != 1:
        raise ValueError(f"expected one physical qc176 donor, got {len(donors)}")
    donor = donors[0]
    locale = next((x for x in donor["locales"] if x.get("name") == "default"), None)
    if locale is None:
        raise ValueError("qc176 default locale missing")
    resources = {r["name_hash"].lower(): r for r in locale["resources"]}

    parsed = json.loads(args.plb.read_text(encoding="utf-8"))
    items = [x for x in parsed["items"] if x.get("sha256") == PLB_SHA]
    if len(items) != 1:
        raise ValueError(f"expected one qc176 PLB parse, got {len(items)}")
    item = items[0]
    parents = item["packages"]
    if parents != [{"package": "com.QC.qc176", "resource_index": 54, "name_hash": "0x71b4a11f"}]:
        raise ValueError("unexpected PLB parent binding")

    rows = []
    for node, parent_id in walk(item["root"]):
        geometry = prop(node, 6)
        path_prop = prop(node, 7)
        path = path_prop.get("decoded_value") if path_prop else None
        name_hash = bkdr131(path) if isinstance(path, str) and path else None
        resource = resources.get(name_hash) if name_hash else None
        rows.append({
            "node_id": node["id"],
            "node_type": node["type"],
            "node_offset": node["offset"],
            "parent_node_id": parent_id,
            "tag6": geometry.get("decoded_value") if geometry else None,
            "tag6_property_offset": geometry.get("offset") if geometry else None,
            "path": path,
            "path_property_offset": path_prop.get("offset") if path_prop else None,
            "path_bkdr131": name_hash,
            "resource": ({
                "index": resource["index"], "name_hash": resource["name_hash"],
                "type": resource["type"], "offset": resource["offset"],
                "length": resource["length"], "sha256": resource["sha256"],
                "raw_fourth_field": resource["raw_fourth_field"],
            } if resource else None),
        })
    by_path = Counter(row["path"] for row in rows if row["path"])
    for row in rows:
        if row["path"]:
            row["sharing"] = {"same_literal_path_node_count": by_path[row["path"]]}
        else:
            row["sharing"] = None

    report = {
        "format": 1,
        "scope": "offline read-only binding evidence; no JRRY execution and no physical verification",
        "donor": {"package_id": donor["manifest"]["id"], "sha256": DONOR_SHA, "size": donor["size"], "alias": donor["manifest"]["alias"]},
        "plb": {"sha256": PLB_SHA, "parents": parents, "node_count": len(rows)},
        "method": {"path_to_resource": "exact UTF-8 literal hashed with BKDR-131 in the donor default locale", "geometry": "decoded PLB tag 6 rectangle", "sharing": "count of PLB nodes with the same literal resource path"},
        "nodes": rows,
        "limitations": ["PLB geometry and literal path linkage are offline observations.", "JRRY bytecode was not executed; labels cannot prove refresh, unit, formatting, or lifecycle semantics.", "No physical GT01 operation or verification was performed."],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"nodes": len(rows), "linked": sum(r["resource"] is not None for r in rows)}))


if __name__ == "__main__":
    main()
