import pathlib,struct,hashlib,argparse
p=argparse.ArgumentParser(description="Generate India recipe from private stock and compiled helper")
p.add_argument("stock", type=pathlib.Path)
a=p.parse_args()
r=pathlib.Path(__file__).resolve().parents[2]
source=(r/'recipes/aa-tbt-48ee793a.recipe').read_text()
segment=bytearray.fromhex(next(l.split()[2] for l in source.splitlines() if l.startswith('segment ')))
while len(segment)%4:segment.append(0)
helper_offset=len(segment)
helper=(r/'native/india-tbt/string.bin').read_bytes()
segment.extend(helper)
preferred=0x1c4120
anchor=0x40
for i,target in enumerate([0xad873,0xad7e3,preferred+helper_offset+0xd,0xa7419,0xba3e1]):
 struct.pack_into('<I',segment,anchor+4*i,(target-(preferred+anchor))&0xffffffff)
stock=a.stock.read_bytes()
assert hashlib.md5(stock).hexdigest()=='b794003497e7382b739a231cb1d2bf10'
lines=['# India EV6 260430 Android Auto navigation protocol and TBT bridge.', '# Shared bridge payload from aa-tbt-48ee793a; India ABI helper is built from native/india-tbt/string.c.', 'id aa-tbt-b7940034','file /system/lib/libautoreceiver_infobank.so']
for offset,value in [(0xb60a2,b'\x06'),(0xba3dc,None),(0x144eba,b'\x03')]:
 start=offset-16;end=offset+(4 if value is None else len(value))+16
 old=stock[start:end];new=bytearray(old)
 if value:new[16:16+len(value)]=value
 lines.append(f'verify {start:#x} {len(old)} {hashlib.md5(old).hexdigest()} '+('-' if value is None else hashlib.md5(new).hexdigest()))
for address in [0xad872,0xad7e2,0xa7418,0xa531c,0xa5328,0xa537c]:
 data=stock[address:address+16]
 lines.append(f'verify {address:#x} 16 {hashlib.md5(data).hexdigest()} -')
lines.append(f'segment {preferred:#x} {segment.hex()}')
for i,target in [(0,0xad873),(1,0xad7e3),(3,0xa7419),(4,0xba3e1)]:lines.append(f'rel32 {anchor+4*i:#x} {anchor:#x} {target:#x}')
for i,target in enumerate([0xa531c,0xa5328,0xa537c]):lines.append(f'rel32 {helper_offset+4*i:#x} {helper_offset:#x} {target:#x}')
lines+=['patch 0xb60a2 06','branch 0xba3dc 0x0','patch 0x144eba 03']
(r/'recipes/aa-tbt-b7940034.recipe').write_text('\n'.join(lines)+'\n')
print('helper',hex(helper_offset),len(helper))
