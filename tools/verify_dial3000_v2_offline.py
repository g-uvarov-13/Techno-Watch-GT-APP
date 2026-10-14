"""Static resource and geometry inspection for the six offline Dial 3000 packages."""
import json
import argparse
from pathlib import Path

from tools.build_dial3000_v2 import ALIASES, IMAGES, RECTS, TEXT, sha
from tools.build_shared_dial_base import parsed_plb
from tools.inspect_dial import bkdr, inspect
from tools.pgf_codec import decode
from tools.render_dial3000_next_mockup import COLORS

ROOT = Path(__file__).resolve().parents[1]


def resource(data, report, path):
    key = f"0x{bkdr('images/' + path + '.png'):08x}"
    item = next(entry for entry in report["default_resources"] if entry["name_hash"] == key)
    return data[item["offset"]:item["offset"] + item["length"]]


def pixels(blob):
    meta, plane, palette = decode(blob)
    result = []
    for y in range(meta["height"]):
        result.extend(tuple(palette[plane[y * meta["stride"] + x] * 4:plane[y * meta["stride"] + x] * 4 + 4])
                      for x in range(meta["width"]))
    return meta, result


def main(revision=2):
    directory = ROOT / f"work/dial3000-v2/candidates-v{revision}"
    records = {item["theme"]: item for item in json.loads((directory / "build-manifest.json").read_text(encoding="utf-8"))["variants"]}
    if set(records) != set(COLORS):
        raise ValueError("theme set mismatch")
    app = (ROOT / "work/shared-dial-base/src/app.js").read_bytes()
    main_js = (ROOT / "work/dial3000-v2/src/main.js").read_bytes()
    if b'now.getFullYear()' not in main_js or b'"."' not in main_js or b'AM_PM' in main_js:
        raise ValueError("date/AM-PM program mismatch")
    for name, color in COLORS.items():
        path = directory / (name + ".dial")
        data = path.read_bytes()
        info = inspect(path, data)
        record = records[name]
        if sha(data) != record["sha256"] or len(data) != record["size"]:
            raise ValueError("package digest mismatch: " + name)
        if len(info["locales"]) != 23 or any(locale["resource_count"] != 71 for locale in info["locales"]):
            raise ValueError("locale/resource shape mismatch: " + name)
        expected_alias = ALIASES[name] if revision == 2 else str(int(ALIASES[name]) + 10)
        if info["manifest"]["alias"] != expected_alias:
            raise ValueError("alias mismatch: " + name)
        entries = info["default_resources"]
        for index, source in ((2, app), (34, main_js)):
            entry = entries[index]
            if data[entry["offset"]:entry["offset"] + entry["length"]] != source:
                raise ValueError("program resource mismatch: " + name)
        plb_entry = entries[37]
        plb = parsed_plb(data[plb_entry["offset"]:plb_entry["offset"] + plb_entry["length"]])
        nodes = plb["children"]
        if [(node["id"], node["type"]) for node in nodes] != [(key, "text") for key in TEXT] + [(key, "image") for key in IMAGES]:
            raise ValueError("PLB node/type mismatch: " + name)
        rgb = tuple(int(color[i:i + 2], 16) for i in (1, 3, 5))
        argb = (255 << 24) | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2]
        for node in nodes:
            properties = {item["tag"]: item["value"] for item in node["properties"]}
            actual = tuple(properties[6][field] for field in ("x", "y", "w", "h"))
            expected_rect = (RECTS if node["type"] == "text" else IMAGES)[node["id"]]
            if revision == 3 and node["id"] == "STEPS":
                expected_rect = (expected_rect[0] + 6, *expected_rect[1:])
            if actual != expected_rect:
                raise ValueError("PLB rectangle mismatch: " + name + "/" + node["id"])
            if node["type"] == "text" and properties[1] != argb:
                raise ValueError("PLB text color mismatch: " + name)
        background_meta, background_pixels = pixels(resource(data, info, "background"))
        if (background_meta["width"], background_meta["height"]) != (466, 466):
            raise ValueError("background dimension mismatch")
        for rect in list(IMAGES.values())[:4]:
            x, y, width, height = rect
            if any(background_pixels[yy * 466 + xx][:3] != (0, 0, 0) for yy in range(y, y + height) for xx in range(x, x + width)):
                raise ValueError("opaque digit glyph would cover nonblack background: " + name)
        for digit in range(10):
            meta, _ = pixels(resource(data, info, "digit_" + str(digit)))
            if (meta["width"], meta["height"]) != (40, 76):
                raise ValueError("digit dimensions mismatch: " + name)
        for level in range(11):
            meta, values = pixels(resource(data, info, "bat_" + str(level)))
            if (meta["width"], meta["height"]) != (84, 10):
                raise ValueError("HUD dimensions mismatch: " + name)
            visible = sum(1 for value in values[:84] if value[:3] != (0, 0, 0))
            if visible != round(84 * level / 10):
                raise ValueError("HUD fill mismatch: " + name + "/" + str(level))
    print("six themes, 23 locales each, 71 resources, digit/HUD PGFs, exact PLB colors and geometry verified")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--revision", type=int, default=2)
    main(parser.parse_args().revision)
