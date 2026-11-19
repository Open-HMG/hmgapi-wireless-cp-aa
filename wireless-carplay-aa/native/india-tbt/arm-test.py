#!/usr/bin/env python3
"""Execute the new helper with the actual India destructor under ARM emulation.
Only allocator imports are stubbed. Requires clang-18, lld and qemu-arm-static.
Manufacturer library stays private and is never modified.
"""
import hashlib, pathlib, struct, subprocess, sys, tempfile
here=pathlib.Path(__file__).resolve().parent
stock=pathlib.Path(sys.argv[1]).read_bytes()
assert hashlib.md5(stock).hexdigest()=='b794003497e7382b739a231cb1d2bf10'
helper=(here/'string.bin').read_bytes()
with tempfile.TemporaryDirectory() as tmp:
 p=pathlib.Path(tmp)
 data=bytearray(stock)
 if len(data)<0x1e0000+len(helper):data.extend(bytes(0x1e0000+len(helper)-len(data)))
 for offset in [0x1d0000,0x1e0000]:
  data[offset:offset+len(helper)]=helper
  for i,target in enumerate([0xa531c,0xa5328,0xa537c]):
   struct.pack_into('<I',data,offset+i*4,(target-offset)&0xffffffff)
 (p/'image.bin').write_bytes(data)
 (p/'images.s').write_text('.section .india,"awx",%progbits\n.global india_image\nindia_image:\n.incbin "'+str(p/'image.bin')+'"\n')
 (p/'layout.ld').write_text('ENTRY(_start)\nPHDRS { code PT_LOAD FLAGS(7); india PT_LOAD FLAGS(7); }\nSECTIONS { . = 0x10000; .text : { *(.text*) *(.rodata*) } :code .data : { *(.data*) } :code .bss : { *(.bss*) *(COMMON) } :code . = 0x200000; .india : { *(.india) } :india /DISCARD/ : { *(.ARM.exidx*) *(.ARM.extab*) *(.comment*) } }')
 subprocess.run(['clang-18','--target=armv7-linux-gnueabi','-marm','-mfloat-abi=soft','-Oz','-fno-builtin','-ffreestanding','-fno-stack-protector','-fno-pic','-fno-pie','-nostdlib','-static','-fuse-ld=lld','-Wl,-T,'+str(p/'layout.ld'),str(here/'arm-test.c'),str(p/'images.s'),'-o',str(p/'test')],check=True)
 subprocess.run(['qemu-arm-static',str(p/'test')],check=True)
print('PASS: 20 ARM constructor/destructor cases, two relocated addresses, allocation/free and buffer guards')
