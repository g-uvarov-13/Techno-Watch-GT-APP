"""Pin corrected six-theme animation while preserving the physically seen v1 packages."""
import hashlib
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "work/android/app/src/main/assets/dynamic_dials"
JAVA = ROOT / "work/android/app/src/main/java/app/gtdial/lab/BundledDynamicDialRepository.java"
BUILD = ROOT / "work/android/app/build.gradle.kts"
THEMES = ("phosphor", "amber", "cyan", "white", "red", "violet")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def line(item):
    return (f'new BundledDynamicDial.PackageVariant("{item["faceId"]}","{item["paletteId"]}",'
            f'"{item["packageAsset"]}","{item["packageSha256"]}",{item["packageSize"]}L,'
            f'"{item["internalPackageId"]}","{item["alias"]}")')


def replace_once(source, old, new):
    if source.count(old) != 1:
        raise ValueError("Expected one occurrence: " + old[:100])
    return source.replace(old, new)


def main():
    registry_path = ASSETS / "offline-package-registry.json"
    registry = json.loads(registry_path.read_text(encoding="utf-8"))
    old = {entry["paletteId"]: entry for entry in registry["items"] if entry["faceId"] == "dial3000walk"}
    manifest = json.loads((ROOT / "work/dial3000-animation/candidates-v3/build-manifest.json").read_text(encoding="utf-8"))
    new = {variant["theme"]: variant for variant in manifest["variants"]}
    if set(old) != set(THEMES) or set(new) != set(THEMES):
        raise ValueError("Six animation themes required")
    source = JAVA.read_text(encoding="utf-8")
    historical = []
    for theme in THEMES:
        item, candidate = old[theme], new[theme]
        active = ASSETS / item["packageAsset"].removeprefix("dynamic_dials/")
        history = ASSETS / f"previous-v10/dial3000walk/{theme}.dial"
        expected_old = ROOT / f"work/dial3000-animation/candidates-v2/{theme}.dial"
        expected_new = ROOT / f"work/dial3000-animation/candidates-v3/{theme}.dial"
        if sha(active) != item["packageSha256"] or active.read_bytes() != expected_old.read_bytes():
            raise ValueError("Previous animation mismatch: " + theme)
        if sha(expected_new) != candidate["packageSha256"] or expected_new.stat().st_size != candidate["packageSize"]:
            raise ValueError("Corrected candidate mismatch: " + theme)
        historical_item = dict(item, packageAsset=f"dynamic_dials/previous-v10/dial3000walk/{theme}.dial")
        historical.append(historical_item)
        history.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(active, history)
        updated = dict(item, packageSha256=candidate["packageSha256"], packageSize=candidate["packageSize"],
                       internalPackageId=candidate["identity"]["id"], alias=candidate["identity"]["alias"])
        source = replace_once(source, line(item), line(updated))
        item.update(updated)
        shutil.copyfile(expected_new, active)
        shutil.copyfile(ROOT / f"outputs/dial3000-walk-{theme}-v2-preview.png", active.with_name("preview.png"))

    marker = "    private static Map<String,BundledDynamicDial.PackageVariant> compiledVariants()"
    history_source = ('    private static Map<String,BundledDynamicDial.PackageVariant> historicalV10(){'
                      'Map<String,BundledDynamicDial.PackageVariant> m=new LinkedHashMap<>();'
                      'BundledDynamicDial.PackageVariant[] all={\n        '
                      + ',\n        '.join(line(item) for item in historical)
                      + '\n    };for(BundledDynamicDial.PackageVariant v:all)'
                      'if(m.put(key(v.faceId,v.paletteId),v)!=null)throw new IllegalStateException("Duplicate historical variant");'
                      'return Collections.unmodifiableMap(m);}\n\n')
    source = replace_once(source, marker, history_source + marker)
    source = replace_once(source, 'private static final Map<String,BundledDynamicDial.PackageVariant> HISTORICAL_V9=historicalV9();',
                          'private static final Map<String,BundledDynamicDial.PackageVariant> HISTORICAL_V9=historicalV9();\n'
                          '    private static final Map<String,BundledDynamicDial.PackageVariant> HISTORICAL_V10=historicalV10();')
    source = replace_once(source, 'HISTORICAL_V8,HISTORICAL_V9)){', 'HISTORICAL_V8,HISTORICAL_V9,HISTORICAL_V10)){')
    source = replace_once(source, 'if(matches(v,internalId,alias,size,sha))return true;return "com.gtdial.pipboy01"',
                          'if(matches(v,internalId,alias,size,sha))return true;for(BundledDynamicDial.PackageVariant v:HISTORICAL_V10.values())if(matches(v,internalId,alias,size,sha))return true;return "com.gtdial.pipboy01"')

    registry_path.write_text(json.dumps(registry, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    coords_path = ASSETS / "shared-base-coordinate-map.json"
    coords = json.loads(coords_path.read_text(encoding="utf-8"))
    for theme in THEMES:
        coords["variants"][f"dial3000walk_{theme}"]["packageSha256"] = old[theme]["packageSha256"]
    coords_path.write_text(json.dumps(coords, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    spec_path = ASSETS / "dial3000walk/render-spec.json"
    spec = json.loads(spec_path.read_text(encoding="utf-8"))
    if spec["animation"]["timingRaw"] != 100:
        raise ValueError("Old animation timing mismatch")
    spec["animation"]["timingRaw"] = 200
    spec["animation"]["watchRuntimeVerified"] = False
    spec_path.write_text(json.dumps(spec, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")

    source = replace_once(source, 'private static final String REGISTRY_SHA256="ec61c83b4324ab5934d6617cd39ff43475dcac5c944474f55d845b2f3b7cffe5";',
                          'private static final String REGISTRY_SHA256="' + sha(registry_path) + '";')
    source = replace_once(source, 'private static final String SHARED_COORDINATES_SHA256="50359e6b811937b5d9d4394a94c44741b4bf2762d2dbfce111e352783faddea1";',
                          'private static final String SHARED_COORDINATES_SHA256="' + sha(coords_path) + '";')
    JAVA.write_text(source, encoding="utf-8")
    build = BUILD.read_text(encoding="utf-8")
    BUILD.write_text(replace_once(replace_once(build, "versionCode = 43", "versionCode = 44"),
                                  'versionName = "0.9.14"', 'versionName = "0.9.15"'), encoding="utf-8")
    print(json.dumps({"themes": len(THEMES), "registrySha256": sha(registry_path),
                      "coordinatesSha256": sha(coords_path), "historicalCount": len(historical)}))


if __name__ == "__main__":
    main()
