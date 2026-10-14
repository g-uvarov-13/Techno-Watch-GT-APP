#!/usr/bin/env python3
"""Read-only verification of the 1.0.0 public asset manifest against an APK.

ASSET_MANIFEST.json at --root is a JSON list of asset-relative POSIX paths,
sizes and lowercase SHA-256 hashes, generated when the public package is made.
Only AGP's dexopt/baseline.prof and baseline.profm may occur as generated APK
assets outside that source manifest; their sizes and hashes are reported.
This verifies packaged bytes and source pins, not firmware safety, DEX behavior,
APK signature, permissions or complete build reproducibility.
"""

import argparse
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path, PurePosixPath


REGISTRY = "dynamic_dials/offline-package-registry.json"
REGISTRY_SHA = "19ed85dad6c61446edd617c46811bb3b2599da8bfe6a3faba7984e77c3b26a0a"
EDITOR_PREFIX = "dial_editor/gt01-text11-v1/"
EDITOR_PINS = {
    "template.dial": ("DONOR_SHA", "71c6c18f914f8b0caee64faf9f3cad26faddd059a2308846e3431a6265713326"),
    "app.js": ("APP_SHA", "ab29e2c90444c809014bdc8de95a2465f5dc254b84f0070507d3692138207f3c"),
    "main.js": ("MAIN_SHA", "93129836ba3a2b6e700e7096c680a46d50a9120d739f3c8f7c06ae9267621afb"),
    "profile.json": ("PROFILE_SHA", "c81bfcf5749b0e0e5b1428b8c5ff55791a20d50c6eb132610929d14e0deccee6"),
}
FORBIDDEN = ("animdiag-native10", "animdiag-js10")
GENERATED_ASSETS = frozenset(("dexopt/baseline.prof", "dexopt/baseline.profm"))
MAX_FILE = 64 * 1024 * 1024
MAX_TOTAL = 512 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def asset_path(value):
    require(isinstance(value, str) and bool(value), "Empty/non-string asset path")
    path = PurePosixPath(value)
    require(not path.is_absolute() and value == path.as_posix()
            and all(part not in (".", "..") for part in path.parts)
            and not any(char in value for char in ("\\", ":", "\0")),
            "Non-canonical asset path: " + repr(value))
    require(not any(token in value for token in FORBIDDEN),
            "Diagnostic animation asset is forbidden: " + value)
    return value


def load_json(data):
    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "Duplicate JSON key: " + key)
            result[key] = value
        return result
    return json.loads(data, object_pairs_hook=unique_object)


def java_pin(source, name, expected):
    matches = re.findall(r'\b' + re.escape(name) + r'\s*=\s*"([^"]+)"', source)
    require(matches == [expected], "Java pin mismatch: " + name)


