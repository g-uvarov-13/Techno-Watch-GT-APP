"""Register the six measured steps-offset packages while preserving v2 files."""
import json
import hashlib
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "work/android/app/src/main/assets/dynamic_dials"
JAVA = ROOT / "work/android/app/src/main/java/app/gtdial/lab/BundledDynamicDialRepository.java"
GRADLE = ROOT / "work/android/app/build.gradle.kts"
SOURCE = ROOT / "work/dial3000-v2/candidates-v3"
MANIFEST = json.loads((SOURCE / "build-manifest.json").read_text(encoding="utf-8"))


def variant_line(item, asset):
    return (f'        new BundledDynamicDial.PackageVariant("dial3000","{item["paletteId"]}",'
            f'"{asset}","{item["packageSha256"]}",{item["packageSize"]}L,'
            f'"{item["internalPackageId"]}","{item["alias"]}")')


def main():
    registry_path = ASSETS / "offline-package-registry.json"
    registry = json.loads(registry_path.read_text(encoding="utf-8"))
    old = [item for item in registry["items"] if item["faceId"] == "dial3000"]
    if len(old) != 6 or any("dial3000v2" not in item["internalPackageId"] for item in old):
        raise ValueError("expected six active v2 variants")
    old_by_theme = {item["paletteId"]: item for item in old}
    new = []
    for variant in MANIFEST["variants"]:
        theme = variant["theme"]
        prior = old_by_theme[theme]
        active = ASSETS / f"dial3000/{theme}/face.dial"
        historical = ASSETS / f"previous-v8/dial3000/{theme}.dial"
        if historical.exists():
            raise FileExistsError(historical)
        historical.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(active, historical)
        if (historical.stat().st_size != prior["packageSize"] or
                hashlib.sha256(historical.read_bytes()).hexdigest() != prior["packageSha256"]):
            raise ValueError("historical package mismatch")
        shutil.copyfile(SOURCE / f"{theme}.dial", active)
        updated = dict(prior)
        updated.update(packageSha256=variant["sha256"], packageSize=variant["size"],
                       internalPackageId=variant["identity"]["id"], alias=variant["identity"]["alias"])
        new.append(updated)
    registry["items"] = [item for item in registry["items"] if item["faceId"] != "dial3000"] + new
    registry_path.write_text(json.dumps(registry, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    source = JAVA.read_text(encoding="utf-8")
    source = source.replace('HISTORICAL_V7.values())if(matches(v,internalId,alias,size,sha))return true;',
                            'HISTORICAL_V7.values())if(matches(v,internalId,alias,size,sha))return true;for(BundledDynamicDial.PackageVariant v:HISTORICAL_V8.values())if(matches(v,internalId,alias,size,sha))return true;')
    source = source.replace('private static final Map<String,BundledDynamicDial.PackageVariant> HISTORICAL_V7=historicalV7();',
                            'private static final Map<String,BundledDynamicDial.PackageVariant> HISTORICAL_V7=historicalV7();\n    private static final Map<String,BundledDynamicDial.PackageVariant> HISTORICAL_V8=historicalV8();')
    source = source.replace('HISTORICAL_V6,HISTORICAL_V7))', 'HISTORICAL_V6,HISTORICAL_V7,HISTORICAL_V8))')
    historical_lines = ',\n'.join(variant_line(item, f'dynamic_dials/previous-v8/dial3000/{item["paletteId"]}.dial') for item in old)
    method = ('    private static Map<String,BundledDynamicDial.PackageVariant> historicalV8(){Map<String,BundledDynamicDial.PackageVariant> m=new LinkedHashMap<>();BundledDynamicDial.PackageVariant[] all={\n'
              + historical_lines + '\n    };for(BundledDynamicDial.PackageVariant v:all)if(m.put(key(v.faceId,v.paletteId),v)!=null)throw new IllegalStateException("Duplicate historical variant");return Collections.unmodifiableMap(m);}\n\n')
    marker = '    private static Map<String,BundledDynamicDial.PackageVariant> compiledVariants()'
    if source.count(marker) != 1:
        raise ValueError("compiledVariants marker mismatch")
    source = source.replace(marker, method + marker)
    for previous, updated in zip(old, new):
        old_line = variant_line(previous, previous["packageAsset"])
        new_line = variant_line(updated, updated["packageAsset"])
        if source.count(old_line) != 1:
            raise ValueError("active Java tuple mismatch")
        source = source.replace(old_line, new_line)
    JAVA.write_text(source, encoding="utf-8")
    gradle = GRADLE.read_text(encoding="utf-8")
    if gradle.count('versionCode = 40') != 1 or gradle.count('versionName = "0.9.11"') != 1:
        raise ValueError("expected 0.9.11 baseline")
    GRADLE.write_text(gradle.replace('versionCode = 40', 'versionCode = 41').replace('versionName = "0.9.11"', 'versionName = "0.9.12"'), encoding="utf-8")


if __name__ == "__main__":
    main()
