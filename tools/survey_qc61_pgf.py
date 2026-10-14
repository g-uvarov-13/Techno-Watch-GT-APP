"""Report whether the exact QC61 donor glyph palettes can be safely recolored."""
from pathlib import Path
from tools.inspect_dial import inspect
from tools.pgf_codec import decode

donor = Path('work/myhealth-gt01-corpus-20260919/sources/4eeade6e145f42bd8419e34d3d1e51d9.dial-6cacd1ca10a0.dial')
raw = donor.read_bytes()
report = inspect(donor, raw)
for item in report['default_resources']:
    if item['type'] != 'PGF image':
        continue
    meta, _, palette = decode(raw[item['offset']:item['offset'] + item['length']])
    bad = sum(max(palette[i:i + 3]) > palette[i + 3] for i in range(0, len(palette), 4))
    print(item['index'], meta['format_raw'], meta['compression'], len(palette) // 4, bad)
