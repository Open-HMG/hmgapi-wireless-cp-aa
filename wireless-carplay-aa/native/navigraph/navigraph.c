/* navigraph: read the car navigation engine's road graph around the car, read only.
 *
 * Runs inside com.mnsoft.navi, next to its engine (libExSLNavi.so), and only calls the
 * engine's own exported functions: the map-matched position (the same calls as
 * Navi::VI::CService::GetVehiclePosition: component 0x7200, its vtable slot 0x20,
 * CPositionInfo::GetDisplayPosition), the links joined at the node ahead
 * (CEntityLink::GetNodeId, CEntityNode::*), and each link's shape end points
 * (CEntityLink::GetPosition). It never starts, loads or routes anything.
 *
 * Adapted from ch0iiih/HMG_Phone_Projection_TBT navigation-api/native (AGPL-3.0):
 * stock_graph_probe.c and stock_graph_geometry.c. Changes: no address anchor or
 * fixed-offset fallbacks (every symbol must be exported), the engine's mArray module tag
 * comes from the engine profile (Korean 260902: 0x2100; US 260507: 0x9550, from its own
 * CInformation callers), every resolved pointer must sit in libExSLNavi.so.
 *
 * Checked on the US 260507 engine (libExSLNavi.so md5 caa8d4c2..., from its own code):
 * component 0x7200 and vtable slot 0x20 as GetVehiclePosition uses them; stConnectLink
 * stride 0x58 (mArray<stConnectLink>::operator[]); mArray {items, count, capacity, grow,
 * u16 module} with grow 16; CVehiclePosition (~0x120 bytes), CEntityLink and CEntityNode
 * (~0x28) well inside the 1 KB buffers.
 *
 * No libc: host-testable (test/navigraph_test.c), the JNI part is navigraph_jni.c.
 */
typedef unsigned int u32;
typedef unsigned short u16;
typedef unsigned char u8;
typedef unsigned long long u64;
typedef __UINTPTR_TYPE__ uptr;
typedef void *(*resolver)(const char *);

struct connection { u64 parcel, link; };
struct snapshot { u64 parcel, link; u32 direction, overpass, underpass, count; struct connection edges[32]; };
union storage { u64 align; u8 bytes[1024]; };
struct array { void *items; u32 count, capacity, grow; u16 module, pad; };
struct point_i32 { int x, y; };
struct geometry_row { u64 parcel, link; int status; u32 length, point_count, overpass, underpass; struct point_i32 points[4]; };

struct api {
    void *(*component_new)(u16); void (*component_delete)(u16, void *);
    void (*position_ctor)(void *); void (*position_dtor)(void *);
    void (*display_position)(void *, void *, u8);
    u64 (*parcel)(void *); u64 (*link)(void *); u32 (*direction)(void *);
    void *(*allocator_default)(void); void (*allocator_ctor)(void *, void *);
    void (*entity_ctor)(void *, const void *); void (*entity_dtor)(void *);
    int (*initialize)(void *, u64, u64, u16);
    int (*spawn)(void *, struct array *, u8); void (*array_clear)(struct array *);
    u32 (*overpass)(void *); u32 (*underpass)(void *);
    /* geometry */
    int (*position)(void *, struct array *); u32 (*length)(void *); void (*points_clear)(struct array *);
    u64 (*node_id)(void *, u8);
    void (*node_ctor)(void *, const void *); void (*node_dtor)(void *);
    int (*node_init)(void *, u64, u64, u32, u16);
    u32 (*node_adjacent)(void *); u32 (*node_count)(void *);
    u64 (*node_map)(void *); u64 (*node_link)(void *, int);
    u16 module;   /* the engine's mArray module tag (profile) */
    const char *missing;
};

static void zero(void *p, u32 n) { u8 *b = p; while (n--) *b++ = 0; }

#define BIND(field, name) do { *(void **)(&a->field) = find(name); if (!a->field) { a->missing = name; return -21; } } while (0)

