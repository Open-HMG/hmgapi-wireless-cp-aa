#!/usr/bin/env python3
"""Verify the measured stock dispatch defect and build an executable-memory recipe.

Stock binaries stay private. The recipe contains hashes and our own ARM stub only.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess

from paths import REPO

STOCK_MD5 = 'fc7cb675b46a44bd893a1edc054c5e71'


class Elf:
    def __init__(self, path):
        self.data = Path(path).read_bytes()
        h = struct.unpack_from('<16sHHIIIIIHHHHHH', self.data)
        assert h[0][:6] == b'\x7fELF\x01\x01' and h[2] == 40, 'Need little-endian ARM ELF32'
        self.ph = [struct.unpack_from('<8I', self.data, h[5] + i * h[9]) for i in range(h[10])]
        self.sh = [struct.unpack_from('<10I', self.data, h[6] + i * h[11]) for i in range(h[12])]
        names = self.section_data(self.sh[h[13]])
        self.sections = {self.string(names, s[0]): s for s in self.sh}
        self.symbols = {}
        for s in self.sh:
            if s[1] not in (2, 11):
                continue
            strings = self.section_data(self.sh[s[6]])
            for p in range(s[4], s[4] + s[5], s[9]):
                name, value, size, info, other, index = struct.unpack_from('<IIIBBH', self.data, p)
                name = self.string(strings, name)
                if name and index:
                    self.symbols[name] = (value, size)

    @staticmethod
    def string(data, off):
        return data[off:data.index(0, off)].decode()

    def section_data(self, s):
        return self.data[s[4]:s[4] + s[5]]

    def offset(self, addr):
        for s in self.sh:
            if s[1] != 8 and s[3] <= addr < s[3] + s[5]:
                return s[4] + addr - s[3]
        raise ValueError('No file byte at ' + hex(addr))

    def word(self, addr):
        return struct.unpack_from('<I', self.data, self.offset(addr))[0]

    def cstring(self, addr):
        return self.string(self.data, self.offset(addr))


def generate(library, output):
    elf = Elf(library)
    assert hashlib.md5(elf.data).hexdigest() == STOCK_MD5, 'Different stock library'
    table, size = elf.symbols['iAP2CtlHandleMsgFuncList']
    rows = []
    for i in range(size // 24):
        at = table + i * 24
        words = [elf.word(at + 4 * n) for n in range(6)]
        rows.append({'name': elf.cstring(words[0]), 'id': words[1], 'handler': words[4], 'at': at})
    lane = next(r for r in rows if r['name'] == 'LaneGuidanceInformation')
    maneuver = next(r for r in rows if r['name'] == 'RouteGuidanceManeuverUpdate')
    assert lane['id'] == maneuver['id'] == 0x5202
    assert lane['handler'] == elf.symbols['iAP2ControlLaneGuidanceInformation'][0]
    assert maneuver['handler'] == elf.symbols['iAP2ControlRouteGuidanceManeuverUpdate'][0]
    assert not any(r['id'] == 0x5204 for r in rows)
    advertised, n = elf.symbols['iAP2CtlRouteGuidanceMsgs']
    assert any([elf.word(a + 4 * k) for k in range(3)] == [0x5204, 1, 1]
               for a in range(advertised, advertised + n, 12)), 'Lane message not advertised'
    target = lane['at'] + 4
    assert target == 0x31c80 and elf.offset(target) == 0x30c80
    assert any(p[0] == 1 and p[6] & 2 and p[2] <= target < p[2] + p[5] for p in elf.ph)
    assert not any(p[0] == 0x6474e552 and p[2] <= target < p[2] + p[5] for p in elf.ph), 'Target must be outside RELRO'
    entry, length = elf.symbols['iAP2ControlSessionHandleMsg']
    entry &= ~1
    assert entry == 0x1465c and length == 480
    for s in elf.sh:
        if s[1] == 9:  # Relocations would make a code fingerprint differ after load.
            for p in range(s[4], s[4] + s[5], s[9]):
                assert not entry <= struct.unpack_from('<I', elf.data, p)[0] < entry + length
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    obj = output / 'dispatch.o'
    subprocess.run(['clang', '--target=armv7-none-eabi', '-march=armv7-a', '-mthumb', '-c',
                    str(REPO / 'wireless-carplay-aa/native/iap2-lane-dispatch.S'), '-o', str(obj)], check=True)
    stub = Elf(obj)
    assert not any(s[1] == 9 and s[5] for s in stub.sh), 'Stub contains unresolved relocations'
    code = stub.section_data(stub.sections['.text'])
    symbols = {k: v[0] for k, v in stub.symbols.items()}
    displaced = symbols['iap2_lane_original_prologue']
    before = elf.data[elf.offset(entry):elf.offset(entry) + length]
    assert before[:4] == code[displaced:displaced + 4]
    recipe = ('# Correct LaneGuidanceInformation dispatch in process memory only.\n'
              '# Full source MD5 is gated by Wireless before requesting this recipe.\n'
              'id iap2-lanes-fc7cb675\nfile /system/lib/libiap2session.so\n'
              f'verify 0x{entry:x} {length} {hashlib.md5(before).hexdigest()} -\n'
              f'segment 0x40000 {code.hex()}\n'
              f'rel32 0x{symbols["iap2_lane_target_delta"]:x} 0x{symbols["iap2_lane_target_pc"] + 4:x} 0x{target:x}\n'
              f'rel32 0x{symbols["iap2_lane_return_delta"]:x} 0x{symbols["iap2_lane_return_pc"] + 4:x} 0x{entry + 5:x}\n'
              f'branch 0x{entry:x} 0\n')
    recipe_path = output / 'iap2-lanes-fc7cb675.recipe'
    recipe_path.write_text(recipe)
    proof = {'stock_md5': STOCK_MD5, 'stock_sha256': hashlib.sha256(elf.data).hexdigest(),
             'dispatch_table': table, 'original_lane_id': lane['id'], 'correct_lane_id': 0x5204,
             'target_virtual_address': target, 'target_file_offset': elf.offset(target),
             'lane_handler': lane['handler'], 'maneuver_handler': maneuver['handler'],
             'advertises_lane_message': True, 'target_writable_and_outside_relro': True,
             'hook_entry': entry, 'hook_fingerprint_bytes': length, 'stub_bytes': len(code),
             'stub_sha256': hashlib.sha256(code).hexdigest(),
             'assembly_sha256': hashlib.sha256((REPO / 'wireless-carplay-aa/native/iap2-lane-dispatch.S').read_bytes()).hexdigest(),
             'recipe_sha256': hashlib.sha256(recipe.encode()).hexdigest(), 'stub_symbols': symbols,
             'stock_file_written': False, 'vehicle_test': 'pending'}
    (output / 'dispatch-proof.json').write_text(json.dumps(proof, indent=2) + '\n')
    print(recipe_path)
    return proof


if __name__ == '__main__':
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--library', type=Path, required=True)
    ap.add_argument('--output', type=Path, default=REPO / 'out/palisade/iap2-lane-fix')
    args = ap.parse_args()
    generate(args.library, args.output)
