import hashlib
import json
import unittest
from pathlib import Path

from tools.build_dynamic_dial import BACKGROUND_HASH, build_package
from tools.inspect_dial import inspect
from tools.pgf_codec import decode


class DynamicDialBuildTest(unittest.TestCase):
    def test_replaces_only_background_and_preserves_manifest(self):
        donor = Path("work/dial-corpus-v2/sources/com.QC.qc176.dial-dfaac700817e.dial")
        image = Path("outputs/GT01-watchface-ready-466.png")
        result = build_package(donor, image)
        self.assertEqual(hashlib.sha256(donor.read_bytes()).hexdigest().upper(),
                         "DFAAC700817E3D363C7A03E8F6B053C1E7DAF8B869E2A6C800FDCD782123EDAD")
        report = inspect(Path("donor.dial"), result)
        self.assertEqual(report["manifest"]["id"], "com.gtdial.pipboy01")
        self.assertEqual(report["manifest"]["name"], "pipboy01")
        self.assertEqual(report["manifest"]["alias"], "1909202601")
        self.assertNotIn(b'"id":"com.QC.qc176"', result)
        self.assertGreater(report["size"], 328024)
        self.assertEqual(report["locales"][0]["resource_count"], 133)
        background = next(r for r in report["default_resources"] if r["name_hash"] == BACKGROUND_HASH)
        meta, _, _ = decode(result[background["offset"]:background["offset"] + background["length"]])
        self.assertEqual((meta["width"], meta["height"], meta["format_raw"], meta["stride"]), (466, 466, 6, 480))
        self.assertEqual(result, build_package(donor, image))

    def test_rejects_unapproved_donor(self):
        donor = Path("outputs/dynamic-dials/pipboy-qc176-offline-v1/face.dial")
        with self.assertRaisesRegex(ValueError, "unsupported donor"):
            build_package(donor, Path("outputs/GT01-watchface-ready-466.png"))


if __name__ == "__main__":
    unittest.main()
