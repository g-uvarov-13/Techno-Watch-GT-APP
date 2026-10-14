#!/usr/bin/env python3
"""Build two offline GT01 faces from one digital runtime and explicit PLB layouts."""
from __future__ import annotations

import hashlib
import json
import struct
from pathlib import Path

from tools.build_qc176_palette_variant import _indexed_blob_with_source_header, _png_bgra
from tools.build_qc35_template_candidate import rebuild
from tools.inspect_dial import inspect
from tools.pgf_codec import decode
from tools.plb_inspect import R, node

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "work/shared-dial-base"
QC64 = ROOT / "work/myhealth-gt01-corpus-20260919/sources/ddbbaa7be6e540ea8756fd52e3e9a169.dial-b31614fd2e87.dial"
QC35 = ROOT / "work/myhealth-gt01-corpus-20260919/sources/b4cfeed623564c44b47ca25dd2ec2ae2.dial-32b267376ff5.dial"
BACKGROUND = ROOT / "work/qc176-diallab3000-offline/background-clean-opaque-466.png"
QC64_SHA = "b31614fd2e87ec56acbc79754a2c4ce4c4034e7d7c6a0d64bd6c292d08e0b874"
QC35_SHA = "32b267376ff51e749212e0a7a6f51d259fdda3ab1ed331fa09dd862e4cfb5063"
FIELDS = ("TIME_M_D", "TIME_W", "HOUR", "MIN", "TIME_S", "AM_PM", "STEPS", "KCAL", "HEART", "BATTERY", "DISTAN")
DIAGNOSTIC = {
    "TIME_M_D": (55, 52, 110, 34), "TIME_W": (190, 52, 90, 34),
    "HOUR": (105, 110, 80, 58), "MIN": (225, 110, 80, 58),
    "TIME_S": (323, 115, 50, 39), "AM_PM": (337, 165, 55, 25),
    "STEPS": (72, 222, 126, 36), "KCAL": (260, 222, 100, 36),
    "HEART": (72, 292, 100, 36), "BATTERY": (260, 292, 100, 36),
    "DISTAN": (176, 365, 120, 36),
}
DIAL3000 = {
    "TIME_M_D": (145, 105, 72, 34), "TIME_W": (225, 105, 59, 34),
    "HOUR": (313, 163, 55, 47), "MIN": (313, 276, 55, 47),
    "TIME_S": (367, 281, 31, 30), "AM_PM": (362, 175, 35, 21),
    "STEPS": (79, 270, 101, 34), "KCAL": (88, 166, 66, 34),
    "HEART": (88, 218, 46, 32), "BATTERY": (88, 335, 60, 29),
    "DISTAN": (190, 344, 59, 29),
}
BASE_FONT = {"TIME_M_D": 20, "TIME_W": 20, "HOUR": 32, "MIN": 32,
             "TIME_S": 18, "AM_PM": 14, "STEPS": 21, "KCAL": 21,
             "HEART": 19, "BATTERY": 19, "DISTAN": 19}
DIAL_FONT = {"TIME_M_D": 19, "TIME_W": 19, "HOUR": 30, "MIN": 30,
             "TIME_S": 16, "AM_PM": 14, "STEPS": 21, "KCAL": 21,
             "HEART": 19, "BATTERY": 19, "DISTAN": 19}
PHOSPHOR_ARGB = 0xFF32FF9A


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def parsed_plb(raw: bytes):
    if raw[:4] != b"PLB\0": raise ValueError("invalid PLB")
    reader = R(raw)
    reader.p = 32
    tree = node(reader, 0, [], {"nodes": 0, "serial": 0})
    if reader.p != len(raw): raise ValueError("PLB trailing bytes")
    return tree