/* every engine function the reader calls; -21 (a->missing names it) if one isn't there */
int navigraph_bind(struct api *a, resolver find, u16 module) {
    if (!a || !find) return -1;
    zero(a, sizeof(*a));
    a->module = module;
    BIND(component_new, "_ZN17CLifecycleManager12NewComponentEt");
    BIND(component_delete, "_ZN17CLifecycleManager15DeleteComponentEtP10CComponent");
    BIND(position_ctor, "_ZN16CVehiclePositionC2Ev");
    BIND(position_dtor, "_ZN16CVehiclePositionD2Ev");
    BIND(display_position, "_ZN13CPositionInfo18GetDisplayPositionER16CVehiclePositionb");
    BIND(parcel, "_ZN16CVehiclePosition11GetParcelIdEv");
    BIND(link, "_ZN16CVehiclePosition9GetLinkIdEv");
    BIND(direction, "_ZN16CVehiclePosition12GetDirectionEv");
    BIND(allocator_default, "_ZN14mByteAllocator19GetDefaultAllocatorEv");
    BIND(allocator_ctor, "_ZN16mEntityAllocatorC2EP14mByteAllocator");
    BIND(entity_ctor, "_ZN11CEntityLinkC2ERK16mEntityAllocator");
    BIND(entity_dtor, "_ZN11CEntityLinkD2Ev");
    BIND(initialize, "_ZN11CEntityLink10InitializeEyyt");
    BIND(spawn, "_ZN11CEntityLink5SpawnEP6mArrayI13stConnectLinkEh");
    BIND(array_clear, "_ZN6mArrayI13stConnectLinkE5clearEv");
    BIND(overpass, "_ZN11CEntityLink15GetAttrOverPassEv");
    BIND(underpass, "_ZN11CEntityLink16GetAttrUnderPassEv");
    BIND(position, "_ZN11CEntityLink11GetPositionEP6mArrayI9mLonLat_tIiEE");
    BIND(length, "_ZN11CEntityLink9GetLengthEv");
    BIND(points_clear, "_ZN6mArrayI9mLonLat_tIiEE5clearEv");
    BIND(node_id, "_ZN11CEntityLink9GetNodeIdEb");
    BIND(node_ctor, "_ZN11CEntityNodeC2ERK16mEntityAllocator");
    BIND(node_dtor, "_ZN11CEntityNodeD2Ev");
    BIND(node_init, "_ZN11CEntityNode10InitializeEyyjt");
    BIND(node_adjacent, "_ZN11CEntityNode13GetAttrAdjestEv");
    BIND(node_count, "_ZN11CEntityNode12GetLinkCountEv");
    BIND(node_map, "_ZN11CEntityNode11GetConMapIdEv");
    BIND(node_link, "_ZN11CEntityNode12GetConLinkIdEi");
    return 0;
}

/* ---- weak symbols: the head unit's bionic (Android 4.4) dlsym returns only STB_GLOBAL symbols,
 * and two of the engine's functions are template instances, STB_WEAK
 * (_ZN6mArrayI13stConnectLinkE5clearEv, _ZN6mArrayI9mLonLat_tIiEE5clearEv: "engine ... lacks
 * _ZN6mArrayI13stConnectLinkE5clearEv" on a US Ioniq 5, 2026-11-21, though the file exports it).
 * navigraph_elf_lookup reads the loaded engine's own dynamic symbol table (DT_HASH, DT_SYMTAB,
 * DT_STRTAB, as the 4.4 linker itself does) and takes a defined global or weak symbol. */
struct elf32_ehdr { u8 ident[16]; u16 type, machine; u32 version, entry, phoff, shoff, flags;
                    u16 ehsize, phentsize, phnum, shentsize, shnum, shstrndx; };
struct elf32_phdr { u32 type, offset, vaddr, paddr, filesz, memsz, flags, align; };
struct elf32_dyn { int tag; u32 val; };
struct elf32_sym { u32 name, value, size; u8 info, other; u16 shndx; };

static u32 elf_hash(const char *s) {
    u32 h = 0, g;
    while (*s) {
        h = (h << 4) + (u8)*s++;
        g = h & 0xf0000000u;
        if (g) h ^= g >> 24;
        h &= ~g;
    }
    return h;
}

/* n % d without libgcc's __aeabi_uidivmod (no libc here; ARMv7-A has no divide instruction) */
static u32 umod32(u32 n, u32 d) {
    u32 r = 0;
    for (int i = 31; i >= 0; i--) {
        r = (r << 1) | ((n >> i) & 1);
        if (r >= d) r -= d;
    }
    return r;
}

static int same_name(const char *a, const char *b, u32 limit) {
    while (limit-- && *a && *a == *b) { a++; b++; }
    return *a == *b;
}

/* a defined STB_GLOBAL or STB_WEAK symbol of the ELF32 library mapped with its first page
 * (ELF and program headers) at base; 0 if it isn't there */
