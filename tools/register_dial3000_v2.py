"""Stage exact offline Dial 3000 v2 metadata after candidate files are inspected."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "work/android/app/src/main/assets/dynamic_dials"
BUILT = json.loads((ROOT / "work/dial3000-v2/candidates-v2/build-manifest.json").read_text(encoding="utf-8"))
PALETTES = ("phosphor", "amber", "cyan", "white", "red", "violet")


def main():
    catalog_path = ASSETS / "catalog.json"
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    prior = next(item for item in catalog["items"] if item["id"] == "pipboy3000")
    dial = next(item for item in catalog["items"] if item["id"] == "dial3000")
    if len(dial["palettes"]) != 1 or len(prior["palettes"]) != 6:
        raise ValueError("catalog is not proven 0.9.10 state")
    dial["name"] = "Dial Lab 3000"
    dial["previewAsset"] = "dynamic_dials/dial3000/phosphor/preview.png"
    dial["verificationScope"] = "offline-unverified-2026-09-29"
    dial["palettes"] = prior["palettes"]
    catalog_path.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    registry_path = ASSETS / "offline-package-registry.json"
    registry = json.loads(registry_path.read_text(encoding="utf-8"))
    if len(registry["items"]) != 20:
        raise ValueError("registry is not proven 0.9.10 state")
    old = [item for item in registry["items"] if item["faceId"] == "dial3000"]
    if len(old) != 1 or old[0]["packageSha256"] != BUILT["sourcePackageSha256"]:
        raise ValueError("proven Dial 3000 baseline missing")
    registry["items"] = [item for item in registry["items"] if item["faceId"] != "dial3000"]
    palette_by_id = {item["id"]: item for item in dial["palettes"]}
    for variant in BUILT["variants"]:
        theme = variant["theme"]
        if theme not in PALETTES:
            raise ValueError("unexpected theme")
        resource = variant["images"]["images/background.png"]
        registry["items"].append({"faceId": "dial3000", "paletteId": theme,
            "packageAsset": f"dynamic_dials/dial3000/{theme}/face.dial",
            "packageSha256": variant["sha256"], "packageSize": variant["size"],
            "internalPackageId": variant["identity"]["id"], "alias": variant["identity"]["alias"],
            "backgroundSha256": resource["sha256"], "primary": palette_by_id[theme]["colors"]["primary"]})
    if len(registry["items"]) != 25:
        raise ValueError("wrong active package count")
    registry_path.write_text(json.dumps(registry, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