def verify(root, apk):
    asset_root = root / "android/app/src/main/assets"
    java_root = root / "android/app/src/main/java/app/gtdial/lab"
    require(asset_root.is_dir(), "Package asset directory is missing")
    records = load_json((root / "ASSET_MANIFEST.json").read_bytes())
    require(isinstance(records, list) and 0 < len(records) <= 10000,
            "ASSET_MANIFEST.json must be a nonempty bounded list")
    expected = {}
    for record in records:
        require(isinstance(record, dict) and set(record) == {"path", "size", "sha256"},
                "Manifest record must contain path, size, sha256")
        name = asset_path(record["path"])
        require(name not in expected, "Duplicate manifest path: " + name)
        require(type(record["size"]) is int and 0 <= record["size"] <= MAX_FILE,
                "Invalid asset size: " + name)
        require(isinstance(record["sha256"], str)
                and re.fullmatch(r"[0-9a-f]{64}", record["sha256"]),
                "Invalid SHA-256: " + name)
        expected[name] = record
    require(sum(item["size"] for item in expected.values()) <= MAX_TOTAL,
            "Manifest assets exceed total size limit")
    require(not set(expected) & GENERATED_ASSETS,
            "Generated AGP profiles must not be declared as source assets")

    source_files = {}
    for path in asset_root.rglob("*"):
        require(not path.is_symlink(), "Symlink in package assets")
        if path.is_file():
            name = asset_path(path.relative_to(asset_root).as_posix())
            require(path.resolve().is_relative_to(asset_root.resolve()),
                    "Asset escapes source directory: " + name)
            source_files[name] = path
    require(set(source_files) == set(expected), "Source/manifest asset set mismatch")

    generated_assets = []
    with zipfile.ZipFile(apk) as archive:
        members = {}
        names = set()
        for member in archive.infolist():
            require(member.filename not in names, "Duplicate APK ZIP entry")
            names.add(member.filename)
            if member.filename.startswith("assets/") and not member.is_dir():
                name = asset_path(member.filename[len("assets/"):])
                require(not member.flag_bits & 1, "Encrypted APK asset: " + name)
                members[name] = member
        require(set(members) - GENERATED_ASSETS == set(expected),
                "APK/manifest asset set mismatch (only AGP baseline profiles are allowed extras)")
        for name in sorted(set(members) & GENERATED_ASSETS):
            member = members[name]
            require(0 < member.file_size <= MAX_FILE, "Invalid AGP profile size: " + name)
            profile_bytes = archive.read(member)
            generated_assets.append({"path": "assets/" + name, "size": len(profile_bytes),
                                     "sha256": digest(profile_bytes)})
        for name, record in expected.items():
            source = source_files[name]
            require(source.stat().st_size == record["size"]
                    and members[name].file_size == record["size"],
                    "Asset size mismatch: " + name)
            source_bytes = source.read_bytes()
            apk_bytes = archive.read(members[name])  # ZipFile also checks entry CRC.
            require(digest(source_bytes) == record["sha256"], "Source SHA mismatch: " + name)
            require(digest(apk_bytes) == record["sha256"] and apk_bytes == source_bytes,
                    "APK/source byte mismatch: " + name)

    require(REGISTRY in expected and expected[REGISTRY]["sha256"] == REGISTRY_SHA,
            "Pinned registry SHA mismatch")
    repository = (java_root / "BundledDynamicDialRepository.java").read_text(encoding="utf-8")
    java_pin(repository, "REGISTRY_SHA256", REGISTRY_SHA)
    registry = load_json(source_files[REGISTRY].read_bytes())
    require(isinstance(registry, dict) and registry.get("schemaVersion") == 1
            and isinstance(registry.get("items"), list) and len(registry["items"]) == 73,
            "Registry must have schemaVersion 1 and 73 active entries")

    # Read the active Java descriptor array only; historical variants are separate.
    active = re.search(r'\bcompiledVariants\s*\(\)\s*\{.*?\ball\s*=\s*\{(.*?)\};',
                       repository, re.DOTALL)
    require(active is not None, "Cannot locate active Java descriptor array")
    descriptors = re.findall(
        r'new BundledDynamicDial\.PackageVariant\("([^"]+)","([^"]+)",'
        r'"([^"]+)","([0-9a-f]{64})",(\d+)L,"([^"]+)","([^"]+)"\)', active.group(1))
    require(len(descriptors) == 73, "Expected 73 active Java descriptors")
    compiled = {}
    for face, palette, path, sha, size, internal, alias in descriptors:
        key = (face, palette)
        require(key not in compiled, "Duplicate active Java descriptor")
        compiled[key] = (path, sha, int(size), internal, alias)
    seen = set()
    for record in registry["items"]:
        require(isinstance(record, dict), "Invalid registry record")
        key = (record["faceId"], record["paletteId"])
        require(key not in seen, "Duplicate active registry identity")
        seen.add(key)
        name = asset_path(record["packageAsset"])
        descriptor = (name, record["packageSha256"], record["packageSize"],
                      record["internalPackageId"], record["alias"])
        require(compiled.get(key) == descriptor, "Registry/Java descriptor mismatch: " + name)
        require(name in expected and expected[name]["size"] == record["packageSize"]
                and expected[name]["sha256"] == record["packageSha256"],
                "Registry/manifest package mismatch: " + name)
    require(seen == set(compiled), "Registry/Java identity set mismatch")

    compiler = (java_root / "Gt01DialCompiler.java").read_text(encoding="utf-8")
    java_pin(compiler, "PREFIX", EDITOR_PREFIX)
    for filename, (constant, sha) in EDITOR_PINS.items():
        name = EDITOR_PREFIX + filename
        require(name in expected and expected[name]["sha256"] == sha,
                "Editor asset pin mismatch: " + name)
        java_pin(compiler, constant, sha)
    return {"assets": len(expected), "totalBytes": sum(r["size"] for r in expected.values()),
            "generatedAssets": generated_assets, "activeRegistryEntries": 73,
            "editorPinnedFiles": 4, "apkSha256": digest(apk.read_bytes())}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True, help="Public package root")
    parser.add_argument("--apk", type=Path, required=True, help="Specific APK to inspect")
    args = parser.parse_args()
    try:
        result = verify(args.root.resolve(), args.apk.resolve())
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, RuntimeError) as error:
        print("FAIL: " + str(error), file=sys.stderr)
        return 1
    print("PASS " + json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
