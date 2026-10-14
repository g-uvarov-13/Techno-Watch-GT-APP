#!/usr/bin/env python3
"""Independent byte-level verifier for the QC61 text-color corpus artifact."""
from __future__ import annotations
import argparse
from hashlib import sha256
from pathlib import Path

try:
    from tools.patch_qc61_text_colors import DONOR_SHA256, NIXIE, NODE_IDS, SOURCE, _nodes
except ModuleNotFoundError:
    from patch_qc61_text_colors import DONOR_SHA256, NIXIE, NODE_IDS, SOURCE, _nodes

def verify(donor: Path, candidate: Path) -> dict:
    before, after = donor.read_bytes(), candidate.read_bytes()
    if sha256(before).hexdigest() != DONOR_SHA256: raise ValueError("unsupported QC61 PLB SHA-256")
    offsets = _nodes(donor)
    if len(before) != len(after): raise ValueError("PLB length changed")
    allowed = {offset + index for offset in offsets.values() for index in range(4)}
    changed = {index for index, (old, new) in enumerate(zip(before, after)) if old != new}
    if not changed <= allowed: raise ValueError("changed bytes are outside five tag1 payloads")
    for node in NODE_IDS:
        offset = offsets[node]
        if int.from_bytes(before[offset:offset+4], "little") != SOURCE or int.from_bytes(after[offset:offset+4], "little") != NIXIE: raise ValueError("unexpected text color payload")
    return {"nodeIds": list(NODE_IDS), "changedPayloadBytes": len(allowed), "physicalChangedBytes": len(changed), "runtime": "UNVERIFIED"}

def main():
    p=argparse.ArgumentParser();p.add_argument("donor",type=Path);p.add_argument("candidate",type=Path);a=p.parse_args();print(verify(a.donor,a.candidate))

if __name__ == "__main__": main()
