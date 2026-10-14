"""Render layout proposals only; these PNGs are not GT01 packages."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "work/qc176-diallab3000-offline/background-clean-opaque-466.png"
OUT = ROOT / "outputs/dial3000-next-mockups"
ASSETS = ROOT / "work/dial3000-v2/assets"
COLORS = {
    "phosphor": "#32FF9A", "amber": "#FFB02E", "cyan": "#46E8FF",
    "white": "#F2F5ED", "red": "#FF4C47", "violet": "#D97BFF",
}
FONT = Path("C:/Windows/Fonts/arialbd.ttf")


def background(color):
    image = Image.open(SOURCE).convert("RGB")
    # Move the whole three-panel artwork, including labels and lines.
    for box in ((83, 147, 185, 181), (76, 197, 177, 234), (83, 250, 185, 286)):
        crop = image.crop(box)
        ImageDraw.Draw(image).rectangle(box, fill=(0, 0, 0))
        image.paste(crop, (box[0] - 4, box[1]))
    # Free the lower rail for seconds and clear the static battery fill region.
    draw = ImageDraw.Draw(image)
    draw.rectangle((319, 336, 369, 343), fill=(0, 0, 0))
    draw.rectangle((69, 323, 153, 334), fill=(0, 0, 0))
    # Recolor the original raster's green artwork without changing its geometry.
    pixels = image.load()
    rgb = tuple(int(color[i:i + 2], 16) for i in (1, 3, 5))
    for y in range(image.height):
        for x in range(image.width):
            r, g, b = pixels[x, y]
            if g > r * 1.4 and g > b * 1.2 and g > 35:
                intensity = g / 255
                pixels[x, y] = tuple(round(v * intensity) for v in rgb)
    return image


def face(color):
    image = background(color)
    draw = ImageDraw.Draw(image)
    rgb = tuple(int(color[i:i + 2], 16) for i in (1, 3, 5))

    def centered(text, rect, size):
        x, y, w, h = rect
        font = ImageFont.truetype(str(FONT), size)
        bounds = draw.textbbox((0, 0), text, font=font)
        tx = x + (w - (bounds[2] - bounds[0])) / 2 - bounds[0]
        ty = y + (h - (bounds[3] - bounds[1])) / 2 - bounds[1]
        draw.text((tx, ty), text, font=font, fill=rgb)

    for text, rect in zip("1125", ((299, 156, 40, 76), (345, 156, 40, 76),
                                   (299, 253, 40, 76), (345, 253, 40, 76))):
        centered(text, rect, 73)
    centered("57", (323, 334, 42, 29), 25)
    for text, pos, size in (
        ("29.09.2026", (140, 105), 16), ("TUE", (269, 105), 16),
        ("24", (84, 166), 21), ("87", (84, 218), 19),
        ("34", (84, 270), 21), ("66%", (88, 335), 19),
        ("0.09", (190, 344), 19),
    ):
        draw.text(pos, text, font=ImageFont.truetype(str(FONT), size), fill=rgb)
    # Example of 66% fill; the future runtime must prove dynamic behavior.
    draw.rectangle((69, 323, 69 + round(84 * .66), 332), fill=rgb)
    return image


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    sheet = Image.new("RGB", (466 * 3, 466 * 2), (0, 0, 0))
    for index, (name, color) in enumerate(COLORS.items()):
        target = ASSETS / name
        target.mkdir(parents=True, exist_ok=True)
        background(color).save(target / "background.png")
        image = face(color)
        image.save(OUT / f"{name}.png")
        sheet.paste(image, ((index % 3) * 466, (index // 3) * 466))
        rgb = tuple(int(color[i:i + 2], 16) for i in (1, 3, 5))
        digit_font = ImageFont.truetype(str(FONT), 73)
        for digit in range(10):
            glyph = Image.new("RGB", (40, 76), (0, 0, 0))
            gd = ImageDraw.Draw(glyph)
            bounds = gd.textbbox((0, 0), str(digit), font=digit_font)
            gd.text(((40 - (bounds[2] - bounds[0])) / 2 - bounds[0],
                     (76 - (bounds[3] - bounds[1])) / 2 - bounds[1]),
                    str(digit), font=digit_font, fill=rgb)
            glyph.save(target / f"digit_{digit}.png")
        for level in range(11):
            hud = Image.new("RGB", (84, 10), (0, 0, 0))
            if level:
                ImageDraw.Draw(hud).rectangle((0, 0, round(84 * level / 10) - 1, 9), fill=rgb)
            hud.save(target / f"bat_{level}.png")
    sheet.save(OUT / "all-themes.png")


if __name__ == "__main__":
    main()
