import hashlib
import tempfile
import unittest
from pathlib import Path

from tools.inspect_dial import inspect
from tools.pgf_codec import decode, encode_indexed_bgra, write_png
from tools.build_qc176_palette_variant import (
    DONOR_SHA256,
    MANIFEST_ALIAS,
    MANIFEST_ID,
    MANIFEST_NAME,
    build_package,
)
from tools.verify_qc176_palette_variant import verify
from tools.patch_face_layout import apply_plan, load_plan


class Qc176PaletteVariantTest(unittest.TestCase):
    def setUp(self):
        self.donor = next(Path("work/dial-corpus-v2/sources").glob("*qc176*"))
        self.assertEqual(hashlib.sha256(self.donor.read_bytes()).hexdigest().upper(), DONOR_SHA256)

    def _background(self, directory):
        image = directory / "new-background.png"
        # Generated only for the isolated builder contract; no candidate package is written.
        write_png(image, 466, 466, bytes((10, 30, 60, 255)) * (466 * 466))
        return image

    def test_build_preserves_index_planes_and_container_fields(self):
        with tempfile.TemporaryDirectory() as temp:
            image = self._background(Path(temp))
            result = build_package(self.donor, image, "#30D5C8")
            self.assertEqual(result, build_package(self.donor, image, "#30D5C8"))
            candidate = Path(temp) / "variant.dial"
            candidate.write_bytes(result)
            self.assertGreater(verify(self.donor, candidate)["recoloredPgfResources"], 0)
        before = inspect(self.donor, self.donor.read_bytes())
        after = inspect(Path("variant.dial"), result)
        self.assertEqual(after["manifest"]["id"], MANIFEST_ID)
        self.assertEqual(after["manifest"]["name"], MANIFEST_NAME)
        self.assertEqual(after["manifest"]["alias"], MANIFEST_ALIAS)
        self.assertEqual(len(after["locales"]), 28)
        for old_locale, new_locale in zip(before["locales"], after["locales"]):
            self.assertEqual(old_locale["resource_count"], 133)
            for old, new in zip(old_locale["resources"], new_locale["resources"]):
                self.assertEqual(old["name_hash"], new["name_hash"])
                self.assertEqual(old["raw_fourth_field"], new["raw_fourth_field"])
        old_default = before["default_resources"]
        new_default = after["default_resources"]
        changed = 0
        for old, new in zip(old_default, new_default):
            if old["type"] != "PGF image":
                continue
            old_blob = self.donor.read_bytes()[old["offset"]:old["offset"] + old["length"]]
            new_blob = result[new["offset"]:new["offset"] + new["length"]]
            old_meta, old_plane, old_palette = decode(old_blob)
            new_meta, new_plane, new_palette = decode(new_blob)
            self.assertEqual(old_meta["width"], new_meta["width"])
            self.assertEqual(old_meta["height"], new_meta["height"])
            self.assertEqual(old_meta["stride"], new_meta["stride"])
            if old_meta["width"] == old_meta["height"] == 466:
                self.assertNotEqual(old_blob, new_blob)
            else:
                self.assertEqual(old_plane, new_plane)
                self.assertEqual(old_palette[3::4], new_palette[3::4])
                self.assertNotEqual(old_palette, new_palette)
                changed += 1
        self.assertGreater(changed, 0)

    def test_rejects_non_opaque_background(self):
        with tempfile.TemporaryDirectory() as temp:
            image = Path(temp) / "transparent.png"
            write_png(image, 466, 466, bytes((1, 2, 3, 0)) * (466 * 466))
            with self.assertRaisesRegex(ValueError, "opaque"):
                build_package(self.donor, image, "#30D5C8")

    def test_rejects_invalid_accent(self):
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(ValueError, "accent"):
                build_package(self.donor, self._background(Path(temp)), "red")

    def test_build_and_verify_replace_only_the_shared_plb_from_a_pinned_plan(self):
        plan_path = Path("work/face-layout-20260923/diallab3000-qc176-plan-v2.json")
        plan = load_plan(plan_path)
        with tempfile.TemporaryDirectory() as temp:
            temp_path = Path(temp)
            plb = temp_path / "candidate.plb"
            plb.write_bytes(apply_plan(Path("work/dial-corpus-v2/blobs") / f"{plan['donor_plb_sha256']}.bin", plan))
            package = temp_path / "candidate.dial"
            package.write_bytes(build_package(self.donor, self._background(temp_path), "#30D5C8", plb_plan=plan_path, plb_result=plb))
            report = verify(self.donor, package, plb_plan=plan_path, plb_result=plb)
        self.assertEqual(report["plbIndex"], 54)
        self.assertEqual(report["plbHash"], "0x71b4a11f")
        self.assertEqual(report["plbNodeDiff"], len(plan["edits"]))

    def test_recolor_preserves_alpha_and_rejects_nonpremultiplied_palette(self):
        from tools.build_qc176_palette_variant import _recolor_indexed_blob
        source = encode_indexed_bgra(1, 1, bytes((40, 80, 120, 255)), format_raw=6, stride=16)
        meta, plane, palette = decode(_recolor_indexed_blob(source, (48, 213, 200)))
        _, _, original_palette = decode(source)
        value = max(original_palette[:3])
        self.assertEqual(plane[:1], b"\0")
        self.assertEqual(palette[3], 255)
        self.assertEqual(palette[:3], bytes((200 * value // 255, 213 * value // 255, 48 * value // 255)))
        _, source_plane, _ = decode(source)
        malformed_header = bytearray(source[:42])
        malformed_header[35] = 0
        malformed = bytes(malformed_header) + source_plane + bytes((255, 0, 0, 1))
        with self.assertRaisesRegex(ValueError, "v <= alpha"):
            _recolor_indexed_blob(bytes(malformed), (48, 213, 200))


if __name__ == "__main__":
    unittest.main()
