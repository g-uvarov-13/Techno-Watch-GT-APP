"""Read-only static inspection of the isolated four-frame walk candidate."""
import json
import argparse
from pathlib import Path
from tools.build_dial3000_animation_candidate import DONOR_DIR, DONORS, FRAME_PATHS, RECT, WORK, digest
from tools.inspect_dial import bkdr, inspect
from tools.plb_inspect import R, node
from tools.pgf_codec import decode



def layout(data, report):
    entry = report["default_resources"][37]
    blob = data[entry["offset"]:entry["offset"] + entry["length"]]
    reader = R(blob)
    reader.p = 32
    result = node(reader, 0, [], {"nodes": 0, "serial": 0})
    if reader.p != len(blob):
        raise ValueError("PLB trailing data")
    return result


def verify(theme, revision):
    donor = DONOR_DIR / f"{theme}.dial"
    source = WORK / f"candidates-v{revision}" / f"{theme}.dial"
    old, new = donor.read_bytes(), source.read_bytes()
    manifest = json.loads((WORK / f"candidates-v{revision}" / f"{theme}-frame-manifest.json").read_text(encoding="utf-8"))
    if digest(old) != DONORS[theme]["sha256"] or digest(new) != manifest["packageSha256"] or len(new) != manifest["packageSize"]:
        raise ValueError("source/candidate digest mismatch")
    before, after = inspect(donor, old), inspect(source, new)
    if len(after["locales"]) != 23 or any(len(locale["resources"]) != 71 for locale in after["locales"]):
        raise ValueError("locale/resource count mismatch")
    allowed = set(manifest["changedResourceIndices"])
    if allowed != {34, 37, 44, 50, 56, 59, 66}:
        raise ValueError("changed indices mismatch")
    for a, b in zip(before["default_resources"], after["default_resources"]):
        if (a["index"], a["name_hash"], a["raw_fourth_field"]) != (b["index"], b["name_hash"], b["raw_fourth_field"]):
            raise ValueError("resource entry semantics changed")
        old_blob = old[a["offset"]:a["offset"] + a["length"]]
        new_blob = new[b["offset"]:b["offset"] + b["length"]]
        if a["index"] not in allowed and old_blob != new_blob:
            raise ValueError("unexpected resource changed: " + str(a["index"]))
        if a["index"] in allowed and old_blob == new_blob:
            raise ValueError("expected resource unchanged: " + str(a["index"]))
    old_layout, new_layout = layout(old, before), layout(new, after)
    if [child["raw_hex"] for child in old_layout["children"]] != [child["raw_hex"] for child in new_layout["children"][:-1]]:
        raise ValueError("existing PLB children changed")
    animation = new_layout["children"][-1]
    props = {item["tag"]: item["value"] for item in animation["properties"]}
    if (animation["type"] != "animated" or animation["id"] != "animatedImage1" or
            props[0] != 12561541 or props[8]["value"] != manifest["timingRaw"] or props[9]["count"] != 4 or
            props[9]["value"] != FRAME_PATHS or tuple(props[6][key] for key in ("x", "y", "w", "h")) != RECT):
        raise ValueError("animation node mismatch")
    for record in manifest["frames"]:
        path = record["path"]
        if path not in FRAME_PATHS:
            raise ValueError("frame path mismatch")
        hash_value = f"0x{bkdr(path):08x}"
        entry = next(item for item in after["default_resources"] if item["name_hash"] == hash_value)
        blob = new[entry["offset"]:entry["offset"] + entry["length"]]
        metadata, _, _ = decode(blob)
        if (metadata["width"], metadata["height"]) != RECT[2:] or digest(blob) != record["pgfSha256"]:
            raise ValueError("PGF frame mismatch: " + path)
        for locale in after["locales"]:
            linked = next(item for item in locale["resources"] if item["name_hash"] == hash_value)
            if linked["offset"] != entry["offset"]:
                raise ValueError("frame locale mismatch")
    program = after["default_resources"][34]
    actual_js = new[program["offset"]:program["offset"] + program["length"]]
    if actual_js != (WORK / "src/main.js").read_bytes():
        raise ValueError("embedded lifecycle JS mismatch")
    return manifest["packageSha256"]


def main(revision=2):
    hashes = {theme: verify(theme, revision) for theme in DONORS}
    print("six themes, 4 opaque PGF frames each, 23 locales each, exact animation nodes and unchanged data resources verified")
    print(json.dumps(hashes, sort_keys=True))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--revision", type=int, choices=(2, 3), default=2)
    main(parser.parse_args().revision)
