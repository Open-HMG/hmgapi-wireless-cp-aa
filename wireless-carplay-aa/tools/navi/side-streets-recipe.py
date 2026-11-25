#!/usr/bin/env python3
"""side-streets-recipe.py - the memory recipe that turns on the car navigation's side
streets (the roads the cluster draws around a turn arrow) in a build of its engine,
libExSLNavi.so (com.mnsoft.navi).

usage: side-streets-recipe.py <libExSLNavi.so> [--out <recipe>] [--patched <file>]

The engine works out side streets for every turn, as Korean cars' own navigation shows
them, but only when Navi::CLUSTER::Turn::CInformation::IsSupportSideStreet() said yes at
start, and that is "the nation is Korea": everywhere else every turn goes to the cluster
with side streets 0. The answer is kept in a flag in CInformation, which three functions
read (MakeSideStreet, MakeSideStreet_RoundAbout, MakeNormalSideStreet). This finds:

  - the flag's offset: in CInformation's constructor, the strb.w r0 right after the call
    to IsSupportSideStreet;
  - in each of the three, the one ldrb.w r3, [rN, #flag] that reads it, followed by a test
    of r3 within two instructions;

and makes each read "movs r3, #1; nop" (4 bytes), so the engine always makes them. All by
exported symbol names, so a recipe comes out for any build with the same code; anything
unexpected (a missing symbol, no read or two of them) stops with an error, no recipe.

The recipe (hmgapi.engine.MemRecipe) holds only hashes of the stock bytes around each
read and the bytes written. --patched writes the patched library too, for the off-car
test (tools/recipes/test.sh); never ship or commit it.
"""
import argparse, hashlib, struct, sys

FUNCS = {
    "ctor": "_ZN4Navi7CLUSTER4Turn12CInformationC2Ev",
    "gate": "_ZN4Navi7CLUSTER4Turn12CInformation19IsSupportSideStreetEv",
    "readers": [
        "_ZN4Navi7CLUSTER4Turn12CInformation14MakeSideStreetEP9CRGTargetRNS1_6stItemE",
        "_ZN4Navi7CLUSTER4Turn12CInformation25MakeSideStreet_RoundAboutEP9CRGTargetRh",
        "_ZN4Navi7CLUSTER4Turn12CInformation20MakeNormalSideStreetEP9CRGTargetRh",
    ],
}
NEW = bytes.fromhex("012300bf")   # movs r3, #1 ; nop
BEFORE, AFTER = 8, 8               # verify context around each 4-byte read


def die(msg):
    sys.exit("side-streets-recipe: " + msg)


