import unittest

from tools.pgf_codec import decode, encode_direct_bgra, encode_indexed_bgra


class PgfEncodeTest(unittest.TestCase):
    def test_encodes_opaque_bgra_with_exact_dimensions(self):
        blob = encode_direct_bgra(2, 1, bytes((1, 2, 3, 255, 4, 5, 6, 255)))
        meta, plane, palette = __import__("tools.pgf_codec", fromlist=["decode"]).decode(blob)
        self.assertEqual((meta["width"], meta["height"], meta["format_raw"]), (2, 1, 1))
        self.assertEqual(plane[:8], bytes((1, 2, 3, 255, 4, 5, 6, 255)))
        self.assertEqual(palette, b"")

    def test_indexed_encoder_preserves_variant_stride_and_is_deterministic(self):
        pixels = bytes((0, 0, 0, 255, 255, 255, 255, 255))
        first = encode_indexed_bgra(2, 1, pixels, format_raw=6, stride=16)
        second = encode_indexed_bgra(2, 1, pixels, format_raw=6, stride=16)
        meta, plane, palette = decode(first)
        self.assertEqual(first, second)
        self.assertEqual((meta["format_raw"], meta["stride"], meta["palette_count"]), (6, 16, 2))
        self.assertEqual(plane[:2], bytes((0, 1)))
        self.assertEqual(len(palette), 8)

    def test_indexed_encoder_rejects_transparency(self):
        with self.assertRaises(ValueError):
            encode_indexed_bgra(1, 1, bytes((0, 0, 0, 0)))


if __name__ == "__main__":
    unittest.main()
