#!/usr/bin/env python3
"""Corpus-only QC61 PLB tag1 text-color experiment; no package integration."""
from __future__ import annotations
import argparse, struct
from hashlib import sha256
from pathlib import Path

try:
    from tools.plb_inspect import inspect_plb
except ModuleNotFoundError:
    from plb_inspect import inspect_plb

DONOR_SHA256 = "a4553c036a2cc0b1f830aaca3e6ae460eba2fef2fc45a90fd85cd8524765fd9d"
NODE_IDS = ("HEART", "STEPS", "DIS_LAB", "BAT_VALUE", "WEATH_V")
SOURCE, NIXIE = 0xFF000000, 0xFFFF7A16

def _nodes(path: Path):
    parsed, _ = inspect_plb(path, []); found = {}
    def walk(node):
        if node["id"] in NODE_IDS:
            matches = [p for p in node["properties"] if p["tag"] == 1]
            if len(matches) != 1 or matches[0]["value"] != SOURCE: raise ValueError("unexpected tag1 source color")
            found[node["id"]] = matches[0]["offset"] + 2
        for child in node["children"]: walk(child)
    walk(parsed["root"])
    if set(found) != set(NODE_IDS): raise ValueError("expected exactly five text nodes")
    return found

def patch(donor: Path) -> bytes:
    raw = donor.read_bytes()
    if sha256(raw).hexdigest() != DONOR_SHA256: raise ValueError("unsupported QC61 PLB SHA-256")
    output = bytearray(raw)
    for offset in _nodes(donor).values(): struct.pack_into("<I", output, offset, NIXIE)
    return bytes(output)

def main():
    p=argparse.ArgumentParser();p.add_argument("donor",type=Path);p.add_argument("output",type=Path);a=p.parse_args()
    if a.output.exists(): raise FileExistsError(f"refusing to overwrite: {a.output}")
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_bytes(patch(a.donor))

if __name__ == "__main__": main()
