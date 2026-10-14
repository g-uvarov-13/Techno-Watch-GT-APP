"""Render actual candidate PGF rasters at bounded sample states for visual review."""
from pathlib import Path
import argparse
from PIL import Image, ImageDraw, ImageFont

from tools.inspect_dial import bkdr, inspect
from tools.pgf_codec import decode
from tools.render_dial3000_next_mockup import COLORS, FONT

ROOT = Path(__file__).resolve().parents[1]


def png_from_pgf(blob):
    meta, plane, palette = decode(blob)
    out = bytearray()
    for y in range(meta["height"]):
        for x in range(meta["width"]):
            index = plane[y * meta["stride"] + x]
            b, g, r, a = palette[index * 4:index * 4 + 4]
            out.extend((r, g, b, a))
    return Image.frombytes("RGBA", (meta["width"], meta["height"]), bytes(out)).convert("RGB")


def main(revision=2):
    input_dir = ROOT / f"work/dial3000-v2/candidates-v{revision}"
    output_dir = ROOT / f"outputs/dial3000-v{revision}-package-renders"
    output_dir.mkdir(parents=True, exist_ok=True)
    for theme, color in COLORS.items():
        package = input_dir / (theme + ".dial")
        data = package.read_bytes()
        report = inspect(package, data)
        resources = {item["name_hash"]: item for item in report["default_resources"]}

        def picture(path):
            item = resources[f"0x{bkdr('images/' + path + '.png'):08x}"]
            return png_from_pgf(data[item["offset"]:item["offset"] + item["length"]])

        for hour, minute, battery in (("00", "00", 0), ("11", "25", 50), ("23", "59", 100)):
            face = picture("background")
            for value, x, y in zip(hour + minute, (299, 345, 299, 345), (156, 156, 253, 253)):
                face.paste(picture("digit_" + value), (x, y))
            face.paste(picture("bat_" + str(battery // 10)), (69, 323))
            draw = ImageDraw.Draw(face)
            rgb = tuple(int(color[i:i + 2], 16) for i in (1, 3, 5))
            for value, xy, size in (("29.09.2026", (140, 105), 16), ("TUE", (269, 105), 16),
                                    ("24", (84, 166), 21), ("87", (84, 218), 19),
                                    ("417" if revision == 3 else "34", (83 if revision == 3 else 84, 270), 21), (f"{battery}%", (88, 335), 19),
                                    ("0.09", (190, 344), 19), ("57", (330, 337), 21)):
                draw.text(xy, value, font=ImageFont.truetype(str(FONT), size), fill=rgb)
            face.save(output_dir / f"{theme}-{hour}-{minute}-battery{battery}.png")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--revision", type=int, default=2)
    main(parser.parse_args().revision)