def symbols(b):
    """{name: (value, size)} from .dynsym"""
    if b[:4] != b"\x7fELF" or b[4] != 1 or b[5] != 1:
        die("not a 32-bit little-endian ELF")
    shoff, = struct.unpack_from("<I", b, 0x20)
    shentsize, shnum = struct.unpack_from("<HH", b, 0x2e)
    secs = [struct.unpack_from("<10I", b, shoff + k * shentsize) for k in range(shnum)]
    out = {}
    for s in secs:
        if s[1] != 11:   # SHT_DYNSYM
            continue
        strtab = secs[s[6]]
        for k in range(s[5] // 16):
            name, value, size, info, other, shndx = struct.unpack_from("<IIIBBH", b, s[4] + k * 16)
            if not size or shndx == 0:
                continue
            p = strtab[4] + name
            n = b[p:b.index(b"\0", p)].decode("ascii", "replace")
            out[n] = (value & ~1, size)
    return out


def text_end(b):
    phoff, = struct.unpack_from("<I", b, 0x1c)
    phnum, = struct.unpack_from("<H", b, 0x2c)
    for k in range(phnum):
        t, off, va, pa, fsz, msz, fl, al = struct.unpack_from("<8I", b, phoff + 32 * k)
        if t == 1:
            if off != 0 or va != 0:
                die("the code segment isn't at file offset 0")
            return fsz
    die("no PT_LOAD")


def insns(b, start, size):
    """(address, halfwords) for each Thumb instruction in [start, start + size)"""
    at, end = start, start + size
    while at < end:
        hw1, = struct.unpack_from("<H", b, at)
        if hw1 >> 11 in (0b11101, 0b11110, 0b11111):
            hw2, = struct.unpack_from("<H", b, at + 2)
            yield at, (hw1, hw2)
            at += 4
        else:
            yield at, (hw1,)
            at += 2


def bl_target(at, hw):
    """the target of a Thumb BL (T1) at address at, or None"""
    if len(hw) != 2 or hw[0] & 0xf800 != 0xf000 or hw[1] & 0xd000 != 0xd000:
        return None
    s = (hw[0] >> 10) & 1
    j1, j2 = (hw[1] >> 13) & 1, (hw[1] >> 11) & 1
    i1, i2 = 1 - (j1 ^ s), 1 - (j2 ^ s)
    off = (s << 24) | (i1 << 23) | (i2 << 22) | ((hw[0] & 0x3ff) << 12) | ((hw[1] & 0x7ff) << 1)
    if s:
        off -= 1 << 25
    return at + 4 + off


def ldrb_strb_imm(hw, load):
    """(Rt, Rn, imm12) for ldrb.w/strb.w Rt, [Rn, #imm12] (T2), else None"""
    if len(hw) != 2:
        return None
    if hw[0] & 0xfff0 != (0xf890 if load else 0xf880):
        return None
    return (hw[1] >> 12, hw[0] & 0xf, hw[1] & 0xfff)


def tests_r3(hw):
    """cbz/cbnz r3, or cmp r3, #0"""
    if len(hw) != 1:
        return False
    h = hw[0]
    return (h & 0xf507 == 0xb103) or h == 0x2b00


def flag_offset(b, syms):
    ctor, gate = syms.get(FUNCS["ctor"]), syms.get(FUNCS["gate"])
    if not ctor or not gate:
        die("no CInformation constructor or IsSupportSideStreet in this library")
    seq = list(insns(b, *ctor))
    for k, (at, hw) in enumerate(seq):
        if bl_target(at, hw) == gate[0]:
            for at2, hw2 in seq[k + 1:k + 3]:
                s = ldrb_strb_imm(hw2, False)
                if s and s[0] == 0:
                    return s[2]
            die("the constructor doesn't keep IsSupportSideStreet's answer where expected")
    die("the constructor doesn't call IsSupportSideStreet")


def reads(b, syms, flag):
    sites = []
    for name in FUNCS["readers"]:
        f = syms.get(name)
        if not f:
            die("no " + name)
        seq = list(insns(b, *f))
        found = []
        for k, (at, hw) in enumerate(seq):
            l = ldrb_strb_imm(hw, True)
            if l and l[0] == 3 and l[2] == flag and any(tests_r3(h) for _, h in seq[k + 1:k + 3]):
                found.append(at)
        if len(found) != 1:
            die("%s: %d reads of the flag (wanted 1)" % (name, len(found)))
        sites.append((name, found[0]))
    return sites


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("lib")
    ap.add_argument("--out")
    ap.add_argument("--patched")
    a = ap.parse_args()
    b = open(a.lib, "rb").read()
    syms = symbols(b)
    end = text_end(b)
    flag = flag_offset(b, syms)
    sites = reads(b, syms, flag)
    md5 = hashlib.md5(b).hexdigest()
    p = bytearray(b)
    for _, at in sites:
        if at + 4 > end:
            die("a read is outside the code segment")
        p[at:at + 4] = NEW
    lines = [
        "# libExSLNavi (car navigation engine): side streets on the cluster outside Korea; the three reads of",
        "# CInformation's IsSupportSideStreet flag (+0x%x) become \"movs r3, #1; nop\"" % flag,
        "# generated by tools/navi/side-streets-recipe.py from engine md5 %s (%d bytes)" % (md5, len(b)),
        "# holds only hashes of the stock bytes and the new bytes written",
        "id navi-side-streets-" + md5[:8],
        "file /data/data/com.mnsoft.navi/BIN/libExSLNavi.so",
    ]
    for name, at in sites:
        lo, hi = at - BEFORE, at + 4 + AFTER
        lines.append("# " + name.split("12CInformation")[1].split("E")[0][2:])
        lines.append("verify 0x%x %d %s %s" % (lo, hi - lo, hashlib.md5(b[lo:hi]).hexdigest(), hashlib.md5(bytes(p[lo:hi])).hexdigest()))
        lines.append("patch 0x%x %s" % (at, NEW.hex()))
    text = "\n".join(lines) + "\n"
    if a.out:
        open(a.out, "w").write(text)
    else:
        sys.stdout.write(text)
    if a.patched:
        open(a.patched, "wb").write(bytes(p))
    print("flag +0x%x, reads at %s" % (flag, ", ".join("0x%x" % s for _, s in sites)), file=sys.stderr)


if __name__ == "__main__":
    main()
