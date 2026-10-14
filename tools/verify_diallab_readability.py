"""Offline geometry check for the photographed Dial Lab 3000 overlap.

This catches nodes entering the illustration and other live data lanes. It does
not emulate GT01 fonts, clipping, animation, or firmware-side layout rules.
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PLAN = ROOT / "work/face-layout-20260927/diallab3000-qc176-plan-v6.json"
PACKAGE = ROOT / "work/android/app/src/main/assets/dynamic_dials/pipboy3000/phosphor/face.dial"


def intersects(a: list[int], b: list[int]) -> bool:
    return a[0] < b[0] + b[2] and b[0] < a[0] + a[2] and a[1] < b[1] + b[3] and b[1] < a[1] + a[3]


def main() -> None:
    edits = json.loads(PLAN.read_text(encoding="utf-8"))["edits"]
    nodes = {entry["node_id"]: entry["replacement"] for entry in edits}
    time = ["H_S", "H_G", "M_G_1", "M_S", "M_G"]
    illustration = [109, 167, 88, 165]
    left_data = [147, 84, 126, 340]
    if any(name not in nodes for name in time):
        raise ValueError("time glyph geometry is incomplete")
    for name in time:
        box = nodes[name]
        if box[0] < 0 or box[1] < 0 or box[0] + box[2] > 466 or box[1] + box[3] > 466:
            raise ValueError("time glyph outside watch canvas: " + name)
        if intersects(box, illustration) or intersects(box, left_data):
            raise ValueError("time glyph overlaps illustration or readings: " + name)
    for first, second in (("H_S", "H_G"), ("M_S", "M_G")):
        if intersects(nodes[first], nodes[second]):
            raise ValueError("adjacent time glyphs overlap: " + first + "/" + second)
    panel = [299, 149, 81, 164]
    for name in time:
        box = nodes[name]
        if (box[0] < panel[0] or box[1] < panel[1] or
                box[0] + box[2] > panel[0] + panel[2] or
                box[1] + box[3] > panel[1] + panel[3]):
            raise ValueError("time glyph outside the right panel: " + name)
    for first in ("H_S", "H_G"):
        if intersects(nodes[first], nodes["M_G_1"]):
            raise ValueError("separator overlaps hour: " + first)
    for first in ("M_S", "M_G"):
        if intersects(nodes[first], nodes["M_G_1"]):
            raise ValueError("separator overlaps minute: " + first)
    from tools.inspect_dial import bkdr, inspect
    from tools.pgf_codec import decode
    raw = PACKAGE.read_bytes()
    report = inspect(PACKAGE, raw)
    by_hash = {item["name_hash"]: item for item in report["default_resources"]}
    for node_id, family in (("H_S", "HOUR1"), ("H_G", "HOUR2"), ("M_S", "MINUTE1"), ("M_G", "MINUTE2")):
        for digit in range(10):
            item = by_hash[f"0x{bkdr(f'images/{family}_{digit}.png'):08x}"]
            meta, _, _ = decode(raw[item["offset"]:item["offset"] + item["length"]])
            if (meta["width"], meta["height"]) != tuple(nodes[node_id][2:]):
                raise ValueError("native PGF differs from PLB: " + family)
    symbol = by_hash[f"0x{bkdr('images/symbol.png'):08x}"]
    meta, _, _ = decode(raw[symbol["offset"]:symbol["offset"] + symbol["length"]])
    if (meta["width"], meta["height"]) != tuple(nodes["M_G_1"][2:]):
        raise ValueError("native separator PGF differs from PLB")
    print("Dial Lab v6: 41 native PGFs match PLB and fit the right panel")
    print("GT01 physical rendering remains unverified")


if __name__ == "__main__":
    main()
