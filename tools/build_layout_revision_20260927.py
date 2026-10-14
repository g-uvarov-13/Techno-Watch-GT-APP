"""Build offline candidate packages for the photo-driven GT01 layout revision."""
from __future__ import annotations

import json
from hashlib import sha256
from pathlib import Path

from tools.build_final_face_palettes import ASSETS, QC176, _qc61_variant
from tools.build_qc176_palette_variant import build_package as build_qc176
from tools.inspect_dial import inspect

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "work/face-layout-20260927"
OUTPUT = WORK / "packages"


def main() -> None:
    old = json.loads((ASSETS / "offline-package-registry.json").read_text(encoding="utf-8"))
    backgrounds = {(r["faceId"], r["paletteId"]): r for r in json.loads((WORK / "backgrounds-v3.json").read_text(encoding="utf-8"))}
    if len(old["items"]) != 18 or len(backgrounds) != 18:
        raise ValueError("unexpected face count")
    rows, packages = [], []
    for record in old["items"]:
        face, palette = record["faceId"], record["paletteId"]
        source = ROOT / backgrounds[(face, palette)]["path"]
        primary = record["primary"]
        current_path = ASSETS / face / palette / "face.dial"
        current_manifest = inspect(current_path, current_path.read_bytes())["manifest"]
        if current_manifest["id"] != record["internalPackageId"] or current_manifest["alias"] != record["alias"]:
            raise ValueError("current package identity differs from pinned registry")
        identity = (record["internalPackageId"], record["alias"], current_manifest["name"])
        if sha256(source.read_bytes()).hexdigest().upper() != backgrounds[(face, palette)]["backgroundSha256"].upper():
            raise ValueError("background hash mismatch")
        if face == "pipboy3000":
            blob = build_qc176(QC176, source, "#" + primary[3:],
                                plb_plan=WORK / "diallab3000-qc176-plan-v6.json",
                                plb_result=WORK / "diallab3000-qc176-v6.plb",
                                identity=(identity[0], identity[2], identity[1]))
        else:
            plan = WORK / ("nixie-core-qc61-layout-v3.json" if face == "bitax" else "pixel-deck-qc61-layout-v4.json")
            blob = _qc61_variant(face, source, primary, identity, plan_override=plan)
        result = inspect(OUTPUT / face / palette / "face.dial", blob)
        if result["manifest"]["id"] != record["internalPackageId"] or result["manifest"]["alias"] != record["alias"]:
            raise ValueError("candidate identity changed")
        row = dict(record)
        row["packageSha256"] = sha256(blob).hexdigest()
        row["packageSize"] = len(blob)
        row["backgroundSha256"] = backgrounds[(face, palette)]["backgroundSha256"].lower()
        rows.append(row)
        packages.append((OUTPUT / face / palette / "face.dial", blob))
    for path, blob in packages:
        if path.exists():
            raise FileExistsError(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(blob)
    registry = OUTPUT / "offline-package-registry.json"
    if registry.exists():
        raise FileExistsError(registry)
    registry.write_text(json.dumps({"schemaVersion": 1, "items": rows}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"packages": len(rows), "registrySha256": sha256(registry.read_bytes()).hexdigest(), "output": str(OUTPUT)}))


if __name__ == "__main__":
    main()
