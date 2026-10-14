"""Rebuild six Dial Lab packages from the photo-adjusted v5 layout offline."""
from __future__ import annotations

import json
from hashlib import sha256
from pathlib import Path

from tools.build_qc176_palette_variant import build_package
from tools.inspect_dial import inspect

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "work/android/app/src/main/assets/dynamic_dials"
REGISTRY = ASSETS / "offline-package-registry.json"
BACKGROUNDS = ROOT / "work/face-palettes-20260923/backgrounds-v2.json"
DONOR = ROOT / "work/dial-corpus-v2/sources/com.QC.qc176.dial-dfaac700817e.dial"
PLAN = ROOT / "work/face-layout-20260923/diallab3000-qc176-plan-v5.json"
PLB = ROOT / "work/face-layout-20260923/diallab3000-qc176-v5.plb"
ARCHIVE = ASSETS / "previous-v4/pipboy3000"


def main() -> None:
    document = json.loads(REGISTRY.read_text(encoding="utf-8"))
    records = [item for item in document["items"] if item["faceId"] == "pipboy3000"]
    backgrounds = {(item["faceId"], item["paletteId"]): item for item in json.loads(BACKGROUNDS.read_text(encoding="utf-8"))}
    if len(records) != 6:
        raise ValueError("expected six Dial Lab variants")
    candidates = []
    for record in records:
        target = ROOT / "work/android/app/src/main/assets" / record["packageAsset"]
        archived = (ARCHIVE / (record["paletteId"] + ".dial")).read_bytes()
        current = target.read_bytes()
        if archived != current or len(current) != record["packageSize"] or sha256(current).hexdigest() != record["packageSha256"]:
            raise ValueError("v4 archive or active package mismatch: " + record["paletteId"])
        row = backgrounds[("pipboy3000", record["paletteId"])]
        source = ROOT / row["path"]
        if sha256(source.read_bytes()).hexdigest().upper() != row["backgroundSha256"].upper():
            raise ValueError("background mismatch: " + record["paletteId"])
        identity = (record["internalPackageId"], inspect(target, current)["manifest"]["name"], record["alias"])
        candidate = build_package(DONOR, source, "#" + record["primary"][3:], plb_plan=PLAN, plb_result=PLB, identity=identity)
        if candidate == current:
            raise ValueError("layout unchanged: " + record["paletteId"])
        candidates.append((record, target, candidate))
    for record, target, candidate in candidates:
        target.write_bytes(candidate)
        record["packageSha256"] = sha256(candidate).hexdigest()
        record["packageSize"] = len(candidate)
        print(record["paletteId"], record["packageSize"], record["packageSha256"])
    REGISTRY.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("registry", sha256(REGISTRY.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
