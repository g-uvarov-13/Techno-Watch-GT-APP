#!/usr/bin/env python3
"""Build an offline-only qc176 palette derivative from explicit local inputs."""
from __future__ import annotations

import argparse
import json
import struct
import zlib
from hashlib import sha256
from pathlib import Path

try:
    from tools.inspect_dial import OUTER_ENTRY, RESOURCE_ENTRY, bkdr, inspect
    from tools.patch_face_layout import apply_plan, load_plan
    from tools.pgf_codec import decode, encode_indexed_bgra, lz4_literal_only
except ModuleNotFoundError:
    from inspect_dial import OUTER_ENTRY, RESOURCE_ENTRY, bkdr, inspect
    from patch_face_layout import apply_plan, load_plan
    from pgf_codec import decode, encode_indexed_bgra, lz4_literal_only


DONOR_SHA256 = "DFAAC700817E3D363C7A03E8F6B053C1E7DAF8B869E2A6C800FDCD782123EDAD"
MANIFEST_ID = "com.gtdial.dl3000"
MANIFEST_NAME = "dl3000"
MANIFEST_ALIAS = "2309202601"
BACKGROUND_INDEX = 38
BACKGROUND_HASH = "0x4f073730"
PLB_INDEX = 54
PLB_HASH = "0x71b4a11f"
PLB_SHA256 = "fea5e448204f445616601fd97f3258179885eb0c1c6356acc97665413000da57"
PLB_LENGTH = 2913
EXTERNAL_PLB = Path("work/dial-corpus-v2/blobs") / f"{PLB_SHA256}.bin"
TIME_GLYPH_PATHS = tuple(
    f"images/{family}_{digit}.png"
    for family in ("HOUR1", "HOUR2", "MINUTE1", "MINUTE2")
    for digit in range(10)
)
TIME_GLYPH_TARGET = (32, 60, 32)
SEPARATOR_PATH = "images/symbol.png"
SEPARATOR_TARGET = (8, 20, 16)


