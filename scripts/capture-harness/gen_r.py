# Generate a stub R.java from the app's real resources, so R.x.y references
# in the compiled files are checked against what actually exists.
import os, re, sys, xml.etree.ElementTree as ET
res = sys.argv[1]; out = sys.argv[2]
kinds = {}
def add(k, n):
    kinds.setdefault(k, set()).add(n.replace('.', '_'))
for d in sorted(os.listdir(res)):
    base = d.split('-')[0]
    p = os.path.join(res, d)
    for f in os.listdir(p):
        fp = os.path.join(p, f)
        if base == 'values':
            try:
                root = ET.parse(fp).getroot()
            except Exception as e:
                print('parse fail', fp, e); continue
            for el in root:
                tag = el.tag
                name = el.get('name')
                if not name: continue
                if tag == 'item':
                    tag = el.get('type') or 'id'
                if tag in ('string-array', 'integer-array', 'array'):
                    tag = 'array'
                if tag == 'declare-styleable':
                    add('styleable', name)
                    for a in el.iter('attr'):
                        add('attr', a.get('name').replace('android:', '')) if not a.get('name').startswith('android:') else None
                    continue
                if tag == 'eat-comment': continue
                add(tag, name)
        else:
            add(base, os.path.splitext(f.replace('.9.png', '.png'))[0])
        if f.endswith('.xml'):
            txt = open(fp, encoding='utf-8', errors='ignore').read()
            for m in re.finditer(r'@\+id/([A-Za-z0-9_.]+)', txt):
                add('id', m.group(1))
with open(out, 'w') as o:
    o.write('package com.solarized.firedown;\npublic final class R {\n')
    for k, names in sorted(kinds.items()):
        o.write(f'  public static final class {k} {{\n')
        for i, n in enumerate(sorted(names)):
            if k == 'styleable':
                o.write(f'    public static final int[] {n} = new int[0];\n')
            else:
                o.write(f'    public static final int {n} = {0x7f000000 + i};\n')
        o.write('  }\n')
    o.write('}\n')
print({k: len(v) for k, v in kinds.items()})
