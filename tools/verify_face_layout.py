#!/usr/bin/env python3
"""Verify a GT01 PLB candidate changed only plan-authorized tag-6 payload bytes."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

try:
    from tools.patch_face_layout import _validate_plan, _walk, load_plan
    from tools.plb_inspect import Reject, inspect_plb
except ModuleNotFoundError:
    from patch_face_layout import _validate_plan, _walk, load_plan
    from plb_inspect import Reject, inspect_plb


def _nodes(parsed):
    result = {}
    for node, _ in _walk(parsed["root"]):
        if node["id"] in result:
            raise ValueError(f"duplicate node id: {node['id']!r}")
        result[node["id"]] = node
    return result


def _shape(node):
    return {key: value for key, value in node.items() if key not in {"properties", "children", "raw_hex", "end", "derived"}}


def verify(donor: Path, result: Path, plan: dict) -> dict:
    before, before_parsed, edits = _validate_plan(donor, plan)
    after = result.read_bytes()
    if len(before) != len(after):
        raise ValueError("candidate size changed")
    try:
        after_parsed, _ = inspect_plb(result, [])
    except Reject as exc:
        raise ValueError(f"invalid candidate PLB: {exc}") from exc
    before_nodes, after_nodes = _nodes(before_parsed), _nodes(after_parsed)
    if list(before_nodes) != list(after_nodes):
        raise ValueError("node hierarchy or ids changed")
    replacements = {node_id: replacement for node_id, _, _, replacement in edits}
    for node_id, old in before_nodes.items():
        new = after_nodes[node_id]
        if _shape(old) != _shape(new) or len(old["properties"]) != len(new["properties"]):
            raise ValueError(f"node structure changed: {node_id}")
        for old_prop, new_prop in zip(old["properties"], new["properties"]):
            if old_prop["tag"] != new_prop["tag"] or old_prop["offset"] != new_prop["offset"] or old_prop["end"] != new_prop["end"]:
                raise ValueError(f"property structure changed: {node_id}")
            if node_id in replacements and old_prop["tag"] == 6:
                if tuple(new_prop["value"][key] for key in ("x", "y", "w", "h")) != replacements[node_id]:
                    raise ValueError(f"replacement mismatch: {node_id}")
            elif old_prop["value"] != new_prop["value"] or old_prop["raw_hex"] != new_prop["raw_hex"]:
                raise ValueError(f"unapproved property change: {node_id}")
    allowed = {index for _, offset, _, _ in edits for index in range(offset + 2, offset + 10)}
    changed = {index for index, (old, new) in enumerate(zip(before, after)) if old != new}
    if not changed or not changed <= allowed:
        raise ValueError("byte diff exceeds tag-6 payload whitelist")
    return {"donorSha256": hashlib.sha256(before).hexdigest(), "resultSha256": hashlib.sha256(after).hexdigest(), "editedNodeIds": [node_id for node_id, _, _, _ in edits], "changedByteOffsets": sorted(changed), "physicalAcceptance": "UNVERIFIED"}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("donor", type=Path)
    parser.add_argument("result", type=Path)
    parser.add_argument("plan", type=Path)
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    if args.report.exists():
        raise FileExistsError(f"refusing to overwrite: {args.report}")
    report = verify(args.donor, args.result, load_plan(args.plan))
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
