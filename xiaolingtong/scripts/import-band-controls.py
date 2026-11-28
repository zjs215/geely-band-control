"""Import Vela control artwork from the installed Lynk & Co 4.3.1 APK.

Usage: python import-band-controls.py path/to/official.apk path/to/aapt2
Requires Pillow. Only transparent margins and display scale are adapted.
"""
import argparse
from io import BytesIO
from pathlib import Path
import re
import subprocess
from zipfile import ZipFile

from PIL import Image

DEST = Path(__file__).resolve().parents[1] / 'band/src/common/controls/local'
# Light strokes (default), dark strokes (loading/unknown), mint strokes (open).
VARIANTS = {
    'LOCK': ('js_off2', 'js_off', 'js_on'),
    'WINDOW': ('cc_off2', 'cc_off', 'cc_on'),
    'FIND': ('xc2', 'xc', None),
    'TRUNK': ('hbx_off', 'hbx_off2', 'hbx_on'),
    'ENGINE': ('yq_off2', 'yq_off', 'yq_on'),
    'SUNROOF': ('tc_off', 'tc_off2', 'tc_on2'),
    'CLIMATE': ('kt_off', 'kt_off2', 'kt_on'),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('aapt2', type=Path)
    args = parser.parse_args()
    resources = subprocess.check_output([args.aapt2, 'dump', 'resources', args.apk]).decode('utf-8', 'replace')
    paths = dict(re.findall(r'mipmap/ic_ck_(\w+)\s*\n\s*\(xxhdpi\) \(file\) (\S+)', resources))
    with ZipFile(args.apk) as apk:
        for category, variants in VARIANTS.items():
            for group, name in zip(('', 'day', 'open'), variants):
                if name is None:
                    continue
                glyph = Image.open(BytesIO(apk.read(paths[name]))).convert('RGBA')
                glyph = glyph.crop(glyph.getchannel('A').getbbox())
                scale = 56 / max(glyph.size)
                glyph = glyph.resize(tuple(round(n * scale) for n in glyph.size), Image.Resampling.LANCZOS)
                icon = Image.new('RGBA', (64, 64))
                icon.paste(glyph, ((64 - glyph.width) // 2, (64 - glyph.height) // 2))
                target = DEST / group / (category + '.png')
                target.parent.mkdir(parents=True, exist_ok=True)
                icon.save(target, optimize=True)


if __name__ == '__main__':
    main()
