#!/usr/bin/env python3
"""Link literal PLB resource paths to same-package, same-locale CR records."""
import argparse
import json
from pathlib import Path


def bkdr(text):
    value = 0
    for byte in text.encode("utf-8"):
        value = (value * 131 + byte) & 0xFFFFFFFF
    return value


def walk(node):
    yield node
    for child in node.get("children", []):
        yield from walk(child)


def property_paths(prop):
    value = prop.get("decoded_value", prop.get("value"))
    tag = prop.get("tag")
    def is_path(text):
        lowered = text.lower()
        return "/" in text and lowered.endswith((".png", ".pgf", ".js", ".xml", ".json", ".ttf"))
    if tag in (2, 7) and isinstance(value, str):
        return [value] if is_path(value) else []
    if tag in (8, 9) and isinstance(value, dict):
        raw = value.get("value")
        if isinstance(raw, str):
            return [raw] if is_path(raw) else []
        if isinstance(raw, list):
            return [item for item in raw if isinstance(item, str) and is_path(item)]
    return []


def load_sources(index_paths):
    sources = {}
    for index_path in index_paths:
        data = json.loads(index_path.read_text(encoding="utf-8"))
        for source in data.get("sources", []):
            package = source.get("manifest", {}).get("id")
            sources[(package, source.get("sha256"))] = source
    return sources


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--index", action="append", required=True, type=Path)
    parser.add_argument("--plb-index", action="append", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError(f"output exists: {args.output}")

    sources = load_sources(args.index)
    by_package = {}
    for (package, digest), source in sources.items():
        by_package.setdefault(package, []).append((digest, source))

    links = []
    for plb_path in args.plb_index:
        parsed = json.loads(plb_path.read_text(encoding="utf-8"))
        for item in parsed.get("items", []):
            for ref in item.get("packages", []):
                package = ref.get("package")
                package_sources = by_package.get(package, [])
                for node in walk(item["root"]):
                    for prop in node.get("properties", []):
                        for literal in property_paths(prop):
                            name_hash = bkdr(literal)
                            for package_sha, source in package_sources:
                                for locale in source.get("locales", []):
                                    matches = []
                                    for resource in locale.get("resources", []):
                                        raw_hash = resource.get("name_hash")
                                        parsed_hash = int(raw_hash, 16) if isinstance(raw_hash, str) else raw_hash
                                        if parsed_hash == name_hash:
                                            matches.append({
                                                "index": resource.get("index"),
                                                "type": resource.get("type"),
                                                "blob_sha256": resource.get("blob_sha256") or resource.get("sha256"),
                                                "offset": resource.get("offset"),
                                                "length": resource.get("length"),
                                            })
                                    links.append({
                                        "package": package,
                                        "package_sha256": package_sha,
                                        "locale": locale.get("name"),
                                        "plb_sha256": item.get("sha256"),
                                        "node_id": node.get("id"),
                                        "node_offset": node.get("offset"),
                                        "property_tag": prop.get("tag"),
                                        "property_offset": prop.get("offset"),
                                        "classification": "exact_literal_path",
                                        "path": literal,
                                        "name_hash": f"0x{name_hash:08x}",
                                        "match_count": len(matches),
                                        "matches": matches,
                                    })

    links.sort(key=lambda row: (row["package_sha256"] or "", row["locale"] or "", row["node_offset"], row["property_offset"], row["path"]))
    counts = {
        "links": len(links),
        "unique_literals": len({(row["package_sha256"], row["path"]) for row in links}),
        "matched_one": sum(row["match_count"] == 1 for row in links),
        "unmatched": sum(row["match_count"] == 0 for row in links),
        "collisions": sum(row["match_count"] > 1 for row in links),
    }
    claims = [{
        "claim_id": "resources.plb-literal-local-link",
        "status": "confirmed",
        "package_sha256": None,
        "blob_sha256": None,
        "offsets": [],
        "method": "BKDR-131 over exact UTF-8 literal; same package and locale only",
        "competing_interpretations": [],
        "minimal_missing_observation": None,
    }]
    output = {"format": 2, "counts": counts, "links": links, "claims": claims}
    args.output.mkdir(parents=True)
    (args.output / "index.json").write_text(json.dumps(output, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(counts))


if __name__ == "__main__":
    main()