def _png_bgra(path: Path) -> tuple[int, int, bytes]:
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("background must be a PNG")
    pos = 8
    compressed = b""
    palette = alpha = None
    width = height = color = depth = None
    while pos < len(data):
        if pos + 12 > len(data):
            raise ValueError("truncated PNG")
        length = struct.unpack_from(">I", data, pos)[0]
        kind = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        if len(body) != length or pos + 12 + length > len(data):
            raise ValueError("truncated PNG chunk")
        pos += 12 + length
        if kind == b"IHDR":
            width, height, depth, color, compression, filtering, interlace = struct.unpack(">IIBBBBB", body)
            if depth != 8 or color not in (2, 3, 6) or compression or filtering or interlace:
                raise ValueError("background PNG must be non-interlaced 8-bit RGB, RGBA or indexed")
        elif kind == b"PLTE":
            palette = body
        elif kind == b"tRNS":
            alpha = body
        elif kind == b"IDAT":
            compressed += body
        elif kind == b"IEND":
            break
    if width is None or height is None or color is None:
        raise ValueError("PNG is missing IHDR")
    if color == 3 and (palette is None or len(palette) % 3):
        raise ValueError("indexed PNG is missing a valid palette")
    raw = zlib.decompress(compressed)
    bpp = 1 if color == 3 else 3 if color == 2 else 4
    row_size = width * bpp
    if len(raw) != height * (row_size + 1):
        raise ValueError("unsupported PNG scanline layout")
    rows, previous, pos = [], bytearray(row_size), 0
    for _ in range(height):
        filter_type, row = raw[pos], bytearray(raw[pos + 1:pos + 1 + row_size])
        pos += row_size + 1
        if filter_type not in range(5):
            raise ValueError("unsupported PNG filter")
        for index in range(row_size):
            left = row[index - bpp] if index >= bpp else 0
            up = previous[index]
            upper_left = previous[index - bpp] if index >= bpp else 0
            if filter_type == 1:
                row[index] = (row[index] + left) & 255
            elif filter_type == 2:
                row[index] = (row[index] + up) & 255
            elif filter_type == 3:
                row[index] = (row[index] + (left + up) // 2) & 255
            elif filter_type == 4:
                predictor = left + up - upper_left
                distances = abs(predictor - left), abs(predictor - up), abs(predictor - upper_left)
                row[index] = (row[index] + (left if distances[0] <= distances[1] and distances[0] <= distances[2] else up if distances[1] <= distances[2] else upper_left)) & 255
        rows.append(row)
        previous = row
    if color == 6:
        return width, height, b"".join(bytes((row[i + 2], row[i + 1], row[i], row[i + 3])) for row in rows for i in range(0, row_size, 4))
    if color == 2:
        return width, height, b"".join(bytes((row[i + 2], row[i + 1], row[i], 255)) for row in rows for i in range(0, row_size, 3))
    result = bytearray()
    for row in rows:
        for index in row:
            if index * 3 + 3 > len(palette):
                raise ValueError("indexed PNG references palette outside PLTE")
            result.extend((palette[index * 3 + 2], palette[index * 3 + 1], palette[index * 3], alpha[index] if alpha and index < len(alpha) else 255))
    return width, height, bytes(result)


def _accent(value: str) -> tuple[int, int, int]:
    if len(value) != 7 or not value.startswith("#"):
        raise ValueError("accent must be #RRGGBB")
    try:
        return tuple(int(value[index:index + 2], 16) for index in (1, 3, 5))
    except ValueError as exc:
        raise ValueError("accent must be #RRGGBB") from exc


def _indexed_blob_with_source_header(source: bytes, width: int, height: int, bgra: bytes, meta: dict) -> bytes:
    if meta["compression"] not in (0, 1) or meta["format_raw"] not in (4, 6):
        raise ValueError("unsupported background PGF")
    encoded = encode_indexed_bgra(width, height, bgra, meta["format_raw"], meta["stride"])
    generated_meta, generated_plane, generated_palette = decode(encoded)
    payload = generated_plane + generated_palette
    header = bytearray(source[:46] if meta["compression"] == 1 else source[:42])
    struct.pack_into("<H", header, 36, generated_meta["palette_count"])
    struct.pack_into("<I", header, 38, generated_meta["decoded_size"])
    if meta["compression"] == 0:
        return bytes(header) + payload
    packed = lz4_literal_only(payload)
    struct.pack_into("<I", header, 42, len(packed))
    return bytes(header) + packed


def _recolor_indexed_blob(source: bytes, accent: tuple[int, int, int]) -> bytes:
    meta, plane, palette = decode(source)
    if meta["format_raw"] not in (4, 6) or meta["compression"] not in (0, 1):
        raise ValueError("unsupported PGF for palette recolor")
    recolored = bytearray(palette)
    for offset in range(0, len(recolored), 4):
        blue, green, red, alpha = recolored[offset:offset + 4]
        value = max(blue, green, red)
        if value > alpha:
            raise ValueError("palette entry violates v <= alpha")
        recolored[offset:offset + 4] = bytes((accent[2] * value // 255, accent[1] * value // 255, accent[0] * value // 255, alpha))
    payload = plane + bytes(recolored)
    if meta["compression"] == 0:
        return source[:42] + payload
    packed = lz4_literal_only(payload)
    header = bytearray(source[:46])
    struct.pack_into("<I", header, 42, len(packed))
    return bytes(header) + packed


def _resize_indexed_plane(source: bytes, target: tuple[int, int, int]) -> bytes:
    """Nearest-neighbour resize that preserves the donor PGF palette exactly."""
    meta, plane, palette = decode(source)
    if meta["format_raw"] not in (4, 6) or meta["compression"] not in (0, 1):
        raise ValueError("unsupported indexed PGF for glyph resize")
    width, height, stride = target
    if stride < width:
        raise ValueError("glyph target stride is smaller than width")
    resized = bytearray(stride * height)
    for y in range(height):
        source_y = y * meta["height"] // height
        for x in range(width):
            source_x = x * meta["width"] // width
            resized[y * stride + x] = plane[source_y * meta["stride"] + source_x]
    payload = bytes(resized) + palette
    header = bytearray(source[:46] if meta["compression"] else source[:42])
    struct.pack_into("<HHH", header, 28, width, height, stride)
    struct.pack_into("<I", header, 38, len(payload))
    if meta["compression"] == 0:
        return bytes(header) + payload
    packed = lz4_literal_only(payload)
    struct.pack_into("<I", header, 42, len(packed))
    return bytes(header) + packed


def _time_resource_offsets(report: dict) -> dict[int, tuple[int, int, int]]:
    """Return shared resource offsets after proving every locale has the donor mapping."""
    targets = {
        f"0x{bkdr(path):08x}": (path, TIME_GLYPH_TARGET)
        for path in TIME_GLYPH_PATHS
    }
    targets[f"0x{bkdr(SEPARATOR_PATH):08x}"] = (SEPARATOR_PATH, SEPARATOR_TARGET)
    default = report["default_resources"]
    offsets: dict[int, tuple[int, int, int]] = {}
    for item in default:
        target = targets.get(item["name_hash"])
        if target is None:
            continue
        if item["type"] != "PGF image":
            raise ValueError(f"expected PGF image for {target[0]}")
        offsets[item["offset"]] = target[1]
    if len(offsets) != len(targets):
        raise ValueError("missing QC176 time glyph or separator resource")
    for locale in report["locales"]:
        for default_item, locale_item in zip(default, locale["resources"]):
            if default_item["name_hash"] in targets and (
                locale_item["name_hash"], locale_item["offset"], locale_item["length"], locale_item["type"]
            ) != (
                default_item["name_hash"], default_item["offset"], default_item["length"], default_item["type"]
            ):
                raise ValueError("QC176 locale glyph mapping differs from the pinned default locale")
    return offsets


def _resource_map(report: dict, raw: bytes) -> tuple[dict[int, tuple[int, bytes]], list[tuple[int, int]]]:
    resources: dict[int, tuple[int, bytes]] = {}
    tables = []
    for locale in report["locales"]:
        tables.append((locale["offset"], locale["length"]))
        for resource in locale["resources"]:
            offset, length = resource["offset"], resource["length"]
            previous = resources.setdefault(offset, (length, raw[offset:offset + length]))
            if previous[0] != length or previous[1] != raw[offset:offset + length]:
                raise ValueError("unsupported overlapping resource extents")
    return resources, tables


def _paired_plb(plan_path: Path | None, result_path: Path | None, original: bytes) -> tuple[bytes | None, dict | None]:
    if (plan_path is None) != (result_path is None):
        raise ValueError("--plb-plan and --plb-result must be provided together")
    if plan_path is None:
        return None, None
    plan = load_plan(plan_path)
    if plan.get("donor_plb_sha256") != PLB_SHA256:
        raise ValueError("unsupported PLB plan donor SHA-256")
    external = EXTERNAL_PLB.read_bytes()
    if sha256(external).hexdigest() != PLB_SHA256 or external != original:
        raise ValueError("extracted PLB does not match external pinned source")
    candidate = result_path.read_bytes()
    if len(candidate) != PLB_LENGTH or sha256(candidate).hexdigest() == PLB_SHA256:
        raise ValueError("invalid PLB result")
    if apply_plan(EXTERNAL_PLB, plan) != candidate:
        raise ValueError("PLB result does not exactly apply the pinned plan")
    return candidate, plan


def build_package(donor: Path, background: Path, accent_text: str, *, plb_plan: Path | None = None, plb_result: Path | None = None, identity: tuple[str,str,str] | None = None) -> bytes:
    raw = donor.read_bytes()
    if sha256(raw).hexdigest().upper() != DONOR_SHA256:
        raise ValueError("unsupported donor SHA-256")
    accent = _accent(accent_text)
    report = inspect(donor, raw)
    if len(report["locales"]) != 28 or any(locale["resource_count"] != 133 for locale in report["locales"]):
        raise ValueError("unsupported qc176 locale/resource layout")
    resources, tables = _resource_map(report, raw)
    default = report["default_resources"]
    time_glyph_offsets = _time_resource_offsets(report)
    backgrounds = [item for item in default if item["index"] == BACKGROUND_INDEX and item["name_hash"] == BACKGROUND_HASH and item["type"] == "PGF image"]
    if len(backgrounds) != 1:
        raise ValueError("expected exact qc176 background index/hash")
    background_item = backgrounds[0]
    plbs = [item for item in default if item["index"] == PLB_INDEX and item["name_hash"] == PLB_HASH and item["type"] == "PLB layout"]
    if len(plbs) != 1 or plbs[0]["length"] != PLB_LENGTH or plbs[0]["sha256"] != PLB_SHA256:
        raise ValueError("expected exact qc176 PLB index/hash/extent")
    plb_item = plbs[0]
    plb_candidate, _ = _paired_plb(plb_plan, plb_result, resources[plb_item["offset"]][1])
    background_meta, _, _ = decode(resources[background_item["offset"]][1])
    if (background_meta["width"], background_meta["height"], background_meta["format_raw"], background_meta["stride"]) != (466, 466, 6, 480):
        raise ValueError("unsupported qc176 background PGF metadata")
    manifest_items = [item for item in default if item["type"] == "JSON" and b'"id"' in resources[item["offset"]][1] and b'"alias"' in resources[item["offset"]][1]]
    if len(manifest_items) != 1:
        raise ValueError("expected exactly one manifest resource")
    manifest_item = manifest_items[0]
    width, height, bgra = _png_bgra(background)
    if (width, height) != (466, 466):
        raise ValueError("background must be 466x466")
    if any(bgra[index] != 255 for index in range(3, len(bgra), 4)):
        raise ValueError("background must be opaque")
    replacements = {offset: blob for offset, (_, blob) in resources.items()}
    old_background = replacements[background_item["offset"]]
    replacements[background_item["offset"]] = _indexed_blob_with_source_header(old_background, width, height, bgra, background_meta)
    if plb_candidate is not None:
        replacements[plb_item["offset"]] = plb_candidate
    for offset, (_, blob) in resources.items():
        if not blob.startswith(b"PGF\0") or offset == background_item["offset"]:
            continue
        meta, _, _ = decode(blob)
        if meta["format_raw"] not in (4, 6):
            raise ValueError("unsupported non-indexed PGF in donor")
        replacements[offset] = _recolor_indexed_blob(blob, accent)
    for offset, target in time_glyph_offsets.items():
        replacements[offset] = _resize_indexed_plane(replacements[offset], target)
    manifest_offset = manifest_item["offset"]
    old_manifest = json.loads(replacements[manifest_offset].decode("utf-8"))
    new_manifest = dict(old_manifest)
    new_manifest.update(dict(zip(("id","name","alias"), identity or (MANIFEST_ID, MANIFEST_NAME, MANIFEST_ALIAS))))
    encoded_manifest = json.dumps(new_manifest, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    if len(encoded_manifest) > resources[manifest_offset][0]:
        raise ValueError("manifest identity exceeds donor manifest extent")
    replacements[manifest_offset] = encoded_manifest + b" " * (resources[manifest_offset][0] - len(encoded_manifest))
    outer_size = 8 + len(report["locales"]) * OUTER_ENTRY.size
    occupied = sorted([(offset, offset + length, "resource", offset) for offset, (length, _) in resources.items()] + [(offset, offset + length, "table", offset) for offset, length in tables])
    cursor = outer_size
    chunks = []
    locations = {}
    for start, end, kind, key in occupied:
        if start < cursor:
            raise ValueError("unsupported overlapping container extents")
        chunks.append(("opaque", raw[cursor:start]))
        chunks.append((kind, key))
        cursor = end
    chunks.append(("opaque", raw[cursor:]))
    output_cursor = outer_size
    for kind, value in chunks:
        if kind == "opaque":
            output_cursor += len(value)
        elif kind == "resource":
            locations[value] = output_cursor
            output_cursor += len(replacements[value])
        else:
            locations[("table", value)] = output_cursor
            output_cursor += next(length for offset, length in tables if offset == value)
    output = bytearray(b"\0" * outer_size)
    for kind, value in chunks:
        if kind == "opaque":
            output.extend(value)
        elif kind == "resource":
            output.extend(replacements[value])
        else:
            old_table = value
            count = struct.unpack_from("<I", raw, old_table + 4)[0]
            table = bytearray(raw[old_table:old_table + 8])
            for index in range(count):
                name_hash, old_offset, old_length, fourth = RESOURCE_ENTRY.unpack_from(raw, old_table + 8 + index * RESOURCE_ENTRY.size)
                table.extend(RESOURCE_ENTRY.pack(name_hash, locations[old_offset], len(replacements[old_offset]), fourth))
            output.extend(table)
    output[:8] = struct.pack("<4sI", b"CR\1\0", len(report["locales"]))
    for index, locale in enumerate(report["locales"]):
        name_hash, _, length, raw_name = OUTER_ENTRY.unpack_from(raw, 8 + index * OUTER_ENTRY.size)
        OUTER_ENTRY.pack_into(output, 8 + index * OUTER_ENTRY.size, name_hash, locations[("table", locale["offset"])], length, raw_name)
    return bytes(output)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("donor", type=Path)
    parser.add_argument("background", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--accent", required=True, help="palette tint as #RRGGBB")
    parser.add_argument("--plb-plan", type=Path, help="pinned rectangle-only PLB plan")
    parser.add_argument("--plb-result", type=Path, help="prebuilt PLB result matching --plb-plan")
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError(f"refusing to overwrite: {args.output}")
    result = build_package(args.donor, args.background, args.accent, plb_plan=args.plb_plan, plb_result=args.plb_result)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(result)
    print(json.dumps({"output": str(args.output), "sha256": sha256(result).hexdigest().upper(), "bytes": len(result)}))


if __name__ == "__main__":
    main()
