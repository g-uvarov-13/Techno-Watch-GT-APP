#!/usr/bin/env python3
"""Build a bounded, offline evidence index for CR .dial packages."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import zipfile
from pathlib import Path
from typing import Iterable

from inspect_dial import MAX_PACKAGE_SIZE, bkdr, inspect

MAX_EXTRACTED = 128 * 1024 * 1024
PRINTABLE = re.compile(rb"[ -~]{4,}")
TERMS = re.compile(r"(?i)(?:step|steps|calor|kcal|heart|pulse|bpm|battery|charge|пульс|шаг|калор|заряд)")


def safe_name(value: str) -> str:
    value = re.sub(r"[^A-Za-z0-9_.-]+", "_", value).strip("._")
    return value[:120] or "unnamed"


def candidates(path: str) -> list[dict[str, object]]:
    result = []
    names = set()
    normalized = path.replace("\\", "/").strip("/")
    if normalized:
        names.add(normalized)
    for embedded_path in re.findall(
        r"(?:[A-Za-z0-9_.-]+/)+[A-Za-z0-9_.-]+", normalized
    ):
        names.add(embedded_path.strip("/"))
    for value in tuple(names):
        leaf = value.rsplit("/", 1)[-1]
        names.add(leaf)
        names.add(Path(leaf).stem)
    for name in sorted(name for name in names if len(name) > 1):
        result.append({"name": name, "hash": f"0x{bkdr(name):08x}"})
    return result


def source_files(inputs: Iterable[Path]) -> Iterable[tuple[str, bytes]]:
    for path in inputs:
        if path.is_dir():
            yield from source_files(sorted(path.rglob("*")))
        elif path.suffix.lower() == ".dial":
            yield str(path), path.read_bytes()
        elif path.suffix.lower() in {".zip", ".xapk", ".apk"}:
            with zipfile.ZipFile(path) as archive:
                for info in archive.infolist():
                    if not info.is_dir() and info.filename.lower().endswith(".dial"):
                        if info.file_size > MAX_PACKAGE_SIZE:
                            raise ValueError(
                                f"archive member exceeds {MAX_PACKAGE_SIZE} byte safety limit: "
                                f"{path}!{info.filename}"
                            )
                        yield f"{path}!{info.filename}", archive.read(info)


def printable_evidence(blob: bytes) -> list[dict[str, object]]:
    found = []
    for match in PRINTABLE.finditer(blob):
        value = match.group().decode("ascii", "replace")
        categories = []
        for category, pattern in (("steps", r"step|шаг"), ("calories", r"calor|kcal|калор"),
                                  ("heart", r"heart|pulse|bpm|пульс"),
                                  ("battery", r"battery|charge|заряд")):
            if re.search(pattern, value, re.IGNORECASE):
                categories.append(category)
        if categories:
            found.append({"offset": match.start(), "value": value, "categories": categories})
    return found


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("inputs", nargs="+", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    blobs_dir = args.output / "blobs"
    blobs_dir.mkdir(exist_ok=True)
    report: dict[str, object] = {"format": 1, "state": "DISCOVERED", "sources": [],
                                 "blobs": {}, "evidence": [], "candidates": []}
    total = 0
    seen_sources = 0
    for source_name, data in source_files(args.inputs):
        seen_sources += 1
        source_hash = hashlib.sha256(data).hexdigest()
        source: dict[str, object] = {"path": source_name, "sha256": source_hash,
                                     "size": len(data), "state": "DISCOVERED"}
        try:
            inspected = inspect(Path(source_name), data)
            source["state"] = "VALIDATED"
            source["manifest"] = inspected.get("manifest")
            source["locales"] = inspected["locales"]
            source_copy = args.output / "sources" / f"{safe_name(Path(source_name).name)}-{source_hash[:12]}.dial"
            source_copy.parent.mkdir(exist_ok=True)
            source_copy.write_bytes(data)
            source["preserved_copy"] = str(source_copy)
            resource_hashes = set()
            embedded_candidates = []
            candidate_blobs_seen = set()
            for locale in inspected["locales"]:
                for resource in locale["resources"]:
                    blob_offset = int(resource["offset"])
                    blob_length = int(resource["length"])
                    blob = data[blob_offset:blob_offset + blob_length]
                    digest = hashlib.sha256(blob).hexdigest()
                    if digest not in candidate_blobs_seen:
                        candidate_blobs_seen.add(digest)
                        for printable in PRINTABLE.findall(blob):
                            embedded_candidates.extend(
                                candidates(printable.decode("ascii", "replace"))
                            )
                    resource_hashes.add(resource["name_hash"])
                    destination = blobs_dir / f"{digest}.bin"
                    if digest not in report["blobs"]:
                        if total + len(blob) > MAX_EXTRACTED:
                            raise ValueError("extraction budget exceeded")
                        destination.write_bytes(blob)
                        total += len(blob)
                        report["blobs"][digest] = {"path": str(destination), "length": len(blob),
                                                   "type": resource["type"]}
                    resource["blob_sha256"] = digest
                    resource["source_path"] = source_name
                    strings = printable_evidence(blob)
                    resource["evidence"] = "string_reference" if strings else None
                    if strings:
                        report["evidence"].append({"source": source_name, "locale": locale["name"],
                            "resource_index": resource["index"], "blob_sha256": digest,
                            "offset": blob_offset, "strings": strings, "label": "string_reference"})
            source["state"] = "EXTRACTED"
            source["state"] = "INDEXED"
            grouped_candidates: dict[tuple[str, str, tuple[str, ...]], dict[str, object]] = {}
            for candidate in candidates(source_name) + embedded_candidates:
                matches = [h for h in resource_hashes if h == candidate["hash"]]
                candidate["resource_refs"] = matches
                if matches:
                    candidate["evidence"] = "hash_matched_candidate"
                else:
                    candidate.pop("evidence", None)
                key = (candidate["name"], candidate["hash"], tuple(matches))
                if key in grouped_candidates:
                    grouped_candidates[key]["occurrences"] += 1
                else:
                    candidate["occurrences"] = 1
                    grouped_candidates[key] = candidate
            report["candidates"].extend(grouped_candidates.values())
        except Exception as exc:
            source["state"] = "REJECTED"
            source["error"] = str(exc)
        report["sources"].append(source)
    if not seen_sources:
        raise SystemExit("no .dial sources found")
    report["state"] = "INDEXED" if all(s["state"] == "INDEXED" for s in report["sources"]) else "REJECTED"
    (args.output / "index.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"state": report["state"], "sources": len(report["sources"]),
                      "blobs": len(report["blobs"]), "extracted_bytes": total}, ensure_ascii=False))


if __name__ == "__main__":
    main()
