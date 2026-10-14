#!/usr/bin/env python3
"""Validate an offline qc176 palette derivative against its exact donor."""
from __future__ import annotations

import argparse
import hashlib
import json
import struct
from pathlib import Path

try:
    from tools.build_qc176_palette_variant import BACKGROUND_HASH, BACKGROUND_INDEX, DONOR_SHA256, MANIFEST_ALIAS, MANIFEST_ID, MANIFEST_NAME, PLB_HASH, PLB_INDEX, PLB_LENGTH, PLB_SHA256, _paired_plb, _time_resource_offsets
    from tools.inspect_dial import OUTER_ENTRY, RESOURCE_ENTRY, inspect, reject_partial_overlaps
    from tools.pgf_codec import decode
except ModuleNotFoundError:
    from build_qc176_palette_variant import BACKGROUND_HASH, BACKGROUND_INDEX, DONOR_SHA256, MANIFEST_ALIAS, MANIFEST_ID, MANIFEST_NAME, PLB_HASH, PLB_INDEX, PLB_LENGTH, PLB_SHA256, _paired_plb, _time_resource_offsets
    from inspect_dial import OUTER_ENTRY, RESOURCE_ENTRY, inspect, reject_partial_overlaps
    from pgf_codec import decode


def _entries(data: bytes):
    if data[:4] != b"CR\1\0":
        raise ValueError("missing CR01")
    locales = struct.unpack_from("<I", data, 4)[0]
    if locales != 28:
        raise ValueError("expected 28 locales")
    outer, entries, ranges, table_ends = [], [], [], []
    for locale in range(locales):
        name_hash, offset, length, raw_name = OUTER_ENTRY.unpack_from(data, 8 + locale * OUTER_ENTRY.size)
        if offset + length > len(data) or data[offset:offset + 4] != b"CR\2\0" or length != 8 + 133 * RESOURCE_ENTRY.size:
            raise ValueError("invalid locale table")
        outer.append((name_hash, length, raw_name))
        table_ends.append(offset + length)
        ranges.append((offset, offset + length, f"locale-{locale}"))
        for index in range(133):
            resource = RESOURCE_ENTRY.unpack_from(data, offset + 8 + index * RESOURCE_ENTRY.size)
            name_hash, resource_offset, resource_length, fourth = resource
            if resource_offset + resource_length > len(data):
                raise ValueError("resource outside package")
            entries.append((locale, index, name_hash, resource_offset, resource_length, fourth))
            ranges.append((resource_offset, resource_offset + resource_length, f"resource-{locale}-{index}"))
    reject_partial_overlaps(ranges)
    if max(table_ends) != len(data):
        raise ValueError("final CR02 table is not at EOF")
    return outer, entries


