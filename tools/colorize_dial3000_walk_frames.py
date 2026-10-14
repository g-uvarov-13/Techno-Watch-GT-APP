"""Derive matching color frames for all Dial 3000 themes from one approved pose set."""
from pathlib import Path
import argparse
from PIL import Image
from tools.render_dial3000_next_mockup import COLORS

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "work/dial3000-animation"
RECT = (184, 138, 84, 171)


def main(revision=1):
    source_dir = WORK / ("frames" if revision == 1 else "frames-v2-source")
    target_dir = WORK / ("frames-by-theme" if revision == 1 else "frames-v2-by-theme")
    suffix = "" if revision == 1 else "-v2"
    sources = [Image.open(source_dir / f"{index:02}.png").convert("RGB") for index in range(4)]
    montage = Image.new("RGB", (466 * 3, 466 * 2), "black")
    for theme_index, (theme, color) in enumerate(COLORS.items()):
        destination = target_dir / theme
        destination.mkdir(parents=True, exist_ok=True)
        rgb = tuple(int(color[index:index + 2], 16) for index in (1, 3, 5))
        frames = []
        for index, original in enumerate(sources):
            pixels = bytearray()
            for green in original.get_flattened_data():
                strength = max(green)
                pixels.extend(min(255, round(channel * strength / 255)) for channel in rgb)
            frame = Image.frombytes("RGB", original.size, bytes(pixels))
            frame.save(destination / f"{index:02}.png")
            face = Image.open(ROOT / f"outputs/dial3000-v3-package-renders/{theme}-11-25-battery50.png").convert("RGB")
            face.paste(frame, RECT[:2])
            frames.append(face)
        frames[0].save(ROOT / f"outputs/dial3000-walk-{theme}{suffix}-v3.gif", save_all=True,
                       append_images=frames[1:], duration=210, loop=0, optimize=False)
        frames[0].save(ROOT / f"outputs/dial3000-walk-{theme}{suffix}-preview.png")
        montage.paste(frames[0], ((theme_index % 3) * 466, (theme_index // 3) * 466))
    montage.save(ROOT / f"outputs/dial3000-walk{suffix}-all-themes.png")
    print("four synchronized frames for six themes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--revision", type=int, choices=(1, 2), default=1)
    main(parser.parse_args().revision)
