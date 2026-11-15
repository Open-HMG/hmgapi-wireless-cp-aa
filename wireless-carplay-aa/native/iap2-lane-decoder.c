/* Bounded replacement for the measured unfinished lane-message handler.
 * No global state, libc, JNI, file writes, or changes to route/maneuver parsing.
 * Input is iAP2 big-endian length/id TLVs. Output is the stock native-notification
 * layout consumed by libIap2Acc.so. One count-1 group item per lane is intentional:
 * that converter recurses once per item, not once per advertised group-array value.
 */
typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;
typedef struct { u32 kind, capacity, used; u8 *data; } Notify;
extern void *lane_context(void *session);
extern Notify *lane_allocate(int size, int count);
extern void lane_notify(void *context, int event, int a, int b, Notify *packet);
extern void lane_log(const char *format, ...);

/* SDK error logger is independent of the debug mask. Numeric fields only. */
static void trace(int code, int a, int b, int c) {
    lane_log("PalisadeLane code=%d a=%d b=%d c=%d\n",code,a,b,c);
}

/* This exact stock Java initializer supplies fields 1..6, leaving lane fields
 * 7/8 absent from the used-field mask. The stock SDK already supports both.
 * The enclosing recipe fingerprints the record finalizer and relevant getters.
 * Advertise six lane events, distinct from the eight lanes within each event. */
void palisade_lane_capability(void *context) {
    if (!context) return;
    u8 *record=*(u8**)((u8*)context+0x49628);
    if (!record) return;
    u32 *description=(u32*)(record+0x1018),*capacity=(u32*)(record+0x101c);
    u32 *mask=(u32*)(record+0x1020),old=*mask;
    *description=64;*capacity=6;*mask=old|0x180;
    trace(3,(int)old,(int)*mask,(int)*capacity);
}

enum { MAX_INPUT=4096, MAX_LANES=8, MAX_COMPONENTS=16, MAX_ANGLES=16, MAX_OUTPUT=4096 };
typedef struct { const u8 *at; u32 left; } Cursor;
typedef struct { u32 id, size; const u8 *data; } Field;
typedef struct { int value[4][MAX_ANGLES]; u32 count[4]; } Lane;
typedef struct { u8 data[MAX_OUTPUT]; u32 used, count; int failed; } Writer;

static u32 be16(const u8 *p) { return ((u32)p[0]<<8)|p[1]; }
/* -1 malformed, 0 exhausted, 1 complete field; never read a partial header. */
static int field(Cursor *c, Field *f) {
    if (!c->left) return 0;
    if (c->left<4) return -1;
    u32 n=be16(c->at);
    if (n<4 || n>c->left) return -1;
    f->id=be16(c->at+2); f->data=c->at+4; f->size=n-4;
    c->at+=n; c->left-=n; return 1;
}

/* Observe the phone's route selection before the stock scalar-U16 parser can
 * discard a packed second index. No allocation, writes, normalization or string
 * payloads. Codes 5/6/7 mean selection / packet summary / incomplete diagnostic.
 * Header and TLV bounds apply only to this observation; stock input is untouched. */
void palisade_route_trace(const u8 *packet, int length) {
    if (!packet || length<6 || length>MAX_INPUT) return;
    if (packet[0]!=0x40 || packet[1]!=0x40 || be16(packet+4)!=0x5201) return;
    u32 declared=be16(packet+2);
    if (declared<6 || declared>(u32)length) return;
    Cursor c={packet+6,declared-6};Field f;
    int component=-1,rc=0;u32 seen=0,selections=0;
    while ((rc=field(&c,&f))>0) {
        seen++;
        if (f.id==0 && component<0 && f.size>=2) component=(int)be16(f.data);
        if (f.id==13 && selections++<16)
            trace(5,(int)f.size,f.size>=2?(int)be16(f.data):-1,
                  f.size>=4?(int)be16(f.data+2):-1);
    }
    trace(6,(int)declared,component,(int)seen);
    if (rc<0) trace(7,1,(int)c.left,(int)seen);
    if (selections>16) trace(7,2,(int)(selections-16),(int)seen);
}

