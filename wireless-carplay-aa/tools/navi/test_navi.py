#!/usr/bin/env python3
"""Host test of tools/navi on synthetic inputs (no car files): side-streets-recipe.py on a
small ELF laid out like the engine's CInformation code, engine-check.py on the same, and
adasis-study.py on a made-up log. Writes the synthetic library, its patched build and
recipe to the directory given, for MemRecipeTest."""
import importlib.util, io, json, os, struct, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = sys.argv[1]
fail = 0


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), os.path.join(HERE, name + ".py"))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def check(what, ok):
    global fail
    print(("ok    " if ok else "FAIL  ") + what)
    if not ok:
        fail = 1


ssr = load("side-streets-recipe")
ads = load("adasis-study")

# ---- instruction decoding (encodings from the ARM manual)
check("bl_target: a backward BL", ssr.bl_target(0x2b1345a, (0xf7fd, 0xffbc)) == 0x2b113d6)
check("bl_target: not a BL", ssr.bl_target(0x1000, (0xf890, 0x3139)) is None and ssr.bl_target(0x1000, (0x2000,)) is None)
check("ldrb.w r3, [r0, #0x139] and strb.w r0, [r4, #0x139]",
      ssr.ldrb_strb_imm((0xf890, 0x3139), True) == (3, 0, 0x139) and ssr.ldrb_strb_imm((0xf884, 0x0139), False) == (0, 4, 0x139)
      and ssr.ldrb_strb_imm((0xf884, 0x0139), True) is None)
check("tests of r3: cbz r3, cbnz r3, cmp r3 #0; not cbz r2 or cmp r3 #1",
      ssr.tests_r3((0xb193,)) and ssr.tests_r3((0xb9b3,)) and ssr.tests_r3((0x2b00,))
      and not ssr.tests_r3((0xb192,)) and not ssr.tests_r3((0x2b01,)))


def bl(at, target):
    off = target - (at + 4)
    s = 1 if off < 0 else 0
    off &= (1 << 25) - 1
    i1, i2 = (off >> 23) & 1, (off >> 22) & 1
    j1, j2 = (1 - i1) ^ s, (1 - i2) ^ s
    return struct.pack("<HH", 0xf000 | (s << 10) | ((off >> 12) & 0x3ff), 0xd000 | (j1 << 13) | (j2 << 11) | ((off >> 1) & 0x7ff))


def h(*v):
    return b"".join(struct.pack("<H", x) for x in v)


def elf(funcs, code_size=0x2000):
    """a 32-bit ARM ELF: one PT_LOAD at 0, code at given addresses, .dynsym for funcs {name: (addr, bytes)}"""
    code = bytearray(code_size)
    for name, (at, body) in funcs.items():
        code[at:at + len(body)] = body
    strtab = b"\0" + b"".join(n.encode() + b"\0" for n in funcs)
    syms = b"\0" * 16
    p = 1
    for name, (at, body) in funcs.items():
        syms += struct.pack("<IIIBBH", p, at | 1, len(body), 0x12, 0, 1)
        p += len(name) + 1
    dynsym_off = len(code)
    dynstr_off = dynsym_off + len(syms)
    shoff = dynstr_off + len(strtab)
    shoff += (4 - shoff % 4) % 4
    body = bytes(code) + syms + strtab
    body += b"\0" * (shoff - len(body))
    sh = b"\0" * 40
    sh += struct.pack("<10I", 0, 11, 2, dynsym_off, dynsym_off, len(syms), 2, 1, 4, 16)
    sh += struct.pack("<10I", 0, 3, 2, dynstr_off, dynstr_off, len(strtab), 0, 0, 1, 0)
    b = bytearray(body + sh)
    hdr = b"\x7fELF\x01\x01\x01" + b"\0" * 9 + struct.pack("<HHIIIIIHHHHHH", 3, 40, 1, 0, 52, shoff, 0x5000000, 52, 32, 1, 40, 3, 0)
    b[:52] = hdr
    b[52:84] = struct.pack("<8I", 1, 0, 0, 0, len(code), len(code), 5, 0x1000)
    return bytes(b)


F = ssr.FUNCS
G, C = 0x1000, 0x1010
R = [0x1030, 0x1050, 0x1070]
funcs = {
    F["gate"]: (G, h(0x2000, 0x4770)),                                           # movs r0, #0; bx lr
    F["ctor"]: (C, h(0xb510) + bl(C + 2, G) + h(0xf884, 0x0139, 0xbd10)),         # push; bl gate; strb.w r0, [r4, #0x139]; pop
    F["readers"][0]: (R[0], h(0xf890, 0x3139, 0xb10b, 0x2001, 0x4770, 0x2000, 0x4770)),   # ldrb.w r3, [r0, #0x139]; cbz r3
    F["readers"][1]: (R[1], h(0xf892, 0x3139, 0x2200, 0x2b00, 0x4770)),                  # ldrb.w r3, [r2, #...]; movs r2, #0; cmp r3, #0
    F["readers"][2]: (R[2], h(0xf890, 0x3139, 0x2b00, 0x4770)),                          # ldrb.w; cmp r3, #0
}
lib = os.path.join(OUT, "navi-synthetic.so")
open(lib, "wb").write(elf(funcs))
r = subprocess.run([sys.executable, "-I", os.path.join(HERE, "side-streets-recipe.py"), lib, "--out", os.path.join(OUT, "navi-synthetic.recipe"),
                    "--patched", os.path.join(OUT, "navi-synthetic.patched.so")], capture_output=True, text=True)
check("recipe: the flag from the constructor, one read in each of the three: " + r.stderr.strip(),
      r.returncode == 0 and "flag +0x139, reads at 0x1030, 0x1050, 0x1070" in r.stderr)
