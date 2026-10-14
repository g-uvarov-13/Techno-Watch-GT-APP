"""Adapt generated sheets to the existing four-frame dial schema; preserve donors."""
from pathlib import Path
import argparse
import copy
import hashlib
import json
import re
import struct
from PIL import Image
from tools.inspect_dial import inspect, bkdr
from tools.build_qc35_template_candidate import rebuild
from tools.build_dial3000_v2 import pgf_from_png
from tools.render_dial3000_next_mockup import COLORS

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / 'work/pipboy-animations-20261010'
OUT = ROOT / 'outputs/pipboy-animations-20261010'
ASSETS = ROOT / 'work/android/app/src/main/assets/dynamic_dials'
STORIES = {'walk': 'Шагающий человечек', 'rpg': 'Человечек, стреляющий из РПГ',
           'auto': 'Человечек, стреляющий из автомата',
           'stranger': 'Таинственный незнакомец', 'bottle': 'Пьющий человечек',
           'savant': 'Человечек с озарением', 'thumb': 'Человечек под ядерным взрывом'}
RECT = (184, 138, 84, 171)

def sha(data):
    return hashlib.sha256(data).hexdigest()

def sheet_frames(story):
    approved = {'rpg': OUT / 'review-v2/rpg-sheet.png',
                'auto': OUT / 'review-v2/auto-horizontal-sheet.png',
                'bottle': OUT / 'review-v3/bottle-sheet.png',
                'thumb': OUT / 'review-v3/thumb-sheet.png'}
    sheet = Image.open(approved.get(story, WORK / 'sheets' / (story + '.png'))).convert('RGB')
    cells = []
    boxes = []
    for i in range(8 if story == 'bottle' else 4):
        rows = 2 if story == 'bottle' else 1
        col, row = i % 4, i // 4
        cell = sheet.crop((round(col * sheet.width / 4), round(row * sheet.height / rows),
                           round((col + 1) * sheet.width / 4), round((row + 1) * sheet.height / rows)))
        # Extract only the violet foreground; black canvas remains opaque.
        mask = cell.getchannel('B').point(lambda p: 255 if p > 75 else 0)
        box = mask.getbbox()
        if box is None:
            raise ValueError('empty sprite: ' + story)
        cells.append(cell)
        boxes.append(box)
    # A common crop retains the seated pose's ground and a stable animation origin.
    union = (min(b[0] for b in boxes), min(b[1] for b in boxes),
             max(b[2] for b in boxes), max(b[3] for b in boxes))
    cells = [cell.crop(union) for cell in cells]
    scale = min(78 / cells[0].width, 163 / cells[0].height)
    result = []
    for cell in cells:
        sprite = cell.resize((round(cell.width * scale), round(cell.height * scale)), Image.Resampling.LANCZOS)
        frame = Image.new('RGB', RECT[2:], 'black')
        frame.paste(sprite, ((84 - sprite.width) // 2, 167 - sprite.height))
        result.append(frame)
    return result

def build_story(story):
    OUT.mkdir(parents=True, exist_ok=True)
    frames = sheet_frames(story)
    records = []
    contacts = Image.new('RGB', (466 * 4, 466 * (2 if len(frames) == 8 else 1)), 'black')
    for theme_index, (theme, color) in enumerate(COLORS.items()):
        donor = ROOT / 'work/dial3000-animation/candidates-v3' / (theme + '.dial')
        raw = donor.read_bytes()
        report = inspect(donor, raw)
        resources = report['default_resources']
        by_name = {r['name_hash']: r for r in resources}
        rgb = tuple(int(color[i:i+2], 16) for i in (1, 3, 5))
        themed = []
        for frame in frames:
            pixels = bytearray()
            for pixel in frame.get_flattened_data():
                strength = max(pixel)
                pixels.extend(round(channel * strength / 255) for channel in rgb)
            themed.append(Image.frombytes('RGB', RECT[2:], bytes(pixels)))
        frame_dir = WORK / 'frames' / story / theme
        frame_dir.mkdir(parents=True, exist_ok=True)
        replacements = {}
        changed = []
        if len(frames) != 4:
            from tools.build_shared_dial_base import parsed_plb
            from tools.build_dial3000_animation_candidate import string
            layout = resources[37]
            plb = raw[layout['offset']:layout['offset']+layout['length']]
            animation = next(n for n in parsed_plb(plb)['children'] if n['id'] == 'animatedImage1')
            paths = next(p for p in animation['properties'] if p['tag'] == 9)
            replacement = struct.pack('<HH', 9, len(frames)) + b''.join(string(f'images/0month{i+1}.png') for i in range(len(frames)))
            body = plb[32:paths['offset']] + replacement + plb[paths['end']:]
            header = bytearray(plb[:32])
            struct.pack_into('<I', header, 8, len(body))
            updated = bytes(header) + body
            parsed_plb(updated)
            replacements[layout['offset']] = updated
            changed.append(37)
        for i, frame in enumerate(themed):
            image_path = frame_dir / f'{i:02}.png'
            frame.save(image_path)
            resource = by_name[f'0x{bkdr(f"images/0month{i+1}.png"):08x}']
            start = resource['offset']
            replacements[start] = pgf_from_png(raw[start:start+resource['length']], image_path)
            changed.append(resource['index'])
        manifest = dict(report['manifest'])
        identity = 'com.gtdial.pip.' + story + '.' + theme
        alias = str(1010202601 + list(STORIES).index(story) * 10 + theme_index)
        manifest.update(id=identity, name='pip' + story + theme, alias=alias)
        resource = resources[44]
        replacements[resource['offset']] = json.dumps(manifest, separators=(',', ':')).encode() + b' '
        data = rebuild(raw, report, replacements)
        destination = WORK / 'packages' / story / (theme + '.dial')
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
        new_report = inspect(destination, data)
        for old, new in zip(resources, new_report['default_resources']):
            if old['index'] not in changed + [44]:
                if raw[old['offset']:old['offset']+old['length']] != data[new['offset']:new['offset']+new['length']]:
                    raise ValueError('non-animation resource changed')
        if len(new_report['locales']) != 23 or any(len(l['resources']) != 71 for l in new_report['locales']):
            raise ValueError('locale shape changed')
        preview_frames = []
        for i, frame in enumerate(themed):
            face = Image.open(ROOT / 'outputs/dial3000-v3-package-renders' / (theme + '-11-25-battery50.png')).convert('RGB')
            face.paste(frame, RECT[:2])
            preview_frames.append(face)
            if theme == 'cyan':
                contacts.paste(face, (466 * (i % 4), 466 * (i // 4)))
        preview_path = OUT / (story + '-' + theme + '.png')
        preview_frames[0].save(preview_path)
        preview_frames[0].save(OUT / (story + '-' + theme + '.gif'), save_all=True,
                               append_images=preview_frames[1:], duration=210, loop=0, optimize=False)
        records.append(dict(faceId='pip' + story, paletteId=theme,
                            packageAsset=f'dynamic_dials/pip{story}/{theme}/face.dial',
                            packageSha256=sha(data), packageSize=len(data),
                            internalPackageId=identity, alias=alias,
                            sourcePackageSha256=sha(raw), changedResourceIndices=changed+[44]))
    contacts.save(OUT / (story + '-contact.png'))
    (WORK / (story + '-manifest.json')).write_text(json.dumps(records, indent=2) + '\n')
    print(story, 'six palettes;', len(frames), 'frames')

def java_line(r):
    return ('new BundledDynamicDial.PackageVariant("'+r['faceId']+'","'+r['paletteId']+'","'+r['packageAsset']+'","'
            +r['packageSha256']+'",'+str(r['packageSize'])+'L,"'+r['internalPackageId']+'","'+r['alias']+'")')

def stage():
    import shutil
    registry = json.loads((WORK / 'before/registry.json').read_text(encoding='utf-8-sig'))
    catalog = json.loads((WORK / 'before/catalog.json').read_text(encoding='utf-8-sig'))
    template = next(x for x in catalog['items'] if x['id'] == 'dial3000walk')
    new_records = []
    for story, title in STORIES.items():
        records = json.loads((WORK / (story+'-manifest.json')).read_text())
        item = copy.deepcopy(template)
        item.update(id='pip'+story, name=title,
                    previewAsset=f'dynamic_dials/pip{story}/phosphor/preview.png',
                    renderSpecAsset=f'dynamic_dials/pip{story}/render-spec.json')
        catalog['items'].append(item)
        for r in records:
            destination = ROOT / 'work/android/app/src/main/assets' / r['packageAsset']
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(WORK / 'packages' / story / (r['paletteId']+'.dial'), destination)
            shutil.copyfile(OUT / (story+'-'+r['paletteId']+'.png'), destination.with_name('preview.png'))
            new_records.append({k:v for k,v in r.items() if k not in ('sourcePackageSha256','changedResourceIndices')})
        spec = json.loads((ASSETS / 'dial3000walk/render-spec.json').read_text())
        spec['layers'][0]['asset'] = item['previewAsset']
        spec['animation']['frameCount'] = 8 if story == 'bottle' else 4
        (ASSETS / ('pip'+story) / 'render-spec.json').write_text(json.dumps(spec, ensure_ascii=False)+'\n', encoding='utf-8')
    template['name'] = 'Шагающий человечек · прежняя версия'
    next(x for x in catalog['items'] if x['id'] == 'dial3000')['name'] = 'Человечек · без анимации'
    registry['items'].extend(new_records)
    registry_path = ASSETS / 'offline-package-registry.json'
    registry_path.write_text(json.dumps(registry, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    (ASSETS / 'catalog.json').write_text(json.dumps(catalog, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    source = (ROOT / 'work/android/app/src/main/java/app/gtdial/lab/BundledDynamicDialRepository.java').read_bytes().decode('utf-8-sig')
    # Replace only our generated tuples; preserve all current source changes.
    source = re.sub(r'\s*new BundledDynamicDial\.PackageVariant\("pip(?:walk|rpg|auto|stranger|bottle|savant|thumb)"[^\n]*?\),', '', source)
    source = source.replace('source.length()!=7', 'source.length()!=14').replace('records.length()!=31', 'records.length()!=73')
    source = re.sub(r'REGISTRY_SHA256="[0-9a-f]+"', 'REGISTRY_SHA256="'+sha(registry_path.read_bytes())+'"', source)
    marker = 'private static Map<String,BundledDynamicDial.PackageVariant> compiledVariants()'
    start = source.index(marker)
    opening = source.index('all={', start) + len('all={')
    source = source[:opening]+'\n        '+',\n        '.join(java_line(r) for r in new_records)+','+source[opening:]
    (ROOT / 'work/android/app/src/main/java/app/gtdial/lab/BundledDynamicDialRepository.java').write_bytes(source.encode('utf-8'))
    for filename in ('MainActivity.java', 'BleWatchSession.java'):
        path = ROOT / 'work/android/app/src/main/java/app/gtdial/lab' / filename
        source = path.read_bytes().decode('utf-8-sig')
        old = '("dial3000".equals(dial.id)||"dial3000walk".equals(dial.id))'
        new = '("dial3000".equals(dial.id)||"dial3000walk".equals(dial.id)||(dial.id.startsWith("pip")&&!dial.id.startsWith("pipboy")))'
        expected = 2 if filename == 'MainActivity.java' else 1
        if source.count(old) + source.count(new) != expected:
            raise ValueError('theme preview source anchor changed: ' + filename)
        path.write_bytes(source.replace(old, new).encode('utf-8'))
    print('staged 42 new variants, preserving all 31 prior variants')

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('story', choices=list(STORIES)+['stage'])
    choice = parser.parse_args().story
    stage() if choice == 'stage' else build_story(choice)
