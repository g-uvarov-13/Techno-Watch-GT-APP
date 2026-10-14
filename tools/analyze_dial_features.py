#!/usr/bin/env python3
"""Offline evidence index for an existing GT01 .dial corpus.

The tool never executes embedded scripts and never attempts to rebuild a dial.
It records observations only; interpretations are deliberately conservative.
"""
from __future__ import annotations

import argparse, json, re, struct
from hashlib import sha256
from pathlib import Path

CONF = {"container_observed", "source_code_observed", "string_reference",
        "hash_matched_candidate", "hypothesis"}
KEYWORDS = {
    "steps": ("steps", "step", "returnstepsitem", "target.steps"),
    "calories": ("calorie", "consumption", "kcal", "returnconsumptionitem"),
    "heart_rate": ("heart", "heartrate", "bpm", "returnheartitem", "sensor_hr"),
    "battery": ("battery", "returnbatteryitem", "levelchange"),
    "distance": ("distance", "returndistanceitem"),
    "weather": ("weather", "weatherarr"),
    "time": ("time", "date", "timeformat"),
    "animation": ("animatedimage", "frame", "pause", "loop", "start"),
    "touch": ("touch", "click", "tap", "gesture"),
}

def evidence(package_sha256, package_id, locale, name_hash, blob_sha, blob_offset,
             byte_offset, length, category, value, confidence, interpretation):
    if confidence not in CONF: raise ValueError(confidence)
    return {"package_sha256": package_sha256, "package_id": package_id,
            "locale": locale, "resource_name_hash": name_hash,
            "blob_sha256": blob_sha, "blob_offset": blob_offset,
            "byte_offset": byte_offset, "length": length, "category": category,
            "observed_value": value, "confidence": confidence,
            "interpretation": interpretation}

def source_lines(blob: bytes):
    """Return complete readable source lines with exact UTF-8 byte offsets."""
    start = 3 if blob.startswith(b"\xef\xbb\xbf") else 0
    try:
        text = blob[start:].decode("utf-8")
    except UnicodeDecodeError:
        return
    pos = 0
    for line_no, line in enumerate(text.splitlines(), 1):
        raw = line.encode("utf-8")
        if len(line.strip()) >= 3:
            yield start + pos, line, len(raw), line_no
        pos += len(raw) + 1


def binary_strings(blob: bytes):
    """Return conservative printable runs from a binary resource."""
    for match in re.finditer(rb"[\x20-\x7e]{3,}", blob):
        yield match.start(), match.group().decode("ascii"), len(match.group()), None

def bkdr(text):
    value = 0
    for b in text.encode("utf-8"):
        value = (value * 131 + b) & 0xffffffff
    return f"0x{value:08x}"

def category(text):
    low = text.lower()
    return [k for k, words in KEYWORDS.items() if any(w in low for w in words)]

def pgf_fields(blob):
    if not blob.startswith(b"PGF\0"): return {}
    out = {"magic": blob[:4].decode("ascii", "replace"), "length": len(blob)}
    if len(blob) >= 0x20:
        # These are raw header fields, reported only when they are plausible.
        version, header = struct.unpack_from("<II", blob, 4)
        out.update({"version_raw": version, "header_size_raw": header})
        w, h = struct.unpack_from("<HH", blob, 0x1c)
        if 1 <= w <= 4096 and 1 <= h <= 4096:
            out.update({"width_raw": w, "height_raw": h})
    return out

