"""Build six isolated offline walk-animation candidates from the confirmed v3 dials."""
from __future__ import annotations

import hashlib
import argparse
import json
import struct
from pathlib import Path

from tools.build_qc176_palette_variant import _png_bgra
from tools.build_dial3000_v2 import pgf_from_png
from tools.build_qc35_template_candidate import rebuild
from tools.build_shared_dial_base import parsed_plb
from tools.inspect_dial import bkdr, inspect
from tools.pgf_codec import decode

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "work/dial3000-animation"
DONOR_DIR = ROOT / "work/dial3000-v2/candidates-v3"
DONORS = {item["theme"]: item for item in json.loads((DONOR_DIR / "build-manifest.json").read_text(encoding="utf-8"))["variants"]}
PLB_INDEX = 37
MAIN_INDEX = 34
MANIFEST_INDEX = 44
RECT = (184, 138, 84, 171)
FRAME_PATHS = [f"images/0month{i}.png" for i in range(1, 5)]
QC132_PLB = ROOT / "work/myhealth-gt01-corpus-20260919/blobs/f8d20a8b34c0be3c37dfa6b259d754d085e4166695037012d8593f81cc0c87f5.bin"
QC132_PLB_SHA = "f8d20a8b34c0be3c37dfa6b259d754d085e4166695037012d8593f81cc0c87f5"


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def string(value: str) -> bytes:
    raw = value.encode("utf-8")
    return struct.pack("<H", len(raw)) + raw


def animation_node(timing_raw: int) -> bytes:
    source = QC132_PLB.read_bytes()
    if digest(source) != QC132_PLB_SHA:
        raise ValueError("QC132 PLB changed")
    proven = next(child for child in parsed_plb(source)["children"] if child["id"] == "animatedImage1")
    props = {item["tag"]: item["value"] for item in proven["properties"]}
    if (proven["type"] != "animated" or proven["auxiliary"] != "" or
            props[0] != 12561541 or props[8]["value"] != 100 or props[9]["count"] != 28):
        raise ValueError("QC132 animation contract differs")
    node = bytearray(struct.pack("<H", 1) + string("animatedImage1") + string("") + struct.pack("<H", 4))
    node.extend(struct.pack("<HI", 0, props[0]))
    node.extend(struct.pack("<H4H", 6, *RECT))
    node.extend(struct.pack("<HI", 8, timing_raw))
    node.extend(struct.pack("<HH", 9, len(FRAME_PATHS)))
    for path in FRAME_PATHS:
        node.extend(string(path))
    node.extend(struct.pack("<H", 0))
    return bytes(node)


def plb_with_animation(source: bytes, timing_raw: int) -> bytes:
    root = parsed_plb(source)
    if root["id"] != "main" or len(root["children"]) != 13 or any(child["id"] == "animatedImage1" for child in root["children"]):
        raise ValueError("unexpected v3 layout")
    body = bytearray(source[32:])
    child_count_offset = root["children"][0]["offset"] - 32 - 2
    if struct.unpack_from("<H", body, child_count_offset)[0] != 13:
        raise ValueError("unexpected root child count")
    struct.pack_into("<H", body, child_count_offset, 14)
    body.extend(animation_node(timing_raw))
    header = bytearray(source[:32])
    struct.pack_into("<I", header, 8, len(body))
    result = bytes(header + body)
    children = parsed_plb(result)["children"]
    if len(children) != 14 or children[-1]["id"] != "animatedImage1" or children[-1]["type"] != "animated":
        raise ValueError("animation PLB failed parse")
    return result


