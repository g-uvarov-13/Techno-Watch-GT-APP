"""Independently check every bundled offline watch-face package and registry entry."""
from __future__ import annotations

import json
import struct
from hashlib import sha256
from pathlib import Path

from tools.inspect_dial import inspect
from tools.patch_face_layout import apply_plan, load_plan
from tools.patch_qc61_text_colors import _nodes as color_nodes
from tools.pgf_codec import decode
from tools.verify_qc176_palette_variant import verify as verify_qc176

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'work/android/app/src/main/assets/dynamic_dials'
QC61 = ROOT / 'work/myhealth-gt01-corpus-20260919/sources/4eeade6e145f42bd8419e34d3d1e51d9.dial-6cacd1ca10a0.dial'
QC176 = ROOT / 'work/dial-corpus-v2/sources/com.QC.qc176.dial-dfaac700817e.dial'
WORK = ROOT / 'work/qc61-authoring-20260923'
BACKGROUNDS = ROOT / 'work/face-layout-20260927/backgrounds-v3.json'


def verify_qc61(path: Path, record: dict, plan_override: Path | None = None) -> None:
    before, after = QC61.read_bytes(), path.read_bytes()
    old, new = inspect(QC61, before), inspect(path, after)
    expected_identity = (record['internalPackageId'], record['alias'])
    if (new['manifest']['id'], new['manifest']['alias']) != expected_identity:
        raise ValueError('QC61 package identity mismatch')
    if len(new['locales']) != 28 or any(x['resource_count'] != 65 for x in new['locales']):
        raise ValueError('QC61 locale/resource count changed')
    if max(x['offset'] + x['length'] for x in new['locales']) != len(after):
        raise ValueError('QC61 trailing or truncated CR02')
    face = record['faceId']
    plan = plan_override or ROOT / 'work/face-layout-20260927' / ('nixie-core-qc61-layout-v3.json' if face == 'bitax' else 'pixel-deck-qc61-layout-v4.json')
    donor_plb = WORK / 'qc61-donor.plb'
    expected_plb = bytearray(apply_plan(donor_plb, load_plan(plan)))
    for offset in color_nodes(donor_plb).values():
        struct.pack_into('<I', expected_plb, offset, int(record['primary'][1:], 16))
    source_dir = WORK / ('resized-glyphs-40x80' if face == 'bitax' else 'pixel-glyphs')
    glyph_manifest = json.loads((source_dir / 'manifest.json').read_text(encoding='utf-8'))
    glyphs = {x['resource_index']: x for x in glyph_manifest['glyphs']}
    if len(glyphs) != 20:
        raise ValueError('QC61 time glyph count')
    rgb = tuple(int(record['primary'][i:i + 2], 16) for i in (3, 5, 7))
    old_offsets, new_offsets = {}, {}
    for left_locale, right_locale in zip(old['locales'], new['locales']):
        if (left_locale['name'], left_locale['hash'], left_locale['length']) != (right_locale['name'], right_locale['hash'], right_locale['length']):
            raise ValueError('QC61 locale metadata changed')
        for left, right in zip(left_locale['resources'], right_locale['resources']):
            if (left['index'], left['name_hash'], left['raw_fourth_field']) != (right['index'], right['name_hash'], right['raw_fourth_field']):
                raise ValueError('QC61 resource hash/index/fourth changed')
            if old_offsets.setdefault(left['offset'], right['offset']) != right['offset'] or new_offsets.setdefault(right['offset'], left['offset']) != left['offset']:
                raise ValueError('QC61 shared resource aliasing changed')
    old_items = {x['index']: x for x in old['default_resources']}
    for item in new['default_resources']:
        index = item['index']
        old_item = old_items[index]
        old_blob = before[old_item['offset']:old_item['offset'] + old_item['length']]
        new_blob = after[item['offset']:item['offset'] + item['length']]
        if index == 28:
            if new_blob != expected_plb:
                raise ValueError('QC61 PLB geometry or text color mismatch')
        elif index == 55:
            meta, _, _ = decode(new_blob)
            if (meta['width'], meta['height']) != (466, 466):
                raise ValueError('QC61 background dimensions')
        elif index in (19, 49, 60):
            old_meta, old_plane, old_palette = decode(old_blob)
            new_meta, new_plane, new_palette = decode(new_blob)
            if old_blob[:42] != new_blob[:42] or old_plane != new_plane or len(old_palette) != len(new_palette) or any(new_palette):
                raise ValueError('QC61 transparent hand invariant')
        elif item['type'] == 'PGF image':
            source = old_blob
            if index in glyphs:
                g = glyphs[index]
                source = (source_dir / g['output']).read_bytes()
                if sha256(source).hexdigest() != g['output_sha256']:
                    raise ValueError('QC61 resized time glyph source changed')
            sm, sp, palette = decode(source)
            nm, np, actual = decode(new_blob)
            if (sm['width'], sm['height'], sm['format_raw']) != (nm['width'], nm['height'], nm['format_raw']) or sp != np or palette[3::4] != actual[3::4]:
                raise ValueError('QC61 glyph geometry, plane or alpha changed')
            for i in range(0, len(palette), 4):
                value = max(palette[i:i + 3])
                expected = bytes((rgb[2] * value // 255, rgb[1] * value // 255, rgb[0] * value // 255))
                if actual[i:i + 3] != expected:
                    raise ValueError('QC61 glyph palette color mismatch')
        elif item['type'] != 'JSON' and old_blob != new_blob:
            raise ValueError('QC61 non-image protocol resource changed')
    old_manifest = dict(old['manifest'])
    old_manifest.update({k: new['manifest'][k] for k in ('id', 'alias', 'name')})
    if old_manifest != new['manifest']:
        raise ValueError('QC61 manifest changed outside identity')


def main() -> None:
    registry_path = ASSETS / 'offline-package-registry.json'
    records = json.loads(registry_path.read_text(encoding='utf-8'))['items']
    backgrounds = {(x['faceId'], x['paletteId']): x for x in json.loads(BACKGROUNDS.read_text(encoding='utf-8'))}
    catalog = json.loads((ASSETS / 'catalog.json').read_text(encoding='utf-8'))
    catalog_map = {(x['id'], p['id']): p for x in catalog['items'] for p in x['palettes']}
    if len(records) != 18 or len({(x['faceId'], x['paletteId']) for x in records}) != 18:
        raise ValueError('registry does not have 18 unique variants')
    if len({x['alias'] for x in records}) != 18 or len({x['internalPackageId'] for x in records}) != 18:
        raise ValueError('duplicate internal package identity')
    for r in records:
        key = r['faceId'], r['paletteId']
        path = ROOT / 'work/android/app/src/main/assets' / r['packageAsset']
        raw = path.read_bytes()
        if len(raw) != r['packageSize'] or sha256(raw).hexdigest() != r['packageSha256']:
            raise ValueError(f'asset integrity mismatch: {key}')
        row = backgrounds[key]
        if r['backgroundSha256'].upper() != row['backgroundSha256'].upper() or sha256((ROOT / row['path']).read_bytes()).hexdigest().upper() != row['backgroundSha256'].upper():
            raise ValueError(f'background source integrity mismatch: {key}')
        if r['primary'] != catalog_map[key]['colors']['primary']:
            raise ValueError(f'catalog color mismatch: {key}')
        if key[0] == 'pipboy3000':
            manifest = inspect(path, raw)['manifest']
            verify_qc176(QC176, path, plb_plan=ROOT / 'work/face-layout-20260927/diallab3000-qc176-plan-v6.json', plb_result=ROOT / 'work/face-layout-20260927/diallab3000-qc176-v6.plb', identity=(r['internalPackageId'], manifest['name'], r['alias']))
        else:
            verify_qc61(path, r)
        print(key[0], key[1], 'verified', r['packageSha256'])
    print('all 18 offline packages verified; physical acceptance remains unproven')


if __name__ == '__main__':
    main()