void *navigraph_elf_lookup(const u8 *base, const char *name) {
    if (!base || !name) return 0;
    const struct elf32_ehdr *eh = (const struct elf32_ehdr *)base;
    if (eh->ident[0] != 0x7f || eh->ident[1] != 'E' || eh->ident[2] != 'L' || eh->ident[3] != 'F' || eh->ident[4] != 1) return 0;
    if (eh->phentsize != sizeof(struct elf32_phdr) || eh->phnum == 0 || eh->phnum > 64 || eh->phoff > 4096) return 0;
    const struct elf32_phdr *ph = (const struct elf32_phdr *)(base + eh->phoff);
    u32 min = ~0u;
    const struct elf32_phdr *dyn = 0;
    for (u32 i = 0; i < eh->phnum; i++) {
        if (ph[i].type == 1 && (ph[i].vaddr & ~0xfffu) < min) min = ph[i].vaddr & ~0xfffu;   /* PT_LOAD */
        if (ph[i].type == 2) dyn = ph + i;                                                       /* PT_DYNAMIC */
    }
    if (!dyn || min == ~0u) return 0;
    const u8 *bias = base - min;
    const struct elf32_dyn *d = (const struct elf32_dyn *)(bias + dyn->vaddr);
    const u32 *hash = 0;
    const struct elf32_sym *syms = 0;
    const char *strs = 0;
    for (u32 i = 0; i < 512 && d[i].tag != 0; i++) {
        if (d[i].tag == 4) hash = (const u32 *)(bias + d[i].val);                     /* DT_HASH */
        else if (d[i].tag == 5) strs = (const char *)(bias + d[i].val);               /* DT_STRTAB */
        else if (d[i].tag == 6) syms = (const struct elf32_sym *)(bias + d[i].val);   /* DT_SYMTAB */
    }
    if (!hash || !syms || !strs) return 0;
    u32 nbucket = hash[0], nchain = hash[1];
    if (!nbucket || nbucket > (1u << 20) || nchain > (1u << 22)) return 0;
    const u32 *bucket = hash + 2, *chain = bucket + nbucket;
    u32 steps = 0;
    for (u32 i = bucket[umod32(elf_hash(name), nbucket)]; i && i < nchain && steps < nchain; i = chain[i], steps++) {
        const struct elf32_sym *sym = syms + i;
        u32 bind = sym->info >> 4;
        if ((bind == 1 || bind == 2) && sym->shndx != 0 && sym->value && same_name(strs + sym->name, name, 4096))
            return (void *)(bias + sym->value);
    }
    return 0;
}

/* the map-matched link and the links the car can go on to from it */
int navigraph_current(struct api *a, struct snapshot *out) {
    if (!a || !out) return -1;
    zero(out, sizeof(*out));
    union storage position, allocator, entity;
    zero(&position, sizeof(position)); zero(&allocator, sizeof(allocator)); zero(&entity, sizeof(entity));
    a->position_ctor(&position);
    void *component = a->component_new(0x7200);
    if (!component) { a->position_dtor(&position); return -2; }
    void **vt = *(void ***)component;
    void *(*position_info)(void *) = (void *(*)(void *))vt[0x20 / sizeof(void *)];
    void *info = position_info(component);
    if (!info) { a->component_delete(0x7200, component); a->position_dtor(&position); return -3; }
    a->display_position(info, &position, 0);
    out->parcel = a->parcel(&position); out->link = a->link(&position); out->direction = a->direction(&position);
    a->component_delete(0x7200, component); a->position_dtor(&position);
    if (out->parcel == ~(u64)0 || out->link == ~(u64)0) return -4;
    a->allocator_ctor(&allocator, a->allocator_default());
    a->entity_ctor(&entity, &allocator);
    if (a->initialize(&entity, out->parcel, out->link, 0) < 0) { a->entity_dtor(&entity); return -5; }
    out->overpass = a->overpass(&entity); out->underpass = a->underpass(&entity);
    struct array edges; zero(&edges, sizeof(edges)); edges.grow = 16; edges.module = a->module;
    a->spawn(&entity, &edges, (u8)out->direction);
    int status = 0;
    if (edges.count > 32 || (edges.count && !edges.items)) status = -6;
    else {
        out->count = edges.count;
        for (u32 i = 0; i < edges.count; i++) {
            u8 *entry = (u8 *)edges.items + i * 0x58;   /* stConnectLink */
            out->edges[i].parcel = *(u64 *)(entry + 0);
            out->edges[i].link = *(u64 *)(entry + 8);
        }
    }
    a->array_clear(&edges); a->entity_dtor(&entity);
    if (status) zero(out, sizeof(*out));
    return status;
}

