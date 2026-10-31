#!/usr/bin/env python3
import hashlib
import io
import os
import sys
import urllib.request
import zipfile

SOURCES = {
    "jisx0213-2004-std.txt": ("http://x0213.org/codetable/jisx0213-2004-std.txt", "388ae7822cbf0aecff09b8466dd630ea343673d01a3c7df9d61947ad1ad5b2dd"),
    "CP932.TXT": ("https://www.unicode.org/Public/MAPPINGS/VENDORS/MICSFT/WINDOWS/CP932.TXT", "c9bc0b0cd42e0fbcb82a09635bb5abed86afbdd4abc9e76fa5716638217cb59f"),
    "Unihan.zip": ("https://www.unicode.org/Public/18.0.0/ucd/Unihan.zip", "4c93ea9c1f636451729a840978f1667a53886af37ba854fdcce109721c63d43e"),
}
HAN = [(0x3400, 0x4DBF), (0x4E00, 0x9FFF), (0xF900, 0xFAFF), (0x20000, 0x3FFFF)]
CP932_JIS = set(range(0x81, 0x85)) | set(range(0x88, 0xA0)) | set(range(0xE0, 0xEB))
CP932_EXT = {0xED, 0xEE, 0xFA, 0xFB, 0xFC}
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "artist/src/main/kotlin/net/seilent/funecomp/artist/gen/JaHan.kt")
WIDTH = 100


def han(c):
    return any(a <= c <= b for a, b in HAN)


def load(cache, name):
    url, want = SOURCES[name]
    path = os.path.join(cache, name)
    if not os.path.exists(path):
        with urllib.request.urlopen(url, timeout=60) as r:
            data = r.read()
        with open(path + ".tmp", "wb") as f:
            f.write(data)
        os.replace(path + ".tmp", path)
    data = open(path, "rb").read()
    got = hashlib.sha256(data).hexdigest()
    if got != want:
        sys.exit("jahan: %s sha256 %s, want %s" % (name, got, want))
    return data


def x0213(data):
    out = set()
    for line in data.decode("utf-8").splitlines():
        if line.startswith("#"):
            continue
        f = line.split("\t")
        if len(f) < 2 or f[0][:2] not in ("3-", "4-"):
            continue
        u = f[1].strip()
        if not u.startswith("U+") or "+" in u[2:]:
            continue
        c = int(u[2:], 16)
        if han(c):
            out.add(c)
    return out


def cp932(data):
    jis, ext = set(), set()
    for line in data.decode("latin-1").splitlines():
        if line.startswith("#"):
            continue
        f = line.split("\t")
        if len(f) < 2 or not f[0].startswith("0x") or not f[1].strip().startswith("0x"):
            continue
        b, c = int(f[0], 16), int(f[1].strip(), 16)
        if not han(c) or b < 0x100:
            continue
        lead = b >> 8
        if lead in CP932_JIS:
            jis.add(c)
        elif lead in CP932_EXT:
            ext.add(c)
        else:
            sys.exit("jahan: CP932 Han 0x%04X at lead 0x%02X" % (b, lead))
    return jis, ext


def unihan(data):
    fields = {"kJis0": set(), "kJIS0213": set(), "kIBMJapan": set()}
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for line in z.read("Unihan_OtherMappings.txt").decode("utf-8").splitlines():
            if not line.startswith("U+"):
                continue
            cp, field, _ = line.split("\t", 2)
            if field in fields:
                fields[field].add(int(cp[2:], 16))
    return fields


def ranges(cps):
    out = []
    s = sorted(cps)
    i = 0
    while i < len(s):
        j = i
        while j + 1 < len(s) and s[j + 1] == s[j] + 1:
            j += 1
        out.append("%x" % s[i] if i == j else "%x-%x" % (s[i], s[j]))
        i = j + 1
    return ",".join(out)


def diff(name, ours, theirs):
    a, b = sorted(ours - theirs), sorted(theirs - ours)
    print("%s: %d ours, %d Unihan, %d only ours, %d only Unihan" % (name, len(ours), len(theirs), len(a), len(b)))
    for c in a[:20]:
        print("  only ours U+%04X %s" % (c, chr(c)))
    for c in b[:20]:
        print("  only Unihan U+%04X %s" % (c, chr(c)))
    return not a and not b


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: tools/jahan.py <download dir>")
    cache = sys.argv[1]
    os.makedirs(cache, exist_ok=True)
    plane = x0213(load(cache, "jisx0213-2004-std.txt"))
    jis, ext = cp932(load(cache, "CP932.TXT"))
    u = unihan(load(cache, "Unihan.zip"))
    ok = diff("cp932 JIS X 0208 kanji against kJis0", jis, u["kJis0"])
    ok = diff("JIS X 0213:2004 against kJis0 and kJIS0213", plane, u["kJis0"] | u["kJIS0213"]) and ok
    rest = sorted(ext - plane)
    ibm = [c for c in rest if c in u["kIBMJapan"]]
    none = [c for c in rest if c not in u["kIBMJapan"]]
    print("cp932 extension kanji: %d, %d not in JIS X 0213, %d of them with kIBMJapan, %d with no Unihan field" % (len(ext), len(rest), len(ibm), len(none)))
    for c in none:
        print("  no Unihan field U+%04X %s" % (c, chr(c)))
    for c in (0x525D, 0x5653, 0x20B9F):
        if c not in plane or c not in u["kJIS0213"]:
            print("JIS X 0213:2004 addition U+%04X missing" % c)
            ok = False
    if not ok:
        sys.exit("jahan: the mapping files and Unihan differ")
    cps = jis | ext | plane
    text = ranges(cps)
    lines = [text[i:i + WIDTH] for i in range(0, len(text), WIDTH)]
    body = "".join("        \"%s\" +\n" % l for l in lines[:-1]) + "        \"%s\"" % lines[-1]
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("package net.seilent.funecomp.artist.gen\n\nobject JaHan {\n    const val SIZE = %d\n\n    const val RANGES =\n%s\n}\n" % (len(cps), body))
    print("wrote %s: %d code points, %d ranges, %d chars" % (OUT, len(cps), text.count(",") + 1, len(text)))


if __name__ == "__main__":
    main()
