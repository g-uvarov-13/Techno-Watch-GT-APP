"""Build the 18 pinned, offline-only GT Dial Lab watch-face variants.

The output registry describes assets; Android still needs an independent allow-list.
No device is contacted by this tool.
"""
from __future__ import annotations

import json
import struct
from hashlib import sha256
from pathlib import Path

from tools.build_qc176_palette_variant import build_package as build_qc176
from tools.build_qc61_face_variant import (
    BACKGROUND_INDEX, DONOR_SHA256, HAND_RESOURCES, PLB_INDEX,
    _indexed_blob_with_source_header, _rgb_png_bgra, _tint,
    build_package as build_qc61,
)
from tools.inspect_dial import inspect
from tools.patch_face_layout import apply_plan, load_plan
from tools.patch_qc61_text_colors import _nodes as color_nodes
from tools.pgf_codec import decode

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'work/android/app/src/main/assets/dynamic_dials'
PALETTES = ROOT / 'work/face-palettes-20260923/backgrounds-v2.json'
CATALOG = ASSETS / 'catalog.json'
QC61 = ROOT / 'work/myhealth-gt01-corpus-20260919/sources/4eeade6e145f42bd8419e34d3d1e51d9.dial-6cacd1ca10a0.dial'
QC176 = ROOT / 'work/dial-corpus-v2/sources/com.QC.qc176.dial-dfaac700817e.dial'
QC61_WORK = ROOT / 'work/qc61-authoring-20260923'
QC176_PLAN = ROOT / 'work/face-layout-20260923/diallab3000-qc176-plan-v4.json'
QC176_PLB = ROOT / 'work/face-layout-20260923/diallab3000-qc176-v4.plb'
REGISTRY = ASSETS / 'offline-package-registry.json'

BASE = {
    ('pipboy3000', 'phosphor'): ('com.gtdial.dl3000', '2309202601', 'dl3000'),
    ('bitax', 'amber'): ('com.gtdial.nixiecore', '2309202602', 'nixiecore'),
    ('pixbot', 'ice'): ('com.gtdial.pixeldeck', '2309202603', 'pixeldeck'),
}
PREFIX = {'pipboy3000': 'dl', 'bitax': 'nx', 'pixbot': 'px'}


def _qc61_variant(face: str, background: Path, primary: str, identity: tuple[str, str, str], plan_override: Path | None = None) -> bytes:
    raw = QC61.read_bytes()
    if sha256(raw).hexdigest() != DONOR_SHA256:
        raise ValueError('QC61 donor hash mismatch')
    report = inspect(QC61, raw)
    by_index = {x['index']: x for x in report['default_resources']}
    work = QC61_WORK
    plan = plan_override or work / ('nixie-core-qc61-layout-v2.json' if face == 'bitax' else 'pixel-deck-qc61-layout-v3.json')
    plb = bytearray(apply_plan(work / 'qc61-donor.plb', load_plan(plan)))
    color = int(primary[1:], 16)
    for offset in color_nodes(work / 'qc61-donor.plb').values():
        struct.pack_into('<I', plb, offset, color)
    source_bg = by_index[BACKGROUND_INDEX]
    source_blob = raw[source_bg['offset']:source_bg['offset'] + source_bg['length']]
    meta, _, _ = decode(source_blob)
    width, height, bgra = _rgb_png_bgra(background)
    if (width, height) != (466, 466) or any(bgra[i] != 255 for i in range(3, len(bgra), 4)):
        raise ValueError('invalid opaque QC61 background')
    extra = {
        PLB_INDEX: bytes(plb),
        BACKGROUND_INDEX: _indexed_blob_with_source_header(source_blob, width, height, bgra, meta),
    }
    glyph_dir = work / ('resized-glyphs-40x80' if face == 'bitax' else 'pixel-glyphs')
    manifest = json.loads((glyph_dir / 'manifest.json').read_text(encoding='utf-8'))
    if manifest.get('donor_sha256') != DONOR_SHA256 or len(manifest.get('glyphs', [])) != 20:
        raise ValueError('invalid exact QC61 time glyph manifest')
    rgb = tuple(int(primary[i:i + 2], 16) for i in (3, 5, 7))
    for glyph in manifest['glyphs']:
        blob = (glyph_dir / glyph['output']).read_bytes()
        if sha256(blob).hexdigest() != glyph['output_sha256']:
            raise ValueError('QC61 time glyph hash mismatch')
        extra[glyph['resource_index']] = _tint(blob, rgb)
    for item in report['default_resources']:
        index = item['index']
        if item['type'] != 'PGF image' or index in extra or index in HAND_RESOURCES:
            continue
        blob = raw[item['offset']:item['offset'] + item['length']]
        extra[index] = _tint(blob, rgb)
    return build_qc61(QC61, extra=extra, identity=identity)


