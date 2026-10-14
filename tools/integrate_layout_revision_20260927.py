"""Integrate independently verified layout candidates with exact receipt history."""
from __future__ import annotations

import json
import re
from hashlib import sha256
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "work/android/app/src/main/assets/dynamic_dials"
WORK = ROOT / "work/face-layout-20260927"
JAVA = ROOT / "work/android/app/src/main/java/app/gtdial/lab/BundledDynamicDialRepository.java"


def tuple_line(row: dict, asset: str) -> str:
    return ('        new BundledDynamicDial.PackageVariant('
            f'"{row["faceId"]}","{row["paletteId"]}","{asset}",'
            f'"{row["packageSha256"]}",{row["packageSize"]}L,'
            f'"{row["internalPackageId"]}","{row["alias"]}")')


def main() -> None:
    old_registry_path = ASSETS / "offline-package-registry.json"
    old_bytes = old_registry_path.read_bytes()
    old = json.loads(old_bytes)["items"]
    candidate_path = WORK / "packages/offline-package-registry.json"
    new_bytes = candidate_path.read_bytes()
    new = json.loads(new_bytes)["items"]
    if len(old) != 18 or len(new) != 18:
        raise ValueError("expected 18 existing and 18 candidate records")
    if [(x["faceId"], x["paletteId"], x["internalPackageId"], x["alias"]) for x in old] != [
        (x["faceId"], x["paletteId"], x["internalPackageId"], x["alias"]) for x in new]:
        raise ValueError("package identities or order changed")
    old_payloads, new_payloads = [], []
    for before, after in zip(old, new):
        face, palette = before["faceId"], before["paletteId"]
        active = ASSETS / face / palette / "face.dial"
        candidate = WORK / "packages" / face / palette / "face.dial"
        historical = ASSETS / "previous-v5" / face / (palette + ".dial")
        if historical.exists():
            raise FileExistsError(historical)
        left, right = active.read_bytes(), candidate.read_bytes()
        for data, row in ((left, before), (right, after)):
            if len(data) != row["packageSize"] or sha256(data).hexdigest() != row["packageSha256"]:
                raise ValueError("package integrity mismatch")
        old_payloads.append((historical, left))
        new_payloads.append((active, right))
    source = JAVA.read_text(encoding="utf-8")
    original_source = source
    old_hash, new_hash = sha256(old_bytes).hexdigest(), sha256(new_bytes).hexdigest()
    if source.count(old_hash) != 1:
        raise ValueError("old registry hash is not pinned once")
    source = source.replace(old_hash, new_hash)
    for before, after in zip(old, new):
        previous = tuple_line(before, before["packageAsset"]).strip()
        replacement = tuple_line(after, after["packageAsset"]).strip()
        if source.count(previous) != 1:
            raise ValueError("compiled old tuple is not pinned once: " + previous)
        source = source.replace(previous, replacement)
    if source.count("HISTORICAL_DIAL_LAB_V4=historicalDialLabV4()") != 1:
        raise ValueError("historical map anchor changed")
    source = source.replace("HISTORICAL_DIAL_LAB_V4=historicalDialLabV4();",
                            "HISTORICAL_DIAL_LAB_V4=historicalDialLabV4();\n"
                            "    private static final Map<String,BundledDynamicDial.PackageVariant> HISTORICAL_V5=historicalV5();")
    source = source.replace("for(BundledDynamicDial.PackageVariant v:HISTORICAL_DIAL_LAB_V4.values())if(matches(v,internalId,alias,size,sha))return true;",
                            "for(BundledDynamicDial.PackageVariant v:HISTORICAL_DIAL_LAB_V4.values())if(matches(v,internalId,alias,size,sha))return true;for(BundledDynamicDial.PackageVariant v:HISTORICAL_V5.values())if(matches(v,internalId,alias,size,sha))return true;")
    source = source.replace("java.util.Arrays.asList(HISTORICAL_DIAL_LAB,HISTORICAL_DIAL_LAB_V4)",
                            "java.util.Arrays.asList(HISTORICAL_DIAL_LAB,HISTORICAL_DIAL_LAB_V4,HISTORICAL_V5)")
    marker = "    private static Map<String,BundledDynamicDial.PackageVariant> compiledVariants()"
    if source.count(marker) != 1:
        raise ValueError("compiled variants anchor changed")
    lines = [tuple_line(row, f'dynamic_dials/previous-v5/{row["faceId"]}/{row["paletteId"]}.dial') for row in old]
    method = ("    private static Map<String,BundledDynamicDial.PackageVariant> historicalV5(){Map<String,BundledDynamicDial.PackageVariant> m=new LinkedHashMap<>();BundledDynamicDial.PackageVariant[] all={\n"
              + ",\n".join(lines)
              + "\n    };for(BundledDynamicDial.PackageVariant v:all)if(m.put(key(v.faceId,v.paletteId),v)!=null)throw new IllegalStateException(\"Duplicate historical variant\");return Collections.unmodifiableMap(m);}\n\n")
    source = source.replace(marker, method + marker)
    if source == original_source or source.count("HISTORICAL_V5") != 3:
        raise ValueError("unexpected Java integration shape")
    backup = WORK / "registry-v5.json"
    if backup.exists():
        raise FileExistsError(backup)
    backup.write_bytes(old_bytes)
    for path, data in old_payloads:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    for path, data in new_payloads:
        path.write_bytes(data)
    old_registry_path.write_bytes(new_bytes)
    JAVA.write_text(source, encoding="utf-8")
    print(json.dumps({"active": len(new_payloads), "historicalV5": len(old_payloads), "registrySha256": new_hash}))


if __name__ == "__main__":
    main()
