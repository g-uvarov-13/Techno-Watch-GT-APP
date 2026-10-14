#!/usr/bin/env python3
"""Create an offline QC35 template-preservation candidate from captured donors.

The program only reads a QC35 .dial, its extracted PLB, and a background PNG.
It writes a manifest, layout proposal, complete node/binding inventory, and a
diagnostic PNG with proposal rectangles.  It never produces a .dial, executes
JRRY, or contacts a watch.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
import zlib
from collections import Counter
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tools.inspect_dial import bkdr, inspect
from tools.plb_inspect import inspect_plb


DONOR_SHA256 = "32b267376ff51e749212e0a7a6f51d259fdda3ab1ed331fa09dd862e4cfb5063"
PLB_SHA256 = "f95cc2687ce749a3f7d2be31d355147013bdad0a523ebb6be2c4dbe1179ca991"
RECTS = {
    "KCAL": [88, 174, 66, 34], "HEART": [88, 226, 46, 32],
    "STEPS": [79, 278, 101, 34], "BATTERY": [88, 335, 60, 29],
    "DISTAN": [190, 344, 59, 29], "HOUR": [313, 163, 42, 47],
    "MIN": [313, 276, 42, 47],
}
VISIBLE_TEXT = {
    "TIME_M_D": [145, 105, 72, 34], "TIME_W": [225, 105, 59, 34],
    "TIME_S": [367, 281, 31, 30], "AM_PM": [362, 175, 35, 21],
}
BUTTON_VALUE_MAP = {
    "BUT_HEART": "HEART", "BUT_STEP": "STEPS", "BUT_CALO": "KCAL",
    "BUT_DISTAN": "DISTAN",
}
QCCLOCK_PATHS = {
    "images/hour.png": {"index": 10, "sha256_prefix": "1f979ec4"},
    "images/min.png": {"index": 7, "sha256_prefix": "8febe9cb"},
    "images/sec.png": {"index": 1, "sha256_prefix": "e0ee1814"},
}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    choices = ((abs(p - a), a), (abs(p - b), b), (abs(p - c), c))
    return min(choices)[1]


def read_rgba_png(path: Path) -> tuple[int, int, bytearray]:
    """Read the project's non-interlaced 8-bit RGBA background without Pillow."""
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    chunks, cursor = [], 8
    while cursor < len(data):
        length = struct.unpack_from(">I", data, cursor)[0]
        kind = data[cursor + 4:cursor + 8]
        body = data[cursor + 8:cursor + 8 + length]
        chunks.append((kind, body))
        cursor += 12 + length
    ihdr = next(body for kind, body in chunks if kind == b"IHDR")
    width, height, depth, color, compression, filt, interlace = struct.unpack(">IIBBBBB", ihdr)
    if (depth, color, compression, filt, interlace) != (8, 6, 0, 0, 0):
        raise ValueError("expected non-interlaced 8-bit RGBA PNG")
    packed = zlib.decompress(b"".join(body for kind, body in chunks if kind == b"IDAT"))
    stride, previous, pixels, offset = width * 4, bytearray(width * 4), bytearray(), 0
    for _ in range(height):
        filter_type, row = packed[offset], bytearray(packed[offset + 1:offset + 1 + stride])
        offset += stride + 1
        for i, value in enumerate(row):
            left = row[i - 4] if i >= 4 else 0
            up = previous[i]
            upper_left = previous[i - 4] if i >= 4 else 0
            if filter_type == 1: row[i] = (value + left) & 255
            elif filter_type == 2: row[i] = (value + up) & 255
            elif filter_type == 3: row[i] = (value + ((left + up) // 2)) & 255
            elif filter_type == 4: row[i] = (value + paeth(left, up, upper_left)) & 255
            elif filter_type != 0: raise ValueError(f"unsupported PNG filter {filter_type}")
        pixels.extend(row); previous = row
    return width, height, pixels


def write_rgba_png(path: Path, width: int, height: int, pixels: bytearray) -> None:
    def chunk(kind: bytes, body: bytes) -> bytes:
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xffffffff)
    rows = b"".join(b"\0" + pixels[row * width * 4:(row + 1) * width * 4] for row in range(height))
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b""))