rec = open(os.path.join(OUT, "navi-synthetic.recipe")).read() if r.returncode == 0 else ""
check("recipe: three verify regions and three 4-byte patches, movs r3 #1; nop",
      rec.count("\nverify ") == 3 and rec.count("\npatch ") == 3 and rec.count(" 012300bf") == 3)
p = open(os.path.join(OUT, "navi-synthetic.patched.so"), "rb").read() if r.returncode == 0 else b""
s = open(lib, "rb").read()
check("patched build: only the 12 bytes of the reads differ",
      len(p) == len(s) and sum(1 for a, b in zip(s, p) if a != b) <= 12
      and all(p[at:at + 4] == bytes.fromhex("012300bf") for at in R))

bad = dict(funcs)
bad[F["readers"][2]] = (R[2], h(0xf890, 0x3139, 0x2b00, 0xf890, 0x3139, 0x2b00, 0x4770))
open(os.path.join(OUT, "bad.so"), "wb").write(elf(bad))
r = subprocess.run([sys.executable, "-I", os.path.join(HERE, "side-streets-recipe.py"), os.path.join(OUT, "bad.so")], capture_output=True, text=True)
check("recipe: two reads in one function: no recipe, an error", r.returncode != 0 and "2 reads" in r.stderr and r.stdout == "")
nogate = {k: v for k, v in funcs.items() if k != F["gate"]}
open(os.path.join(OUT, "nogate.so"), "wb").write(elf(nogate))
r = subprocess.run([sys.executable, "-I", os.path.join(HERE, "side-streets-recipe.py"), os.path.join(OUT, "nogate.so")], capture_output=True, text=True)
check("recipe: no IsSupportSideStreet: no recipe", r.returncode != 0 and r.stdout == "")

r = subprocess.run([sys.executable, "-I", os.path.join(HERE, "engine-check.py"), lib], capture_output=True, text=True)
try:
    prof = json.loads(r.stdout)
except ValueError:
    prof = {}
check("engine-check: side streets found, the stock-graph symbols all missing here",
      prof.get("side_streets", {}).get("recipe") is True and len(prof.get("stock_graph", {}).get("missing", [])) == 28
      and prof["stock_graph"]["anchor_new_component"] is None)
r = subprocess.run([sys.executable, "-I", os.path.join(HERE, "engine-check.py"), os.path.join(OUT, "bad.so")], capture_output=True, text=True)
check("engine-check: says why there's no recipe", '"recipe": false' in r.stdout and "2 reads" in r.stdout)


# ---- adasis-study on a made-up drive (coordinates and names invented)
def pb(n, v):
    if isinstance(v, int):
        return varint(n << 3) + varint(v)
    return varint(n << 3 | 2) + varint(len(v)) + v


def varint(v):
    out = b""
    while True:
        c = v & 0x7f
        v >>= 7
        out += bytes([c | (0x80 if v else 0)])
        if not v:
            return out


def state(t):
    return pb(1, pb(1, pb(1, t)) + pb(2, pb(1, b"Nowhere St")))


def position(m):
    return pb(1, pb(1, pb(1, m) + pb(2, b"300") + pb(3, 6)))


def dump(ts, kind, b):
    return "11-03 10:00:%06.3f D/NAVDUMP (  1): %s %d %s" % (ts, kind, len(b), b.hex())


def pos(ts, off):
    return "11-03 10:00:%06.3f I/CoNA    ( 2): W [1][x] [ENGINE][POS] \t(1, 2)\tOff : %d \tCyc : 0 \tLane : 1" % (ts, off)


def stub(ts, off, raw):
    return ("11-03 10:00:%06.3f I/CoNA    ( 2): W [1][x] [ENGINE][STUB] \t(1, 2)\tOff : %d \tCyc : 0 \tpathid : 8 \tsubid : 8 \t"
            "repro : 21 \tLane : 1 \tOnRoute : 0 \tRoadClass: 6 \tLinkClass: 1 \tTurnAngle: %d \tRightOfWay: 3" % (ts, off, raw))


log = [pos(1, 50), dump(1.1, "S", state(8)), dump(1.2, "P", position(150)), stub(2, 200, 64), stub(2.1, 200, 192),
       stub(2.2, 201, 2), pos(10, 150), dump(10.1, "P", position(50)), pos(12, 195), dump(12.1, "P", position(5)),
       dump(15, "S", state(7)), pos(16, 210), dump(16.1, "P", position(300)), stub(17, 900, 250)]
check("adasis: maneuver types and meters from the NAVDUMP messages",
      ads.maneuver_type(state(8)) == 8 and ads.step_meters(position(150)) == 150 and ads.step_meters(pb(1, pb(1, pb(2, b"0")))) == 0)
check("adasis: turn angles in degrees (64 right 90, 192 left 90, 250 about -8)",
      ads.angle(64) == 90 and ads.angle(192) == -90 and round(ads.angle(250)) == -8)
rows, stubs = ads.study(log, 25)
check("adasis: the right turn at 200 m has the crossroad's branches, the next turn none",
      len(rows) == 2 and rows[0][0] == 8 and rows[0][1] == 200 and sorted(round(s["angle"]) for s in rows[0][2]) == [-90, 3, 90]
      and rows[1][0] == 7 and rows[1][2] == [])
buf = io.StringIO()
sys.stdout, out = buf, sys.stdout
sys.argv = ["adasis-study.py", os.path.join(OUT, "drive.log")]
open(os.path.join(OUT, "drive.log"), "w").write("\n".join(log) + "\n")
ads.main()
sys.stdout = out
text = buf.getvalue()
check("adasis: the output has no coordinates and no road names",
      "Nowhere" not in text and "(1, 2)" not in text and "side branches [-90, 90]" in text)

sys.exit(fail)
