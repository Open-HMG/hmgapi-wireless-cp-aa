/* Host test of native/navigraph/navigraph.c against a mock engine: the position calls in
 * GetVehiclePosition's order, the profile's mArray module tag on every array, edge and
 * end-point copying, the node rows' layout, the refusals, and that every engine object
 * made is destroyed. Adapted from ch0iiih's test_stock_graph_probe.c (AGPL-3.0). */
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "../../native/navigraph/navigraph.c"

/* an ELF32 library laid out as the loader maps it (each PT_LOAD at its vaddr) */
static u8 *map_elf(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) return 0;
    fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    u8 *file = malloc((size_t)n);
    assert(fread(file, 1, (size_t)n, f) == (size_t)n);
    fclose(f);
    const struct elf32_ehdr *eh = (const struct elf32_ehdr *)file;
    const struct elf32_phdr *ph = (const struct elf32_phdr *)(file + eh->phoff);
    u32 top = 0;
    for (u32 i = 0; i < eh->phnum; i++) if (ph[i].type == 1 && ph[i].vaddr + ph[i].memsz > top) top = ph[i].vaddr + ph[i].memsz;
    u8 *image = calloc(1, top + 4096);
    for (u32 i = 0; i < eh->phnum; i++) if (ph[i].type == 1) memcpy(image + ph[i].vaddr, file + ph[i].offset, ph[i].filesz);
    free(file);
    return image;
}

static const u8 *engine_image;
static void *elf_find(const char *name) { return navigraph_elf_lookup(engine_image, name); }

#define MODULE 0x9550
static int mode, components, positions, entities, nodes, arrays, point_arrays;
static int info_value, allocator_value;
static void *vtable[16];
static void *component[1];
static unsigned char edges[3 * 0x58];

static void *new_component(u16 id) { assert(id == 0x7200); return mode == 1 ? 0 : (components++, component); }
static void delete_component(u16 id, void *c) { assert(id == 0x7200 && c == component); components--; }
static void *get_info(void *c) { assert(c == component); return mode == 2 ? 0 : &info_value; }
static void position_ctor(void *p) { zero(p, 1024); positions++; }
static void position_dtor(void *p) { assert(p); positions--; }
static void display(void *info, void *p, u8 raw) { assert(info == &info_value && p && raw == 0); }
static u64 parcel(void *p) { (void)p; return mode == 3 ? ~(u64)0 : 100; }
static u64 link(void *p) { (void)p; return 7; }
static u32 direction(void *p) { (void)p; return 1; }
static u32 link_pos(void *p, u8 raw) { assert(p && raw == 0); return 40; }
static u32 link_len(void *p, u8 raw) { assert(p && raw == 0); return 150; }
static void *allocator_default(void) { return &allocator_value; }
static void allocator_ctor(void *a, void *b) { assert(a && b == &allocator_value); }
static void entity_ctor(void *e, const void *a) { assert(e && a); entities++; }
static void entity_dtor(void *e) { assert(e); entities--; }
static int initialize(void *e, u64 p, u64 l, u16 t) { assert(e && p == 100 && t == 0); return mode == 4 || (mode == 6 && l == 9) ? -1 : 0; }
static int spawn(void *e, struct array *a, u8 d) {
    assert(e && d == 1 && a->grow == 16 && a->module == MODULE);
    a->items = edges; a->count = mode == 5 ? 33 : 2; arrays++; return 0;
}
static void array_clear(struct array *a) { assert(a); if (a->items) arrays--; }
static u32 overpass(void *e) { (void)e; return 0; }
static u32 underpass(void *e) { (void)e; return 1; }
/* each link: a straight line of 3 points from the node (0, 0) out to a direction */
static struct point_i32 pts[3];
static int position(void *e, struct array *a) {
    assert(e && a->grow == 16 && a->module == MODULE);
    a->items = pts; a->count = 3; point_arrays++; return 0;
}
static u32 length(void *e) { (void)e; return 120; }
static void points_clear(struct array *a) { if (a->items) point_arrays--; }
static u64 node_id(void *e, u8 d) { assert(e && d == 1); return mode == 7 ? ~(u64)0 : 55; }
static void node_ctor(void *n, const void *a) { assert(n && a); nodes++; }
static void node_dtor(void *n) { assert(n); nodes--; }
static int node_init(void *n, u64 p, u64 id, u32 x, u16 y) { assert(n && p == 100 && id == 55 && !x && !y); return 0; }
static u32 node_adjacent(void *n) { (void)n; return mode == 8; }
static u32 node_count(void *n) { (void)n; return mode == 9 ? 9 : 3; }
static u64 node_map(void *n) { (void)n; return 100; }
static u64 node_link(void *n, int i) { (void)n; static const u64 l[3] = { 7, 8, 9 }; return mode == 10 && i == 0 ? 6 : l[i]; }

