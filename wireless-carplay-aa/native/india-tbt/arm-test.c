/* Offline ARM test: execute the actual relocated STL string helper and India
 * destructor. Replace only external Android PLT functions with instrumented
 * allocator/memcpy stubs in this disposable emulated process. */
typedef unsigned int u32;
typedef unsigned char u8;
extern u8 india_image[];
static u8 heap[16384] __attribute__((aligned(16)));
static u32 used,allocs,frees,pool_allocs,large_allocs,errors;
static void *last_ptr;
static u32 last_size;

static __attribute__((noreturn)) void quit(int code) {
    register int r0 __asm__("r0")=code;
    register int r7 __asm__("r7")=1;
    __asm__ volatile("svc 0"::"r"(r0),"r"(r7):"memory");
    __builtin_unreachable();
}
static void *allocate(u32 n) {
    used=(used+15)&~15U;
    if(used+n+16>sizeof(heap)) quit(80);
    last_ptr=heap+used;last_size=n;used+=n+16;allocs++;
    for(u32 i=0;i<n+16;i++)((u8*)last_ptr)[i]=0xa5;
    return last_ptr;
}
static void *large(u32 n){large_allocs++;return allocate(n);}
static void *pool(u32 *n){pool_allocs++;*n=(*n+7)&~7U;return allocate(*n);}
static void release(void *p){if(p!=last_ptr)errors++;frees++;}
static void release_pool(void *p,u32 n){if(n!=last_size)errors++;release(p);}
static void *copy(void *d,const void *s,u32 n){for(u32 i=0;i<n;i++)((u8*)d)[i]=((const u8*)s)[i];return d;}
static void length_error(void *s){(void)s;quit(81);}
static void stub(u8 *base,u32 off,void *target) {
    int delta=(u8*)target-(base+off+8);
    if(delta&3 || delta<-(1<<25) || delta>=(1<<25))quit(82);
    *(u32*)(base+off)=0xea000000|((delta>>2)&0xffffff);
}
static void setup(u8 *base,int india) {
    stub(base,india?0xa531c:0xa534c,large);
    stub(base,india?0xa5328:0xa5358,pool);
    stub(base,india?0xa53ac:0xa5364,copy);
    stub(base,india?0xa537c:0xa5370,length_error);
    stub(base,india?0xa5334:0xa5310,release);stub(base,0xa5340,release_pool);
}
static void check(u8 *base,u32 ctor,u32 dtor,u32 length) {
    u32 object[6];u8 input[260];u8 *data=(u8*)object;
    used=allocs=frees=pool_allocs=large_allocs=errors=0;
    for(u32 i=0;i<6;i++)object[i]=0xcccccccc;
    object[4]=(u32)data;object[5]=(u32)data;
    for(u32 i=0,j=0;i<sizeof(input);i++){input[i]=j+'A';if(++j==26)j=0;}
    ((void(*)(void*,void*,void*))(base+ctor+1))(object,input,input+length);
    data=(u8*)object[5];
    if(object[4]!=(u32)(data+length) || data[length])quit(10);
    for(u32 i=0;i<length;i++)if(data[i]!=input[i])quit(11);
    if(allocs!=(length>=16) || pool_allocs!=((length>=16)&&(length<=127)) || large_allocs!=(length>=128))quit(12);
    if(length>=16)for(u32 i=last_size;i<last_size+16;i++)if(data[i]!=0xa5)quit(13);
    ((void(*)(void*))(base+dtor+1))(object);
    if(frees!=allocs || errors)quit(14);
}
void _start(void){
    static const u32 lengths[]={0,1,15,16,17,126,127,128,129,255};
    setup(india_image,1);
    for(u32 i=0;i<sizeof(lengths)/sizeof(lengths[0]);i++){
        check(india_image,0x1d000c,0xa7418,lengths[i]);
        check(india_image,0x1e000c,0xa7418,lengths[i]);
    }
    quit(0);
}