def main() -> None:
    catalog = json.loads(CATALOG.read_text(encoding='utf-8'))
    backgrounds = {(x['faceId'], x['paletteId']): x for x in json.loads(PALETTES.read_text(encoding='utf-8'))}
    face_items = {x['id']: x for x in catalog['items'] if x['id'] in PREFIX}
    if len(face_items) != 3 or len(backgrounds) != 18 or REGISTRY.exists():
        raise ValueError('unexpected face manifest or existing output registry')
    records = []
    packages = []
    next_alias = 4
    for face in ('pipboy3000', 'bitax', 'pixbot'):
        item = face_items[face]
        if len(item['palettes']) != 6:
            raise ValueError('face must have six palettes')
        for palette in item['palettes']:
            key = face, palette['id']
            row = backgrounds[key]
            primary = palette['colors']['primary']
            if primary.upper() != row['primary'].upper():
                raise ValueError(f'catalog/background palette mismatch: {key}')
            source = ROOT / row['path']
            if sha256(source.read_bytes()).hexdigest().upper() != row['backgroundSha256'].upper():
                raise ValueError(f'background hash mismatch: {key}')
            if key in BASE:
                identity = BASE[key]
            else:
                identity = (f'com.gtdial.{PREFIX[face]}.{palette["id"]}', f'23092026{next_alias:02d}', f'{PREFIX[face]}{palette["id"]}')
                next_alias += 1
            output = ASSETS / face / palette['id'] / 'face.dial'
            if output.exists():
                raise FileExistsError(output)
            if face == 'pipboy3000':
                blob = build_qc176(QC176, source, '#' + primary[3:], plb_plan=QC176_PLAN, plb_result=QC176_PLB, identity=(identity[0], identity[2], identity[1]))
            else:
                blob = _qc61_variant(face, source, primary, identity)
            actual = inspect(output, blob)
            if tuple(actual['manifest'][k] for k in ('id', 'alias', 'name')) != identity:
                raise ValueError(f'package identity mismatch: {key}')
            if len(actual['locales']) != 28 or any(x['resource_count'] != (133 if face == 'pipboy3000' else 65) for x in actual['locales']):
                raise ValueError(f'package locale/resource count: {key}')
            records.append({
                'faceId': face, 'paletteId': palette['id'],
                'packageAsset': f'dynamic_dials/{face}/{palette["id"]}/face.dial',
                'packageSha256': sha256(blob).hexdigest(), 'packageSize': len(blob),
                'internalPackageId': identity[0], 'alias': identity[1],
                'backgroundSha256': row['backgroundSha256'].lower(), 'primary': primary,
            })
            packages.append((output, blob))
            print(face, palette['id'], len(blob), sha256(blob).hexdigest())
    if len(records) != 18 or len({x['alias'] for x in records}) != 18 or len({x['internalPackageId'] for x in records}) != 18:
        raise ValueError('duplicate package identity')
    for output, blob in packages:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(blob)
    REGISTRY.write_text(json.dumps({'schemaVersion': 1, 'items': records}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('registry', REGISTRY, sha256(REGISTRY.read_bytes()).hexdigest())


if __name__ == '__main__':
    main()
