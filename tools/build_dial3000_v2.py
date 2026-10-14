"""Build offline Dial 3000 visual candidates from the physically proven package."""
from __future__ import annotations

import hashlib
import argparse
import json
import struct
from pathlib import Path

from tools.build_qc176_palette_variant import _indexed_blob_with_source_header, _png_bgra
from tools.build_qc35_template_candidate import rebuild
from tools.build_shared_dial_base import parsed_plb
from tools.inspect_dial import bkdr, inspect
from tools.pgf_codec import decode, encode_indexed_bgra, lz4_literal_only
from tools.render_dial3000_next_mockup import COLORS

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "work/dial3000-v2"
DONOR = ROOT / "work/shared-dial-base/candidates-v5/dial3000.dial"
DONOR_SHA = "71c6c18f914f8b0caee64faf9f3cad26faddd059a2308846e3431a6265713326"
TEXT = ("TIME_M_D", "TIME_W", "TIME_S", "STEPS", "KCAL", "HEART", "BATTERY", "DISTAN")
RECTS = {
    "TIME_M_D": (140, 105, 118, 34), "TIME_W": (269, 105, 36, 34),
    "TIME_S": (323, 334, 42, 29), "STEPS": (77, 270, 101, 34),
    "KCAL": (84, 166, 66, 34), "HEART": (84, 218, 46, 32),
    "BATTERY": (88, 335, 60, 29), "DISTAN": (190, 344, 59, 29),
}
FONTS = {"TIME_M_D": 16, "TIME_W": 16, "TIME_S": 21, "STEPS": 21,
         "KCAL": 21, "HEART": 19, "BATTERY": 19, "DISTAN": 19}
IMAGES = {"H10": (299, 156, 40, 76), "H01": (345, 156, 40, 76),
          "M10": (299, 253, 40, 76), "M01": (345, 253, 40, 76),
          "HUD": (69, 323, 84, 10)}
ALIASES = {"phosphor": "2909202601", "amber": "2909202602", "cyan": "2909202603",
           "white": "2909202604", "red": "2909202605", "violet": "2909202606"}


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def image_node(ident: str, rect: tuple[int, int, int, int]) -> bytes:
    name = ident.encode("ascii")
    return (struct.pack("<HH", 10, len(name)) + name + struct.pack("<HHHIH4HH", 0, 2, 0, 13947080, 6, *rect, 0))


def make_plb(source: bytes, argb: int, steps_shift: int = 0) -> bytes:
    root = parsed_plb(source)
    if root["id"] != "main" or len(root["children"]) != 11:
        raise ValueError("unexpected proven PLB root")
    by_name = {child["id"]: child for child in root["children"]}
    if set(by_name) != set(TEXT) | {"HOUR", "MIN", "AM_PM"}:
        raise ValueError("unexpected proven PLB nodes")
    prefix = bytearray(source[32:root["children"][0]["offset"]])
    struct.pack_into("<H", prefix, len(prefix) - 2, len(TEXT) + len(IMAGES))
    body = bytearray(prefix)
    for name in TEXT:
        child = by_name[name]
        blob = bytearray(source[child["offset"]:child["end"]])
        props = {item["tag"]: item for item in child["properties"]}
        rect = RECTS[name]
        if name == "STEPS":
            rect = (rect[0] + steps_shift, *rect[1:])
        struct.pack_into("<4H", blob, props[6]["offset"] - child["offset"] + 2, *rect)
        struct.pack_into("<I", blob, props[1]["offset"] - child["offset"] + 2, argb)
        struct.pack_into("<H", blob, props[3]["offset"] - child["offset"] + 2, FONTS[name])
        body.extend(blob)
    for name, rect in IMAGES.items():
        body.extend(image_node(name, rect))
    header = bytearray(source[:32])
    struct.pack_into("<I", header, 8, len(body))
    result = bytes(header + body)
    children = parsed_plb(result)["children"]
    if [(item["id"], item["type"]) for item in children] != [(name, "text") for name in TEXT] + [(name, "image") for name in IMAGES]:
        raise ValueError("generated PLB nodes differ")
    return result


def pgf_from_png(source: bytes, png: Path) -> bytes:
    meta, _, _ = decode(source)
    if meta["format_raw"] != 6 or meta["compression"] not in (0, 1):
        raise ValueError("unexpected proven indexed PGF")
    width, height, bgra = _png_bgra(png)
    if any(bgra[i] != 255 for i in range(3, len(bgra), 4)):
        raise ValueError("PGF image must be opaque")
    stride = (width + 15) // 16 * 16
    encoded = encode_indexed_bgra(width, height, bgra, 6, stride)
    generated, plane, palette = decode(encoded)
    header = bytearray(source[:46] if meta["compression"] == 1 else source[:42])
    struct.pack_into("<HHH", header, 28, width, height, stride)
    struct.pack_into("<H", header, 36, generated["palette_count"])
    struct.pack_into("<I", header, 38, generated["decoded_size"])
    payload = plane + palette
    if meta["compression"] == 1:
        packed = lz4_literal_only(payload)
        struct.pack_into("<I", header, 42, len(packed))
        result = bytes(header + packed)
    else:
        result = bytes(header + payload)
    roundtrip, _, _ = decode(result)
    if (roundtrip["width"], roundtrip["height"]) != (width, height):
        raise ValueError("PGF dimensions changed in round trip")
    return result


