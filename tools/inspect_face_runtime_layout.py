"""Render native-size QC176 clock PGFs and mark uncertain runtime widget bounds.

This is an offline diagnostic. It does not execute JRRY or predict its position overrides.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from tools.inspect_dial import bkdr, inspect
from tools.pgf_codec import decode
from tools.plb_inspect import inspect_plb


CLOCK = {"H_S": "HOUR1", "H_G": "HOUR2", "M_S": "MINUTE1", "M_G": "MINUTE2"}
RUNTIME = {"KCAL_4", "BPM_2", "DAY_S", "DAY_G", "WEEK", "BAT_2", "PERCENT", "STEP_5", "DIS_PAN", "TEM_0"}


def rgba(blob: bytes) -> Image.Image:
    meta, plane, palette = decode(blob)
    if meta["format_raw"] not in (4, 6):
        raise ValueError("expected indexed PGF")
    pixels = bytearray()
    for y in range(meta["height"]):
        for x in range(meta["width"]):
            i = plane[y * meta["stride"] + x] * 4
            b, g, r, a = palette[i:i + 4]
            pixels.extend((r, g, b, a))
    return Image.frombytes("RGBA", (meta["width"], meta["height"]), bytes(pixels))


def nodes(root: dict):
    def walk(node: dict, parent_xy: tuple[int, int]):
        prop = next((x["value"] for x in node.get("properties", []) if x["tag"] == 6), None)
        x, y = parent_xy
        if prop is not None:
            x += prop["x"]
            y += prop["y"]
            yield node["id"], (x, y, prop["w"], prop["h"])
        for child in node.get("children", []):
            yield from walk(child, (x, y))
    return dict(walk(root, (0, 0)))


def inspect_layout(package: Path, plb_path: Path, output: Path, clock: str, *, mark_runtime: bool = True) -> dict:
    raw = package.read_bytes()
    report = inspect(package, raw)
    by_hash = {item["name_hash"]: item for item in report["default_resources"]}
    plb, _ = inspect_plb(plb_path, [])
    geometry = nodes(plb["root"])
    background = next(item for item in report["default_resources"] if item["index"] == 38)
    image = rgba(raw[background["offset"]:background["offset"] + background["length"]])
    native = {}
    digits = {"H_S": clock[0], "H_G": clock[1], "M_S": clock[2], "M_G": clock[3]}
    for node_id, family in CLOCK.items():
        path = f"images/{family}_{digits[node_id]}.png"
        item = by_hash[f"0x{bkdr(path):08x}"]
        glyph = rgba(raw[item["offset"]:item["offset"] + item["length"]])
        x, y, w, h = geometry[node_id]
        native[node_id] = {"pgf": list(glyph.size), "plb": [w, h], "xy": [x, y]}
        image.alpha_composite(glyph, (x, y))
    item = by_hash[f"0x{bkdr('images/symbol.png'):08x}"]
    glyph = rgba(raw[item["offset"]:item["offset"] + item["length"]])
    x, y, w, h = geometry["M_G_1"]
    native["M_G_1"] = {"pgf": list(glyph.size), "plb": [w, h], "xy": [x, y]}
    image.alpha_composite(glyph, (x, y))
    draw = ImageDraw.Draw(image)
    if mark_runtime:
        for node_id in sorted(RUNTIME):
            if node_id not in geometry:
                continue
            x, y, w, h = geometry[node_id]
            draw.rectangle((x, y, x + w, y + h), outline=(255, 180, 0, 255), width=1)
    output.parent.mkdir(parents=True, exist_ok=True)
    image.convert("RGB").save(output)
    result = {"package": str(package), "clock": clock, "nativeClock": native,
              "runtimeGeometry": {key: list(value) for key, value in geometry.items() if key in RUNTIME},
              "runtimePositionOverride": "unknown; JRRY was not executed",
              "image": str(output)}
    output.with_suffix(".json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("package", type=Path)
    parser.add_argument("plb", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--clock", default="1556")
    parser.add_argument("--clean", action="store_true", help="omit uncertain runtime bounds")
    args = parser.parse_args()
    if len(args.clock) != 4 or not args.clock.isdecimal():
        raise ValueError("--clock must contain four digits")
    print(json.dumps(inspect_layout(args.package, args.plb, args.output, args.clock, mark_runtime=not args.clean)))


if __name__ == "__main__":
    main()
