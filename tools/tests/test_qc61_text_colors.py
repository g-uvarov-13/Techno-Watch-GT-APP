import tempfile
import unittest
from pathlib import Path

from tools.patch_qc61_text_colors import patch
from tools.verify_qc61_text_colors import verify


class Qc61TextColorsTest(unittest.TestCase):
    donor = Path("work/qc61-authoring-20260923/qc61-donor.plb")

    def test_changes_only_five_tag1_payloads(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "candidate.plb"
            output.write_bytes(patch(self.donor))
            report = verify(self.donor, output)
        self.assertEqual(report["nodeIds"], ["HEART", "STEPS", "DIS_LAB", "BAT_VALUE", "WEATH_V"])
        self.assertEqual(report["changedPayloadBytes"], 20)


if __name__ == "__main__": unittest.main()
