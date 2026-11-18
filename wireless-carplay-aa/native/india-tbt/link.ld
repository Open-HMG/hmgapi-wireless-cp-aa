SECTIONS { . = 0; .text : { *(.text.entry) *(.text*) *(.rodata*) } /DISCARD/ : { *(.ARM.exidx*) *(.comment*) } }
