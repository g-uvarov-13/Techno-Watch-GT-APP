import hashlib
import tempfile
import unittest
from pathlib import Path

from tools.inspect_dial import inspect
from tools.pgf_codec import decode
from tools.build_qc61_face_variant import DONOR_SHA256, build_package
from tools.verify_qc61_face_variant import verify
from tools.build_qc61_face_variant import build_nixie_package


class Qc61FaceVariantTest(unittest.TestCase):
    donor = Path("work/myhealth-gt01-catalog-20260919/dials/4eeade6e145f42bd8419e34d3d1e51d9.dial")

    def setUp(self):
        self.assertEqual(hashlib.sha256(self.donor.read_bytes()).hexdigest(), DONOR_SHA256)

    def test_deterministic_build_changes_only_hand_palettes(self):
        with tempfile.TemporaryDirectory() as directory:
            candidate = Path(directory) / "qc61-black-hands.dial"
            first = build_package(self.donor)
            candidate.write_bytes(first)
            self.assertEqual(first, build_package(self.donor))
            report = verify(self.donor, candidate)
        self.assertEqual(report["changedResources"], 3)
        before, after = inspect(self.donor), inspect(candidate, first)
        self.assertEqual(after["manifest"], before["manifest"])
        for old, new in zip(before["locales"], after["locales"]):
            self.assertEqual(old["resource_count"], 65)
            for old_item, new_item in zip(old["resources"], new["resources"]):
                self.assertEqual(old_item["name_hash"], new_item["name_hash"])
                self.assertEqual(old_item["raw_fourth_field"], new_item["raw_fourth_field"])

    def test_transparency_and_index_plane_are_preserved(self):
        raw = self.donor.read_bytes()
        result = build_package(self.donor)
        before, after = inspect(self.donor, raw), inspect(Path("candidate.dial"), result)
        for index in (19, 49, 60):
            old, new = before["default_resources"][index], after["default_resources"][index]
            old_meta, old_plane, old_palette = decode(raw[old["offset"]:old["offset"] + old["length"]])
            new_meta, new_plane, new_palette = decode(result[new["offset"]:new["offset"] + new["length"]])
            self.assertEqual(old_meta["width"], new_meta["width"])
            self.assertEqual(old_meta["height"], new_meta["height"])
            self.assertEqual(old_meta["stride"], new_meta["stride"])
            self.assertEqual(old_plane, new_plane)
            self.assertEqual(new_palette, b"\0" * len(new_palette))

    def test_rejects_wrong_donor_and_modified_candidate(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            wrong = path / "wrong.dial"
            wrong.write_bytes(self.donor.read_bytes()[:-1])
            with self.assertRaisesRegex(ValueError, "donor"):
                build_package(wrong)
            candidate = path / "candidate.dial"
            damaged = bytearray(build_package(self.donor))
            report = inspect(Path("candidate.dial"), bytes(damaged))
            hand = report["default_resources"][60]
            # The hour hand is raw PGF, so this changes its indexed plane directly.
            damaged[hand["offset"] + 42] ^= 1
            candidate.write_bytes(damaged)
            with self.assertRaises(ValueError):
                verify(self.donor, candidate)

    def test_rejects_modified_hand_header(self):
        with tempfile.TemporaryDirectory() as directory:
            candidate = Path(directory) / "candidate.dial"
            damaged = bytearray(build_package(self.donor))
            hand = inspect(Path("candidate.dial"), bytes(damaged))["default_resources"][60]
            damaged[hand["offset"] + 36] ^= 1
            candidate.write_bytes(damaged)
            with self.assertRaises(ValueError):
                verify(self.donor, candidate)

    def test_nixie_package_changes_only_pinned_resources(self):
        root = Path("work/qc61-authoring-20260923")
        with tempfile.TemporaryDirectory() as directory:
            candidate = Path(directory) / "nixie.dial"
            combined = Path(directory) / "combined.plb"
            from tools.build_qc61_face_variant import build_combined_nixie_plb
            combined.write_bytes(build_combined_nixie_plb(root / "nixie-core-qc61-layout-v1.json"))
            candidate.write_bytes(build_nixie_package(self.donor, root / "nixie-core-clean-466.png", root / "resized-glyphs-40x80", root / "nixie-core-qc61-layout-v1.json", combined, accent="#FF6A00"))
            result = verify(self.donor, candidate, nixie=True, glyph_dir=root / "resized-glyphs-40x80", plb_result=combined, plb_plan=root / "nixie-core-qc61-layout-v1.json", accent="#FF6A00")
        self.assertEqual(result["changedIndices"], [0, 1, 6, 9, 12, 15, 19, 21, 24, 25, 28, 29, 32, 34, 35, 36, 38, 40, 43, 46, 49, 54, 55, 59, 60, 63])

    def test_pixel_verifier_rejects_jrry_tamper(self):
        from tools.verify_qc61_face_variant import verify_pixel
        pixel = Path("work/qc61-authoring-20260923/pixel-size-experiment/pixel-deck-offline.dial")
        with tempfile.TemporaryDirectory() as directory:
            damaged = bytearray(pixel.read_bytes())
            report = inspect(pixel, bytes(damaged))
            jrry = next(x for x in report["default_resources"] if x["type"] == "JRRY script")
            damaged[jrry["offset"] + 8] ^= 1
            candidate = Path(directory) / "damaged.dial"; candidate.write_bytes(damaged)
            with self.assertRaisesRegex(ValueError, "non-approved"):
                verify_pixel(self.donor, candidate)


if __name__ == "__main__":
    unittest.main()
