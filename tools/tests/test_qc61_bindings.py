import unittest

from tools.analyze_qc61_bindings import analyze


class Qc61BindingsTest(unittest.TestCase):
    def test_clock_paths_are_bound_in_every_locale_and_runtime_is_unknown(self):
        result = analyze()
        rows = result["digit_families"]["clock"]
        self.assertEqual([(x["family"], x["path"], x["resource_index"]) for x in rows], [("hour", "images/hour.png", 60), ("minute", "images/min.png", 49), ("second", "images/sec.png", 19)])
        self.assertTrue(all(len(x["locale_references"]) == 28 for x in rows))
        self.assertEqual(result["digit_families"]["runtime"], {"initialRead": None, "update": None, "cleanup": None, "units": None, "missingValueBehavior": None})


if __name__ == "__main__":
    unittest.main()
