import tempfile
import unittest
from pathlib import Path

from tools.pgf_codec import decode
from tools.resize_qc61_glyphs import resize_glyphs
from tools.verify_qc61_glyphs import verify


class Qc61GlyphsTest(unittest.TestCase):
    donor = Path("work/myhealth-gt01-catalog-20260919/dials/4eeade6e145f42bd8419e34d3d1e51d9.dial")

    def test_resizes_exact_twenty_glyphs_with_bitwise_palettes(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "glyphs"
            manifest = resize_glyphs(self.donor, output)
            self.assertEqual(len(manifest["glyphs"]), 20)
            self.assertEqual(verify(self.donor, output)["glyphCount"], 20)
            for glyph in manifest["glyphs"]:
                meta, plane, palette = decode((output / glyph["output"]).read_bytes())
                self.assertEqual((meta["width"], meta["height"], meta["stride"]), (40, 80, 48))
                self.assertEqual(len(plane), 3840)
                self.assertEqual(meta["palette_count"] * 4, len(palette))

    def test_verifier_rejects_modified_output(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "glyphs"
            manifest = resize_glyphs(self.donor, output)
            glyph = output / manifest["glyphs"][0]["output"]
            damaged = bytearray(glyph.read_bytes())
            damaged[-1] ^= 1
            glyph.write_bytes(damaged)
            with self.assertRaises(ValueError):
                verify(self.donor, output)

    def test_pixel_profile_dimensions_and_verifier_guards(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "pixel"
            manifest = resize_glyphs(self.donor, output, pixel=True)
            self.assertEqual(manifest["glyphs"][0]["target"], [64, 88, 64])
            self.assertEqual(manifest["glyphs"][10]["target"], [24, 32, 32])
            self.assertEqual(verify(self.donor, output)["glyphCount"], 20)
            (output / "time_0.pgf").unlink()
            with self.assertRaises(OSError): verify(self.donor, output)

    def test_rejects_wrong_donor_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            wrong = Path(directory) / "wrong.dial"
            wrong.write_bytes(self.donor.read_bytes()[:-1])
            with self.assertRaisesRegex(ValueError, "donor"):
                resize_glyphs(wrong, Path(directory) / "output", pixel=True)


if __name__ == "__main__":
    unittest.main()
