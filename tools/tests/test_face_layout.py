import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from tools.patch_face_layout import apply_plan, load_plan
from tools.verify_face_layout import verify


DONOR = Path("work/dial-corpus-v2/blobs/fea5e448204f445616601fd97f3258179885eb0c1c6356acc97665413000da57.bin")


class FaceLayoutTest(unittest.TestCase):
    def setUp(self):
        self.sha = hashlib.sha256(DONOR.read_bytes()).hexdigest()
        self.plan = {
            "format": 1,
            "donor_plb_sha256": self.sha,
            "edits": [{"node_id": "H_S", "expected": [104, 179, 58, 104], "replacement": [105, 179, 58, 104]}],
        }

    def test_applies_exact_rect_payload_only_and_verifies(self):
        result = apply_plan(DONOR, self.plan)
        self.assertEqual(len(result), DONOR.stat().st_size)
        with tempfile.TemporaryDirectory() as temp:
            candidate = Path(temp) / "candidate.plb"
            candidate.write_bytes(result)
            report = verify(DONOR, candidate, self.plan)
        self.assertEqual(report["editedNodeIds"], ["H_S"])
        changed = [i for i, (a, b) in enumerate(zip(DONOR.read_bytes(), result)) if a != b]
        self.assertEqual(changed, [100])

    def test_rejects_noop_wrong_sha_malformed_and_duplicate_edits(self):
        for bad in (
            {"format": 1, "donor_plb_sha256": self.sha, "edits": []},
            {**self.plan, "donor_plb_sha256": "0" * 64},
            {"format": 1, "donor_plb_sha256": self.sha, "edits": [{"node_id": "H_S", "expected": [104, 179, 58, 104], "replacement": [104, 179, 58, 104]}]},
            {**self.plan, "extra": True},
            {**self.plan, "edits": self.plan["edits"] * 2},
        ):
            with self.assertRaises(ValueError):
                apply_plan(DONOR, bad)

    def test_rejects_unknown_bad_field_and_parent_out_of_bounds(self):
        bad_field = {**self.plan, "edits": [{"node_id": "nope", "expected": [1, 1, 1, 1], "replacement": [1, 1, 1, 2]}]}
        out_of_bounds = {**self.plan, "edits": [{"node_id": "H_S", "expected": [104, 179, 58, 104], "replacement": [450, 179, 58, 104]}]}
        fractional = {**self.plan, "edits": [{"node_id": "H_S", "expected": [104, 179, 58, 104], "replacement": [105.5, 179, 58, 104]}]}
        for bad in (bad_field, out_of_bounds, fractional):
            with self.assertRaises(ValueError):
                apply_plan(DONOR, bad)

    def test_load_plan_rejects_duplicate_json_key(self):
        with tempfile.TemporaryDirectory() as temp:
            plan = Path(temp) / "plan.json"
            plan.write_text('{"format":1,"format":1,"donor_plb_sha256":"%s","edits":[]}' % self.sha, encoding="utf-8")
            with self.assertRaises(ValueError):
                load_plan(plan)

    def test_verifier_rejects_a_byte_outside_the_payload_whitelist(self):
        candidate = bytearray(apply_plan(DONOR, self.plan))
        candidate[10] ^= 1
        with tempfile.TemporaryDirectory() as temp:
            result = Path(temp) / "corrupt.plb"
            result.write_bytes(candidate)
            with self.assertRaises(ValueError):
                verify(DONOR, result, self.plan)


if __name__ == "__main__":
    unittest.main()
