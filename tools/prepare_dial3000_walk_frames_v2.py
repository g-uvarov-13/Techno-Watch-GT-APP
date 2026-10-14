"""Crop the corrected four poses into one exact watch-sized animation rectangle."""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "work/dial3000-animation"
OUT = WORK / "frames-v2-source"
SHEET = WORK / "walk-source-corrected-sheet.png"
RIGHT = WORK / "walk-source-right-step.png"


def fitted(source):
    source = source.convert("RGB")
    mask = source.getchannel("B").point(lambda value: 255 if value > 75 else 0)
    bounds = mask.getbbox()
    if not bounds:
        raise ValueError("empty character")
    left, top, right, bottom = bounds
    cut = source.crop((max(0, left - 8), max(0, top - 8), min(source.width, right + 8), min(source.height, bottom + 8)))
    cut = cut.resize((78, 163), Image.Resampling.LANCZOS)
    out = Image.new("RGB", (84, 171), "black")
    out.paste(cut, (3, 4))
    return out


def main():
    source = Image.open(SHEET)
    if source.size != (2172, 724):
        raise ValueError("corrected sprite sheet dimensions")
    panels = [source.crop((left, 25, right, 690)) for left, right in ((0, 340), (680, 1030), (1820, 2110))]
    right = Image.open(RIGHT)
    poses = [panels[0], panels[1], right, panels[2]]
    OUT.mkdir(parents=True, exist_ok=True)
    for index, pose in enumerate(poses):
        fitted(pose).save(OUT / f"{index:02}.png")
    print("four corrected poses, 84x171")


if __name__ == "__main__":
    main()