def rectangle(pixels: bytearray, canvas_width: int, canvas_height: int, rect: list[int]) -> None:
    x, y, width, height = rect
    for px in range(x, x + width):
        for py in (y, y + height - 1):
            if 0 <= px < canvas_width and 0 <= py < canvas_height: pixels[(py * canvas_width + px) * 4:(py * canvas_width + px) * 4 + 4] = bytes((255, 160, 0, 255))
    for py in range(y, y + height):
        for px in (x, x + width - 1):
            if 0 <= px < canvas_width and 0 <= py < canvas_height: pixels[(py * canvas_width + px) * 4:(py * canvas_width + px) * 4 + 4] = bytes((255, 160, 0, 255))


def overlaps(a: list[int], b: list[int]) -> bool:
    return a[0] < b[0] + b[2] and b[0] < a[0] + a[2] and a[1] < b[1] + b[3] and b[1] < a[1] + a[3]


def qccclock_resources(dial: dict) -> dict:
    hashes = {f"0x{bkdr(path):08x}": (path, expected) for path, expected in QCCLOCK_PATHS.items()}
    locales = []
    for locale in dial["locales"]:
        found = []
        for resource in locale["resources"]:
            expected = hashes.get(resource["name_hash"])
            if expected is None:
                continue
            path, check = expected
            if resource["index"] != check["index"] or not resource["sha256"].startswith(check["sha256_prefix"]):
                raise ValueError(f"unexpected QCCLOCK {path} resource in locale {locale['name']}")
            found.append({"path": path, "name_hash": resource["name_hash"], "index": resource["index"], "sha256": resource["sha256"]})
        if len(found) != len(QCCLOCK_PATHS):
            raise ValueError(f"missing QCCLOCK resource in locale {locale['name']}")
        locales.append({"locale": locale["name"], "resources": found})
    return {"literal_clock_identifier": "QCCLOCK", "resource_mapping_method": "BKDR-131 literal path hash matched to each locale resource table", "all_locales_verified": True, "locales": locales, "status": "resource identity is established; runtime timing and rendering semantics remain unknown"}


def properties(node: dict) -> dict[str, list[dict]]:
    grouped: dict[str, list[dict]] = {}
    for prop in node.get("properties", []):
        grouped.setdefault(str(prop["tag"]), []).append(prop["decoded_value"])
    return grouped


def walk(node: dict, parent: str | None = None):
    yield node, parent
    for child in node.get("children", []):
        yield from walk(child, node["id"])