/* a link's length, attributes and shape end points (the first two and last two) */
int navigraph_read(struct api *a, u64 parcel, u64 link, struct geometry_row *out) {
    zero(out, sizeof(*out)); out->parcel = parcel; out->link = link;
    if (parcel == ~(u64)0 || link == ~(u64)0) return out->status = -4;
    union storage allocator, entity; zero(&allocator, sizeof(allocator)); zero(&entity, sizeof(entity));
    a->allocator_ctor(&allocator, a->allocator_default()); a->entity_ctor(&entity, &allocator);
    if (a->initialize(&entity, parcel, link, 0) != 0) { a->entity_dtor(&entity); return out->status = -5; }
    out->length = a->length(&entity);
    out->overpass = a->overpass(&entity); out->underpass = a->underpass(&entity);
    struct array points; zero(&points, sizeof(points)); points.grow = 16; points.module = a->module;
    int status = a->position(&entity, &points);
    if (status != 0) out->status = -30;
    else if (!points.items || points.count < 2 || points.count > 4096) out->status = -31;
    else {
        out->point_count = points.count;
        struct point_i32 *p = (struct point_i32 *)points.items;
        out->points[0] = p[0]; out->points[1] = p[1];
        out->points[2] = p[points.count - 2]; out->points[3] = p[points.count - 1];
    }
    a->points_clear(&points); a->entity_dtor(&entity);
    return out->status;
}

/* every link at the node a link reaches in a direction (0/1), the link itself among them */
int navigraph_node(struct api *a, u64 parcel, u64 link, u32 direction, struct connection *roads, u32 *count) {
    *count = 0;
    if (direction > 1 || parcel == ~(u64)0 || link == ~(u64)0) return -34;
    union storage allocator, entity, node;
    zero(&allocator, sizeof(allocator)); zero(&entity, sizeof(entity)); zero(&node, sizeof(node));
    a->allocator_ctor(&allocator, a->allocator_default()); a->entity_ctor(&entity, &allocator);
    if (a->initialize(&entity, parcel, link, 0) != 0) { a->entity_dtor(&entity); return -5; }
    u64 node_id = a->node_id(&entity, (u8)direction);
    a->node_ctor(&node, &allocator);
    int status = -35;
    if (node_id != ~(u64)0 && a->node_init(&node, parcel, node_id, 0, 0) == 0) {
        if (a->node_adjacent(&node)) status = -36;   /* joins another parcel: not handled */
        else {
            u32 n = a->node_count(&node); u64 map = a->node_map(&node); int found = 0;
            if (n < 1 || n > 8 || map == ~(u64)0) status = -37;
            else {
                for (u32 i = 0; i < n; i++) {
                    roads[i].parcel = map; roads[i].link = a->node_link(&node, (int)i);
                    if (roads[i].link == ~(u64)0) break;
                    if (map == parcel && roads[i].link == link) found = 1;
                    (*count)++;
                }
                status = *count == n && found ? 0 : -38;
            }
        }
    }
    a->node_dtor(&node); a->entity_dtor(&entity);
    if (status) *count = 0;
    return status;
}

/* the node ahead as rows: {4, status, rows, roads at the node, 1, rows x 15}; a row is
 * parcel, link, status, length, point count, 4 end points (x, y), overpass, underpass;
 * row 0 the current link. values holds 5 + 9 * 15. Returns the number of values. */
int navigraph_node_rows(struct api *a, u64 parcel, u64 link, u32 direction, long long *values) {
    zero(values, (5 + 9 * 15) * sizeof(long long));
    values[0] = 4;
    struct connection roads[8]; u32 count = 0;
    int status = navigraph_node(a, parcel, link, direction, roads, &count);
    values[1] = status;
    if (status) return 5;
    int rows = 1 + (int)count;
    values[2] = rows; values[3] = count; values[4] = 1;
    for (int i = 0; i < rows; i++) {
        struct geometry_row row;
        navigraph_read(a, i == 0 ? parcel : roads[i - 1].parcel, i == 0 ? link : roads[i - 1].link, &row);
        long long *v = values + 5 + i * 15;
        v[0] = (long long)row.parcel; v[1] = (long long)row.link; v[2] = row.status; v[3] = row.length; v[4] = row.point_count;
        for (int j = 0; j < 4; j++) { v[5 + j * 2] = row.points[j].x; v[6 + j * 2] = row.points[j].y; }
        v[13] = row.overpass; v[14] = row.underpass;
    }
    return 5 + rows * 15;
}
