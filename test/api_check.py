#!/usr/bin/env python3
"""Poor man's NewApi lint: lists framework methods/fields/classes referenced by classes.dex
that do not exist in the minSdk android.jar, together with the app method that references them.
Usage: api_check.py classes.dex android-26.jar
"""
import re, struct, subprocess, sys, zipfile
from collections import defaultdict

def parse_class(b):
    # minimal class-file parser: returns (name, super, interfaces, {(name,desc)} methods, {name} fields)
    p = 8
    n = struct.unpack('>H', b[p:p+2])[0]; p += 2
    cp = [None] * n
    i = 1
    while i < n:
        tag = b[p]; p += 1
        if tag == 1:
            ln = struct.unpack('>H', b[p:p+2])[0]; p += 2
            cp[i] = b[p:p+ln].decode('utf-8', 'replace'); p += ln
        elif tag in (3, 4): p += 4
        elif tag in (5, 6): p += 8; i += 1
        elif tag == 7: cp[i] = ('C', struct.unpack('>H', b[p:p+2])[0]); p += 2
        elif tag == 8: p += 2
        elif tag in (9, 10, 11, 12): p += 4
        elif tag == 15: p += 3
        elif tag == 16: p += 2
        elif tag in (17, 18): p += 4
        elif tag in (19, 20): p += 2
        else: raise ValueError('bad tag %d' % tag)
        i += 1
    p += 2
    this = cp[cp[struct.unpack('>H', b[p:p+2])[0]][1]]; p += 2
    si = struct.unpack('>H', b[p:p+2])[0]; p += 2
    sup = cp[cp[si][1]] if si else None
    ni = struct.unpack('>H', b[p:p+2])[0]; p += 2
    ifaces = []
    for _ in range(ni):
        ifaces.append(cp[cp[struct.unpack('>H', b[p:p+2])[0]][1]]); p += 2
    def members():
        nonlocal p
        out = set()
        cnt = struct.unpack('>H', b[p:p+2])[0]; p += 2
        for _ in range(cnt):
            _, ni_, di = struct.unpack('>HHH', b[p:p+6]); p += 6
            out.add((cp[ni_], cp[di]))
            ac = struct.unpack('>H', b[p:p+2])[0]; p += 2
            for _ in range(ac):
                ln = struct.unpack('>I', b[p+2:p+6])[0]; p += 6 + ln
        return out
    fields = {f[0] for f in members()}
    methods = members()
    return this, sup, ifaces, methods, fields

def load_jar(path):
    db = {}
    with zipfile.ZipFile(path) as z:
        for nm in z.namelist():
            if nm.endswith('.class'):
                try:
                    t, s, ifs, ms, fs = parse_class(z.read(nm))
                    db[t] = (s, ifs, ms, fs)
                except Exception:
                    pass
    return db

def has_method(db, cls, name, desc, seen=None):
    seen = seen or set()
    if cls in seen or cls not in db: return False
    seen.add(cls)
    s, ifs, ms, _ = db[cls]
    if (name, desc) in ms: return True
    if s and has_method(db, s, name, desc, seen): return True
    return any(has_method(db, i, name, desc, seen) for i in ifs)

def has_field(db, cls, name, seen=None):
    seen = seen or set()
    if cls in seen or cls not in db: return False
    seen.add(cls)
    s, ifs, _, fs = db[cls]
    if name in fs: return True
    if s and has_field(db, s, name, seen): return True
    return any(has_field(db, i, name, seen) for i in ifs)

def main():
    dex, jar = sys.argv[1], sys.argv[2]
    db = load_jar(jar)
    out = subprocess.run(['dexdump', '-d', dex], capture_output=True).stdout.decode('utf-8', 'replace')
    fw = ('Landroid/', 'Ljava/', 'Ljavax/', 'Ldalvik/', 'Lorg/json/', 'Lorg/xml/', 'Lorg/w3c/')
    cur = '?'
    problems = defaultdict(set)
    # our classes: descriptor -> superclass, declared methods
    supers, declared = {}, defaultdict(set)
    cls = None
    for line in out.splitlines():
        m = re.match(r"\s*Class descriptor\s*:\s*'(L[^']+;)'", line)
        if m: cls = m.group(1); continue
        m = re.match(r"\s*Superclass\s*:\s*'(L[^']+;)'", line)
        if m and cls: supers[cls] = m.group(1); continue
        m = re.match(r"\s*name\s*:\s*'([^']+)'", line)
        if m and cls: declared[cls].add(m.group(1))
    def framework_owner(c, n):
        # walk our class chain; return the first framework ancestor if the method isn't ours
        seen = set()
        while c in supers and c not in seen:
            seen.add(c)
            if n in declared[c]: return None
            c = supers[c]
        return c if c.startswith(fw) else None
    meth_hdr = re.compile(r"^[0-9a-f]+:\s+\|\[[0-9a-f]+\] (\S+)$")
    mref = re.compile(r"(L[\w/$]+;)\.([\w$<>]+):(\([^)]*\)\S+)")
    fref = re.compile(r"(L[\w/$]+;)\.([\w$]+):(\S+)\s+// field@")
    cref = re.compile(r"(?:new-instance|const-class|check-cast|instance-of|new-array)[^,]*, (L[\w/$]+;)")
    fw = ('Landroid/', 'Ljava/', 'Ljavax/', 'Ldalvik/', 'Lorg/json/', 'Lorg/xml/', 'Lorg/w3c/')
    for line in out.splitlines():
        m = meth_hdr.match(line)
        if m:
            cur = m.group(1); continue
        if '|' not in line: continue
        for c, n, d in mref.findall(line):
            if not c.startswith(fw):
                owner = framework_owner(c, n)
                if owner is None: continue
                c = owner
            cn = c[1:-1]
            if cn not in db: problems['class ' + cn].add(cur)
            elif not has_method(db, cn, n, d): problems['method %s.%s%s' % (cn, n, d)].add(cur)
        fm = fref.search(line)
        if fm and fm.group(1).startswith(fw):
            cn = fm.group(1)[1:-1]
            if cn not in db: problems['class ' + cn].add(cur)
            elif not has_field(db, cn, fm.group(2)): problems['field %s.%s' % (cn, fm.group(2))].add(cur)
        for c in cref.findall(line):
            if c.startswith(fw) and c[1:-1] not in db: problems['class ' + c[1:-1]].add(cur)
    for k in sorted(problems):
        print(k)
        for u in sorted(problems[k]): print('    used in', u)
    print('TOTAL', len(problems))

main()
