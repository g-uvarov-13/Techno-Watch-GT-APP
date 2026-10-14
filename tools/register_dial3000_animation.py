"""Stage six walk-animation packages and preserve the confirmed 0.9.12 files."""
import hashlib
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "work/android/app/src/main/assets/dynamic_dials"
JAVA = ROOT / "work/android/app/src/main/java/app/gtdial/lab/BundledDynamicDialRepository.java"
GRADLE = ROOT / "work/android/app/build.gradle.kts"
BUILT = json.loads((ROOT / "work/dial3000-animation/candidates-v2/build-manifest.json").read_text(encoding="utf-8"))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def variant_line(item, asset):
    return (f'        new BundledDynamicDial.PackageVariant("dial3000","{item["paletteId"]}",'
            f'"{asset}","{item["packageSha256"]}",{item["packageSize"]}L,'
            f'"{item["internalPackageId"]}","{item["alias"]}")')


def main():
    registry_path = ASSETS / "offline-package-registry.json"
    registry = json.loads(registry_path.read_text(encoding="utf-8"))
    old = [item for item in registry["items"] if item["faceId"] == "dial3000"]
    if len(old) != 6 or any("dial3000v3" not in item["internalPackageId"] for item in old):
        raise ValueError("confirmed 0.9.12 variants not active")
    old_by_theme = {item["paletteId"]: item for item in old}
    variants = BUILT["variants"]
    if {item["theme"] for item in variants} != set(old_by_theme):
        raise ValueError("theme set mismatch")
    new = []
    for variant in variants:
        theme = variant["theme"]
        prior = old_by_theme[theme]
        active = ASSETS / f"dial3000/{theme}/face.dial"
        historical = ASSETS / f"previous-v9/dial3000/{theme}.dial"
        if historical.exists():
            raise FileExistsError(historical)
        if active.stat().st_size != prior["packageSize"] or sha(active) != prior["packageSha256"]:
            raise ValueError("active 0.9.12 package mismatch")
        historical.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(active, historical)
        candidate = ROOT / f"work/dial3000-animation/candidates-v2/{theme}.dial"
        if sha(candidate) != variant["packageSha256"] or candidate.stat().st_size != variant["packageSize"]:
            raise ValueError("candidate mismatch")
        shutil.copyfile(candidate, active)
        preview = ROOT / f"outputs/dial3000-walk-{theme}-preview.png"
        shutil.copyfile(preview, ASSETS / f"dial3000/{theme}/preview.png")
        updated = dict(prior)
        updated.update(packageSha256=variant["packageSha256"], packageSize=variant["packageSize"],
                       internalPackageId=variant["identity"]["id"], alias=variant["identity"]["alias"])
        new.append(updated)
    registry["items"] = [item for item in registry["items"] if item["faceId"] != "dial3000"] + new
    registry_path.write_text(json.dumps(registry, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    map_path = ASSETS / "shared-base-coordinate-map.json"
    coordinates = json.loads(map_path.read_text(encoding="utf-8"))
    for variant in variants:
        theme = variant["theme"]
        entry = coordinates["variants"]["dial3000_" + theme]
        if entry["packageSha256"] != old_by_theme[theme]["packageSha256"] or len(entry["rootAndChildren"]) != 14:
            raise ValueError("0.9.12 coordinate map mismatch")
        entry["packageSha256"] = variant["packageSha256"]
        entry["rootAndChildren"].append({"id": "animatedImage1", "type": "animated",
                                          "rect": [184, 138, 84, 171], "serializationOrder": 14,
                                          "frameCount": 4, "timingRaw": 100, "timingUnit": "unknown"})
    map_path.write_text(json.dumps(coordinates, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    map_source = ROOT / "work/dial3000-animation/candidates-v2/coordinate-map.json"
    shutil.copyfile(map_path, map_source)

    source = JAVA.read_text(encoding="utf-8")
    source = source.replace('HISTORICAL_V8.values())if(matches(v,internalId,alias,size,sha))return true;',
                            'HISTORICAL_V8.values())if(matches(v,internalId,alias,size,sha))return true;for(BundledDynamicDial.PackageVariant v:HISTORICAL_V9.values())if(matches(v,internalId,alias,size,sha))return true;')
    source = source.replace('private static final Map<String,BundledDynamicDial.PackageVariant> HISTORICAL_V8=historicalV8();',
                            'private static final Map<String,BundledDynamicDial.PackageVariant> HISTORICAL_V8=historicalV8();\n    private static final Map<String,BundledDynamicDial.PackageVariant> HISTORICAL_V9=historicalV9();')
    source = source.replace('HISTORICAL_V7,HISTORICAL_V8))', 'HISTORICAL_V7,HISTORICAL_V8,HISTORICAL_V9))')
    historical_lines = ',\n'.join(variant_line(item, f'dynamic_dials/previous-v9/dial3000/{item["paletteId"]}.dial') for item in old)
    method = ('    private static Map<String,BundledDynamicDial.PackageVariant> historicalV9(){Map<String,BundledDynamicDial.PackageVariant> m=new LinkedHashMap<>();BundledDynamicDial.PackageVariant[] all={\n'
              + historical_lines + '\n    };for(BundledDynamicDial.PackageVariant v:all)if(m.put(key(v.faceId,v.paletteId),v)!=null)throw new IllegalStateException("Duplicate historical variant");return Collections.unmodifiableMap(m);}\n\n')
    marker = '    private static Map<String,BundledDynamicDial.PackageVariant> compiledVariants()'
    if source.count(marker) != 1:
        raise ValueError("compiledVariants marker mismatch")
    source = source.replace(marker, method + marker)
    for previous, updated in zip(old, new):
        old_line = variant_line(previous, previous["packageAsset"])
        if source.count(old_line) != 1:
            raise ValueError("active Java tuple mismatch")
        source = source.replace(old_line, variant_line(updated, updated["packageAsset"]))
    source = source.replace('nodes.length()!=((' + '"base3000".equals(faceId))?12:14)',
                            'nodes.length()!=((' + '"base3000".equals(faceId))?12:15)')
    if 'nodes.length()!=((' + '"base3000".equals(faceId))?12:15)' not in source:
        raise ValueError("coordinate node count patch failed")
    source = source.replace('private static final String REGISTRY_SHA256="9be5304548e2d21865c0c12aa95dbc14b518dc4bcdf5ec612589f5e1f2287fea";',
                            'private static final String REGISTRY_SHA256="' + sha(registry_path) + '";')
    source = source.replace('private static final String SHARED_COORDINATES_SHA256="21627b4bde1d933ecdaa258cee45179c14aaa89b0d4192b67cd94a0a439271e8";',
                            'private static final String SHARED_COORDINATES_SHA256="' + sha(map_path) + '";')
    JAVA.write_text(source, encoding="utf-8")
    gradle = GRADLE.read_text(encoding="utf-8")
    if gradle.count('versionCode = 41') != 1 or gradle.count('versionName = "0.9.12"') != 1:
        raise ValueError("expected 0.9.12 baseline")
    GRADLE.write_text(gradle.replace('versionCode = 41', 'versionCode = 42').replace('versionName = "0.9.12"', 'versionName = "0.9.13"'), encoding="utf-8")
    print(json.dumps({"registrySha256": sha(registry_path), "coordinateSha256": sha(map_path), "variants": len(new)}))


if __name__ == "__main__":
    main()
