"""Expose verified v3 and experimental animation as separate selectable faces."""
import hashlib
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "work/android/app/src/main/assets/dynamic_dials"
JAVA = ROOT / "work/android/app/src/main/java/app/gtdial/lab/BundledDynamicDialRepository.java"
ACTIVITY = ROOT / "work/android/app/src/main/java/app/gtdial/lab/MainActivity.java"
SESSION = ROOT / "work/android/app/src/main/java/app/gtdial/lab/BleWatchSession.java"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def line(item):
    return (f'        new BundledDynamicDial.PackageVariant("{item["faceId"]}","{item["paletteId"]}",'
            f'"{item["packageAsset"]}","{item["packageSha256"]}",{item["packageSize"]}L,'
            f'"{item["internalPackageId"]}","{item["alias"]}")')


def main():
    registry_path = ASSETS / "offline-package-registry.json"
    registry = json.loads(registry_path.read_text(encoding="utf-8"))
    animated = [item for item in registry["items"] if item["faceId"] == "dial3000"]
    if len(animated) != 6 or any("dial3000anim" not in item["internalPackageId"] for item in animated):
        raise ValueError("animation staging not found")
    old_manifest = json.loads((ROOT / "work/dial3000-v2/candidates-v3/build-manifest.json").read_text(encoding="utf-8"))
    stable = []
    walk = []
    for item in animated:
        theme = item["paletteId"]
        active = ASSETS / item["packageAsset"].removeprefix("dynamic_dials/")
        expected_walk = ROOT / f"work/dial3000-animation/candidates-v2/{theme}.dial"
        if sha(active) != item["packageSha256"] or active.read_bytes() != expected_walk.read_bytes():
            raise ValueError("staged animation mismatch")
        walk_asset = f"dynamic_dials/dial3000walk/{theme}/face.dial"
        walk_path = ASSETS / f"dial3000walk/{theme}/face.dial"
        walk_path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(active, walk_path)
        shutil.copyfile(ASSETS / f"dial3000/{theme}/preview.png", walk_path.with_name("preview.png"))
        walk.append(dict(item, faceId="dial3000walk", packageAsset=walk_asset))
        original = next(entry for entry in old_manifest["variants"] if entry["theme"] == theme)
        stable_file = ROOT / f"work/dial3000-v2/candidates-v3/{theme}.dial"
        if sha(stable_file) != original["sha256"]:
            raise ValueError("stable candidate digest")
        shutil.copyfile(stable_file, active)
        shutil.copyfile(ROOT / f"outputs/dial3000-v3-package-renders/{theme}-11-25-battery50.png",
                        ASSETS / f"dial3000/{theme}/preview.png")
        stable.append(dict(item, packageSha256=original["sha256"], packageSize=original["size"],
                           internalPackageId=original["identity"]["id"], alias=original["identity"]["alias"]))
    registry["items"] = [item for item in registry["items"] if item["faceId"] != "dial3000"] + stable + walk
    registry_path.write_text(json.dumps(registry, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    catalog_path = ASSETS / "catalog.json"
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    existing = next(item for item in catalog["items"] if item["id"] == "dial3000")
    new_card = dict(existing)
    new_card.update(id="dial3000walk", name="Dial Lab 3000 · анимация (опыт)",
                    previewAsset="dynamic_dials/dial3000walk/phosphor/preview.png",
                    renderSpecAsset="dynamic_dials/dial3000walk/render-spec.json",
                    verificationScope="offline-animation-unverified-2026-09-29")
    catalog["items"].append(new_card)
    catalog_path.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    spec = json.loads((ASSETS / "dial3000/render-spec.json").read_text(encoding="utf-8"))
    spec["layers"][0]["asset"] = "dynamic_dials/dial3000walk/phosphor/preview.png"
    spec["animation"] = {"node": "animatedImage1", "frameCount": 4, "timingRaw": 100,
                         "timingUnit": "unknown", "watchRuntimeVerified": False}
    (ASSETS / "dial3000walk/render-spec.json").write_text(json.dumps(spec, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")

    map_path = ASSETS / "shared-base-coordinate-map.json"
    coordinates = json.loads(map_path.read_text(encoding="utf-8"))
    original_map = json.loads((ROOT / "work/dial3000-v2/candidates-v3/coordinate-map.json").read_text(encoding="utf-8"))
    for item in animated:
        theme = item["paletteId"]
        key = "dial3000_" + theme
        animated_entry = coordinates["variants"][key]
        if animated_entry["packageSha256"] != item["packageSha256"] or len(animated_entry["rootAndChildren"]) != 15:
            raise ValueError("animated coordinate mismatch")
        coordinates["variants"]["dial3000walk_" + theme] = animated_entry
        coordinates["variants"][key] = original_map["variants"][key]
    map_path.write_text(json.dumps(coordinates, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    shutil.copyfile(map_path, ROOT / "work/dial3000-animation/candidates-v2/coordinate-map.json")

    source = JAVA.read_text(encoding="utf-8")
    for old, new in zip(animated, walk):
        if source.count(line(old)) != 1:
            raise ValueError("Java staged tuple mismatch")
        source = source.replace(line(old), line(new))
    stable_lines = ',\n'.join(line(item) for item in stable)
    marker = '        new BundledDynamicDial.PackageVariant("pipboy3000","phosphor","dynamic_dials/pipboy3000/phosphor/face.dial"'
    if source.count(marker) != 1:
        raise ValueError("Java active insert marker mismatch")
    source = source.replace(marker, stable_lines + ',\n' + marker)
    source = source.replace('source.length()!=6)', 'source.length()!=7)')
    source = source.replace('"base3000","dial3000")))', '"base3000","dial3000","dial3000walk")))')
    source = source.replace('records.length()!=25)', 'records.length()!=31)')
    source = source.replace('"dial3000".equals(dial.id))logSharedCoordinates',
                            '"dial3000".equals(dial.id)||"dial3000walk".equals(dial.id))logSharedCoordinates')
    source = source.replace('"base3000".equals(faceId)?"base":"dial3000_"+paletteId',
                            '"base3000".equals(faceId)?"base":faceId+"_"+paletteId')
    source = source.replace('nodes.length()!=((' + '"base3000".equals(faceId))?12:15)',
                            'nodes.length()!=((' + '"base3000".equals(faceId))?12:("dial3000walk".equals(faceId)?15:14))')
    source = source.replace('private static final String REGISTRY_SHA256="78b942e650fc081d75cf35c2bda4fcdb316844953605b1f594d4768554334c80";',
                            'private static final String REGISTRY_SHA256="' + sha(registry_path) + '";')
    source = source.replace('private static final String SHARED_COORDINATES_SHA256="4ea5a0404f5c10a6926cc5dca08c7f0e2fb376a295ea2217f332f285e0a9659f";',
                            'private static final String SHARED_COORDINATES_SHA256="' + sha(map_path) + '";')
    for required in ('source.length()!=7)', 'records.length()!=31)', '"dial3000walk".equals(faceId)?15:14'):
        if required not in source:
            raise ValueError("Java registry/coordinate patch missing: " + required)
    JAVA.write_text(source, encoding="utf-8")

    for path in (ACTIVITY, SESSION):
        value = path.read_text(encoding="utf-8")
        value = value.replace('"dial3000".equals(dial.id)?"dynamic_dials/dial3000/"+palette.id+',
                              '("dial3000".equals(dial.id)||"dial3000walk".equals(dial.id))?"dynamic_dials/"+dial.id+"/"+palette.id+')
        value = value.replace('"dial3000".equals(dial.id)){image.clearColorFilter()',
                              '("dial3000".equals(dial.id)||"dial3000walk".equals(dial.id))){image.clearColorFilter()')
        value = value.replace('"dial3000".equals(dial.id)?"dynamic_dials/dial3000/"+variant.paletteId+',
                              '("dial3000".equals(dial.id)||"dial3000walk".equals(dial.id))?"dynamic_dials/"+dial.id+"/"+variant.paletteId+')
        path.write_text(value, encoding="utf-8")
    print(json.dumps({"stableVariants": len(stable), "walkVariants": len(walk),
                      "registrySha256": sha(registry_path), "coordinateSha256": sha(map_path)}))


if __name__ == "__main__":
    main()