def build(theme: str, revision: int = 2) -> dict:
    if revision not in (2, 3):
        raise ValueError("unsupported animation revision")
    timing_raw = 100 if revision == 2 else 200
    donor = DONOR_DIR / f"{theme}.dial"
    donor_sha = DONORS[theme]["sha256"]
    raw = donor.read_bytes()
    if digest(raw) != donor_sha:
        raise ValueError("v3 donor changed")
    report = inspect(donor, raw)
    entries = report["default_resources"]
    if len(report["locales"]) != 23 or any(len(locale["resources"]) != 71 for locale in report["locales"]):
        raise ValueError("donor locale/resource shape changed")
    names = {item["name_hash"]: item for item in entries}
    source_js = (WORK / "src/main.js").read_bytes()
    if b'animatedImage1: {value: "start", loop: true}' not in source_js or b'animatedImage1: {value: "pause", loop: true}' not in source_js:
        raise ValueError("animation JS contract missing")
    replacements = {entries[MAIN_INDEX]["offset"]: source_js}
    plb_entry = entries[PLB_INDEX]
    replacements[plb_entry["offset"]] = plb_with_animation(raw[plb_entry["offset"]:plb_entry["offset"] + plb_entry["length"]], timing_raw)
    manifest = dict(report["manifest"])
    manifest.update(id=("com.gtdial.dial3000anim." if revision == 2 else "com.gtdial.dial3000anim2.") + theme,
                    name=("dial3000anim" if revision == 2 else "dial3000anim2") + theme,
                    alias=str((2909202621 if revision == 2 else 2909202631) + list(DONORS).index(theme)))
    replacements[entries[MANIFEST_INDEX]["offset"]] = json.dumps(manifest, separators=(",", ":"), ensure_ascii=False).encode() + b" "
    records = []
    for index, path in enumerate(FRAME_PATHS):
        entry = names.get(f"0x{bkdr(path):08x}")
        if entry is None or entry["type"] != "PGF image":
            raise ValueError("missing frame slot: " + path)
        shared = [item["name_hash"] for item in entries if item["offset"] == entry["offset"]]
        if shared != [entry["name_hash"]]:
            raise ValueError("frame slot aliases another resource")
        for locale in report["locales"]:
            locale_item = next((item for item in locale["resources"] if item["name_hash"] == entry["name_hash"]), None)
            if not locale_item or locale_item["offset"] != entry["offset"]:
                raise ValueError("frame slot locale mismatch")
        source = raw[entry["offset"]:entry["offset"] + entry["length"]]
        image = WORK / ("frames-by-theme" if revision == 2 else "frames-v2-by-theme") / theme / f"{index:02}.png"
        width, height, bgra = _png_bgra(image)
        if (width, height) != RECT[2:] or any(bgra[i] != 255 for i in range(3, len(bgra), 4)):
            raise ValueError("frame dimensions/opacity")
        blob = pgf_from_png(source, image)
        metadata, _, _ = decode(blob)
        if (metadata["width"], metadata["height"]) != RECT[2:]:
            raise ValueError("frame PGF roundtrip")
        replacements[entry["offset"]] = blob
        records.append({"index": index, "path": path, "resourceIndex": entry["index"],
                        "nameHash": entry["name_hash"], "pngSha256": digest(image.read_bytes()),
                        "pgfSha256": digest(blob), "pgfSize": len(blob)})
    result = rebuild(raw, report, replacements)
    out = WORK / f"candidates-v{revision}" / f"{theme}.dial"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(result)
    checked = inspect(out, result)
    if (len(checked["locales"]) != 23 or any(len(locale["resources"]) != 71 for locale in checked["locales"]) or
            checked["manifest"]["id"] != manifest["id"]):
        raise ValueError("rebuilt dial shape/identity")
    info = {"classification": "OFFLINE_ANIMATION_CANDIDATE_NO_WATCH_WRITE", "theme": theme,
            "sourcePackageSha256": donor_sha,
            "packageSha256": digest(result), "packageSize": len(result), "identity": manifest,
            "rect": RECT, "timingRaw": timing_raw, "timingUnit": "unknown", "frames": records,
            "changedResourceIndices": sorted([MAIN_INDEX, PLB_INDEX, MANIFEST_INDEX] + [item["resourceIndex"] for item in records])}
    (out.parent / f"{theme}-frame-manifest.json").write_text(json.dumps(info, indent=2) + "\n", encoding="utf-8")
    return info


def main(revision: int = 2) -> None:
    results = [build(theme, revision) for theme in DONORS]
    (WORK / f"candidates-v{revision}/build-manifest.json").write_text(json.dumps({"classification": "OFFLINE_ANIMATION_CANDIDATES_NO_WATCH_WRITE",
        "variants": [{key: result[key] for key in ("theme", "sourcePackageSha256", "packageSha256", "packageSize", "identity", "rect", "frames")}
                     for result in results]}, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"revision": revision, "themes": [result["theme"] for result in results], "sizes": [result["packageSize"] for result in results]}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--revision", type=int, choices=(2, 3), default=2)
    main(parser.parse_args().revision)
