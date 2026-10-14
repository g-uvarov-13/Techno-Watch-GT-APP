"""Regenerate only Dial Lab 3000 packages from the photo-corrected PLB plan.

Keep the v3 bytes beside the plan so existing watch ownership receipts remain
independently auditable. This script does not contact a watch.
"""
from __future__ import annotations

import json
from hashlib import sha256
from pathlib import Path

from tools.build_qc176_palette_variant import build_package

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "work/android/app/src/main/assets/dynamic_dials"
REGISTRY = ASSETS / "offline-package-registry.json"
BACKGROUNDS = ROOT / "work/face-palettes-20260923/backgrounds-v2.json"
DONOR = ROOT / "work/dial-corpus-v2/sources/com.QC.qc176.dial-dfaac700817e.dial"
PLAN = ROOT / "work/face-layout-20260923/diallab3000-qc176-plan-v4.json"
PLB = ROOT / "work/face-layout-20260923/diallab3000-qc176-v4.plb"
ARCHIVE = ROOT / "work/face-layout-20260923/diallab3000-v3-packages"


def main() -> None:
    document = json.loads(REGISTRY.read_text(encoding="utf-8"))
    records = [r for r in document["items"] if r["faceId"] == "pipboy3000"]
    backgrounds = {(r["faceId"], r["paletteId"]): r for r in json.loads(BACKGROUNDS.read_text(encoding="utf-8"))}
    if len(records) != 6 or ARCHIVE.exists():
        raise ValueError("unexpected Dial Lab registry or existing archive")
    old = []
    new = []
    for record in records:
        target = ROOT / "work/android/app/src/main/assets" / record["packageAsset"]
        prior = target.read_bytes()
        if len(prior) != record["packageSize"] or sha256(prior).hexdigest() != record["packageSha256"]:
            raise ValueError("prior package mismatch " + record["paletteId"])
        row = backgrounds[("pipboy3000", record["paletteId"])]
        source = ROOT / row["path"]
        if sha256(source.read_bytes()).hexdigest().upper() != row["backgroundSha256"].upper():
            raise ValueError("background mismatch " + record["paletteId"])
        # Preserve manifest name from the exact old package for every palette.
        from tools.inspect_dial import inspect
        identity = (record["internalPackageId"], inspect(target, prior)["manifest"]["name"], record["alias"])
        candidate = build_package(DONOR, source, "#" + record["primary"][3:], plb_plan=PLAN, plb_result=PLB, identity=identity)
        if candidate == prior:
            raise ValueError("layout did not change " + record["paletteId"])
        old.append((record["paletteId"], prior))
        new.append((record, target, candidate))
    ARCHIVE.mkdir(parents=True)
    for palette, prior in old:
        (ARCHIVE / (palette + ".dial")).write_bytes(prior)
    for record, target, candidate in new:
        target.write_bytes(candidate)
        record["packageSha256"] = sha256(candidate).hexdigest()
        record["packageSize"] = len(candidate)
        print(record["paletteId"], record["packageSize"], record["packageSha256"])
    REGISTRY.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("registry", sha256(REGISTRY.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