static int lane_read(Lane *l, const u8 *p, u32 n, int *detail) {
    for (u32 k=0;k<4;k++) l->count[k]=0;
    Cursor c={p,n}; Field f; int rc;
    while ((rc=field(&c,&f))>0) {
        if (f.id>3) continue;
        *detail=(int)((f.id<<16)|f.size);
        u32 width=f.id==1?1:2;
        if (f.size%width) return -31;
        u32 count=f.size/width;
        if (count>MAX_ANGLES-l->count[f.id]) return -32;
        if (f.id<2 && l->count[f.id]+count>1) return -33;
        for (u32 j=0;j<count;j++) {
            int v=width==1?f.data[j]:(int)be16(f.data+j*2);
            if (f.id>=2) {
                if (v&0x8000) v-=65536;
                if (v < -180 || v>360) { *detail=v;return -34; }
            }
            l->value[f.id][l->count[f.id]++]=v;
        }
    }
    if (rc<0) { *detail=(int)c.left;return -30; }
    return 1;
}
static void word_at(u8 *p, u32 v) {
    p[0]=(u8)v;p[1]=(u8)(v>>8);p[2]=(u8)(v>>16);p[3]=(u8)(v>>24);
}
static void word(Writer *w,u32 v) {
    if (w->used>MAX_OUTPUT-4) {w->failed=1;return;}
    word_at(w->data+w->used,v);w->used+=4;
}
static void item(Writer *w,u32 id,u32 type,u32 count,const int *values) {
    if (w->used>MAX_OUTPUT-6) {w->failed=1;return;}
    w->data[w->used++]=(u8)id;w->data[w->used++]=(u8)type;
    word(w,count);
    if (type!=13) for (u32 k=0;k<count;k++) word(w,(u32)values[k]);
    w->count++;
}

/* Same four-argument ABI as iAP2ControlLaneGuidanceInformation. */
void palisade_lane_entry(void *session, u32 unused, const u8 *input, int length) {
    (void)unused;
    trace(1,length,0,0);
#define DROP(code,a,b) { trace(code,a,b,0); return; }
    if (!input || length<0 || length>MAX_INPUT) DROP(20,length,0);
    Cursor c={input,(u32)length};Field f;int rc;
    int components[MAX_COMPONENTS],index=0;u32 nc=0,nl=0;int have_index=0;
    Lane lanes[MAX_LANES];
    while ((rc=field(&c,&f))>0) {
        if (f.id==0) {
            if (!f.size || f.size%2 || f.size/2>MAX_COMPONENTS-nc) DROP(21,(int)f.size,(int)nc);
            for(u32 j=0;j<f.size;j+=2) components[nc++]=(int)be16(f.data+j);
        } else if (f.id==1) {
            if (f.size!=2 || have_index) DROP(22,(int)f.size,have_index);
            index=(int)be16(f.data);have_index=1;
        } else if (f.id==2) {
            if (nl==MAX_LANES) DROP(28,(int)f.size,(int)nl);
            int detail=0,parsed=lane_read(&lanes[nl],f.data,f.size,&detail);
            if (parsed<0) { trace(23,-parsed,detail,(int)nl);return; }
            nl++;
        }
    }
    if (rc<0 || !nc) DROP(24,rc,(int)nc);
    Writer w;w.used=8;w.count=0;w.failed=0;
    item(&w,0,5,nc,components);
    if(have_index) item(&w,1,5,1,&index);
    if(!nl) item(&w,2,13,0,0);
    for(u32 j=0;j<nl;j++) {
        item(&w,2,13,1,0);
        u32 start=w.used,root_count=w.count;word(&w,0);word(&w,0);w.count=0;
        for(u32 k=0;k<4;k++) {
            /* Empty angle fields are retained, including a zero-degree highlight. */
            item(&w,k,k==1?8:5,lanes[j].count[k],lanes[j].value[k]);
        }
        word_at(w.data+start,w.used-start);word_at(w.data+start+4,w.count);
        w.count=root_count;
    }
    if(w.failed) DROP(25,(int)w.used,0);
    void *context=lane_context(session);
    if(!context) DROP(26,0,0);
    Notify *packet=lane_allocate((int)w.used,(int)w.count);
    if(!packet) DROP(27,(int)w.used,0);
    /* Stock allocator initializes the first two words and owns the lifetime. */
    for(u32 k=8;k<w.used;k++) packet->data[k]=w.data[k];
    packet->used=w.used;
    trace(4,index,(int)nl,(int)nc);
    lane_notify(context,122,0,0,packet); /* stock notify destroys the packet */
#undef DROP
}