def analyze(index_path: Path, output: Path):
    index = json.loads(index_path.read_text(encoding="utf-8"))
    rows, packages = [], []
    for source in sorted(index.get("sources", []), key=lambda x: x.get("sha256", "")):
        p = Path(source["path"])
        if not p.is_absolute(): p = Path.cwd() / p
        package_sha = source.get("sha256") or sha256(p.read_bytes()).hexdigest()
        manifest = source.get("manifest") or {}
        pid = manifest.get("id", "unknown")
        type_counts = {}
        locale_hashes = {}
        for loc in source.get("locales", []):
            locale_hashes[loc.get("name", "?")] = sorted({r.get("sha256") for r in loc.get("resources", [])})
            for r in loc.get("resources", []): type_counts[r.get("type", "other")] = type_counts.get(r.get("type", "other"), 0) + 1
        packages.append({"package_sha256": package_sha, "package_id": pid,
                         "size": source.get("size"), "path": str(p),
                         "manifest": manifest, "resource_type_counts": type_counts,
                         "default_resource_count": next((x.get("resource_count") for x in source.get("locales", []) if x.get("name") == "default"), 0),
                         "locale_blob_hashes": locale_hashes,
                         "locale_blob_hash_deltas": {k: v for k, v in locale_hashes.items() if k != "default" and v != locale_hashes.get("default", [])}})
        data = p.read_bytes()
        seen = set()
        for loc in source.get("locales", []):
            lname = loc.get("name", "?")
            for res in loc.get("resources", []):
                key = (lname, res.get("index"), res.get("sha256") or res.get("blob_sha256"))
                if key in seen: continue
                seen.add(key)
                off, length = int(res["offset"]), int(res["length"])
                blob = data[off:off+length]
                sha = res.get("sha256") or sha256(blob).hexdigest()
                typ = res.get("type", "other")
                rows.append(evidence(package_sha, pid, lname, res.get("name_hash"), sha,
                                     off, 0, length, "resource_type", typ,
                                     "container_observed", "Container classifier result."))
                if typ == "PGF image":
                    rows.append(evidence(package_sha, pid, lname, res.get("name_hash"), sha,
                                         off, 0, length, "pgf_header", pgf_fields(blob),
                                         "container_observed", "Raw PGF header fields; codec not inferred."))
                source_like = False
                if typ in ("other", "JSON", "XML"):
                    try:
                        decoded = blob.decode("utf-8-sig")
                        source_like = bool(re.search(r"module\.exports|\bApp\s*\(|\bfunction\s+\w+", decoded))
                    except UnicodeDecodeError:
                        pass
                text_items = source_lines(blob) if source_like else binary_strings(blob)
                for item in text_items or ():
                    bo, text, blen, line_no = item
                    cats = category(text)
                    for cat in cats:
                        conf = "source_code_observed" if source_like else "string_reference"
                        observed = {"text": text}
                        if line_no is not None:
                            observed["line_number"] = line_no
                        rows.append(evidence(package_sha, pid, lname, res.get("name_hash"), sha,
                                             off, bo, blen, cat, observed, conf,
                                             "Embedded text references this feature; runtime binding is not proven."))
                    for match in re.finditer(r"(?:images/|image/)[^\"'\s]+", text, re.I):
                        path = match.group().rstrip(",);]}")
                        candidate = bkdr(path)
                        matches = [r for r in loc.get("resources", []) if r.get("name_hash") == candidate]
                        rows.append(evidence(package_sha, pid, lname, res.get("name_hash"), sha, off, bo, blen,
                                             "resource_path_hash", {"path": path, "candidate_hash": candidate,
                                             "matches": len(matches)}, "hash_matched_candidate" if matches else "hypothesis",
                                             "BKDR candidate retained; collisions and missing targets are explicit."))
    # Identical blobs across locales are one observation with explicit locale references.
    grouped = {}
    for row in rows:
        key = tuple((k, json.dumps(row[k], ensure_ascii=False, sort_keys=True) if isinstance(row[k], (dict, list)) else row[k]) for k in row if k != "locale")
        if key not in grouped: grouped[key] = dict(row); grouped[key]["locale_refs"] = [row["locale"]]
        elif row["locale"] not in grouped[key]["locale_refs"]: grouped[key]["locale_refs"].append(row["locale"])
    rows = list(grouped.values())
    rows.sort(key=lambda x: (x["package_sha256"], x["blob_offset"], x["byte_offset"], x["category"], str(x["observed_value"])))
    report = {"format": 1, "tool": "analyze_dial_features", "packages": packages,
              "evidence": rows, "limitations": [
                  "JRRY/JS and PLB are never executed.",
                  "Text references do not prove runtime binding or units.",
                  "PLB geometry and PGF codec are intentionally not inferred."]}
    output.mkdir(parents=True, exist_ok=True)
    (output / "features.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    counts = {}
    for row in rows: counts[row["category"]] = counts.get(row["category"], 0) + 1
    lines = ["# GT01 dial feature evidence", "", f"Packages: {len(packages)}", f"Evidence records: {len(rows)}", "", "## Categories", ""]
    lines += [f"- `{k}`: {counts[k]}" for k in sorted(counts)]
    highlights = [p for p in packages if p["package_id"] in {"com.QC.qc64", "com.QC.qc132", "com.QC.qc35"}]
    lines += ["", "## Highlights", "", "The corpus includes qc64 source-JS evidence, qc132 frame resources, and qc35 PLB text anchors; inspect `features.json` for byte offsets and locale references.", "", "## Method and limits", "", "Only container metadata and byte-preserving embedded strings were inspected. Embedded code was not executed; geometry, codec, units and refresh semantics remain unconfirmed.", "", "## Package inventory", ""]
    lines += [f"- `{p['package_id']}` — `{p['package_sha256']}`" for p in packages]
    (output / "features.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return report

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("index", type=Path)
    ap.add_argument("output", type=Path)
    args = ap.parse_args()
    report = analyze(args.index, args.output)
    print(json.dumps({"packages": len(report["packages"]), "evidence": len(report["evidence"])}))

if __name__ == "__main__": main()