def main(revision: int = 2) -> None:
    if revision not in (2, 3):
        raise ValueError("unsupported revision")
    raw = DONOR.read_bytes()
    if sha(raw) != DONOR_SHA:
        raise ValueError("physically proven donor changed")
    app = (WORK / "src/app.js").read_bytes()
    main_js = (WORK / "src/main.js").read_bytes()
    if app != (ROOT / "work/shared-dial-base/src/app.js").read_bytes():
        raise ValueError("app lifecycle source changed")
    report = inspect(DONOR, raw)
    resources = {item["name_hash"]: item for item in report["default_resources"]}
    expected_paths = [f"images/digit_{i}.png" for i in range(10)] + [f"images/bat_{i}.png" for i in range(11)]
    expected_paths += ["images/background.png"]
    for path in expected_paths:
        item = resources.get(f"0x{bkdr(path):08x}")
        if not item or item["type"] != "PGF image":
            raise ValueError("missing proven image path " + path)
        for locale in report["locales"]:
            linked = next((entry for entry in locale["resources"] if entry["name_hash"] == item["name_hash"]), None)
            if not linked or linked["offset"] != item["offset"] or linked["raw_fourth_field"] != item["raw_fourth_field"]:
                raise ValueError("locale image mapping differs: " + path)
    if len(report["locales"]) != 23:
        raise ValueError("locale count changed")
    plb_entry = report["default_resources"][37]
    manifest_entry = report["default_resources"][44]
    program_entry = report["default_resources"][34]
    out = WORK / f"candidates-v{revision}"
    out.mkdir(parents=True, exist_ok=True)
    records = []
    prior_map = json.loads((ROOT / "work/shared-dial-base/candidates-v5/coordinate-map.json").read_text(encoding="utf-8"))
    coordinate_map = {"format": 2, "coordinateSpace": prior_map["coordinateSpace"],
                      "note": "Exact PLB rectangles. Labels, lines, character and ticks are background pixels.",
                      "variants": {"base": prior_map["variants"]["base"]}}
    for name, color in COLORS.items():
        path = out / f"{name}.dial"
        if path.exists():
            raise FileExistsError(path)
        rgb = tuple(int(color[i:i + 2], 16) for i in (1, 3, 5))
        argb = (255 << 24) | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2]
        identity = {"id": f"com.gtdial.dial3000v{revision}." + name,
                    "name": f"dial3000v{revision}" + name,
                    "alias": ALIASES[name] if revision == 2 else str(int(ALIASES[name]) + 10)}
        manifest = dict(report["manifest"])
        manifest.update(identity)
        replacements = {
            program_entry["offset"]: main_js,
            plb_entry["offset"]: make_plb(raw[plb_entry["offset"]:plb_entry["offset"] + plb_entry["length"]], argb, 6 if revision == 3 else 0),
            manifest_entry["offset"]: json.dumps(manifest, separators=(",", ":"), ensure_ascii=False).encode("utf-8") + b" ",
        }
        asset_dir = WORK / "assets" / name
        image_records = {}
        for image_path in expected_paths:
            entry = resources[f"0x{bkdr(image_path):08x}"]
            filename = image_path.removeprefix("images/")
            source = raw[entry["offset"]:entry["offset"] + entry["length"]]
            if image_path == "images/background.png":
                width, height, bgra = _png_bgra(asset_dir / filename)
                blob = _indexed_blob_with_source_header(source, width, height, bgra, decode(source)[0])
            else:
                blob = pgf_from_png(source, asset_dir / filename)
            replacements[entry["offset"]] = blob
            image_records[image_path] = {"index": entry["index"], "hash": entry["name_hash"],
                                         "sha256": sha(blob), "size": len(blob)}
        built = rebuild(raw, report, replacements)
        checked = inspect(path, built)
        if checked["manifest"]["id"] != identity["id"] or len(checked["locales"]) != 23:
            raise ValueError("built package manifest/locales invalid")
        manifest_built = checked["default_resources"][44]
        extent = built[manifest_built["offset"]:manifest_built["offset"] + manifest_built["length"]]
        if b"\0" in extent or json.loads(extent.decode("utf-8")) != manifest:
            raise ValueError("built manifest is not full JSON")
        path.write_bytes(built)
        plb_built = checked["default_resources"][37]
        plb = parsed_plb(built[plb_built["offset"]:plb_built["offset"] + plb_built["length"]])
        nodes = []
        for child in [plb] + plb["children"]:
            props = {item["tag"]: item["value"] for item in child["properties"]}
            rect = props[6]
            mapped = {"id": child["id"], "type": child["type"],
                      "rect": [rect[key] for key in ("x", "y", "w", "h")],
                      "serializationOrder": child["serialization_order"]}
            if child["type"] == "text":
                mapped.update({"fontPixels": props[3]["index"], "argb": f"#{props[1]:08X}"})
            elif child["type"] == "image":
                mapped["valueSource"] = "main.js value path"
            nodes.append(mapped)
        coordinate_map["variants"]["dial3000_" + name] = {
            "packageSha256": sha(built), "rootAndChildren": nodes,
            "backgroundRaster": {"resourceIndex": 60, "rect": [0, 0, 466, 466],
                                 "semanticParts": "raster pixels; not independent nodes"}}
        records.append({"theme": name, "identity": identity, "sha256": sha(built),
                        "size": len(built), "plbSha256": sha(replacements[plb_entry["offset"]]),
                        "mainJsSha256": sha(main_js), "appJsSha256": sha(app),
                        "images": image_records})
    (out / "build-manifest.json").write_text(json.dumps({"classification": "OFFLINE_UNVERIFIED_NO_WATCH_WRITE",
        "sourcePackageSha256": DONOR_SHA, "variants": records}, indent=2) + "\n", encoding="utf-8")
    (out / "coordinate-map.json").write_text(json.dumps(coordinate_map, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({"classification": "OFFLINE_UNVERIFIED_NO_WATCH_WRITE",
                      "variants": [{"theme": item["theme"], "sha256": item["sha256"], "size": item["size"]} for item in records]}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--revision", type=int, default=2)
    main(parser.parse_args().revision)