static void *resolve(const char *name) {
    /* the binder must ask by mangled name; hand back the mock with that role */
    static const struct { const char *n; void *f; } t[] = {
        { "_ZN17CLifecycleManager12NewComponentEt", (void *)new_component },
        { "_ZN17CLifecycleManager15DeleteComponentEtP10CComponent", (void *)delete_component },
        { "_ZN16CVehiclePositionC2Ev", (void *)position_ctor }, { "_ZN16CVehiclePositionD2Ev", (void *)position_dtor },
        { "_ZN13CPositionInfo18GetDisplayPositionER16CVehiclePositionb", (void *)display },
        { "_ZN16CVehiclePosition11GetParcelIdEv", (void *)parcel }, { "_ZN16CVehiclePosition9GetLinkIdEv", (void *)link },
        { "_ZN16CVehiclePosition12GetDirectionEv", (void *)direction },
        { "_ZN16CVehiclePosition10GetLinkPosEb", (void *)link_pos }, { "_ZN16CVehiclePosition10GetLinkLenEb", (void *)link_len },
        { "_ZN14mByteAllocator19GetDefaultAllocatorEv", (void *)allocator_default },
        { "_ZN16mEntityAllocatorC2EP14mByteAllocator", (void *)allocator_ctor },
        { "_ZN11CEntityLinkC2ERK16mEntityAllocator", (void *)entity_ctor }, { "_ZN11CEntityLinkD2Ev", (void *)entity_dtor },
        { "_ZN11CEntityLink10InitializeEyyt", (void *)initialize },
        { "_ZN11CEntityLink5SpawnEP6mArrayI13stConnectLinkEh", (void *)spawn },
        { "_ZN6mArrayI13stConnectLinkE5clearEv", (void *)array_clear },
        { "_ZN11CEntityLink15GetAttrOverPassEv", (void *)overpass }, { "_ZN11CEntityLink16GetAttrUnderPassEv", (void *)underpass },
        { "_ZN11CEntityLink11GetPositionEP6mArrayI9mLonLat_tIiEE", (void *)position },
        { "_ZN11CEntityLink9GetLengthEv", (void *)length }, { "_ZN6mArrayI9mLonLat_tIiEE5clearEv", (void *)points_clear },
        { "_ZN11CEntityLink9GetNodeIdEb", (void *)node_id },
        { "_ZN11CEntityNodeC2ERK16mEntityAllocator", (void *)node_ctor }, { "_ZN11CEntityNodeD2Ev", (void *)node_dtor },
        { "_ZN11CEntityNode10InitializeEyyjt", (void *)node_init }, { "_ZN11CEntityNode13GetAttrAdjestEv", (void *)node_adjacent },
        { "_ZN11CEntityNode12GetLinkCountEv", (void *)node_count }, { "_ZN11CEntityNode11GetConMapIdEv", (void *)node_map },
        { "_ZN11CEntityNode12GetConLinkIdEi", (void *)node_link },
    };
    for (unsigned i = 0; i < sizeof(t) / sizeof(t[0]); i++) {
        const char *a = t[i].n, *b = name;
        while (*a && *a == *b) { a++; b++; }
        if (*a == *b) return mode == 11 && i == 22 ? 0 : t[i].f;
    }
    return 0;
}

static int same(const char *a, const char *b) {
    if (!a || !b) return 0;
    while (*a && *a == *b) { a++; b++; }
    return *a == *b;
}

static int clean(void) { return !components && !positions && !entities && !nodes && !arrays && !point_arrays; }

