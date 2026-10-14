#!/usr/bin/env python3
"""Read-only inspector for the PersimWear/GT01 CR .dial container."""

from __future__ import annotations

import argparse
import json
import re
import struct
from collections import Counter
from hashlib import sha256
from pathlib import Path


OUTER_ENTRY = struct.Struct("<III8s")
RESOURCE_ENTRY = struct.Struct("<IIII")
MAX_PACKAGE_SIZE = 32 * 1024 * 1024
MAX_LOCALES = 256
MAX_RESOURCES_PER_LOCALE = 16384


def u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def bkdr(text: str) -> int:
    value = 0
    for byte in text.encode("utf-8"):
        value = (value * 131 + byte) & 0xFFFFFFFF
    return value


def require_range(data: bytes, offset: int, length: int, label: str) -> None:
    if offset < 0 or length < 0 or offset + length > len(data):
        raise ValueError(
            f"{label} is outside the package: offset={offset}, length={length}, "
            f"package={len(data)}"
        )


def reject_partial_overlaps(ranges: list[tuple[int, int, str]]) -> None:
    """Reject intersecting ranges unless they are byte-for-byte identical."""
    ordered = sorted(ranges, key=lambda item: (item[0], item[1]))
    for previous, current in zip(ordered, ordered[1:]):
        p_start, p_end, p_label = previous
        c_start, c_end, c_label = current
        if c_start < p_end and (c_start, c_end) != (p_start, p_end):
            raise ValueError(f"partial overlap: {p_label} and {c_label}")


def classify(blob: bytes) -> str:
    if blob.startswith(b"PGF\0"):
        return "PGF image"
    if blob.startswith(b"JRRY"):
        return "JRRY script"
    if blob.startswith(b"PLB\0"):
        return "PLB layout"
    if blob.startswith(b"<?xml"):
        return "XML"
    if blob.lstrip().startswith(b"{"):
        return "JSON"
    return "other"


def extract_manifest(data: bytes) -> dict[str, object] | None:
    match = re.search(rb'\{\s*"id"\s*:\s*"[^"]+".*?\}\s*\x00', data, re.DOTALL)
    if not match:
        return None
    return json.loads(match.group(0).rstrip(b"\x00\r\n").decode("utf-8"))


def inspect(path: Path, data: bytes | None = None) -> dict[str, object]:
    data = path.read_bytes() if data is None else data
    if len(data) > MAX_PACKAGE_SIZE:
        raise ValueError(f"package exceeds {MAX_PACKAGE_SIZE} byte safety limit")
    if len(data) < 8 or data[:4] != b"CR\x01\x00":
        raise ValueError("not a supported CR v1 .dial container")

    locale_count = u32(data, 4)
    if locale_count > MAX_LOCALES:
        raise ValueError(f"locale count exceeds safety limit: {locale_count}")
    outer_size = 8 + locale_count * OUTER_ENTRY.size
    require_range(data, 0, outer_size, "outer locale table")

    locales: list[dict[str, object]] = []
    default_resources: list[dict[str, object]] = []
    locale_ranges: list[tuple[int, int, str]] = []
    resource_ranges: list[tuple[int, int, str]] = []
    for index in range(locale_count):
        name_hash, offset, length, raw_name = OUTER_ENTRY.unpack_from(
            data, 8 + index * OUTER_ENTRY.size
        )
        name = raw_name.rstrip(b"\0").decode("ascii")
        require_range(data, offset, length, f"locale {name}")
        locale_ranges.append((offset, offset + length, f"locale {name}"))
        if name_hash != bkdr(name):
            raise ValueError(f"locale hash mismatch for {name}")
        if length < 8 or data[offset : offset + 4] != b"CR\x02\x00":
            raise ValueError(f"locale {name} does not point to a CR v2 resource table")

        resource_count = u32(data, offset + 4)
        if resource_count > MAX_RESOURCES_PER_LOCALE:
            raise ValueError(f"locale {name} resource count exceeds safety limit")
        expected_length = 8 + resource_count * RESOURCE_ENTRY.size
        if expected_length != length:
            raise ValueError(
                f"locale {name} table length mismatch: {length} != {expected_length}"
            )
        locale_report: dict[str, object] = {
                "name": name,
                "hash": f"0x{name_hash:08x}",
            "offset": offset,
            "length": length,
            "resource_count": resource_count,
            "resources": [],
        }
        for resource_index in range(resource_count):
            resource_name_hash, blob_offset, blob_length, source_size = RESOURCE_ENTRY.unpack_from(
                data, offset + 8 + resource_index * RESOURCE_ENTRY.size
            )
            require_range(data, blob_offset, blob_length, f"{name} resource {resource_index}")
            resource_ranges.append((blob_offset, blob_offset + blob_length,
                                    f"{name} resource {resource_index}"))
            blob = data[blob_offset : blob_offset + blob_length]
            resource = {
                "index": resource_index,
                "name_hash": f"0x{resource_name_hash:08x}",
                "offset": blob_offset,
                "length": blob_length,
                "raw_fourth_field": source_size,
                "source_size_field": source_size,
                "sha256": sha256(blob).hexdigest(),
                "type": classify(blob),
            }
            locale_report["resources"].append(resource)
            if name == "default":
                default_resources.append(resource)
        locales.append(locale_report)

    reject_partial_overlaps(locale_ranges + resource_ranges)

    counts = Counter(item["type"] for item in default_resources)
    manifest = None
    for item in default_resources:
        if item["type"] != "JSON":
            continue
        blob = data[item["offset"]:item["offset"] + item["length"]]
        try:
            candidate = json.loads(blob.rstrip(b"\x00\r\n ").decode("utf-8"))
            if isinstance(candidate, dict) and "id" in candidate and "alias" in candidate:
                manifest = candidate
                break
        except (UnicodeDecodeError, json.JSONDecodeError):
            pass
    return {
        "path": str(path),
        "size": len(data),
        "format": "CR v1 with CR v2 locale resource tables",
        "hash_algorithm": "BKDR-131 (32-bit)",
        "manifest": manifest if manifest is not None else extract_manifest(data),
        "locales": locales,
        "default_resource_types": dict(sorted(counts.items())),
        "default_resources": default_resources,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("dial", type=Path)
    parser.add_argument("--json", action="store_true", help="emit complete JSON")
    args = parser.parse_args()
    report = inspect(args.dial)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return
    print(f"{report['format']}; {report['size']} bytes")
    print(f"hash: {report['hash_algorithm']}")
    print(f"manifest: {json.dumps(report['manifest'], ensure_ascii=False)}")
    print(f"locales: {len(report['locales'])}")
    print(f"default resources: {report['default_resource_types']}")


if __name__ == "__main__":
    main()