def verify(donor: Path, result: Path, *, plb_plan: Path | None = None, plb_result: Path | None = None, identity: tuple[str, str, str] | None = None) -> dict:
    before, after = donor.read_bytes(), result.read_bytes()
    if hashlib.sha256(before).hexdigest().upper() != DONOR_SHA256:
        raise ValueError("unsupported donor SHA-256")
    old_report, new_report = inspect(donor, before), inspect(result, after)
    time_offsets = _time_resource_offsets(old_report)
    time_hash_targets = {
        item["name_hash"]: time_offsets[item["offset"]]
        for item in old_report["default_resources"] if item["offset"] in time_offsets
    }
    old_plbs = [item for item in old_report["default_resources"] if item["index"] == PLB_INDEX and item["name_hash"] == PLB_HASH and item["type"] == "PLB layout"]
    if len(old_plbs) != 1 or old_plbs[0]["length"] != PLB_LENGTH or old_plbs[0]["sha256"] != PLB_SHA256:
        raise ValueError("expected exact qc176 PLB index/hash/extent")
    plb_item = old_plbs[0]
    plb_candidate, plb_plan_data = _paired_plb(plb_plan, plb_result, before[plb_item["offset"]:plb_item["offset"] + plb_item["length"]])
    old_outer, old_entries = _entries(before)
    new_outer, new_entries = _entries(after)
    if old_outer != new_outer:
        raise ValueError("locale hashes, lengths, or raw names changed")
    if len(old_entries) != 28 * 133 or len(new_entries) != 28 * 133:
        raise ValueError("unexpected resource entry count")
    if new_report["manifest"] is None:
        raise ValueError("missing manifest")
    expected_manifest = dict(old_report["manifest"])
    package_id, package_name, package_alias = identity or (MANIFEST_ID, MANIFEST_NAME, MANIFEST_ALIAS)
    expected_manifest.update({"id": package_id, "name": package_name, "alias": package_alias})
    if new_report["manifest"] != expected_manifest:
        raise ValueError("manifest has changes outside approved identity")
    manifest_index = next(item["index"] for item in old_report["default_resources"] if item["type"] == "JSON" and b'"id"' in before[item["offset"]:item["offset"] + item["length"]] and b'"alias"' in before[item["offset"]:item["offset"] + item["length"]])
    changed_pgf = 0
    backgrounds = 0
    plb_references = 0
    for old, new in zip(old_entries, new_entries):
        if old[:3] != new[:3] or old[5] != new[5]:
            raise ValueError("resource hash or fourth field changed")
        old_blob = before[old[3]:old[3] + old[4]]
        new_blob = after[new[3]:new[3] + new[4]]
        if old[1] == PLB_INDEX and f"0x{old[2]:08x}" == PLB_HASH:
            plb_references += 1
            if old[4] != PLB_LENGTH or old_blob != before[plb_item["offset"]:plb_item["offset"] + plb_item["length"]]:
                raise ValueError("PLB references do not share the pinned extent")
            if plb_candidate is None:
                if new_blob != old_blob:
                    raise ValueError("PLB changed without paired plan inputs")
            elif new[4] != PLB_LENGTH or new_blob != plb_candidate:
                raise ValueError("PLB result differs from the pinned candidate")
            continue
        if old_blob.startswith(b"PGF\0"):
            old_meta, old_plane, old_palette = decode(old_blob)
            new_meta, new_plane, new_palette = decode(new_blob)
            if old_meta["width"] == old_meta["height"] == 466:
                backgrounds += 1
                if old[1] != BACKGROUND_INDEX or f"0x{old[2]:08x}" != BACKGROUND_HASH:
                    raise ValueError("unexpected 466x466 PGF")
                if (new_meta["width"], new_meta["height"], new_meta["format_raw"], new_meta["stride"], new_meta["compression"]) != (466, 466, old_meta["format_raw"], old_meta["stride"], old_meta["compression"]):
                    raise ValueError("background metadata changed")
                if old_blob[:36] != new_blob[:36]:
                    raise ValueError("background unknown header bytes changed")
            else:
                if f"0x{old[2]:08x}" in time_hash_targets:
                    width, height, stride = time_hash_targets[f"0x{old[2]:08x}"]
                    if (new_meta["width"], new_meta["height"], new_meta["stride"]) != (width, height, stride):
                        raise ValueError("time PGF native size differs from PLB target")
                    if any(old_meta[key] != new_meta[key] for key in ("format_raw", "compression", "palette_count")) or old_palette[3::4] != new_palette[3::4]:
                        raise ValueError("time PGF format or alpha palette changed")
                    for y in range(height):
                        for x in range(width):
                            source_x = x * old_meta["width"] // width
                            source_y = y * old_meta["height"] // height
                            if new_plane[y * stride + x] != old_plane[source_y * old_meta["stride"] + source_x]:
                                raise ValueError("time PGF indexed plane differs from exact downsample")
                        if any(new_plane[y * stride + width:(y + 1) * stride]):
                            raise ValueError("time PGF stride padding is not zero")
                    changed_pgf += 1
                    continue
                structural = ("width", "height", "stride", "format_raw", "compression", "palette_count", "decoded_size")
                header_size = 46 if old_meta["compression"] == 1 else 42
                fixed_header = 42 if old_meta["compression"] == 1 else header_size
                if any(old_meta[key] != new_meta[key] for key in structural) or old_blob[:fixed_header] != new_blob[:fixed_header] or old_plane != new_plane or old_palette[3::4] != new_palette[3::4]:
                    raise ValueError("non-background PGF changed outside palette RGB")
                changed_pgf += 1
        elif old[1] != manifest_index and old_blob != new_blob:
            raise ValueError("non-PGF resource changed")
    if changed_pgf == 0:
        raise ValueError("no indexed PGFs recolored")
    if backgrounds != 28:
        raise ValueError("expected one shared 466x466 background per locale")
    if plb_references != 28:
        raise ValueError("expected exactly 28 shared PLB references")
    report = {"donorSha256": DONOR_SHA256, "resultSha256": hashlib.sha256(after).hexdigest().upper(), "locales": 28, "resourcesPerLocale": 133, "backgroundIndex": BACKGROUND_INDEX, "backgroundHash": BACKGROUND_HASH, "recoloredPgfResources": changed_pgf, "plbIndex": PLB_INDEX, "plbHash": PLB_HASH, "plbReferences": plb_references, "physicalAcceptance": "UNVERIFIED"}
    if plb_candidate is not None:
        report.update({"plbSourceSha256": PLB_SHA256.upper(), "plbResultSha256": hashlib.sha256(plb_candidate).hexdigest().upper(), "plbPlanSha256": hashlib.sha256(plb_plan.read_bytes()).hexdigest().upper(), "plbNodeDiff": len(plb_plan_data["edits"])})
    return report


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("donor", type=Path)
    parser.add_argument("result", type=Path)
    parser.add_argument("report", type=Path)
    parser.add_argument("--plb-plan", type=Path, help="pinned rectangle-only PLB plan")
    parser.add_argument("--plb-result", type=Path, help="prebuilt PLB result matching --plb-plan")
    args = parser.parse_args()
    report = verify(args.donor, args.result, plb_plan=args.plb_plan, plb_result=args.plb_result)
    if args.report.exists():
        raise FileExistsError(f"refusing to overwrite: {args.report}")
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
