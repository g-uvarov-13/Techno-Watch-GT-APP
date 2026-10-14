#!/usr/bin/env python3
"""Read bounded JerryScript v17 snapshot structure without executing bytecode.

Format reference: JerryScript commit 99c9a22b782fc1162157fcb3d01e70b5efa27e1d.
This inventories function blocks and their literal references; it does not infer
call semantics from nearby strings.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import struct
from pathlib import Path

from tools.inspect_dial import inspect

SOURCES = {
    "qc35": (
        "work/myhealth-gt01-corpus-20260919/sources/"
        "b4cfeed623564c44b47ca25dd2ec2ae2.dial-32b267376ff5.dial",
        "32b267376ff51e749212e0a7a6f51d259fdda3ab1ed331fa09dd862e4cfb5063",
        2,
        "2d99247ecf946df166512d57b2e0cc531603dcaa6ab9da7c99e261fcee30972b",
    ),
    "qc176": (
        "work/dial-corpus-v2/sources/com.QC.qc176.dial-dfaac700817e.dial",
        "dfaac700817e3d363c7a03e8f6b053c1e7daf8b869e2a6c800fdcd782123edad",
        51,
        "6e7c9876c693f1322d4d7b14abeec36f1e9f30b36026ee7c0c896dbdd9e0574a",
    ),
}


def need(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def u16(blob: bytes, at: int) -> int:
    need(0 <= at <= len(blob) - 2, f"u16 out of bounds at {at}")
    return struct.unpack_from("<H", blob, at)[0]


def u32(blob: bytes, at: int) -> int:
    need(0 <= at <= len(blob) - 4, f"u32 out of bounds at {at}")
    return struct.unpack_from("<I", blob, at)[0]


def literal(blob: bytes, table: int, value: int) -> dict:
    if value & 7 != 7:
        return {"kind": "other", "raw": value}
    offset = value >> 4
    address = table + offset
    need(table <= address < len(blob), f"literal offset out of bounds: {offset}")
    if value & 8:
        return {"kind": "number_snapshot", "raw": value, "offset": address,
                "status": "number width not established"}
    length = u16(blob, address)
    need(length <= 65535 and address + 2 + length <= len(blob),
         f"literal string out of bounds: {address}")
    data = blob[address + 2:address + 2 + length]
    return {"kind": "string", "raw": value, "offset": address,
            "value": data.decode("utf-8", "replace")}


def disassemble(blob: bytes, block: dict, opcodes: dict) -> list[dict]:
    start = block["bytecode_offset"]
    end = block["offset"] + block["size"]
    if block["status_flags"] & 16 and not block["status_flags"] & 8:
        end -= block["argument_end"] * 4
    need(start <= end, "bytecode start beyond block")
    refs = {item["index"]: item for item in block["literal_refs"]}
    limit, delta = (128, 0x8000) if block["status_flags"] & 2 else (255, 0xFE01)
    pos = start
    instructions = []
    while pos < end:
        need(len(instructions) < 4096, "instruction bound exceeded")
        offset = pos
        op = blob[pos]
        pos += 1
        table = opcodes["common"]
        if op == 0:
            need(pos < end, "truncated extended opcode")
            op = blob[pos]
            pos += 1
            table = opcodes["extended"]
        need(op < len(table), f"unknown opcode at {offset}")
        meta = table[op]
        flags = meta["flags"]
        literal_count = 3 if meta["name"] == "CBC_PUSH_THREE_LITERALS" else (
            2 if flags & 2 else 1 if flags & 1 else 0)
        operands = []
        for _ in range(literal_count):
            need(pos < end, f"truncated literal at {offset}")
            index = blob[pos]
            pos += 1
            if index >= limit:
                need(pos < end, f"truncated extended literal at {offset}")
                index = ((index << 8) | blob[pos]) - delta
                pos += 1
            need(0 <= index < block["literal_end"], f"literal index out of range at {offset}")
            operands.append({"type": "literal", "index": index,
                             "ref": refs.get(index, {"kind": "register"})})
        if flags & 4:
            need(pos < end, f"truncated byte argument at {offset}")
            operands.append({"type": "byte", "value": blob[pos]})
            pos += 1
        if flags & 8:
            length = 3 if meta["name"].endswith("_3") else 2 if meta["name"].endswith("_2") else 1
            need(pos + length <= end, f"truncated branch at {offset}")
            value = int.from_bytes(blob[pos:pos + length], "big")
            pos += length
            direction = 1 if flags & 16 else -1
            operands.append({"type": "branch", "distance": value,
                             "target": offset + direction * value})
        instructions.append({"offset": offset, "end": pos, "opcode": meta["name"],
                             "operands": operands})
        if (meta["name"].startswith(("CBC_RETURN", "CBC_END", "CBC_THROW"))
                and end - pos <= 7 and not any(blob[pos:end])):
            break
    need(end - pos <= 7 and not any(blob[pos:end]), "nonzero bytes after final instruction")
    boundaries = {item["offset"] for item in instructions} | {pos}
    for instruction in instructions:
        for operand in instruction["operands"]:
            if operand["type"] == "branch":
                need(operand["target"] in boundaries,
                     f"branch into operand at {instruction['offset']} "
                     f"{instruction['opcode']} target={operand['target']} "
                     f"near={sorted(boundaries, key=lambda value: abs(value - operand['target']))[:4]}")
    return instructions


def parse(blob: bytes, opcodes: dict) -> dict:
    need(32 <= len(blob) <= 32 * 1024 * 1024, "snapshot size outside bound")
    magic, version, flags, table, count, root = struct.unpack_from("<6I", blob)
    need((magic, version, flags, count, root) == (0x5952524A, 17, 0x100, 1, 24),
         "unsupported snapshot header")
    need(root < table <= len(blob) and table % 8 == 0, "invalid block/literal boundary")
    blocks = []
    pos = root
    while pos < table:
        need(len(blocks) < 256, "too many compiled blocks")
        size_units, refs, status = struct.unpack_from("<HHH", blob, pos)
        size = size_units * 8
        need(size >= 16 and pos + size <= table and refs > 0, "invalid compiled block")
        need(status & 1 and not status & ~0x3FF, "unsupported block flags")
        if status & 4:
            fields = struct.unpack_from("<6H", blob, pos + 6)
            header_size = 20
        else:
            fields = tuple(blob[pos + 6:pos + 12])
            header_size = 12
        stack, args, register_end, ident_end, const_end, literal_end = fields
        need(args <= register_end <= ident_end <= const_end <= literal_end,
             "invalid literal group boundaries")
        count_literals = literal_end - register_end
        need(header_size + 4 * count_literals <= size, "literal array crosses block")
        refs_out = []
        for index in range(register_end, literal_end):
            value = u32(blob, pos + header_size + (index - register_end) * 4)
            if index >= const_end:
                child = pos + value
                need(value == 0 or root <= child < table, "child block outside snapshot")
                item = {"kind": "function_reference", "relative_offset": value,
                        "target_offset": child}
            else:
                item = literal(blob, table, value)
            refs_out.append({"index": index, **item})
        blocks.append({"offset": pos, "size": size, "status_flags": status,
                       "stack_limit": stack, "argument_end": args,
                       "register_end": register_end, "ident_end": ident_end,
                       "const_literal_end": const_end, "literal_end": literal_end,
                       "bytecode_offset": pos + header_size + 4 * count_literals,
                       "literal_refs": refs_out})
        pos += size
    need(pos == table, "compiled blocks do not end at literal table")
    starts = {block["offset"] for block in blocks}
    for block in blocks:
        for item in block["literal_refs"]:
            if item["kind"] == "function_reference":
                need(item["target_offset"] in starts, "child reference is not a block start")
    for block in blocks:
        block["instructions"] = disassemble(blob, block, opcodes)
    return {"format": "JerryScript snapshot v17", "snapshot_sha256": hashlib.sha256(blob).hexdigest(),
            "size": len(blob), "literal_table_offset": table,
            "block_count": len(blocks), "blocks": blocks,
            "semantics": "unverified; opcodes and call arguments not decoded"}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    need(not args.output.exists(), "refusing to replace an existing report")
    root = Path(__file__).resolve().parents[1]
    opcodes = json.loads((root / "work/shared-dial-base/jerryscript-v17-opcodes.json")
                         .read_text(encoding="utf-8"))
    need(opcodes["source_commit"] == "99c9a22b782fc1162157fcb3d01e70b5efa27e1d",
         "opcode provenance changed")
    report = {"format": 1, "upstream_commit": "99c9a22b782fc1162157fcb3d01e70b5efa27e1d",
              "execution": "not_performed", "sources": {}}
    for name, (path, package_sha, index, blob_sha) in SOURCES.items():
        source = root / path
        package = source.read_bytes()
        need(hashlib.sha256(package).hexdigest() == package_sha, f"{name} package hash changed")
        resources = inspect(source, package)["default_resources"]
        item = next((entry for entry in resources if entry["index"] == index), None)
        need(item is not None and item["type"] == "JRRY script", f"{name} JRRY missing")
        blob = package[item["offset"]:item["offset"] + item["length"]]
        need(hashlib.sha256(blob).hexdigest() == blob_sha, f"{name} JRRY hash changed")
        report["sources"][name] = parse(blob, opcodes)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({name: {"blocks": value["block_count"], "size": value["size"]}
                      for name, value in report["sources"].items()}))


if __name__ == "__main__":
    main()