def make_plb(source_root: bytes, source_texts: bytes, rectangles: dict, fonts: dict) -> bytes:
    root = parsed_plb(source_root)
    donor = parsed_plb(source_texts)
    if root["id"] != "main" or [p["tag"] for p in root["properties"]] != [2, 6]:
        raise ValueError("unexpected QC64 root")
    text_nodes = {child["id"]: child for child in donor["children"] if child["type"] == "text"}
    if set(FIELDS) - set(text_nodes): raise ValueError("QC35 text archetypes missing")
    prefix = bytearray(source_root[32:root["children"][0]["offset"]])
    struct.pack_into("<H", prefix, len(prefix) - 2, len(FIELDS))
    body = bytearray(prefix)
    for field in FIELDS:
        source = text_nodes[field]
        if source["children"]: raise ValueError("text archetype has children")
        blob = bytearray(source_texts[source["offset"]:source["end"]])
        rect = next(p for p in source["properties"] if p["tag"] == 6)
        struct.pack_into("<4H", blob, rect["offset"] - source["offset"] + 2, *rectangles[field])
        color = next(p for p in source["properties"] if p["tag"] == 1)
        struct.pack_into("<I", blob, color["offset"] - source["offset"] + 2, PHOSPHOR_ARGB)
        font = next(p for p in source["properties"] if p["tag"] == 3)
        struct.pack_into("<H", blob, font["offset"] - source["offset"] + 2, fonts[field])
        body.extend(blob)
    header = bytearray(source_root[:32])
    struct.pack_into("<I", header, 8, len(body))
    result = bytes(header + body)
    verified = parsed_plb(result)
    if [child["id"] for child in verified["children"]] != list(FIELDS): raise ValueError("PLB field mismatch")
    if any(child["type"] != "text" for child in verified["children"]): raise ValueError("non-text child")
    for child in verified["children"]:
        props = {item["tag"]: item["value"] for item in child["properties"]}
        if props[1] != PHOSPHOR_ARGB or props[3]["index"] != fonts[child["id"]]:
            raise ValueError("PLB text style mismatch")
    return result


def package_variant(raw: bytes, report: dict, donor_plb: bytes, background: bytes,
                    rectangles: dict, fonts: dict, identity: dict, program: dict) -> tuple[bytes, dict]:
    entries = {entry["index"]: entry for entry in report["default_resources"]}
    expected = {2: ("0x0a2e9022", "other"), 34: ("0x6f3375aa", "other"),
                37: ("0x71b4a11f", "PLB layout"), 44: ("0x8b5c306d", "JSON"),
                60: ("0xd4958190", "PGF image")}
    for index, (name_hash, kind) in expected.items():
        if (entries[index]["name_hash"], entries[index]["type"]) != (name_hash, kind):
            raise ValueError(f"unexpected QC64 resource {index}")
    def original(index: int) -> bytes:
        entry = entries[index]
        return raw[entry["offset"]:entry["offset"] + entry["length"]]
    manifest = dict(report["manifest"])
    manifest.update(identity)
    source_background = original(60)
    meta = decode(source_background)[0]
    new_background = _indexed_blob_with_source_header(source_background, 466, 466, background, meta)
    replacements = {
        entries[2]["offset"]: program["app"],
        entries[34]["offset"]: program["main"],
        entries[37]["offset"]: make_plb(original(37), donor_plb, rectangles, fonts),
        entries[44]["offset"]: json.dumps(manifest, separators=(",", ":"), ensure_ascii=False).encode("utf-8") + b" ",
        entries[60]["offset"]: new_background,
    }
    result = rebuild(raw, report, replacements)
    checked = inspect(Path(identity["name"] + ".dial"), result)
    if checked["manifest"]["id"] != identity["id"]: raise ValueError("manifest round trip failed")
    manifest_entry = checked["default_resources"][44]
    manifest_bytes = result[manifest_entry["offset"]:manifest_entry["offset"] + manifest_entry["length"]]
    if b"\0" in manifest_bytes or json.loads(manifest_bytes.decode("utf-8")) != manifest:
        raise ValueError("manifest contains NUL or does not round trip as complete JSON")
    if checked["default_resources"][37]["sha256"] != sha(replacements[entries[37]["offset"]]):
        raise ValueError("PLB resource round trip failed")
    return result, {"identity": identity, "sha256": sha(result),
                    "programSha256": {key: sha(data) for key, data in program.items()},
                    "plbSha256": sha(replacements[entries[37]["offset"]]),
                    "backgroundSha256": sha(new_background), "changedDefaultIndices": list(expected)}