int main(int argc, char **argv) {
    struct api a;
    if (argc > 1) {
        /* our own library (ARM ELF32): a global and a weak symbol, by the loaded image's symbol table */
        u8 *own = map_elf(argv[1]);
        assert(own);
        void *jni = navigraph_elf_lookup(own, "Java_hmgapi_mods_wireless_NaviGraph_nativeBind");
        void *weak = navigraph_elf_lookup(own, "navigraph_weak_probe");
        assert(jni && ((uptr)jni & 1) && weak && ((uptr)weak & 1));
        assert(!navigraph_elf_lookup(own, "navigraph_no_such_symbol") && !navigraph_elf_lookup(own, "dlsym"));
        static u8 junk[4096];
        assert(!navigraph_elf_lookup(junk, "navigraph_weak_probe") && !navigraph_elf_lookup(0, "x"));
        puts("ok    elf lookup: a global and a weak (STB_WEAK) symbol by name, Thumb bit kept; absent, undefined, not ELF: none");
        free(own);
    }
    if (argc > 2) {
        /* the car's engine (a private stock file): every one of the 28, the two weak mArray clears too */
        u8 *eng = map_elf(argv[2]);
        assert(eng);
        engine_image = eng;
        assert(navigraph_bind(&a, elf_find, MODULE) == 0);
        puts("ok    elf lookup on the engine given: all 30 functions bound, mArray<stConnectLink>::clear and mArray<mLonLat_t>::clear (weak) included");
        free(eng);
    }
    component[0] = vtable; vtable[0x20 / sizeof(void *)] = (void *)get_info;
    *(u64 *)(edges + 0) = 100; *(u64 *)(edges + 8) = 8;
    *(u64 *)(edges + 0x58) = 100; *(u64 *)(edges + 0x60) = 9;
    pts[0].x = 0; pts[0].y = 0; pts[1].x = 10; pts[1].y = 20; pts[2].x = 30; pts[2].y = 40;

    mode = 11;
    assert(navigraph_bind(&a, resolve, MODULE) == -21 && same(a.missing, "_ZN11CEntityLink9GetNodeIdEb"));
    mode = 0;
    assert(navigraph_bind(&a, resolve, MODULE) == 0 && a.module == MODULE);
    puts("ok    bind: all 30 engine functions by name; a missing one is named");

    struct snapshot s;
    assert(navigraph_current(&a, &s) == 0 && s.parcel == 100 && s.link == 7 && s.direction == 1 && s.underpass == 1);
    assert(s.pos == 40 && s.len == 150);
    assert(s.count == 2 && s.edges[0].link == 8 && s.edges[1].link == 9 && clean());
    puts("ok    current: the matched link, 40 m along its 150, and its 2 onward links (stride 0x58), the profile's module tag, all freed");
    for (mode = 1; mode <= 5; mode++) assert(navigraph_current(&a, &s) < 0 && s.count == 0 && clean());
    mode = 0;
    puts("ok    current: no component, no position info, no position, a link that won't load, 33 links: refused, all freed");

    long long v[5 + 9 * 15];
    int n = navigraph_node_rows(&a, 100, 7, 1, v);
    assert(n == 5 + 4 * 15 && v[0] == 4 && v[1] == 0 && v[2] == 4 && v[3] == 3 && v[4] == 1);
    long long *r = v + 5;
    assert(r[0] == 100 && r[1] == 7 && r[2] == 0 && r[3] == 120 && r[4] == 3);
    assert(r[5] == 0 && r[6] == 0 && r[7] == 10 && r[8] == 20 && r[9] == 10 && r[10] == 20 && r[11] == 30 && r[12] == 40);
    assert(r[13] == 0 && r[14] == 1 && v[5 + 3 * 15 + 1] == 9 && clean());
    puts("ok    node: 4 rows (the link, then the node's 3 links), 15 values each, first two and last two points, all freed");
    mode = 6;
    n = navigraph_node_rows(&a, 100, 7, 1, v);
    assert(n == 5 + 4 * 15 && v[5 + 3 * 15 + 2] == -5 && clean());
    puts("ok    node: a link that won't load keeps its row with status -5");
    int want[] = { 0, 0, 0, 0, 0, 0, 0, -35, -36, -37, -38 };
    for (mode = 7; mode <= 10; mode++) {
        n = navigraph_node_rows(&a, 100, 7, 1, v);
        assert(n == 5 && v[1] == want[mode] && v[2] == 0 && clean());
    }
    mode = 0;
    assert(navigraph_node_rows(&a, 100, 7, 2, v) == 5 && v[1] == -34);
    puts("ok    node: no node, a node joining another parcel, 9 links, the link not at its node, a bad direction: refused");
    return 0;
}
