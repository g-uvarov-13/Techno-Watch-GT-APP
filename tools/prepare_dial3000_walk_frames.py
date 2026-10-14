"""Prepare four opaque, bounded preview frames from the approved walk concept."""
from pathlib import Path
import argparse
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {"phosphor": (ROOT / "work/dial3000-animation/walk-source-sheet.png", 4),
           "violet": (ROOT / "outputs/dial3000-walk-sprites-concept.png", 6)}
RECT = (184, 138, 84, 171)


def main(theme="phosphor"):
    path, panel_count = SOURCES[theme]
    source = Image.open(path).convert("RGB")
    panel_width = source.width / panel_count
    out = ROOT / "work/dial3000-animation" / ("frames" if theme == "phosphor" else "frames-violet")
    out.mkdir(parents=True, exist_ok=True)
    frames = []
    for panel_index in (0, 1):
        panel = source.crop((round(panel_index * panel_width) + 18, 60,
                             round((panel_index + 1) * panel_width) - 18, source.height - 35))
        mask = panel.point(lambda value: 255 if value > 70 else 0).getchannel("G")
        bounds = mask.getbbox()
        if not bounds:
            raise ValueError("missing character in panel")
        panel = panel.crop((max(0, bounds[0] - 8), max(0, bounds[1] - 8),
                            min(panel.width, bounds[2] + 8), min(panel.height, bounds[3] + 8)))
        panel = panel.resize((78, 163), Image.Resampling.LANCZOS)
        base = Image.new("RGB", (84, 171), "black")
        base.paste(panel, (3, 4))
        frames.append(base)
        composed = Image.new("RGB", (84, 171), "black")
        composed.paste(base.crop((0, 0, 84, 103)), (0, 0))
        lower = base.crop((0, 103, 84, 171)).transpose(Image.Transpose.FLIP_LEFT_RIGHT)
        composed.paste(lower, (0, 103))
        frames.append(composed)
    for index, frame in enumerate(frames):
        frame.save(out / f"{index:02}.png")
    faces = []
    revision = 3 if theme == "phosphor" else 2
    original = Image.open(ROOT / f"outputs/dial3000-v{revision}-package-renders/{theme}-11-25-battery50.png").convert("RGB")
    for frame in frames:
        face = original.copy()
        face.paste(frame, RECT[:2])
        faces.append(face)
    preview = ROOT / f"outputs/dial3000-walk-{theme}-concept.gif"
    faces[0].save(preview, save_all=True, append_images=faces[1:], duration=210, loop=0, optimize=False)
    print(preview)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--theme", choices=tuple(SOURCES), default="phosphor")
    main(parser.parse_args().theme)
