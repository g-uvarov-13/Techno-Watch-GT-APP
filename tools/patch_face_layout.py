#!/usr/bin/env python3
"""Apply a hash-pinned, rectangle-only offline patch to one GT01 PLB blob."""
from __future__ import annotations

import argparse
import hashlib
import json
import struct
from pathlib import Path

try:
    from tools.plb_inspect import Reject, inspect_plb
except ModuleNotFoundError:
    from plb_inspect import Reject, inspect_plb


def _reject_duplicate(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON field: {key}")
        result[key] = value
    return result


def load_plan(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_reject_duplicate)
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"invalid plan JSON: {exc}") from exc
    if not isinstance(value, dict):
        raise ValueError("plan must be an object")
    return value


def _rect(value, name: str) -> tuple[int, int, int, int]:
    if not isinstance(value, list) or len(value) != 4:
        raise ValueError(f"{name} must be [x,y,w,h]")
    if any(type(item) is not int or item < 0 or item > 0xffff for item in value):
        raise ValueError(f"{name} must contain four unsigned 16-bit integers")
    x, y, w, h = value
    if not w or not h:
        raise ValueError(f"{name} width and height must be positive")
    return x, y, w, h


def _walk(node, parent=None):
    yield node, parent
    for child in node["children"]:
        yield from _walk(child, node)


def _tag6(node):
    matches = [item for item in node["properties"] if item["tag"] == 6]
    if len(matches) != 1:
        raise ValueError(f"node {node['id']!r} must contain exactly one tag 6")
    return matches[0]


def _validate_plan(donor: Path, plan: dict):
    if set(plan) != {"format", "donor_plb_sha256", "edits"} or type(plan["format"]) is not int or plan["format"] != 1:
        raise ValueError("plan must contain only format=1, donor_plb_sha256, and edits")
    expected_sha = plan["donor_plb_sha256"]
    if not isinstance(expected_sha, str) or len(expected_sha) != 64 or any(ch not in "0123456789abcdef" for ch in expected_sha):
        raise ValueError("donor_plb_sha256 must be 64 lowercase hexadecimal characters")
    raw = donor.read_bytes()
    if hashlib.sha256(raw).hexdigest() != expected_sha:
        raise ValueError("donor PLB SHA-256 mismatch")
    if not isinstance(plan["edits"], list) or not plan["edits"]:
        raise ValueError("plan must contain at least one edit")
    try:
        parsed, _ = inspect_plb(donor, [])
    except Reject as exc:
        raise ValueError(f"invalid donor PLB: {exc}") from exc
    nodes = {}
    for node, parent in _walk(parsed["root"]):
        node_id = node["id"]
        if not isinstance(node_id, str) or node_id in nodes:
            raise ValueError(f"duplicate or invalid PLB node id: {node_id!r}")
        nodes[node_id] = (node, parent)
    normalized = []
    requested = set()
    for edit in plan["edits"]:
        if not isinstance(edit, dict) or set(edit) != {"node_id", "expected", "replacement"}:
            raise ValueError("each edit must contain only node_id, expected, and replacement")
        node_id = edit["node_id"]
        if not isinstance(node_id, str) or node_id in requested:
            raise ValueError("duplicate or invalid requested node_id")
        requested.add(node_id)
        if node_id not in nodes:
            raise ValueError(f"unknown node_id: {node_id}")
        expected, replacement = _rect(edit["expected"], "expected"), _rect(edit["replacement"], "replacement")
        node, parent = nodes[node_id]
        property_ = _tag6(node)
        actual = tuple(property_["value"][key] for key in ("x", "y", "w", "h"))
        if expected != actual:
            raise ValueError(f"expected rectangle mismatch for {node_id}")
        if replacement == expected:
            raise ValueError(f"no-op edit for {node_id}")
        if parent is None:
            raise ValueError("root geometry cannot be edited")
        parent_rect = _tag6(parent)["value"]
        if replacement[0] + replacement[2] > parent_rect["w"] or replacement[1] + replacement[3] > parent_rect["h"]:
            raise ValueError(f"replacement is outside parent bounds for {node_id}")
        normalized.append((node_id, property_["offset"], expected, replacement))
    return raw, parsed, normalized


def apply_plan(donor: Path, plan: dict) -> bytes:
    raw, _, edits = _validate_plan(donor, plan)
    output = bytearray(raw)
    for _, offset, _, replacement in edits:
        # Tag and payload size occupy the first two bytes; only the 8-byte payload changes.
        struct.pack_into("<4H", output, offset + 2, *replacement)
    return bytes(output)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("donor", type=Path)
    parser.add_argument("plan", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError(f"refusing to overwrite: {args.output}")
    result = apply_plan(args.donor, load_plan(args.plan))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(result)
    print(json.dumps({"output": str(args.output), "sha256": hashlib.sha256(result).hexdigest(), "bytes": len(result)}))


if __name__ == "__main__":
    main()