def main() -> None:
    raw, qc35raw = QC64.read_bytes(), QC35.read_bytes()
    if sha(raw) != QC64_SHA or sha(qc35raw) != QC35_SHA: raise ValueError("unpinned donor")
    report, qc35report = inspect(QC64, raw), inspect(QC35, qc35raw)
    plb_entry = qc35report["default_resources"][3]
    donor_plb = qc35raw[plb_entry["offset"]:plb_entry["offset"] + plb_entry["length"]]
    if sha(donor_plb) != "f95cc2687ce749a3f7d2be31d355147013bdad0a523ebb6be2c4dbe1179ca991":
        raise ValueError("unpinned text archetypes")
    width, height, diallab_bgra = _png_bgra(BACKGROUND)
    if (width, height) != (466, 466): raise ValueError("wrong dial background dimensions")
    black_bgra = bytes((0, 0, 0, 255)) * (466 * 466)
    program = {key: (WORK / "src" / (key + ".js")).read_bytes() for key in ("app", "main")}
    variants = (
        ("base", black_bgra, DIAGNOSTIC, BASE_FONT, {"id": "com.gtdial.base3000", "name": "base3000", "alias": "2809202601"}),
        ("dial3000", diallab_bgra, DIAL3000, DIAL_FONT, {"id": "com.gtdial.dial3000", "name": "dial3000", "alias": "2809202602"}),
    )
    out = WORK / "candidates-v5"
    out.mkdir(parents=True, exist_ok=True)
    records = []
    coordinate_map = {"format": 1, "coordinateSpace": {"width": 466, "height": 466,
        "origin": "top-left", "unit": "pixel", "rect": "x,y,width,height"},
        "note": "PLB rectangles are exact. Drawn labels, border and character are pixels inside the background image, not separate PLB objects.",
        "variants": {}}
    log_lines = []
    for label, bgra, rectangles, fonts, identity in variants:
        path = out / (label + ".dial")
        if path.exists(): raise FileExistsError(path)
        dial, record = package_variant(raw, report, donor_plb, bgra, rectangles, fonts, identity, program)
        path.write_bytes(dial)
        report_built = inspect(path, dial)
        entry = report_built["default_resources"][37]
        plb = parsed_plb(dial[entry["offset"]:entry["offset"] + entry["length"]])
        nodes = []
        for item in [plb] + plb["children"]:
            props = {p["tag"]: p["value"] for p in item["properties"]}
            geometry = props[6]
            mapped = {"id": item["id"], "type": item["type"],
                      "rect": [geometry[k] for k in ("x", "y", "w", "h")],
                      "serializationOrder": item["serialization_order"]}
            if item["type"] == "text":
                mapped.update({"fontPixels": props[3]["index"], "argb": f"#{props[1]:08X}",
                               "fontPath": props[3]["value"]})
            else:
                mapped["backgroundPath"] = props[2]
            nodes.append(mapped)
            log_lines.append(json.dumps({"variant": label, "event": "PLB_COORDINATE", **mapped}, ensure_ascii=False))
        coordinate_map["variants"][label] = {"packageSha256": record["sha256"],
            "rootAndChildren": nodes, "backgroundRaster": {"resourceIndex": 60,
            "rect": [0, 0, 466, 466], "semanticParts": "raster pixels; not independent nodes"}}
        record.update({"path": str(path), "layout": {key: list(value) for key, value in rectangles.items()},
                       "fontPixels": fonts, "argb": f"#{PHOSPHOR_ARGB:08X}"})
        records.append(record)
    (out / "build-manifest.json").write_text(json.dumps({"sourceQc64Sha256": QC64_SHA,
        "sourceQc35PlbSha256": sha(donor_plb), "classification": "OFFLINE_UNVERIFIED_NO_WATCH_WRITE",
        "variants": records}, indent=2) + "\n", encoding="utf-8")
    (out / "coordinate-map.json").write_text(json.dumps(coordinate_map, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    (out / "coordinate-log.jsonl").write_text("\n".join(log_lines) + "\n", encoding="utf-8")
    for line in log_lines: print(line)
    print(json.dumps({"classification": "OFFLINE_UNVERIFIED_NO_WATCH_WRITE",
                      "variants": [{"path": item["path"], "sha256": item["sha256"]} for item in records]}))


if __name__ == "__main__": main()