def resource_name(resource: dict) -> str | None:
    # A CR resource table preserves only its BKDR-131 name hash.  Literal names
    # can be evidenced only when the PLB itself references one.
    return None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dial", type=Path, required=True)
    parser.add_argument("--plb", type=Path, required=True)
    parser.add_argument("--background", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--replace", action="store_true", help="replace only this tool's four owned output artifacts")
    args = parser.parse_args()

    if args.output.exists() and not args.replace:
        raise ValueError(f"refusing to overwrite existing output: {args.output}")
    if sha256(args.dial) != DONOR_SHA256:
        raise ValueError("unexpected QC35 donor SHA-256")
    if sha256(args.plb) != PLB_SHA256:
        raise ValueError("unexpected QC35 PLB SHA-256")

    dial = inspect(args.dial)
    plb, _ = inspect_plb(args.plb, [])
    width, height, image = read_rgba_png(args.background)
    if (width, height) != (466, 466):
        raise ValueError(f"expected 466x466 background, got {(width, height)}")
    background_sha = sha256(args.background)

    nodes = []
    literal_paths: dict[str, list[str]] = {}
    for node, parent in walk(plb["root"]):
        props = properties(node)
        for path in props.get("7", []):
            if isinstance(path, str):
                literal_paths.setdefault(path, []).append(node["id"])
        nodes.append({
            "id": node["id"], "parent_id": parent, "path": node["path"],
            "type_code": node["type_code"], "type": node["type"],
            "geometry": node["derived"]["geometry_rect"],
            "properties_by_tag": props,
            "font_properties": {tag: values for tag, values in props.items() if tag in {"0", "1", "2", "3", "4", "5", "8", "9"}},
            "runtime_status": "unknown; PLB data does not prove refresh, formatting, units, or lifecycle",
        })

    visible_collisions = [{"proposed": name, "visible_text": text_name} for name, rect in RECTS.items() for text_name, text_rect in VISIBLE_TEXT.items() if overlaps(rect, text_rect)]
    button_geometry = {node["id"]: node["geometry"] for node in nodes if node["id"] in BUTTON_VALUE_MAP}
    button_intersections = [{"proposed": name, "button": button} for name, rect in RECTS.items() for button, geometry in button_geometry.items() if overlaps(rect, [geometry["x"], geometry["y"], geometry["w"], geometry["h"]])]
    layout = {
        "format": 1,
        "scope": "offline layout proposal only; no PLB or dial bytes were changed",
        "coordinate_space": {"width": 466, "height": 466, "origin": "top-left", "rect": "[x, y, width, height]"},
        "proposed_runtime_rectangles": RECTS,
        "visible_text_diagnostic_rectangles": VISIBLE_TEXT,
        "button_value_mapping": {button: {"value": value, "button_geometry": button_geometry[button]} for button, value in BUTTON_VALUE_MAP.items()},
        "visible_text_collisions": visible_collisions,
        "button_hit_test_intersections": button_intersections,
        "runtime_semantics": "unknown: labels are planning names only and must not be treated as proven data bindings",
        "source_layout": {"plb_sha256": PLB_SHA256, "node_count": plb["node_count"], "geometry_status": plb["geometry_status"]},
    }
    bindings = {
        "format": 1,
        "scope": "offline donor inventory; no JRRY execution and no physical verification",
        "donor": {"path": str(args.dial), "sha256": DONOR_SHA256, "manifest": dial["manifest"], "default_resource_types": dial["default_resource_types"]},
        "plb": {"path": str(args.plb), "sha256": PLB_SHA256, "node_count": plb["node_count"], "node_type_counts": dict(Counter(n["type"] for n in nodes))},
        "nodes": nodes,
        "literal_paths": literal_paths,
        "qccclock_resources": qccclock_resources(dial),
        "unknown_runtime_behavior": ["JRRY was not executed.", "Font family, rendering flags, text formatting, update cadence, and values are not proven by this inventory.", "Proposed rectangles are not bound to donor nodes or watch data."],
    }
    manifest = {
        "format": 1, "classification": "offline-only template-preservation candidate",
        "source_background": {"path": str(args.background), "sha256": background_sha, "dimensions": [466, 466], "preserved_pixels": True},
        "donor": {"qc35_sha256": DONOR_SHA256, "plb_sha256": PLB_SHA256},
        "artifacts": ["layout-plan.json", "bindings.json", "preview.png"],
        "safety": ["No .dial was produced.", "Preview overlays are diagnostic only.", "No watch, Bluetooth, JRRY execution, or write operation occurred."],
    }

    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (args.output / "layout-plan.json").write_text(json.dumps(layout, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (args.output / "bindings.json").write_text(json.dumps(bindings, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for rect in RECTS.values(): rectangle(image, width, height, rect)
    write_rgba_png(args.output / "preview.png", width, height, image)
    print(json.dumps({"output": str(args.output), "nodes": len(nodes), "background_sha256": background_sha, "source_pixels_preserved": True}))


if __name__ == "__main__":
    main()
