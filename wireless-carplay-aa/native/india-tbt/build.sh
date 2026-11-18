#!/bin/sh
set -eu
cd "$(dirname "$0")"
clang-18 --target=armv7a-linux-gnueabi -mthumb -Os -ffreestanding -fno-builtin -fno-stack-protector -fno-unwind-tables -fno-asynchronous-unwind-tables -nostdlib -static -fuse-ld=lld -Wl,-T,link.ld -Wl,--build-id=none -Wl,-e,india_string -o string.elf string.c
llvm-objcopy-18 -O binary --only-section=.text string.elf string.bin
# Entry and table layout are part of the recipe contract.
llvm-nm-18 -n string.elf
[ "$(llvm-nm-18 string.elf | grep ' india_allocator_table$' | cut -d' ' -f1)" = 00000000 ]
[ "$(llvm-nm-18 string.elf | grep ' india_string$' | cut -d' ' -f1)" = 0000000c ]
