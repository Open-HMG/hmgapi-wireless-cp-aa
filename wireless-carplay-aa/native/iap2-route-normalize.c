/* Preserve packed route selections at the assembled route-handler boundary.
 * Only field 13 changes representation: each uint16 becomes a scalar TLV the
 * measured SDK already supports. No input writes, heap allocation or cache guess.
 */
typedef unsigned char u8;
typedef unsigned int u32;
typedef __UINTPTR_TYPE__ uptr;
extern int route_original(void *session, u32 session_id, const u8 *input, int length);
extern void lane_log(const char *format, ...);
enum { ROUTE_INPUT=4096, ROUTE_VALUES=16, ROUTE_OUTPUT=ROUTE_INPUT+4*ROUTE_VALUES };

static u32 number(const u8 *p) { return ((u32)p[0]<<8)|p[1]; }

/* Validate the entire payload before writing anything. Zero means unchanged;
 * negative means unsupported input: the wrapper preserves stock handling. */
static int expanded_size(const u8 *input, int length) {
    if (length<0 || length>ROUTE_INPUT || (!input && length)) return -1;
    u32 at=0, growth=0, values=0;
    while (at<(u32)length) {
        if ((u32)length-at<4) return -1;
        u32 n=number(input+at), id=number(input+at+2);
        if (n<4 || n>(u32)length-at) return -1;
        if (id==13) {
            u32 size=n-4;
            if (!size || (size&1)) return -2;
            u32 count=size/2;
            values+=count;
            growth+=4*(count-1);
        }
        at+=n;
    }
    if (!growth) return 0;
    if (values>ROUTE_VALUES) return -3;
    return length+(int)growth;
}

int palisade_route_expand(const u8 *input, int length, u8 *output, int capacity) {
    int size=expanded_size(input,length);
    if (size<=0) return size;
    if (!output || capacity<size) return -4;
    if ((uptr)output<(uptr)input+(u32)length && (uptr)input<(uptr)output+(u32)size) return -4;
    u32 at=0, used=0;
    while (at<(u32)length) {
        u32 n=number(input+at), id=number(input+at+2);
        if (id==13) {
            for (u32 j=4;j<n;j+=2) {
                output[used++]=0;output[used++]=6;
                output[used++]=0;output[used++]=13;
                output[used++]=input[at+j];output[used++]=input[at+j+1];
            }
        } else {
            for (u32 j=0;j<n;j++) output[used++]=input[at+j];
        }
        at+=n;
    }
    return (int)used;
}

/* Keep the bounded stack copy alive until the original synchronous handler has
 * returned. The original handler owns its notification, not this input buffer. */
static __attribute__((noinline)) int rewritten(void *session, u32 session_id,
                                              const u8 *input, int length) {
    u8 output[ROUTE_OUTPUT];
    int size=palisade_route_expand(input,length,output,sizeof(output));
    if (size>0) {
        lane_log("PalisadeLane code=%d a=%d b=%d c=%d\n",8,length,size,(size-length)/4);
        return route_original(session,session_id,output,size);
    }
    return route_original(session,session_id,input,length);
}

int palisade_route_entry(void *session, u32 session_id, const u8 *input, int length) {
    int size=expanded_size(input,length);
    if (size>0) return rewritten(session,session_id,input,length);
    if (size<0) lane_log("PalisadeLane code=%d a=%d b=%d c=%d\n",9,-size,length,0);
    return route_original(session,session_id,input,length);
}
