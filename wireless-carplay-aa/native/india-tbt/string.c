/* India 260430 STL string constructor ABI, implemented from observed layout.
 * The caller initializes the inline buffer pointers. The stock destructor owns
 * deallocation, so allocations must use the same pool and large allocator. */
typedef unsigned int uint32;
typedef unsigned char byte;
struct String { byte storage[16]; byte *end; byte *begin; };

__attribute__((section(".text.entry")))
void india_string(struct String *s, const byte *begin, const byte *end) {
    uint32 *table;
    __asm__("adr %0, india_allocator_table" : "=r"(table));
    uint32 length = (uint32)(end - begin);
    uint32 size = length + 1;
    if (size == 0) {
        uint32 message[] = {0x69736162, 0x74735f63, 0x676e6972, 0};
        ((void (*)(const char *))((byte *)table + table[2]))((const char *)message);
    } else if (size > 16) {
        byte *data;
        if (size > 128)
            data = ((byte *(*)(uint32))((byte *)table + table[0]))(size);
        else
            data = ((byte *(*)(uint32 *))((byte *)table + table[1]))(&size);
        s->begin = s->end = data;
        *(byte **)s->storage = data + size;
    }
    for (uint32 i = 0; i < length; ++i) s->begin[i] = begin[i];
    s->end = s->begin + length;
    *s->end = 0;
}
__asm__(".section .text.entry,\"ax\",%progbits\n"
        ".balign 4\n.global india_allocator_table\n"
        "india_allocator_table:\n.word 0, 0, 0\n");
