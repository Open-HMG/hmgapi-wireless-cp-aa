/* navigraph's JNI side, for hmgapi.mods.wireless.NaviGraph (in com.mnsoft.navi).
 * Symbols come only from the engine already loaded in this process (dlsym with
 * RTLD_DEFAULT, no dlopen; weak ones from the engine's own symbol table, which 4.4's dlsym
 * skips), and each must belong to libExSLNavi.so (dladdr).
 * Adapted from ch0iiih/HMG_Phone_Projection_TBT navigation-api/native (AGPL-3.0). */
#include <jni.h>
#include "navigraph.c"

struct dl_info { const char *file; void *base; const char *name; void *address; };
extern void *dlsym(void *, const char *);
extern int dladdr(const void *, struct dl_info *);

static struct api engine;
static int bound;
static const char *refused;

static int ends_with(const char *s, const char *tail) {
    if (!s) return 0;
    const char *a = s, *b = tail;
    while (*a) a++;
    while (*b) b++;
    while (b > tail) {
        if (a == s || *--a != *--b) return 0;
    }
    return a == s || a[-1] == '/';
}

/* where the engine is mapped (its ELF header), for the weak symbols dlsym won't return */
static const u8 *engine_base;

/* for the host test: a weak symbol in this library itself */
__attribute__((weak, visibility("default"))) int navigraph_weak_probe(void) { return 7; }

static void *find(const char *name) {
    void *p = dlsym((void *)(uptr)0xffffffffu, name);   /* RTLD_DEFAULT on 32-bit bionic */
    if (!p) {
        /* Android 4.4's dlsym skips STB_WEAK: look in the engine's own symbol table */
        if (!engine_base) {
            void *g = dlsym((void *)(uptr)0xffffffffu, "_ZN17CLifecycleManager12NewComponentEt");
            struct dl_info gi; zero(&gi, sizeof(gi));
            if (g && dladdr(g, &gi) && ends_with(gi.file, "libExSLNavi.so")) engine_base = (const u8 *)gi.base;
        }
        if (engine_base) p = navigraph_elf_lookup(engine_base, name);
        if (!p) return 0;
    }
    struct dl_info info; zero(&info, sizeof(info));
    if (!dladdr(p, &info) || !ends_with(info.file, "libExSLNavi.so")) { refused = name; return 0; }
    return p;
}

/* null when every engine function is there; else what's missing or not the engine's */
JNIEXPORT jstring JNICALL Java_hmgapi_mods_wireless_NaviGraph_nativeBind(JNIEnv *env, jclass cls, jint module) {
    (void)cls;
    refused = 0;
    int status = navigraph_bind(&engine, find, (u16)module);
    bound = status == 0;
    if (bound) return 0;
    if (refused) return (*env)->NewStringUTF(env, refused);
    return (*env)->NewStringUTF(env, engine.missing ? engine.missing : "bind failed");
}

/* {status, parcel, link, direction, overpass, underpass, count, meters along the link, its length,
 *  count x (parcel, link)} */
JNIEXPORT jlongArray JNICALL Java_hmgapi_mods_wireless_NaviGraph_nativeCurrent(JNIEnv *env, jclass cls) {
    (void)cls;
    jlong v[9 + 64]; zero(v, sizeof(v));
    int n = 1;
    if (!bound) v[0] = -20;
    else {
        struct snapshot s;
        v[0] = navigraph_current(&engine, &s);
        if (v[0] == 0) {
            v[1] = (jlong)s.parcel; v[2] = (jlong)s.link; v[3] = s.direction; v[4] = s.overpass; v[5] = s.underpass; v[6] = s.count;
            v[7] = s.pos; v[8] = s.len;
            for (u32 i = 0; i < s.count; i++) { v[9 + 2 * i] = (jlong)s.edges[i].parcel; v[10 + 2 * i] = (jlong)s.edges[i].link; }
            n = 9 + 2 * (int)s.count;
        }
    }
    jlongArray r = (*env)->NewLongArray(env, n);
    if (r) (*env)->SetLongArrayRegion(env, r, 0, n, v);
    return r;
}

/* the node a link reaches in a direction: navigraph_node_rows' values */
JNIEXPORT jlongArray JNICALL Java_hmgapi_mods_wireless_NaviGraph_nativeNode(JNIEnv *env, jclass cls, jlong parcel, jlong link, jint direction) {
    (void)cls;
    long long v[5 + 9 * 15];
    int n;
    if (!bound) { zero(v, sizeof(v)); v[0] = 4; v[1] = -20; n = 5; }
    else n = navigraph_node_rows(&engine, (u64)parcel, (u64)link, (u32)direction, v);
    jlongArray r = (*env)->NewLongArray(env, n);
    if (r) (*env)->SetLongArrayRegion(env, r, 0, n, (const jlong *)v);
    return r;
}
